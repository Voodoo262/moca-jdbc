package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaClient;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for the MOCA command protocol.
 *
 * <pre>{@code
 * Class.forName("io.github.voodoo262.jdbc.moca.MocaDriver");   // optional; auto-registered via ServiceLoader
 * try (Connection conn = DriverManager.getConnection(
 *          "jdbc:moca:http://host:4500/service", "user", "password");
 *      Statement stmt = conn.createStatement();
 *      ResultSet rs = stmt.executeQuery("list warehouses"))
 * {
 *     while (rs.next()) System.out.println(rs.getString("wh_id"));
 * }
 * }</pre>
 *
 * <p>The driver is registered automatically through
 * {@code META-INF/services/java.sql.Driver}, so {@code Class.forName} is not needed on
 * any JDBC 4.0+ runtime.
 *
 * <p>{@link #getPropertyInfo} lists the accepted connection properties, and the URL forms
 * are documented on the README.
 */
public final class MocaDriver implements Driver
{
    static final String NAME = "MOCA JDBC Driver";

    /**
     * Read from the jar manifest's {@code Implementation-Version}, which the build stamps
     * from the Gradle version, so a release cannot ship a driver that misreports itself.
     * Running from a plain classes directory (which is what {@code ./gradlew test} does)
     * has no manifest, hence the fallback.
     */
    private static final String FALLBACK_VERSION = "1.0";

    private static final String VERSION = versionFromManifest();
    static final int VERSION_MAJOR = versionPart(0);
    static final int VERSION_MINOR = versionPart(1);

    private static String versionFromManifest()
    {
        final Package pkg = MocaDriver.class.getPackage();
        final String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? FALLBACK_VERSION : version;
    }

    /**
     * @param index 0 for major, 1 for minor
     * @return that dot-separated component of {@code VERSION}, or 0 if it is absent or
     *         not a number. A qualifier such as {@code 1.2.0-SNAPSHOT} must not stop the
     *         driver loading, so this never throws.
     */
    private static int versionPart(final int index)
    {
        final String[] parts = VERSION.split("\\.");
        if (index >= parts.length) return 0;
        try
        {
            return Integer.parseInt(parts[index].split("-")[0].trim());
        }
        catch (final NumberFormatException ex)
        {
            return 0;
        }
    }

    /** @return the full version string, as {@code DatabaseMetaData.getDriverVersion()} reports it. */
    static String getVersion()
    {
        return VERSION;
    }

    private static final Logger PARENT_LOGGER = Logger.getLogger("io.github.voodoo262.jdbc.moca");

    static
    {
        try
        {
            DriverManager.registerDriver(new MocaDriver());
        }
        catch (final SQLException ex)
        {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Override
    public Connection connect(final String url, final Properties info) throws SQLException
    {
        // The JDBC contract: return null (do not throw) for a URL belonging to some
        // other driver, so DriverManager can go on to try the next one.
        if (!acceptsURL(url))
        {
            return null;
        }

        final MocaUrl parsed = MocaUrl.parse(url, info);
        final String user = parsed.getUser();
        final String password = parsed.getPassword();
        if (user == null || password == null)
        {
            throw new SQLException(
                    "MOCA requires a user and password; supply them as connection properties "
                            + "or via DriverManager.getConnection(url, user, password)", "28000");
        }

        final MocaClient client = new MocaClient(
                parsed.getEndpoint(),
                user,
                password,
                parsed.getLocaleId(),
                parsed.getConnectTimeout(),
                parsed.getReadTimeout());

        return new MocaConnection(client, parsed.getDisplayUrl(), user);
    }

    @Override
    public boolean acceptsURL(final String url)
    {
        return MocaUrl.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info)
    {
        final Properties known = info == null ? new Properties() : info;

        final DriverPropertyInfo user = new DriverPropertyInfo(
                MocaUrl.PROP_USER, known.getProperty(MocaUrl.PROP_USER));
        user.description = "MOCA user id";
        user.required = true;

        final DriverPropertyInfo password = new DriverPropertyInfo(
                MocaUrl.PROP_PASSWORD, known.getProperty(MocaUrl.PROP_PASSWORD));
        password.description = "MOCA password";
        password.required = true;

        final DriverPropertyInfo locale = new DriverPropertyInfo(
                MocaUrl.PROP_LOCALE_ID, known.getProperty(MocaUrl.PROP_LOCALE_ID));
        locale.description = "LOCALE_ID to set on each command, e.g. US_ENGLISH. "
                + "Defaults to the server's locale.";
        locale.required = false;

        final DriverPropertyInfo connectTimeout = new DriverPropertyInfo(
                MocaUrl.PROP_CONNECT_TIMEOUT, known.getProperty(MocaUrl.PROP_CONNECT_TIMEOUT));
        connectTimeout.description = "TCP connect timeout in milliseconds (default 10000)";
        connectTimeout.required = false;

        final DriverPropertyInfo readTimeout = new DriverPropertyInfo(
                MocaUrl.PROP_READ_TIMEOUT, known.getProperty(MocaUrl.PROP_READ_TIMEOUT));
        readTimeout.description = "Response timeout in milliseconds (default 60000). "
                + "Statement.setQueryTimeout overrides this per command.";
        readTimeout.required = false;

        return new DriverPropertyInfo[] { user, password, locale, connectTimeout, readTimeout };
    }

    @Override public int getMajorVersion() { return VERSION_MAJOR; }

    @Override public int getMinorVersion() { return VERSION_MINOR; }

    /**
     * @return false, always. MOCA is not SQL: it is a command language with its own
     *         grammar, so no part of this driver can claim SQL-92 entry-level compliance.
     */
    @Override
    public boolean jdbcCompliant()
    {
        return false;
    }

    @Override
    public Logger getParentLogger()
    {
        return PARENT_LOGGER;
    }
}
