package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaColumn;
import io.github.voodoo262.jdbc.moca.protocol.MocaRows;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Column metadata for a {@link MocaResultSet}. Indexes are 1-based, per JDBC.
 */
final class MocaResultSetMetaData implements ResultSetMetaData
{
    private final MocaRows rows;

    MocaResultSetMetaData(final MocaRows rows)
    {
        this.rows = rows;
    }

    @Override
    public int getColumnCount()
    {
        return rows.getColumnCount();
    }

    @Override public String getColumnName(final int column) throws SQLException { return columnAt(column).getName(); }
    @Override public String getColumnLabel(final int column) throws SQLException { return columnAt(column).getName(); }
    @Override public int getColumnType(final int column) throws SQLException { return columnAt(column).getSqlType(); }
    @Override public String getColumnTypeName(final int column) throws SQLException { return columnAt(column).getSqlTypeName(); }
    @Override public String getColumnClassName(final int column) throws SQLException { return columnAt(column).getJavaClass().getName(); }
    @Override public int getPrecision(final int column) throws SQLException { return columnAt(column).getLength(); }
    @Override public int getColumnDisplaySize(final int column) throws SQLException { return columnAt(column).getLength(); }

    @Override
    public int isNullable(final int column) throws SQLException
    {
        return columnAt(column).isNullable() ? columnNullable : columnNoNulls;
    }

    @Override
    public int getScale(final int column) throws SQLException
    {
        // MOCA reports a length but no scale, so this is unknown rather than zero.
        columnAt(column);
        return 0;
    }

    @Override
    public boolean isSigned(final int column) throws SQLException
    {
        switch (columnAt(column).getSqlType())
        {
            case Types.INTEGER:
            case Types.DOUBLE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isCaseSensitive(final int column) throws SQLException
    {
        return columnAt(column).getSqlType() == Types.VARCHAR;
    }

    // A MOCA result is a published projection, not a view over a named table: the
    // server does not tell us which table (if any) a column came from.
    @Override public String getTableName(final int column) throws SQLException { columnAt(column); return ""; }
    @Override public String getSchemaName(final int column) throws SQLException { columnAt(column); return ""; }
    @Override public String getCatalogName(final int column) throws SQLException { columnAt(column); return ""; }

    @Override public boolean isAutoIncrement(final int column) throws SQLException { columnAt(column); return false; }
    @Override public boolean isCurrency(final int column) throws SQLException { columnAt(column); return false; }
    @Override public boolean isSearchable(final int column) throws SQLException { columnAt(column); return true; }
    @Override public boolean isReadOnly(final int column) throws SQLException { columnAt(column); return true; }
    @Override public boolean isWritable(final int column) throws SQLException { columnAt(column); return false; }
    @Override public boolean isDefinitelyWritable(final int column) throws SQLException { columnAt(column); return false; }

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

    /** @param column 1-based */
    private MocaColumn columnAt(final int column) throws SQLException
    {
        if (column < 1 || column > rows.getColumnCount())
        {
            throw new SQLException(
                    "Column index " + column + " is out of range 1.." + rows.getColumnCount(), "22023");
        }
        return rows.getColumn(column - 1);
    }
}
