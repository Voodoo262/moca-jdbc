package io.github.voodoo262.jdbc.moca.protocol;

import java.sql.Types;

/**
 * A single column as described by the {@code <metadata>} block of a MOCA response.
 *
 * <p>MOCA describes a column's type with a single character. The mapping to
 * {@link java.sql.Types} is fixed by the server, not by us.
 */
public final class MocaColumn
{
    private final String name;
    private final char mocaType;
    private final int length;
    private final boolean nullable;

    public MocaColumn(final String name, final char mocaType, final int length, final boolean nullable)
    {
        this.name = name;
        this.mocaType = mocaType;
        this.length = length;
        this.nullable = nullable;
    }

    public String getName() { return name; }

    public char getMocaType() { return mocaType; }

    public int getLength() { return length; }

    public boolean isNullable() { return nullable; }

    /** @return the {@link java.sql.Types} constant this MOCA type maps to. */
    public int getSqlType()
    {
        switch (mocaType)
        {
            case 'O': return Types.BOOLEAN;
            case 'I':
            case 'P': return Types.INTEGER;
            case 'F':
            case 'X': return Types.DOUBLE;
            case 'D': return Types.TIMESTAMP;
            case 'S':
            case 'Z': return Types.VARCHAR;
            case 'V': return Types.BINARY;
            // 'R' (result set), 'J', 'G' and '?' have no JDBC equivalent. An
            // unrecognised character is reported as OTHER rather than throwing:
            // a new server-side type must not take down an existing client.
            default:  return Types.OTHER;
        }
    }

    public String getSqlTypeName()
    {
        switch (getSqlType())
        {
            case Types.BOOLEAN:   return "BOOLEAN";
            case Types.INTEGER:   return "INTEGER";
            case Types.DOUBLE:    return "DOUBLE";
            case Types.TIMESTAMP: return "TIMESTAMP";
            case Types.VARCHAR:   return "VARCHAR";
            case Types.BINARY:    return "BINARY";
            default:              return "OTHER";
        }
    }

    /** @return the Java class {@code getObject} yields for this column. */
    public Class<?> getJavaClass()
    {
        switch (getSqlType())
        {
            case Types.BOOLEAN:   return Boolean.class;
            case Types.INTEGER:   return Integer.class;
            case Types.DOUBLE:    return Double.class;
            case Types.TIMESTAMP: return java.sql.Timestamp.class;
            case Types.BINARY:    return byte[].class;
            default:              return String.class;
        }
    }

    @Override
    public String toString()
    {
        return name + " " + getSqlTypeName() + "(" + length + ")" + (nullable ? "" : " NOT NULL");
    }
}
