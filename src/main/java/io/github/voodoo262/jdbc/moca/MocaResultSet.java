package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaColumn;
import io.github.voodoo262.jdbc.moca.protocol.MocaRows;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Calendar;
import java.util.Map;

/**
 * A read-only, scroll-insensitive {@link ResultSet} over a {@link MocaRows}.
 *
 * <p>Scrollable because a MOCA response arrives whole: the rows are already in memory,
 * so backwards movement costs nothing and tools that page a grid get it for free.
 *
 * <p><strong>Columns are 1-based</strong>, as JDBC requires. The underlying
 * {@link MocaRows} is 0-based; this class is the only place that conversion happens.
 */
final class MocaResultSet implements ResultSet
{
    /**
     * MOCA renders its {@code D} (datetime) type as a packed numeric string.
     * ISO forms are accepted too, since native-SQL passthrough can surface them.
     */
    private static final DateTimeFormatter[] TIMESTAMP_FORMATS = {
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
        DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    };

    private final MocaRows rows;
    private final Statement statement;
    private final MocaResultSetMetaData metaData;

    /** -1 = before first; rowCount = after last. */
    private int rowIndex = -1;
    private boolean wasNull;
    private boolean closed;

    MocaResultSet(final MocaRows rows, final Statement statement)
    {
        this.rows = rows;
        this.statement = statement;
        this.metaData = new MocaResultSetMetaData(rows);
    }

    // ---------------------------------------------------------------- cursor

    @Override
    public boolean next() throws SQLException
    {
        requireOpen();
        if (rowIndex >= rows.getRowCount())
        {
            return false; // already exhausted; do not run the cursor past after-last
        }
        rowIndex++;
        return rowIndex < rows.getRowCount();
    }

    @Override
    public boolean previous() throws SQLException
    {
        requireOpen();
        if (rowIndex < 0)
        {
            return false;
        }
        rowIndex--;
        return rowIndex >= 0;
    }

    @Override
    public boolean first() throws SQLException
    {
        requireOpen();
        rowIndex = 0;
        return rows.getRowCount() > 0;
    }

    @Override
    public boolean last() throws SQLException
    {
        requireOpen();
        rowIndex = rows.getRowCount() - 1;
        return rows.getRowCount() > 0;
    }

    @Override
    public void beforeFirst() throws SQLException
    {
        requireOpen();
        rowIndex = -1;
    }

    @Override
    public void afterLast() throws SQLException
    {
        requireOpen();
        rowIndex = rows.getRowCount();
    }

    /** @param row 1-based; negative counts back from the end, as JDBC specifies. */
    @Override
    public boolean absolute(final int row) throws SQLException
    {
        requireOpen();
        if (row == 0)
        {
            rowIndex = -1;
            return false;
        }
        rowIndex = row > 0 ? row - 1 : rows.getRowCount() + row;
        if (rowIndex < 0)
        {
            rowIndex = -1;
            return false;
        }
        if (rowIndex >= rows.getRowCount())
        {
            rowIndex = rows.getRowCount();
            return false;
        }
        return true;
    }

    @Override
    public boolean relative(final int offset) throws SQLException
    {
        requireOpen();
        return absolute(rowIndex + 1 + offset);
    }

    @Override
    public int getRow() throws SQLException
    {
        requireOpen();
        return isOnRow() ? rowIndex + 1 : 0;
    }

    @Override public boolean isBeforeFirst() throws SQLException { requireOpen(); return rowIndex < 0 && rows.getRowCount() > 0; }
    @Override public boolean isAfterLast() throws SQLException { requireOpen(); return rowIndex >= rows.getRowCount() && rows.getRowCount() > 0; }
    @Override public boolean isFirst() throws SQLException { requireOpen(); return rowIndex == 0 && rows.getRowCount() > 0; }
    @Override public boolean isLast() throws SQLException { requireOpen(); return rowIndex == rows.getRowCount() - 1 && rows.getRowCount() > 0; }

    // --------------------------------------------------------------- getters

    @Override
    public int findColumn(final String columnLabel) throws SQLException
    {
        requireOpen();
        final int index = rows.indexOfColumn(columnLabel);
        if (index < 0)
        {
            throw new SQLException("No such column: " + columnLabel, "42703");
        }
        return index + 1; // JDBC is 1-based
    }

    @Override
    public String getString(final int columnIndex) throws SQLException
    {
        return rawValue(columnIndex);
    }

    @Override
    public boolean getBoolean(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return false;
        // MOCA sends its boolean ('O') type as 1/0.
        return "1".equals(value.trim()) || Boolean.parseBoolean(value.trim());
    }

    @Override
    public byte getByte(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0;
        return parse(columnIndex, value, Byte::parseByte);
    }

    @Override
    public short getShort(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0;
        return parse(columnIndex, value, Short::parseShort);
    }

    @Override
    public int getInt(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0;
        return parse(columnIndex, value, Integer::parseInt);
    }

    @Override
    public long getLong(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0L;
        return parse(columnIndex, value, Long::parseLong);
    }

    @Override
    public float getFloat(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0f;
        return parse(columnIndex, value, Float::parseFloat);
    }

    @Override
    public double getDouble(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return 0d;
        return parse(columnIndex, value, Double::parseDouble);
    }

    @Override
    public BigDecimal getBigDecimal(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;
        return parse(columnIndex, value, BigDecimal::new);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(final int columnIndex, final int scale) throws SQLException
    {
        final BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;
        if (metaData.getColumnType(columnIndex) == Types.BINARY)
        {
            try
            {
                return Base64.getDecoder().decode(value.trim());
            }
            catch (final IllegalArgumentException ex)
            {
                throw new SQLDataException(
                        "Column '" + metaData.getColumnName(columnIndex) + "' is not valid Base64", "22003", ex);
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Timestamp getTimestamp(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;
        return Timestamp.valueOf(parseDateTime(columnIndex, value));
    }

    @Override
    public Date getDate(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;
        return Date.valueOf(parseDateTime(columnIndex, value).toLocalDate());
    }

    @Override
    public Time getTime(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;
        return Time.valueOf(parseDateTime(columnIndex, value).toLocalTime());
    }

    @Override
    public Object getObject(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        if (value == null) return null;

        switch (metaData.getColumnType(columnIndex))
        {
            case Types.BOOLEAN:   return getBoolean(columnIndex);
            case Types.INTEGER:   return getInt(columnIndex);
            case Types.DOUBLE:    return getDouble(columnIndex);
            case Types.TIMESTAMP: return getTimestamp(columnIndex);
            case Types.BINARY:    return getBytes(columnIndex);
            default:              return value;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(final int columnIndex, final Class<T> type) throws SQLException
    {
        if (type == null)
        {
            throw new SQLException("Target type must not be null", "HY000");
        }
        if (rawValue(columnIndex) == null) return null;

        if (type == String.class)     return (T) getString(columnIndex);
        if (type == Boolean.class)    return (T) Boolean.valueOf(getBoolean(columnIndex));
        if (type == Byte.class)       return (T) Byte.valueOf(getByte(columnIndex));
        if (type == Short.class)      return (T) Short.valueOf(getShort(columnIndex));
        if (type == Integer.class)    return (T) Integer.valueOf(getInt(columnIndex));
        if (type == Long.class)       return (T) Long.valueOf(getLong(columnIndex));
        if (type == Float.class)      return (T) Float.valueOf(getFloat(columnIndex));
        if (type == Double.class)     return (T) Double.valueOf(getDouble(columnIndex));
        if (type == BigDecimal.class) return (T) getBigDecimal(columnIndex);
        if (type == byte[].class)     return (T) getBytes(columnIndex);
        if (type == Date.class)       return (T) getDate(columnIndex);
        if (type == Time.class)       return (T) getTime(columnIndex);
        if (type == Timestamp.class)  return (T) getTimestamp(columnIndex);
        if (type == LocalDate.class)     return (T) parseDateTime(columnIndex, rawValue(columnIndex)).toLocalDate();
        if (type == LocalDateTime.class) return (T) parseDateTime(columnIndex, rawValue(columnIndex));
        if (type == Object.class)     return (T) getObject(columnIndex);

        throw new SQLFeatureNotSupportedException(
                "Cannot convert column '" + metaData.getColumnName(columnIndex) + "' to " + type.getName());
    }

    @Override
    public Reader getCharacterStream(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        return value == null ? null : new StringReader(value);
    }

    @Override
    public InputStream getBinaryStream(final int columnIndex) throws SQLException
    {
        final byte[] value = getBytes(columnIndex);
        return value == null ? null : new java.io.ByteArrayInputStream(value);
    }

    @Override
    public InputStream getAsciiStream(final int columnIndex) throws SQLException
    {
        final String value = rawValue(columnIndex);
        return value == null ? null
                : new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    // Calendar-aware overloads: MOCA sends no zone information, so the calendar
    // cannot be honoured. Delegating is more useful than throwing, but a caller
    // relying on the zone would be misled, so this is documented in the README.
    @Override public Date getDate(final int columnIndex, final Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Time getTime(final int columnIndex, final Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Timestamp getTimestamp(final int columnIndex, final Calendar cal) throws SQLException { return getTimestamp(columnIndex); }

    // --- label-based overloads, all of which resolve the label then delegate ---

    @Override public String getString(final String c) throws SQLException { return getString(findColumn(c)); }
    @Override public boolean getBoolean(final String c) throws SQLException { return getBoolean(findColumn(c)); }
    @Override public byte getByte(final String c) throws SQLException { return getByte(findColumn(c)); }
    @Override public short getShort(final String c) throws SQLException { return getShort(findColumn(c)); }
    @Override public int getInt(final String c) throws SQLException { return getInt(findColumn(c)); }
    @Override public long getLong(final String c) throws SQLException { return getLong(findColumn(c)); }
    @Override public float getFloat(final String c) throws SQLException { return getFloat(findColumn(c)); }
    @Override public double getDouble(final String c) throws SQLException { return getDouble(findColumn(c)); }
    @Override public BigDecimal getBigDecimal(final String c) throws SQLException { return getBigDecimal(findColumn(c)); }
    @Override @Deprecated public BigDecimal getBigDecimal(final String c, final int s) throws SQLException { return getBigDecimal(findColumn(c), s); }
    @Override public byte[] getBytes(final String c) throws SQLException { return getBytes(findColumn(c)); }
    @Override public Date getDate(final String c) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(final String c) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(final String c) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public Date getDate(final String c, final Calendar cal) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(final String c, final Calendar cal) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(final String c, final Calendar cal) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public Object getObject(final String c) throws SQLException { return getObject(findColumn(c)); }
    @Override public <T> T getObject(final String c, final Class<T> type) throws SQLException { return getObject(findColumn(c), type); }
    @Override public Reader getCharacterStream(final String c) throws SQLException { return getCharacterStream(findColumn(c)); }
    @Override public InputStream getBinaryStream(final String c) throws SQLException { return getBinaryStream(findColumn(c)); }
    @Override public InputStream getAsciiStream(final String c) throws SQLException { return getAsciiStream(findColumn(c)); }

    // ---------------------------------------------------------------- state

    @Override
    public boolean wasNull() throws SQLException
    {
        requireOpen();
        return wasNull;
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException
    {
        requireOpen();
        return metaData;
    }

    @Override
    public Statement getStatement()
    {
        return statement;
    }

    @Override public int getType() { return TYPE_SCROLL_INSENSITIVE; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public int getHoldability() { return HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchDirection(final int direction) { /* advisory only; rows are already in memory */ }
    @Override public int getFetchSize() { return rows.getRowCount(); }
    @Override public void setFetchSize(final int size) { /* advisory only; the response arrives whole */ }
    @Override public String getCursorName() throws SQLException { throw unsupported("Named cursors"); }

    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { /* no warnings are produced */ }

    @Override
    public void close()
    {
        closed = true;
    }

    @Override
    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException
    {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException(getClass().getName() + " is not a wrapper for " + iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface)
    {
        return iface.isInstance(this);
    }

    // -------------------------------------------------------------- internals

    private boolean isOnRow()
    {
        return rowIndex >= 0 && rowIndex < rows.getRowCount();
    }

    /**
     * Reads the raw value at a 1-based column index and records whether it was NULL.
     * Every getter funnels through here so {@link #wasNull()} is always correct.
     */
    private String rawValue(final int columnIndex) throws SQLException
    {
        requireOpen();
        if (!isOnRow())
        {
            throw new SQLException(
                    "Cursor is not on a row; call next() first", "24000");
        }
        if (columnIndex < 1 || columnIndex > rows.getColumnCount())
        {
            throw new SQLException(
                    "Column index " + columnIndex + " is out of range 1.." + rows.getColumnCount(), "22023");
        }
        final String value = rows.getValue(rowIndex, columnIndex - 1);
        wasNull = value == null;
        return value;
    }

    private <T> T parse(final int columnIndex, final String value, final java.util.function.Function<String, T> parser)
            throws SQLException
    {
        try
        {
            return parser.apply(value.trim());
        }
        catch (final NumberFormatException | ArithmeticException ex)
        {
            throw new SQLDataException(
                    "Column '" + metaData.getColumnName(columnIndex) + "' value '" + value + "' is not numeric",
                    "22018", ex);
        }
    }

    private LocalDateTime parseDateTime(final int columnIndex, final String value) throws SQLException
    {
        final String trimmed = value.trim();
        for (final DateTimeFormatter format : TIMESTAMP_FORMATS)
        {
            try
            {
                return LocalDateTime.parse(trimmed, format);
            }
            catch (final DateTimeParseException ignored)
            {
                // try the next format
            }
        }
        // A date with no time component is legal in MOCA; widen it to midnight.
        try
        {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("yyyyMMdd")).atStartOfDay();
        }
        catch (final DateTimeParseException ignored)
        {
            // fall through
        }
        try
        {
            return LocalDate.parse(trimmed).atStartOfDay();
        }
        catch (final DateTimeParseException ex)
        {
            throw new SQLDataException(
                    "Column '" + metaData.getColumnName(columnIndex) + "' value '" + value
                            + "' is not a recognised MOCA datetime", "22007", ex);
        }
    }

    private void requireOpen() throws SQLException
    {
        if (closed)
        {
            throw new SQLException("ResultSet is closed", "24000");
        }
    }

    private static SQLFeatureNotSupportedException unsupported(final String what)
    {
        return new SQLFeatureNotSupportedException(what + " is not supported by the MOCA JDBC driver");
    }

    // ------------------------------------------------------------- read-only

    // MOCA publishes results; it has no updatable cursor. Every mutator below is a
    // hard SQLFeatureNotSupportedException rather than a silent no-op, so a caller
    // that tries to write through the grid is told, not quietly ignored.

    @Override public boolean rowUpdated() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public boolean rowInserted() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public boolean rowDeleted() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void insertRow() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateRow() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void deleteRow() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void refreshRow() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void cancelRowUpdates() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void moveToInsertRow() throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void moveToCurrentRow() throws SQLException { throw unsupported("Updatable result sets"); }

    @Override public void updateNull(int c) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBoolean(int c, boolean v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateByte(int c, byte v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateShort(int c, short v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateInt(int c, int v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateLong(int c, long v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateFloat(int c, float v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateDouble(int c, double v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBigDecimal(int c, BigDecimal v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateString(int c, String v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBytes(int c, byte[] v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateDate(int c, Date v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateTime(int c, Time v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateTimestamp(int c, Timestamp v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(int c, InputStream v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(int c, InputStream v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(int c, Reader v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(int c, Object v, int s) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(int c, Object v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNull(String c) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBoolean(String c, boolean v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateByte(String c, byte v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateShort(String c, short v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateInt(String c, int v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateLong(String c, long v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateFloat(String c, float v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateDouble(String c, double v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBigDecimal(String c, BigDecimal v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateString(String c, String v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBytes(String c, byte[] v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateDate(String c, Date v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateTime(String c, Time v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateTimestamp(String c, Timestamp v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(String c, InputStream v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(String c, InputStream v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(String c, Reader v, int l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(String c, Object v, int s) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(String c, Object v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateRef(int c, Ref v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateRef(String c, Ref v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(int c, Blob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(String c, Blob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(int c, Clob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(String c, Clob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateArray(int c, Array v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateArray(String c, Array v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateRowId(int c, RowId v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateRowId(String c, RowId v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNString(int c, String v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNString(String c, String v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(int c, NClob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(String c, NClob v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateSQLXML(int c, SQLXML v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateSQLXML(String c, SQLXML v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNCharacterStream(int c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNCharacterStream(String c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(int c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(int c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(int c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(String c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(String c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(String c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(int c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(String c, InputStream v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(int c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(String c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(int c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(String c, Reader v, long l) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNCharacterStream(int c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNCharacterStream(String c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(int c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(int c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(int c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateAsciiStream(String c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBinaryStream(String c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateCharacterStream(String c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(int c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateBlob(String c, InputStream v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(int c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateClob(String c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(int c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateNClob(String c, Reader v) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(int c, Object v, SQLType t, int s) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(String c, Object v, SQLType t, int s) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(int c, Object v, SQLType t) throws SQLException { throw unsupported("Updatable result sets"); }
    @Override public void updateObject(String c, Object v, SQLType t) throws SQLException { throw unsupported("Updatable result sets"); }

    // ------------------------------------------------ types MOCA does not have

    @Override public Object getObject(int c, Map<String, Class<?>> map) throws SQLException { throw unsupported("Type maps"); }
    @Override public Object getObject(String c, Map<String, Class<?>> map) throws SQLException { throw unsupported("Type maps"); }
    @Override public Ref getRef(int c) throws SQLException { throw unsupported("REF values"); }
    @Override public Ref getRef(String c) throws SQLException { throw unsupported("REF values"); }
    @Override public Blob getBlob(int c) throws SQLException { throw unsupported("BLOB values"); }
    @Override public Blob getBlob(String c) throws SQLException { throw unsupported("BLOB values"); }
    @Override public Clob getClob(int c) throws SQLException { throw unsupported("CLOB values"); }
    @Override public Clob getClob(String c) throws SQLException { throw unsupported("CLOB values"); }
    @Override public Array getArray(int c) throws SQLException { throw unsupported("ARRAY values"); }
    @Override public Array getArray(String c) throws SQLException { throw unsupported("ARRAY values"); }
    @Override public URL getURL(int c) throws SQLException { throw unsupported("DATALINK values"); }
    @Override public URL getURL(String c) throws SQLException { throw unsupported("DATALINK values"); }
    @Override public RowId getRowId(int c) throws SQLException { throw unsupported("ROWID values"); }
    @Override public RowId getRowId(String c) throws SQLException { throw unsupported("ROWID values"); }
    @Override public NClob getNClob(int c) throws SQLException { throw unsupported("NCLOB values"); }
    @Override public NClob getNClob(String c) throws SQLException { throw unsupported("NCLOB values"); }
    @Override public SQLXML getSQLXML(int c) throws SQLException { throw unsupported("SQLXML values"); }
    @Override public SQLXML getSQLXML(String c) throws SQLException { throw unsupported("SQLXML values"); }
    @Override public String getNString(int c) throws SQLException { return getString(c); }
    @Override public String getNString(String c) throws SQLException { return getString(c); }
    @Override public Reader getNCharacterStream(int c) throws SQLException { return getCharacterStream(c); }
    @Override public Reader getNCharacterStream(String c) throws SQLException { return getCharacterStream(c); }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(final int c) throws SQLException { throw unsupported("getUnicodeStream"); }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(final String c) throws SQLException { throw unsupported("getUnicodeStream"); }
}
