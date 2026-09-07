package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaColumn;
import io.github.voodoo262.jdbc.moca.protocol.MocaRows;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the fixed-shape result sets {@link java.sql.DatabaseMetaData} is contractually
 * required to return.
 *
 * <p>JDBC specifies each of these down to the column name, order and type, and callers
 * (DBeaver among them) read them positionally. So the shapes here are not ours to choose:
 * they are transcribed from the {@code DatabaseMetaData} javadoc, and a metadata method
 * that has nothing to report must return an <em>empty result set of the right shape</em>,
 * never null and never a throw.
 */
final class MetaResults
{
    /** MOCA's VARCHAR type character. */
    private static final char STR = 'S';
    /** MOCA's INTEGER type character. */
    private static final char INT = 'I';
    /** MOCA's BOOLEAN type character. */
    private static final char BOOL = 'O';

    private final List<MocaColumn> columns = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();

    private MetaResults() {}

    static MetaResults of(final String... columnNames)
    {
        final MetaResults results = new MetaResults();
        for (final String name : columnNames)
        {
            results.columns.add(new MocaColumn(name, STR, 0, true));
        }
        return results;
    }

    /**
     * Marks a column as INTEGER. JDBC types such as {@code DATA_TYPE} and {@code NULLABLE}
     * are read with {@code getInt}, so they must be typed, not stringly.
     */
    MetaResults intColumn(final String name)
    {
        return retype(name, INT);
    }

    private MetaResults retype(final String name, final char mocaType)
    {
        for (int i = 0; i < columns.size(); i++)
        {
            if (columns.get(i).getName().equals(name))
            {
                columns.set(i, new MocaColumn(name, mocaType, 0, true));
                return this;
            }
        }
        throw new IllegalArgumentException("No such column: " + name);
    }

    /** {@code NON_UNIQUE} is read with getBoolean, so it must not be stringly typed. */
    MetaResults boolColumn(final String name)
    {
        return retype(name, BOOL);
    }

    MetaResults row(final Object... values)
    {
        if (values.length != columns.size())
        {
            throw new IllegalArgumentException(
                    "Row has " + values.length + " values but there are " + columns.size() + " columns");
        }
        final List<String> row = new ArrayList<>(values.length);
        for (final Object value : values)
        {
            row.add(value == null ? null : String.valueOf(value));
        }
        rows.add(row);
        return this;
    }

    MocaRows build()
    {
        return new MocaRows(columns, rows);
    }

    /** @return an empty result set with the given columns — the shape JDBC demands when there is nothing to report. */
    static MocaRows empty(final String... columnNames)
    {
        return of(columnNames).build();
    }

    // --------------------------------------------------- the specified shapes

    static final String[] TABLES = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"
    };

    static final String[] COLUMNS = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
        "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
        "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
        "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
        "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"
    };

    /** Applies the integer typing the {@code getColumns} contract requires. */
    static MetaResults columnsResult()
    {
        return of(COLUMNS)
                .intColumn("DATA_TYPE")
                .intColumn("COLUMN_SIZE")
                .intColumn("BUFFER_LENGTH")
                .intColumn("DECIMAL_DIGITS")
                .intColumn("NUM_PREC_RADIX")
                .intColumn("NULLABLE")
                .intColumn("SQL_DATA_TYPE")
                .intColumn("SQL_DATETIME_SUB")
                .intColumn("CHAR_OCTET_LENGTH")
                .intColumn("ORDINAL_POSITION")
                .intColumn("SOURCE_DATA_TYPE");
    }

    static final String[] SCHEMAS = { "TABLE_SCHEM", "TABLE_CATALOG" };

    static final String[] CATALOGS = { "TABLE_CAT" };

    static final String[] TABLE_TYPES = { "TABLE_TYPE" };

    static final String[] TYPE_INFO = {
        "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS",
        "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE",
        "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE",
        "SQL_DATETIME_SUB", "NUM_PREC_RADIX"
    };

    static MetaResults typeInfoResult()
    {
        return of(TYPE_INFO)
                .intColumn("DATA_TYPE")
                .intColumn("PRECISION")
                .intColumn("NULLABLE")
                .intColumn("SEARCHABLE")
                .intColumn("MINIMUM_SCALE")
                .intColumn("MAXIMUM_SCALE")
                .intColumn("SQL_DATA_TYPE")
                .intColumn("SQL_DATETIME_SUB")
                .intColumn("NUM_PREC_RADIX");
    }

    static final String[] PRIMARY_KEYS = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"
    };

    /** {@code KEY_SEQ} is read with getShort, so it must be typed. */
    static MetaResults primaryKeysResult()
    {
        return of(PRIMARY_KEYS).intColumn("KEY_SEQ");
    }

    static final String[] CROSS_REFERENCE = {
        "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
        "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
        "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY"
    };

    static final String[] INDEX_INFO = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME",
        "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES",
        "FILTER_CONDITION"
    };

    static MetaResults indexInfoResult()
    {
        return of(INDEX_INFO)
                .boolColumn("NON_UNIQUE")
                .intColumn("TYPE")
                .intColumn("ORDINAL_POSITION")
                .intColumn("CARDINALITY")
                .intColumn("PAGES");
    }

    static final String[] PROCEDURES = {
        "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2", "RESERVED3",
        "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"
    };

    static final String[] PROCEDURE_COLUMNS = {
        "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE",
        "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS",
        "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
        "IS_NULLABLE", "SPECIFIC_NAME"
    };

    static final String[] BEST_ROW_IDENTIFIER = {
        "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
        "DECIMAL_DIGITS", "PSEUDO_COLUMN"
    };

    static final String[] VERSION_COLUMNS = {
        "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
        "DECIMAL_DIGITS", "PSEUDO_COLUMN"
    };

    static final String[] TABLE_PRIVILEGES = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE"
    };

    static final String[] COLUMN_PRIVILEGES = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
        "IS_GRANTABLE"
    };

    static final String[] UDTS = {
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE"
    };

    static final String[] SUPER_TYPES = {
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME"
    };

    static final String[] SUPER_TABLES = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"
    };

    static final String[] ATTRIBUTES = {
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME",
        "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF",
        "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
        "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"
    };

    static final String[] CLIENT_INFO_PROPERTIES = {
        "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"
    };

    static final String[] FUNCTIONS = {
        "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME"
    };

    static final String[] FUNCTION_COLUMNS = {
        "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE",
        "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS",
        "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"
    };

    static final String[] PSEUDO_COLUMNS = {
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE",
        "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH",
        "IS_NULLABLE"
    };
}
