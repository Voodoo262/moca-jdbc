package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests: a real {@link DriverManager} lookup, over real HTTP, against
 * {@link FakeMocaServer}. Nothing here is mocked out of the driver itself.
 */
class MocaDriverTest
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

    // ------------------------------------------------------------ registration

    @Test
    void driverIsAutoRegisteredViaServiceLoader() throws SQLException
    {
        // No Class.forName: if META-INF/services is wired up, DriverManager already has it.
        final Driver driver = DriverManager.getDriver("jdbc:moca:http://host:4500/service");
        assertNotNull(driver);
        assertEquals(MocaDriver.class, driver.getClass());
    }

    @Test
    void acceptsOnlyMocaUrls() throws SQLException
    {
        final Driver driver = new MocaDriver();
        assertTrue(driver.acceptsURL("jdbc:moca:http://host/service"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://host/db"));

        // JDBC requires null, not an exception, so DriverManager can try the next driver.
        assertNull(driver.connect("jdbc:postgresql://host/db", new Properties()));
    }

    @Test
    void reportsItsPropertiesForDriverManagers() throws SQLException
    {
        final Driver driver = new MocaDriver();
        assertFalse(driver.jdbcCompliant(), "MOCA is not SQL, so the driver must not claim compliance");
        assertEquals(5, driver.getPropertyInfo(server.url(), null).length);
    }

    // ------------------------------------------------------------------- login

    @Test
    void logsInAndOut() throws SQLException
    {
        try (final Connection conn = connect())
        {
            assertFalse(conn.isClosed());
        }
        assertEquals("login user", server.received().get(0));
        assertEquals("logout user", server.lastCommand());
    }

    @Test
    void rejectsMissingCredentials()
    {
        final SQLException ex = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(server.url()));
        assertEquals("28000", ex.getSQLState());
    }

    // -------------------------------------------------------------- result sets

    @Test
    void readsColumnsOneBased() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("publish data"))
        {
            // The point of the whole exercise: JDBC columns start at 1, not 0.
            assertEquals(1, rs.findColumn("a"));
            assertEquals(2, rs.findColumn("b"));

            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals("one", rs.getString(2));
            assertEquals(1, rs.getInt("a"));
            assertEquals("one", rs.getString("b"));

            assertTrue(rs.next());
            assertEquals(2, rs.getInt("a"));

            assertFalse(rs.next());
        }
    }

    @Test
    void columnIndexZeroIsOutOfRange() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("publish data"))
        {
            assertTrue(rs.next());
            // 0 was a valid column in the old client. Under JDBC it must not be.
            assertThrows(SQLException.class, () -> rs.getString(0));
            assertThrows(SQLException.class, () -> rs.getString(3));
        }
    }

    @Test
    void tracksNulls() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("publish data"))
        {
            assertTrue(rs.next());
            assertEquals("one", rs.getString("b"));
            assertFalse(rs.wasNull());

            assertTrue(rs.next());
            assertNull(rs.getString("b"), "an empty <fld/> is SQL NULL");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void exposesColumnTypes() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("publish data"))
        {
            final ResultSetMetaData meta = rs.getMetaData();
            assertEquals(2, meta.getColumnCount());
            assertEquals("a", meta.getColumnName(1));
            assertEquals(Types.INTEGER, meta.getColumnType(1));
            assertEquals(Types.VARCHAR, meta.getColumnType(2));
            assertEquals(Integer.class.getName(), meta.getColumnClassName(1));
        }
    }

    @Test
    void scrollsBackwards() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("publish data"))
        {
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.next());
            assertTrue(rs.next());
            assertTrue(rs.isLast());
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());

            assertTrue(rs.previous());
            assertEquals(2, rs.getInt("a"));
            assertTrue(rs.absolute(1));
            assertEquals(1, rs.getInt("a"));
        }
    }

    // ----------------------------------------------------------------- errors

    @Test
    void noRowsIsAnEmptyResultSetNotAnError() throws SQLException
    {
        // MOCA calls this an error (510). JDBC does not: a query that matches nothing
        // yields an empty ResultSet. This is the adaptation the whole driver hinges on.
        // MOCA still sends the column list, so the empty result stays properly typed.
        server.on("[select", FakeMocaServer.errorWithColumns(510, "No rows affected", "polcod", "polvar"));

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("[select polcod, polvar from poldat where 1 = 2]"))
        {
            assertFalse(rs.next(), "510 must surface as an empty result set");

            // ...and it must still know its columns, 1-based.
            assertEquals(2, rs.getMetaData().getColumnCount());
            assertEquals(1, rs.findColumn("polcod"));
            assertEquals(2, rs.findColumn("polvar"));
        }
    }

    @Test
    void aCommandThatPublishesNothingYieldsAnEmptyResultSet() throws SQLException
    {
        // "commit", "remove data ...", and friends publish no columns at all. executeQuery
        // still has to hand back a ResultSet rather than throw.
        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement();
             final ResultSet rs = stmt.executeQuery("remove data where a = 1"))
        {
            assertFalse(rs.next());
            assertEquals(0, rs.getMetaData().getColumnCount());
        }
    }

    @Test
    void serverErrorsBecomeSqlExceptions() throws SQLException
    {
        server.on("publish data a = 1", FakeMocaServer.error(505, "Syntax error"));

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            final SQLException ex = assertThrows(SQLException.class,
                    () -> stmt.executeQuery("publish data a = 1"));

            // The MOCA status travels as the vendor code, and the SQLState is the
            // standard one DBeaver keys its error reporting off.
            assertEquals(505, ex.getErrorCode());
            assertEquals("42601", ex.getSQLState());
            assertTrue(ex.getMessage().contains("Syntax error"));
        }
    }

    @Test
    void unsupportedFeaturesThrowTheJdbcException() throws SQLException
    {
        // Not UnsupportedOperationException: a JDBC caller catches SQLException, and an
        // unchecked throw from a driver takes the host application down with it.
        try (final Connection conn = connect())
        {
            assertThrows(SQLFeatureNotSupportedException.class, () -> conn.prepareCall("whatever"));
            assertThrows(SQLFeatureNotSupportedException.class, conn::setSavepoint);
        }
    }

    // --------------------------------------------------------- prepared statements

    @Test
    void bindsParametersAsMocaLiterals() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt =
                     conn.prepareStatement("publish data where a = ? and b = ? and c = ?"))
        {
            stmt.setInt(1, 42);
            stmt.setString(2, "hello");
            stmt.setNull(3, Types.VARCHAR);
            stmt.executeQuery();
        }

        assertEquals("publish data where a = 42 and b = 'hello' and c = null", server.received().get(1));
    }

    @Test
    void escapesQuotesInParameters() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("publish data where b = ?"))
        {
            // MOCA has no bind protocol, so the value is inlined. If the quote were not
            // doubled, everything after it would run as command text.
            stmt.setString(1, "O'Brien' & remove data where 1 = 1");
            stmt.executeQuery();
        }

        assertEquals(
            "publish data where b = 'O''Brien'' & remove data where 1 = 1'",
            server.received().get(1),
            "the injected quote must be escaped, leaving one single literal");
    }

    @Test
    void aQuestionMarkInsideALiteralIsNotAParameter() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("publish data where b = 'why?'"))
        {
            // Zero parameters, so this must run as written rather than complain about an unset one.
            stmt.executeQuery();
        }

        assertEquals("publish data where b = 'why?'", server.received().get(1));
    }

    @Test
    void refusesToRunWithAnUnsetParameter() throws SQLException
    {
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("publish data where a = ?"))
        {
            final SQLException ex = assertThrows(SQLException.class, stmt::executeQuery);
            assertEquals("07001", ex.getSQLState());
        }
    }

    // ------------------------------------------------------------ transactions

    @Test
    void commitsAndRollsBackViaMocaCommands() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect())
        {
            conn.setAutoCommit(false);
            try (final Statement stmt = conn.createStatement())
            {
                stmt.executeQuery("publish data");
            }
            conn.commit();
            assertEquals("commit", server.lastCommand());

            try (final Statement stmt = conn.createStatement())
            {
                stmt.executeQuery("publish data");
            }
            conn.rollback();
            assertEquals("rollback", server.lastCommand());
        }
    }

    @Test
    void doesNotCommitWhenNothingHasRun() throws SQLException
    {
        try (final Connection conn = connect())
        {
            conn.setAutoCommit(false);
            conn.commit();
            // Only the login; committing an empty transaction must not cost a round trip.
            assertEquals(1, server.received().size());
        }
    }

    @Test
    void closingRollsBackAnOpenTransaction() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect())
        {
            conn.setAutoCommit(false);
            try (final Statement stmt = conn.createStatement())
            {
                stmt.executeQuery("publish data");
            }
            // Uncommitted work must not land just because the caller closed.
        }

        assertTrue(server.received().contains("rollback"));
    }

    @Test
    void maxRowsTruncatesTheResult() throws SQLException
    {
        server.on("publish data", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.setMaxRows(1);
            try (final ResultSet rs = stmt.executeQuery("publish data"))
            {
                assertTrue(rs.next());
                assertFalse(rs.next(), "the second row must be dropped");
            }
        }
    }
}
