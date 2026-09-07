package io.github.voodoo262.jdbc.moca.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An in-memory result carrier: the columns and rows of one MOCA response.
 *
 * <p>Values are held as the raw strings the server sent, and coerced lazily by the
 * JDBC layer. A {@code null} element is a SQL NULL.
 *
 * <p>Indexes here are <strong>0-based</strong>, like the Java collections they wrap.
 * The 1-based indexing JDBC mandates is applied by {@code MocaResultSet}, which is
 * the only thing that should ever touch these indexes.
 */
public final class MocaRows
{
    private static final MocaRows EMPTY = new MocaRows(Collections.emptyList(), Collections.emptyList());

    private final List<MocaColumn> columns;
    private final List<List<String>> rows;
    /** Lower-cased column name to 0-based ordinal; first occurrence wins, as in JDBC. */
    private final Map<String, Integer> byName;

    public MocaRows(final List<MocaColumn> columns, final List<List<String>> rows)
    {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));

        final Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < this.columns.size(); i++)
        {
            // putIfAbsent: a MOCA command can publish the same column name twice
            // (e.g. two joined tables); JDBC says findColumn returns the first.
            index.putIfAbsent(this.columns.get(i).getName().toLowerCase(Locale.ROOT), i);
        }
        this.byName = Collections.unmodifiableMap(index);
    }

    /** A result with no columns and no rows — what a MOCA command that publishes nothing returns. */
    public static MocaRows empty()
    {
        return EMPTY;
    }

    public List<MocaColumn> getColumns() { return columns; }

    public int getColumnCount() { return columns.size(); }

    public int getRowCount() { return rows.size(); }

    /** @param columnIndex 0-based */
    public MocaColumn getColumn(final int columnIndex) { return columns.get(columnIndex); }

    /**
     * @return the 0-based ordinal of {@code columnName}, or {@code -1} if there is no such column.
     */
    public int indexOfColumn(final String columnName)
    {
        if (columnName == null) return -1;
        final Integer index = byName.get(columnName.toLowerCase(Locale.ROOT));
        return index == null ? -1 : index;
    }

    /**
     * @return a view holding at most {@code maxRows} rows, or {@code this} if it already
     *         does. {@code maxRows <= 0} means unlimited, per {@link java.sql.Statement#setMaxRows}.
     */
    public MocaRows limit(final int maxRows)
    {
        if (maxRows <= 0 || maxRows >= rows.size())
        {
            return this;
        }
        return new MocaRows(columns, rows.subList(0, maxRows));
    }

    /**
     * @param rowIndex    0-based
     * @param columnIndex 0-based
     * @return the raw value, or {@code null} for SQL NULL
     */
    public String getValue(final int rowIndex, final int columnIndex)
    {
        final List<String> row = rows.get(rowIndex);
        // A short row is treated as trailing NULLs rather than an error: the server
        // omits trailing empty fields on some commands.
        return columnIndex < row.size() ? row.get(columnIndex) : null;
    }
}
