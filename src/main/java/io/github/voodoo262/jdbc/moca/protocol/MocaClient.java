package io.github.voodoo262.jdbc.moca.protocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MOCA protocol client: one authenticated session against one MOCA server,
 * speaking {@code application/moca-xml} over HTTP.
 *
 * <p>This layer knows about MOCA and nothing about JDBC beyond {@link SQLException},
 * which it uses so that failures need no translation on the way out.
 */
public final class MocaClient implements AutoCloseable
{
    private static final Logger LOG = Logger.getLogger(MocaClient.class.getName());

    private static final String CONTENT_TYPE = "application/moca-xml";
    private static final String ENV_SESSION_KEY = "SESSION_KEY";
    private static final String ENV_LOCALE_ID = "LOCALE_ID";

    private final URI endpoint;
    private final HttpClient http;
    private final Duration readTimeout;
    private final String localeId;

    private String sessionKey;
    private boolean closed;

    /**
     * Connects and logs in. On return the client holds a live MOCA session.
     *
     * @param endpoint     the MOCA HTTP endpoint, e.g. {@code http://host:4500/service}
     * @param userId       MOCA user id
     * @param password     MOCA password
     * @param localeId     locale to set on each command, or {@code null} for the server default
     * @param connectTimeout TCP connect timeout
     * @param readTimeout    per-request response timeout
     */
    public MocaClient(final URI endpoint,
                      final String userId,
                      final String password,
                      final String localeId,
                      final Duration connectTimeout,
                      final Duration readTimeout) throws SQLException
    {
        this.endpoint = endpoint;
        this.readTimeout = readTimeout;
        this.localeId = localeId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        login(userId, password);
    }

    /**
     * Runs a MOCA command in the current session.
     *
     * <p>A {@link MocaServerException#NO_ROWS_AFFECTED} status is <em>not</em> raised as an
     * error. MOCA treats "nothing matched" as a failure; JDBC treats it as an empty
     * {@code ResultSet}, and callers of this driver are written against JDBC. The server
     * still sends the column list in that case, so the empty result is properly typed.
     */
    public MocaRows execute(final String command, final boolean autoCommit) throws SQLException
    {
        return execute(command, autoCommit, null);
    }

    /**
     * @param timeout overrides the connection's read timeout for this command only
     *                (this is how {@code Statement.setQueryTimeout} is honoured);
     *                {@code null} to use the connection's.
     * @see #execute(String, boolean)
     */
    public MocaRows execute(final String command, final boolean autoCommit, final Duration timeout)
            throws SQLException
    {
        requireOpen();
        if (sessionKey == null)
        {
            throw new SQLNonTransientConnectionException("Not logged in", "08003");
        }

        final MocaRequestXml request = new MocaRequestXml(command)
                .autoCommit(autoCommit)
                .environment(ENV_SESSION_KEY, sessionKey);
        if (localeId != null)
        {
            request.environment(ENV_LOCALE_ID, localeId);
        }

        final MocaResponse response = send(request, timeout != null ? timeout : readTimeout);
        if (response.isSuccess() || response.getStatusCode() == MocaServerException.NO_ROWS_AFFECTED)
        {
            return response.getResults();
        }
        throw new MocaServerException(response.getMessage(), response.getStatusCode(), response.getResults());
    }

    /**
     * @param timeout bounds the check, or {@code null} to use the connection's read
     *                timeout. {@code Connection.isValid} passes its own argument here:
     *                a pool asking "are you alive within 2 seconds" must not block for
     *                the 60-second default.
     * @return true if the session still answers
     */
    public boolean ping(final Duration timeout)
    {
        if (closed || sessionKey == null) return false;
        try
        {
            execute("publish data where ping = 1", true, timeout);
            return true;
        }
        catch (final SQLException ex)
        {
            LOG.log(Level.FINE, "MOCA ping failed", ex);
            return false;
        }
    }

    public boolean isClosed()
    {
        return closed;
    }

    public URI getEndpoint()
    {
        return endpoint;
    }

    @Override
    public void close()
    {
        if (closed) return;
        try
        {
            if (sessionKey != null)
            {
                execute("logout user", true);
            }
        }
        catch (final SQLException ex)
        {
            // A failed logout must not stop the caller from closing. The session
            // will lapse server-side regardless.
            LOG.log(Level.FINE, "MOCA logout failed", ex);
        }
        finally
        {
            sessionKey = null;
            closed = true;
        }
    }

    private void login(final String userId, final String password) throws SQLException
    {
        final MocaRequestXml request = new MocaRequestXml("login user")
                .autoCommit(true)
                .context("usr_id", userId)
                .context("usr_pswd", password);

        final MocaResponse response = send(request, readTimeout);
        if (!response.isSuccess())
        {
            // 08004 = server rejected the connection. Bad credentials land here.
            throw new SQLNonTransientConnectionException(
                    "MOCA login failed: " + response.getMessage(), "08004", response.getStatusCode());
        }

        final MocaRows results = response.getResults();
        final int keyColumn = results.indexOfColumn("session_key");
        if (results.getRowCount() == 0 || keyColumn < 0)
        {
            throw new SQLNonTransientConnectionException(
                    "MOCA login returned no session key", "08004");
        }
        this.sessionKey = results.getValue(0, keyColumn);
    }

    private MocaResponse send(final MocaRequestXml request, final Duration timeout) throws SQLException
    {
        final byte[] body = request.toXml().getBytes(StandardCharsets.UTF_8);

        final HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", CONTENT_TYPE)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        final HttpResponse<String> httpResponse;
        try
        {
            httpResponse = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (final IOException ex)
        {
            throw new SQLNonTransientConnectionException(
                    "Could not reach MOCA server at " + endpoint + ": " + ex.getMessage(), "08006", ex);
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for MOCA server", "08000", ex);
        }

        if (httpResponse.statusCode() != 200)
        {
            throw new SQLNonTransientConnectionException(
                    "MOCA server returned HTTP " + httpResponse.statusCode(), "08006");
        }

        return MocaResponseXml.parse(httpResponse.body());
    }

    private void requireOpen() throws SQLException
    {
        if (closed)
        {
            throw new SQLNonTransientConnectionException("Connection is closed", "08003");
        }
    }
}
