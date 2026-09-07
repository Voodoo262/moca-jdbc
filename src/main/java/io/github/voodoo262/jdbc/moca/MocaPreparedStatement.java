package io.github.voodoo262.jdbc.moca;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * A {@link PreparedStatement} over MOCA.
 *
 * <p>MOCA has no bind-parameter protocol: a command is a string, and the server offers
 * no way to send values out-of-band. So {@code ?} placeholders are substituted
 * <strong>client-side</strong>, into MOCA literal syntax, before the command is sent.
 *
 * <p>That makes the escaping in {@link #quote(String)} security-relevant rather than
 * cosmetic — it is the only thing standing between a caller's untrusted value and the
 * command text. Strings are wrapped in single quotes with embedded quotes doubled, which
 * is MOCA's own escape. Callers who need a guarantee stronger than correct escaping
 * should not build commands from untrusted input at all.
 *
 * <p>The placeholder scanner deliberately ignores a {@code ?} that falls inside a string
 * literal, so a command like {@code publish data where a = 'why?'} has zero parameters,
 * not one.
 */
final class MocaPreparedStatement extends MocaStatement implements PreparedStatement
{
    private static final DateTimeFormatter MOCA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Literal text around each placeholder; always {@code parameters.length + 1} long. */
    private final List<String> fragments;
    /** Rendered MOCA literals, indexed 0-based; {@code null} means "not yet set". */
    private final String[] parameters;

    private final String sql;

    MocaPreparedStatement(final MocaConnection connection, final String sql) throws SQLException
    {
        super(connection);
        this.sql = sql;
        this.fragments = split(sql);
        this.parameters = new String[fragments.size() - 1];
    }

    /**
     * Splits a command at each top-level {@code ?}, ignoring placeholders inside string
     * literals.
     */
    private static List<String> split(final String sql)
    {
        final List<String> fragments = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++)
        {
            final char c = sql.charAt(i);
            if (c == '\'')
            {
                // A doubled quote is an escaped quote, not the end of the literal.
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'')
                {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                current.append(c);
            }
            else if (c == '?' && !inString)
            {
                fragments.add(current.toString());
                current.setLength(0);
            }
            else
            {
                current.append(c);
            }
        }
        fragments.add(current.toString());
        return fragments;
    }

    /** @return the command with every placeholder replaced by its literal. */
    private String render() throws SQLException
    {
        final StringBuilder command = new StringBuilder(fragments.get(0));
        for (int i = 0; i < parameters.length; i++)
        {
            if (parameters[i] == null)
            {
                throw new SQLException(
                        "Parameter " + (i + 1) + " of " + parameters.length + " was never set", "07001");
            }
            command.append(parameters[i]).append(fragments.get(i + 1));
        }
        return command.toString();
    }

    // ---------------------------------------------------------------- execute

    @Override
    public boolean execute() throws SQLException
    {
        return doExecute(render());
    }

    @Override
    public ResultSet executeQuery() throws SQLException
    {
        return doExecuteQuery(render());
    }

    @Override
    public int executeUpdate() throws SQLException
    {
        return doExecuteUpdate(render());
    }

    // ---------------------------------------- the Statement methods JDBC forbids here

    // Inherited from MocaStatement, but JDBC requires a PreparedStatement to reject all
    // three: a caller reaching them has a bug, and running the string would quietly
    // bypass the parameter machinery this class exists for.

    @Override
    public boolean execute(final String sql) throws SQLException
    {
        throw notOnAPreparedStatement();
    }

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException
    {
        throw notOnAPreparedStatement();
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException
    {
        throw notOnAPreparedStatement();
    }

    private static SQLException notOnAPreparedStatement()
    {
        return new SQLException(
                "A PreparedStatement cannot execute a different command; use the no-argument "
                        + "execute/executeQuery/executeUpdate", "07003");
    }

    // ------------------------------------------------------------- parameters

    /**
     * @param index 1-based, per JDBC
     * @param literal already-rendered MOCA literal
     */
    private void set(final int index, final String literal) throws SQLException
    {
        requireOpen();
        if (index < 1 || index > parameters.length)
        {
            throw new SQLException(
                    "Parameter index " + index + " is out of range 1.." + parameters.length, "07009");
        }
        parameters[index - 1] = literal;
    }

    /**
     * Renders a string as a MOCA literal. Embedded single quotes are doubled — the escape
     * MOCA itself uses — so a value can never terminate its own literal and run as command
     * text.
     */
    private static String quote(final String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }

    @Override public void setNull(int i, int sqlType) throws SQLException { set(i, "null"); }
    @Override public void setNull(int i, int sqlType, String typeName) throws SQLException { set(i, "null"); }

    @Override public void setBoolean(int i, boolean v) throws SQLException { set(i, v ? "true" : "false"); }
    @Override public void setByte(int i, byte v) throws SQLException { set(i, Byte.toString(v)); }
    @Override public void setShort(int i, short v) throws SQLException { set(i, Short.toString(v)); }
    @Override public void setInt(int i, int v) throws SQLException { set(i, Integer.toString(v)); }
    @Override public void setLong(int i, long v) throws SQLException { set(i, Long.toString(v)); }
    @Override public void setFloat(int i, float v) throws SQLException { set(i, Float.toString(v)); }
    @Override public void setDouble(int i, double v) throws SQLException { set(i, Double.toString(v)); }

    @Override
    public void setBigDecimal(final int i, final BigDecimal v) throws SQLException
    {
        set(i, v == null ? "null" : v.toPlainString());
    }

    @Override
    public void setString(final int i, final String v) throws SQLException
    {
        set(i, v == null ? "null" : quote(v));
    }

    @Override
    public void setDate(final int i, final Date v) throws SQLException
    {
        set(i, v == null ? "null" : quote(v.toLocalDate().atStartOfDay().format(MOCA_TIMESTAMP)));
    }

    @Override
    public void setTime(final int i, final Time v) throws SQLException
    {
        set(i, v == null ? "null" : quote(LocalDate.EPOCH.atTime(v.toLocalTime()).format(MOCA_TIMESTAMP)));
    }

    @Override
    public void setTimestamp(final int i, final Timestamp v) throws SQLException
    {
        set(i, v == null ? "null" : quote(v.toLocalDateTime().format(MOCA_TIMESTAMP)));
    }

    @Override public void setDate(int i, Date v, Calendar cal) throws SQLException { setDate(i, v); }
    @Override public void setTime(int i, Time v, Calendar cal) throws SQLException { setTime(i, v); }
    @Override public void setTimestamp(int i, Timestamp v, Calendar cal) throws SQLException { setTimestamp(i, v); }

    @Override
    public void setObject(final int i, final Object v) throws SQLException
    {
        if (v == null)                     { set(i, "null"); return; }
        if (v instanceof String)           { setString(i, (String) v); return; }
        if (v instanceof Boolean)          { setBoolean(i, (Boolean) v); return; }
        if (v instanceof Byte)             { setByte(i, (Byte) v); return; }
        if (v instanceof Short)            { setShort(i, (Short) v); return; }
        if (v instanceof Integer)          { setInt(i, (Integer) v); return; }
        if (v instanceof Long)             { setLong(i, (Long) v); return; }
        if (v instanceof Float)            { setFloat(i, (Float) v); return; }
        if (v instanceof Double)           { setDouble(i, (Double) v); return; }
        if (v instanceof BigDecimal)       { setBigDecimal(i, (BigDecimal) v); return; }
        if (v instanceof Timestamp)        { setTimestamp(i, (Timestamp) v); return; }
        if (v instanceof Date)             { setDate(i, (Date) v); return; }
        if (v instanceof Time)             { setTime(i, (Time) v); return; }
        if (v instanceof LocalDateTime)    { set(i, quote(((LocalDateTime) v).format(MOCA_TIMESTAMP))); return; }
        if (v instanceof LocalDate)        { set(i, quote(((LocalDate) v).atStartOfDay().format(MOCA_TIMESTAMP))); return; }

        throw new SQLException(
                "Cannot bind parameter " + i + " of type " + v.getClass().getName()
                        + " — MOCA has no literal syntax for it", "22003");
    }

    @Override public void setObject(int i, Object v, int targetSqlType) throws SQLException { setObject(i, v); }
    @Override public void setObject(int i, Object v, int targetSqlType, int scale) throws SQLException { setObject(i, v); }

    @Override
    public void clearParameters() throws SQLException
    {
        requireOpen();
        java.util.Arrays.fill(parameters, null);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException
    {
        requireOpen();
        // MOCA cannot describe a command's parameters: there is no prepare step on the
        // server, so nothing knows their types until a value is bound.
        throw unsupported("Parameter metadata");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException
    {
        requireOpen();
        // Likewise, the shape of the result is unknown until the command has run.
        final ResultSet current = getResultSet();
        return current == null ? null : current.getMetaData();
    }

    @Override
    public String toString()
    {
        return sql;
    }

    // ------------------------------------------- types MOCA cannot carry inline

    @Override public void setBytes(int i, byte[] v) throws SQLException { throw unsupported("Binary parameters"); }
    @Override public void setAsciiStream(int i, InputStream v, int l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setBinaryStream(int i, InputStream v, int l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setCharacterStream(int i, Reader v, int l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setAsciiStream(int i, InputStream v, long l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setBinaryStream(int i, InputStream v, long l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setCharacterStream(int i, Reader v, long l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setAsciiStream(int i, InputStream v) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setBinaryStream(int i, InputStream v) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setCharacterStream(int i, Reader v) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setNCharacterStream(int i, Reader v, long l) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setNCharacterStream(int i, Reader v) throws SQLException { throw unsupported("Stream parameters"); }
    @Override public void setNString(int i, String v) throws SQLException { setString(i, v); }
    @Override public void setRef(int i, Ref v) throws SQLException { throw unsupported("REF parameters"); }
    @Override public void setBlob(int i, Blob v) throws SQLException { throw unsupported("BLOB parameters"); }
    @Override public void setBlob(int i, InputStream v, long l) throws SQLException { throw unsupported("BLOB parameters"); }
    @Override public void setBlob(int i, InputStream v) throws SQLException { throw unsupported("BLOB parameters"); }
    @Override public void setClob(int i, Clob v) throws SQLException { throw unsupported("CLOB parameters"); }
    @Override public void setClob(int i, Reader v, long l) throws SQLException { throw unsupported("CLOB parameters"); }
    @Override public void setClob(int i, Reader v) throws SQLException { throw unsupported("CLOB parameters"); }
    @Override public void setNClob(int i, NClob v) throws SQLException { throw unsupported("NCLOB parameters"); }
    @Override public void setNClob(int i, Reader v, long l) throws SQLException { throw unsupported("NCLOB parameters"); }
    @Override public void setNClob(int i, Reader v) throws SQLException { throw unsupported("NCLOB parameters"); }
    @Override public void setArray(int i, Array v) throws SQLException { throw unsupported("ARRAY parameters"); }
    @Override public void setURL(int i, URL v) throws SQLException { throw unsupported("DATALINK parameters"); }
    @Override public void setRowId(int i, RowId v) throws SQLException { throw unsupported("ROWID parameters"); }
    @Override public void setSQLXML(int i, SQLXML v) throws SQLException { throw unsupported("SQLXML parameters"); }

    @Override
    @Deprecated
    public void setUnicodeStream(int i, InputStream v, int l) throws SQLException { throw unsupported("setUnicodeStream"); }

    @Override public void addBatch() throws SQLException { throw unsupported("Batch execution"); }
}
