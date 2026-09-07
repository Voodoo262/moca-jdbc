package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection and statement lifecycle, plus the protocol hardening. These are the JDBC
 * contract obligations that are easy to get subtly wrong and that no caller exercises
 * until something is already broken.
 */
class MocaLifecycleTest
{
    private FakeMocaServer server;

    @BeforeEach
    void startServer() throws IOException
    {
        server = new FakeMocaServer();
    }

    @AfterEach
    void stopServer()
    {
        server.close();
    }

    private Connection connect() throws SQLException
    {
        return DriverManager.getConnection(server.url(), "rfaust", "secret");
    }

    // ----------------------------------------------------- credentials in the URL

    @Test
    void theUrlHandedBackDoesNotCarryThePassword() throws SQLException
    {
        // password is an ordinary connection property, so passing it in the URL is legal.
        // But getURL() is what DBeaver shows in its connection-info panel, and what any
        // logger will happily write out.
        final String url = server.url() + "?user=rfaust&password=hunter2&localeId=US_ENGLISH";

        try (final Connection conn = DriverManager.getConnection(url))
        {
            final String reported = conn.getMetaData().getURL();
            assertFalse(reported.contains("hunter2"), "the password leaked: " + reported);
            assertTrue(reported.contains("password=****"), reported);
            // Everything else must survive, or the URL stops being useful for diagnosis.
            assertTrue(reported.contains("user=rfaust"), reported);
            assertTrue(reported.contains("localeId=US_ENGLISH"), reported);
        }
    }

    @Test
    void aUrlWithNoQueryStringIsUnchanged() throws SQLException
    {
        try (final Connection conn = connect())
        {
            assertEquals(server.url(), conn.getMetaData().getURL());
        }
    }

    // ------------------------------------------------------------------ version

    @Test
    void reportsAVersionEvenWithNoJarManifest() throws SQLException
    {
        // Under Gradle the classes come from a directory, so there is no manifest to read
        // Implementation-Version from. That must degrade to the fallback, not blow up.
        try (final Connection conn = connect())
        {
            final DatabaseMetaData meta = conn.getMetaData();
            assertEquals("1.0", meta.getDriverVersion());
            assertEquals(1, meta.getDriverMajorVersion());
            assertEquals(0, meta.getDriverMinorVersion());
        }
    }

    // -------------------------------------------------- PreparedStatement contract

    @Test
    void aPreparedStatementRefusesToRunADifferentCommand() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("publish data where a = ?"))
        {
            // JDBC forbids these three on a PreparedStatement. Running the string would
            // silently bypass the parameter machinery this class exists for.
            assertThrows(SQLException.class, () -> stmt.execute("remove data"));
            assertThrows(SQLException.class, () -> stmt.executeQuery("remove data"));
            assertThrows(SQLException.class, () -> stmt.executeUpdate("remove data"));

            assertFalse(server.received().contains("remove data"),
                    "a refused command must never reach the server");
        }
    }

    @Test
    void theNoArgumentPreparedStatementPathStillWorks() throws SQLException
    {
        // Guards the refactor that made the above possible: the internal execute path must
        // not route through the public string-taking methods it now rejects.
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("publish data where a = ?"))
        {
            stmt.setInt(1, 7);
            try (final ResultSet rs = stmt.executeQuery())
            {
                assertTrue(rs.next());
            }
            assertEquals("publish data where a = 7", server.received().get(1));
        }
    }

    // -------------------------------------------------------- statement lifecycle

    @Test
    void closingTheConnectionClosesItsStatements() throws SQLException
    {
        final Connection conn = connect();
        final Statement plain = conn.createStatement();
        final PreparedStatement prepared = conn.prepareStatement("publish data where a = ?");

        conn.close();

        assertTrue(plain.isClosed(), "JDBC: closing a connection closes its statements");
        assertTrue(prepared.isClosed());
    }

    @Test
    void closingAStatementDoesNotCloseTheOthers() throws SQLException
    {
        try (final Connection conn = connect())
        {
            final Statement first = conn.createStatement();
            final Statement second = conn.createStatement();

            first.close();

            assertTrue(first.isClosed());
            assertFalse(second.isClosed());
        }
    }

    @Test
    void metadataResultSetsDoNotAccumulateStatements() throws SQLException
    {
        try (final Connection conn = connect())
        {
            final DatabaseMetaData meta = conn.getMetaData();
            // Every one of these needs a Statement to hang off. A fresh one per call would
            // pile up for the life of the connection.
            for (int i = 0; i < 50; i++)
            {
                meta.getPrimaryKeys(null, null, "POLDAT").close();
                meta.getSchemas().close();
            }
            assertEquals("MOCA", meta.getDatabaseProductName());
        }
    }

    // -------------------------------------------------------------------- timeouts

    @Test
    void isValidHonoursItsTimeout() throws SQLException
    {
        try (final Connection conn = connect())
        {
            // The connection's own read timeout is 60s. A pool asking "alive within 1s?"
            // must get an answer in about a second, not sixty.
            server.delayResponses(3_000);

            final long start = System.nanoTime();
            final boolean valid = conn.isValid(1);
            final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertFalse(valid, "the ping could not have completed inside the timeout");
            assertTrue(elapsedMillis < 2_500,
                    "isValid(1) waited " + elapsedMillis + "ms; it ignored its timeout");
        }
    }

    @Test
    void setQueryTimeoutBoundsACommand() throws SQLException
    {
        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            server.delayResponses(3_000);
            stmt.setQueryTimeout(1);

            final long start = System.nanoTime();
            assertThrows(SQLException.class, () -> stmt.executeQuery("publish data"));
            final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis < 2_500,
                    "the command ran for " + elapsedMillis + "ms despite a 1s query timeout");
        }
        finally
        {
            server.delayResponses(0);
        }
    }

    // ------------------------------------------------- native SQL passthrough

    @Test
    void wrapsABareSelectInMocaPassthroughBrackets() throws SQLException
    {
        // This is what makes "view table data" work: DBeaver generates a plain SELECT,
        // which MOCA would reject as an unknown command.
        server.on("select", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeQuery("select * from poldat").close();
        }

        assertEquals("[select * from poldat]", server.received().get(1));
    }

    @Test
    void doesNotDoubleWrapAnAlreadyBracketedSelect() throws SQLException
    {
        server.on("select", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeQuery("[select * from poldat]").close();
        }

        assertEquals("[select * from poldat]", server.received().get(1));
    }

    @Test
    void dropsATrailingSemicolonWhenWrapping() throws SQLException
    {
        // Idiomatic in a SQL editor, and meaningless once inside the brackets.
        server.on("select", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeQuery("  select * from poldat;  ").close();
        }

        assertEquals("[select * from poldat]", server.received().get(1));
    }

    @Test
    void leavesMocaCommandsAlone() throws SQLException
    {
        server.on("list user tables", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeQuery("list user tables").close();
        }

        assertEquals("list user tables", server.received().get(1));
    }

    @Test
    void doesNotWrapACommandThatMerelyStartsWithTheLettersSelect() throws SQLException
    {
        // "selective" is not "select". The check is on a whole word, not a prefix.
        server.on("selective", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeQuery("selective publish data").close();
        }

        assertEquals("selective publish data", server.received().get(1));
    }

    @Test
    void nativeSqlReportsTheSameTransformation() throws SQLException
    {
        try (final Connection conn = connect())
        {
            assertEquals("[select * from poldat]", conn.nativeSQL("select * from poldat"));
            assertEquals("list user tables", conn.nativeSQL("list user tables"));
        }
    }

    // ------------------------------------------------------------------- XXE

    @Test
    void aResponseCarryingADoctypeIsRejected() throws SQLException
    {
        // The response comes off the network, so the parser refuses doctypes outright,
        // which closes XXE and entity expansion together.
        server.on("publish data",
            "<!DOCTYPE moca-response [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<moca-response><status>0</status><moca-results>"
                + "<metadata><column name=\"v\" type=\"S\" length=\"10\" nullable=\"true\"/></metadata>"
                + "<data><row><fld>&xxe;</fld></row></data>"
                + "</moca-results></moca-response>");

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            final SQLException ex =
                    assertThrows(SQLException.class, () -> stmt.executeQuery("publish data"));
            assertEquals("08S01", ex.getSQLState());
        }
    }
}
