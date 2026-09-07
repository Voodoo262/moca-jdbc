package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Value coercion: how the raw strings MOCA puts on the wire become typed JDBC values.
 *
 * <p>Kept apart from {@link MocaDriverTest}, which is about the driver's control flow.
 * These all run through the same real HTTP path.
 */
class MocaResultSetTest
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

    /** A one-row result whose single column has the given MOCA type character and value. */
    private ResultSet oneValue(final Connection conn, final char mocaType, final String value)
            throws SQLException
    {
        server.on("publish data", FakeMocaServer.success(
            "<metadata><column name=\"v\" type=\"" + mocaType + "\" length=\"30\" nullable=\"true\"/></metadata>"
                + "<data><row><fld>" + value + "</fld></row></data>"));

        final Statement stmt = conn.createStatement();
        final ResultSet rs = stmt.executeQuery("publish data");
        assertTrue(rs.next());
        return rs;
    }

    // ---------------------------------------------------------------- datetime

    @Test
    void readsThePackedMocaTimestamp() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "20260907143000"))
        {
            assertEquals(Timestamp.valueOf("2026-09-07 14:30:00"), rs.getTimestamp(1));
            assertEquals(Date.valueOf("2026-09-07"), rs.getDate(1));
            assertEquals(Time.valueOf("14:30:00"), rs.getTime(1));
        }
    }

    @Test
    void readsAPackedTimestampWithNoSeconds() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "202609071430"))
        {
            assertEquals(Timestamp.valueOf("2026-09-07 14:30:00"), rs.getTimestamp(1));
        }
    }

    @Test
    void readsAnIsoTimestamp() throws SQLException
    {
        // Native-SQL passthrough can surface an ISO form rather than MOCA's packed one.
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "2026-09-07T14:30:00"))
        {
            assertEquals(Timestamp.valueOf("2026-09-07 14:30:00"), rs.getTimestamp(1));
        }
    }

    @Test
    void widensADateWithNoTimeToMidnight() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "20260907"))
        {
            assertEquals(Timestamp.valueOf("2026-09-07 00:00:00"), rs.getTimestamp(1));
        }
    }

    @Test
    void anUnparseableDateIsADataError() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "not-a-date"))
        {
            final SQLException ex = assertThrows(SQLDataException.class, () -> rs.getTimestamp(1));
            assertEquals("22007", ex.getSQLState());
        }
    }

    // ------------------------------------------------------------------ binary

    @Test
    void decodesTheBinaryTypeAsBase64() throws SQLException
    {
        final byte[] raw = { 0x00, 0x01, (byte) 0xFF, 0x7F };
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'V', Base64.getEncoder().encodeToString(raw)))
        {
            assertArrayEquals(raw, rs.getBytes(1));
        }
    }

    @Test
    void invalidBase64InABinaryColumnIsADataError() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'V', "!!!not base64!!!"))
        {
            assertThrows(SQLDataException.class, () -> rs.getBytes(1));
        }
    }

    @Test
    void getBytesOnANonBinaryColumnIsTheUtf8Encoding() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'S', "hello"))
        {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), rs.getBytes(1));
        }
    }

    // ----------------------------------------------------------------- numbers

    @Test
    void nonNumericTextInANumericGetterIsADataError() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'I', "twelve"))
        {
            final SQLException ex = assertThrows(SQLDataException.class, () -> rs.getInt(1));
            assertEquals("22018", ex.getSQLState());
            // The message has to name the column; "not numeric" alone is useless in a grid.
            assertTrue(ex.getMessage().contains("v"), ex.getMessage());
        }
    }

    @Test
    void readsTheBooleanTypeAsOneOrZero() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'O', "1"))
        {
            assertTrue(rs.getBoolean(1));
        }
    }

    // ------------------------------------------------------- getObject(Class)

    @Test
    void convertsToARequestedType() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'I', "42"))
        {
            assertEquals(42, rs.getObject(1, Integer.class));
            assertEquals(42L, rs.getObject(1, Long.class));
            assertEquals(new BigDecimal("42"), rs.getObject(1, BigDecimal.class));
            assertEquals("42", rs.getObject(1, String.class));
        }
    }

    @Test
    void convertsToJavaTimeTypes() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'D', "20260907143000"))
        {
            assertEquals(LocalDate.of(2026, 9, 7), rs.getObject(1, LocalDate.class));
            assertEquals(LocalDateTime.of(2026, 9, 7, 14, 30), rs.getObject(1, LocalDateTime.class));
        }
    }

    @Test
    void aNullIsNullWhateverTypeIsAskedFor() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'I', ""))
        {
            assertNull(rs.getObject(1, Integer.class));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void refusesAConversionItCannotMake() throws SQLException
    {
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'S', "x"))
        {
            assertThrows(SQLException.class, () -> rs.getObject(1, Thread.class));
        }
    }

    // -------------------------------------------------------------- encoding

    @Test
    void roundTripsNonAsciiValues() throws SQLException
    {
        // Regression test for the build's source encoding: if javac reads the sources in
        // the platform charset rather than UTF-8, string handling here goes wrong on a
        // non-UTF-8 machine. Also proves the HTTP layer really is UTF-8 end to end.
        final String value = "Ünïcödé — em-dash, ©, 日本語";
        try (final Connection conn = connect();
             final ResultSet rs = oneValue(conn, 'S', value))
        {
            assertEquals(value, rs.getString(1));
        }
    }
}
