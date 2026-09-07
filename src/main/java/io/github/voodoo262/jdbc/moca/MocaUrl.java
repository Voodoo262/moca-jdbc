package io.github.voodoo262.jdbc.moca;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.time.Duration;
import java.util.Properties;

/**
 * A parsed {@code jdbc:moca:} URL plus the connection {@link Properties} that came with it.
 *
 * <p>Accepted forms:
 * <pre>
 *   jdbc:moca:http://host:4500/service
 *   jdbc:moca:https://host/service
 *   jdbc:moca://host:4500/service          (scheme defaults to http)
 * </pre>
 *
 * <p>Query parameters are read as driver properties and stripped from the endpoint,
 * so {@code jdbc:moca:http://host:4500/service?localeId=US_ENGLISH} works. Properties
 * passed to {@link java.sql.DriverManager#getConnection(String, Properties)} take
 * precedence over the ones in the URL, which is what callers expect when they build
 * a URL once and vary credentials per connection.
 */
final class MocaUrl
{
    static final String URL_PREFIX = "jdbc:moca:";

    static final String PROP_USER = "user";
    static final String PROP_PASSWORD = "password";
    static final String PROP_LOCALE_ID = "localeId";
    static final String PROP_CONNECT_TIMEOUT = "connectTimeout";
    static final String PROP_READ_TIMEOUT = "readTimeout";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Generous on purpose. A MOCA command can be an arbitrary amount of work on the
     * server; the 5s the original client used will time out a real report.
     */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    /** Stands in for a password in any URL that is going to be shown to somebody. */
    private static final String REDACTED = "****";

    private final URI endpoint;
    private final Properties properties;
    private final String displayUrl;

    private MocaUrl(final URI endpoint, final Properties properties, final String displayUrl)
    {
        this.endpoint = endpoint;
        this.properties = properties;
        this.displayUrl = displayUrl;
    }

    static boolean acceptsUrl(final String url)
    {
        return url != null && url.startsWith(URL_PREFIX);
    }

    /**
     * @param url   a {@code jdbc:moca:} URL
     * @param given properties from the caller; may be {@code null}
     */
    static MocaUrl parse(final String url, final Properties given) throws SQLException
    {
        if (!acceptsUrl(url))
        {
            throw new SQLNonTransientConnectionException("Not a MOCA JDBC URL: " + url, "08001");
        }

        String remainder = url.substring(URL_PREFIX.length());
        if (remainder.startsWith("//"))
        {
            remainder = "http:" + remainder;
        }

        final URI parsed;
        try
        {
            parsed = new URI(remainder);
        }
        catch (final URISyntaxException ex)
        {
            throw new SQLNonTransientConnectionException("Malformed MOCA JDBC URL: " + url, "08001", ex);
        }

        if (parsed.getHost() == null)
        {
            throw new SQLNonTransientConnectionException("MOCA JDBC URL has no host: " + url, "08001");
        }
        final String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        {
            throw new SQLNonTransientConnectionException(
                    "MOCA JDBC URL must be http or https, got '" + scheme + "': " + url, "08001");
        }

        final Properties properties = new Properties();
        putQueryParameters(parsed.getRawQuery(), properties);
        if (given != null)
        {
            // Caller-supplied properties win over the URL's query string.
            properties.putAll(given);
        }

        final URI endpoint;
        try
        {
            // Drop the query: it carried driver properties, not anything the server wants.
            endpoint = new URI(parsed.getScheme(), parsed.getAuthority(), parsed.getPath(), null, null);
        }
        catch (final URISyntaxException ex)
        {
            throw new SQLNonTransientConnectionException("Malformed MOCA JDBC URL: " + url, "08001", ex);
        }

        final MocaUrl mocaUrl = new MocaUrl(endpoint, properties, redact(url));
        // Validate eagerly. A bad timeout is a malformed URL, and the caller should hear
        // about it from the function that parses URLs, not from a getter three frames later.
        mocaUrl.getConnectTimeout();
        mocaUrl.getReadTimeout();
        return mocaUrl;
    }

    private static void putQueryParameters(final String rawQuery, final Properties into)
    {
        if (rawQuery == null || rawQuery.isEmpty()) return;
        for (final String pair : rawQuery.split("&"))
        {
            if (pair.isEmpty()) continue;
            final int eq = pair.indexOf('=');
            if (eq < 0)
            {
                into.setProperty(decode(pair), "");
            }
            else
            {
                into.setProperty(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
    }

    private static String decode(final String value)
    {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Blanks the password out of a URL's query string.
     *
     * <p>{@code password} is an ordinary connection property, so it is perfectly legal to
     * pass it in the URL. But the URL is also what {@code DatabaseMetaData.getURL()}
     * returns, which DBeaver shows in its connection-info panel and which anything at all
     * may log. The credential must not travel with it.
     */
    static String redact(final String url)
    {
        final int query = url.indexOf('?');
        if (query < 0) return url;

        final StringBuilder redacted = new StringBuilder(url.substring(0, query + 1));
        final String[] pairs = url.substring(query + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++)
        {
            if (i > 0) redacted.append('&');
            final int eq = pairs[i].indexOf('=');
            final String name = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
            if (PROP_PASSWORD.equalsIgnoreCase(decode(name)))
            {
                redacted.append(name).append('=').append(REDACTED);
            }
            else
            {
                redacted.append(pairs[i]);
            }
        }
        return redacted.toString();
    }

    URI getEndpoint() { return endpoint; }

    /** @return the URL with any password blanked out, safe to show and to log. */
    String getDisplayUrl() { return displayUrl; }

    Properties getProperties() { return properties; }

    String getUser() { return properties.getProperty(PROP_USER); }

    String getPassword() { return properties.getProperty(PROP_PASSWORD); }

    String getLocaleId() { return properties.getProperty(PROP_LOCALE_ID); }

    Duration getConnectTimeout() throws SQLException
    {
        return durationOf(PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    Duration getReadTimeout() throws SQLException
    {
        return durationOf(PROP_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    private Duration durationOf(final String name, final Duration fallback) throws SQLException
    {
        final String value = properties.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try
        {
            final long millis = Long.parseLong(value.trim());
            if (millis <= 0)
            {
                throw new SQLNonTransientConnectionException(
                        name + " must be a positive number of milliseconds, got '" + value + "'", "08001");
            }
            return Duration.ofMillis(millis);
        }
        catch (final NumberFormatException ex)
        {
            throw new SQLNonTransientConnectionException(
                    name + " must be a number of milliseconds, got '" + value + "'", "08001", ex);
        }
    }
}
