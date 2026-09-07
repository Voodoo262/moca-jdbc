package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaColumn;
import io.github.voodoo262.jdbc.moca.protocol.MocaRows;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Metadata for a MOCA connection.
 *
 * <p>MOCA is a command language, not a schema, so much of this describes what the driver
 * <em>cannot</em> do. The part that does work is the catalog, which comes from MOCA's own
 * commands rather than from the database underneath:
 *
 * <ul>
 *   <li>{@link #getTables} &mdash; {@code list user tables description}</li>
 *   <li>{@link #getColumns} &mdash; {@code list table columns where table = '...'}</li>
 *   <li>{@link #getIndexInfo}, {@link #getPrimaryKeys} &mdash;
 *       {@code list table indexes where table = '...'}</li>
 * </ul>
 *
 * <p>Those commands take no name pattern of their own, so the JDBC patterns are applied
 * here, case-insensitively &mdash; the commands themselves disagree about casing, with
 * {@code list table columns} answering in lower case and {@code list table indexes} in
 * upper.
 *
 * <p>Every catalog query is wrapped so that a failure yields an <em>empty</em> result set
 * rather than an exception: a tool browsing the tree must degrade to "no tables" rather
 * than break outright, since whether these commands are available depends on the MOCA
 * user's role.
 */
final class MocaDatabaseMetaData implements DatabaseMetaData
{
    private static final Logger LOG = Logger.getLogger(MocaDatabaseMetaData.class.getName());

    private static final Pattern MATCH_EVERYTHING = Pattern.compile(".*", Pattern.DOTALL);

    /**
     * MOCA's own command vocabulary. Not SQL keywords — these are what a MOCA console
     * would highlight, and what {@code getSQLKeywords} is asked for.
     */
    private static final String MOCA_KEYWORDS = String.join(",",
        "publish", "data", "where", "and", "or", "not", "list", "get", "create", "change",
        "remove", "add", "delete", "set", "if", "else", "while", "try", "catch", "finally",
        "return", "raise", "commit", "rollback", "do", "then", "end", "null", "true", "false",
        "like", "in", "is", "between", "order", "by", "group", "having", "distinct", "top",
        "rows", "login", "logout", "user");

    private final MocaConnection connection;
    /** Created lazily and reused: every metadata result set has to hang off a Statement. */
    private MocaStatement metadataStatement;

    MocaDatabaseMetaData(final MocaConnection connection)
    {
        this.connection = connection;
    }

    // ------------------------------------------------------------- identity

    @Override public Connection getConnection() { return connection; }
    @Override public String getURL() { return connection.getUrl(); }
    @Override public String getUserName() { return connection.getUser(); }

    @Override public String getDatabaseProductName() { return "MOCA"; }

    @Override
    public String getDatabaseProductVersion()
    {
        // Seen in real MOCA traffic; if the command or the column is absent on this
        // server, an unknown version is better than a failed connection.
        try
        {
            final MocaRows rows = connection.getClient().execute(
                    "list library versions where category = 'MOCAbase'", true);
            final int version = rows.indexOfColumn("version");
            if (rows.getRowCount() > 0 && version >= 0)
            {
                final String value = rows.getValue(0, version);
                if (value != null && !value.isBlank()) return value;
            }
        }
        catch (final SQLException ex)
        {
            LOG.log(Level.FINE, "Could not read MOCA version", ex);
        }
        return "unknown";
    }

    @Override public int getDatabaseMajorVersion() { return 0; }
    @Override public int getDatabaseMinorVersion() { return 0; }

    @Override public String getDriverName() { return MocaDriver.NAME; }
    @Override public String getDriverVersion() { return MocaDriver.getVersion(); }
    @Override public int getDriverMajorVersion() { return MocaDriver.VERSION_MAJOR; }
    @Override public int getDriverMinorVersion() { return MocaDriver.VERSION_MINOR; }

    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }

    // ------------------------------------------------------------- catalog

    /**
     * Lists tables with their descriptions.
     *
     * <p>{@code list user tables} alone is much cheaper (roughly 0.05s against a 1000-table
     * schema, versus 0.8s for this), but it returns no description, and the description is
     * what a tool shows as the table's remarks. The extra second on a tree expand is worth
     * it; if it ever is not, dropping the {@code description} suffix is the whole change.
     */
    private static final String LIST_TABLES = "list user tables description";

    @Override
    public ResultSet getTables(final String catalog, final String schemaPattern,
                               final String tableNamePattern, final String[] types) throws SQLException
    {
        // JDBC lets a caller ask for only certain table types. MOCA publishes one kind of
        // thing here, so a request that excludes TABLE matches nothing.
        if (types != null && !containsIgnoreCase(types, "TABLE"))
        {
            return statementOf(MetaResults.empty(MetaResults.TABLES));
        }

        final MocaRows rows = queryQuietly(LIST_TABLES);
        final int nameColumn = rows.indexOfColumn("table_name");
        final int descriptionColumn = rows.indexOfColumn("description");

        final MetaResults results = MetaResults.of(MetaResults.TABLES);
        if (nameColumn < 0)
        {
            return statementOf(results.build());
        }

        // The command takes no pattern of its own, so the JDBC pattern is applied here.
        final Pattern pattern = likePattern(tableNamePattern);
        final List<String[]> matched = new ArrayList<>();
        for (int row = 0; row < rows.getRowCount(); row++)
        {
            final String name = rows.getValue(row, nameColumn);
            if (name == null || !pattern.matcher(name).matches()) continue;
            matched.add(new String[] {
                name,
                descriptionColumn < 0 ? null : rows.getValue(row, descriptionColumn)
            });
        }
        // JDBC specifies this ordering, and MOCA's own is not guaranteed to match.
        matched.sort(Comparator.comparing(entry -> entry[0], String.CASE_INSENSITIVE_ORDER));

        for (final String[] entry : matched)
        {
            results.row(null, null, entry[0], "TABLE", entry[1], null, null, null, null, null);
        }
        return statementOf(results.build());
    }

    @Override
    public ResultSet getColumns(final String catalog, final String schemaPattern,
                                final String tableNamePattern, final String columnNamePattern)
            throws SQLException
    {
        final MetaResults results = MetaResults.columnsResult();
        final Pattern columns = likePattern(columnNamePattern);

        for (final String table : tablesMatching(tableNamePattern))
        {
            final MocaRows rows = queryQuietly(
                    "list table columns where table = '" + escape(table) + "'");

            final int columnName = rows.indexOfColumn("column_name");
            if (columnName < 0) continue;

            final int description = rows.indexOfColumn("lngdsc");
            final int mocaType = rows.indexOfColumn("comtyp");
            final int length = rows.indexOfColumn("length");
            final int nullable = rows.indexOfColumn("null_flg");

            for (int row = 0; row < rows.getRowCount(); row++)
            {
                final String name = rows.getValue(row, columnName);
                if (name == null || !columns.matcher(name).matches()) continue;

                // comtyp is MOCA's own single-character type, the same vocabulary the
                // response metadata uses -- so the mapping already exists and there is no
                // need to guess from a native SQL type name.
                final MocaColumn column = new MocaColumn(
                        name,
                        typeCharOf(mocaType < 0 ? null : rows.getValue(row, mocaType)),
                        0,
                        nullable < 0 || isTrue(rows.getValue(row, nullable)));

                results.row(
                    /* TABLE_CAT        */ null,
                    /* TABLE_SCHEM      */ null,
                    /* TABLE_NAME       */ table,
                    /* COLUMN_NAME      */ name,
                    /* DATA_TYPE        */ column.getSqlType(),
                    /* TYPE_NAME        */ column.getSqlTypeName(),
                    /* COLUMN_SIZE      */ length < 0 ? null : rows.getValue(row, length),
                    /* BUFFER_LENGTH    */ null,
                    /* DECIMAL_DIGITS   */ null,
                    /* NUM_PREC_RADIX   */ 10,
                    /* NULLABLE         */ column.isNullable() ? columnNullable : columnNoNulls,
                    /* REMARKS          */ description < 0 ? null : rows.getValue(row, description),
                    /* COLUMN_DEF       */ null,
                    /* SQL_DATA_TYPE    */ null,
                    /* SQL_DATETIME_SUB */ null,
                    /* CHAR_OCTET_LENGTH*/ null,
                    /* ORDINAL_POSITION */ row + 1,
                    /* IS_NULLABLE      */ column.isNullable() ? "YES" : "NO",
                    /* SCOPE_CATALOG    */ null,
                    /* SCOPE_SCHEMA     */ null,
                    /* SCOPE_TABLE      */ null,
                    /* SOURCE_DATA_TYPE */ null,
                    /* IS_AUTOINCREMENT */ "",
                    /* IS_GENERATEDCOLUMN */ "");
            }
        }
        return statementOf(results.build());
    }

    @Override
    public ResultSet getPrimaryKeys(final String catalog, final String schema, final String table)
            throws SQLException
    {
        final MetaResults results = MetaResults.primaryKeysResult();
        if (table == null) return statementOf(results.build());

        for (final MocaIndex index : indexesOf(table))
        {
            if (!index.isPrimaryKey()) continue;

            final List<String> keys = index.getKeys();
            for (int i = 0; i < keys.size(); i++)
            {
                results.row(null, null, index.getTable(), keys.get(i), i + 1, index.getName());
            }
            // A table has at most one primary key; stop rather than merge two candidates.
            break;
        }
        return statementOf(results.build());
    }

    @Override
    public ResultSet getIndexInfo(final String catalog, final String schema, final String table,
                                  final boolean unique, final boolean approximate) throws SQLException
    {
        final MetaResults results = MetaResults.indexInfoResult();
        if (table == null) return statementOf(results.build());

        for (final MocaIndex index : indexesOf(table))
        {
            if (unique && !index.isUnique()) continue;

            final List<String> keys = index.getKeys();
            for (int i = 0; i < keys.size(); i++)
            {
                results.row(
                    /* TABLE_CAT        */ null,
                    /* TABLE_SCHEM      */ null,
                    /* TABLE_NAME       */ index.getTable(),
                    /* NON_UNIQUE       */ !index.isUnique(),
                    /* INDEX_QUALIFIER  */ null,
                    /* INDEX_NAME       */ index.getName(),
                    /* TYPE             */ index.isClustered() ? tableIndexClustered : tableIndexOther,
                    /* ORDINAL_POSITION */ i + 1,
                    /* COLUMN_NAME      */ keys.get(i),
                    // MOCA does not say which direction a key is stored in.
                    /* ASC_OR_DESC      */ null,
                    /* CARDINALITY      */ null,
                    /* PAGES            */ null,
                    /* FILTER_CONDITION */ null);
            }
        }
        return statementOf(results.build());
    }

    /**
     * @return the indexes on {@code table}, as {@code list table indexes} reports them.
     *         Empty rather than throwing if the command is unavailable.
     */
    private List<MocaIndex> indexesOf(final String table)
    {
        final MocaRows rows = queryQuietly(
                "list table indexes where table = '" + escape(table) + "'");

        final int tableName = rows.indexOfColumn("table_name");
        final int indexName = rows.indexOfColumn("index_name");
        final int description = rows.indexOfColumn("index_description");
        final int keys = rows.indexOfColumn("index_keys");

        final List<MocaIndex> indexes = new ArrayList<>();
        if (indexName < 0 || keys < 0) return indexes;

        for (int row = 0; row < rows.getRowCount(); row++)
        {
            indexes.add(new MocaIndex(
                    tableName < 0 ? table : rows.getValue(row, tableName),
                    rows.getValue(row, indexName),
                    description < 0 ? null : rows.getValue(row, description),
                    rows.getValue(row, keys)));
        }
        return indexes;
    }

    /**
     * One row of {@code list table indexes}.
     *
     * <p>MOCA reports an index's properties as English prose in {@code index_description}
     * ({@code "clustered, unique, primary key located on primary"}), not as flags, so they
     * have to be read back out of it. Substring matching is crude but it is what the server
     * offers.
     */
    private static final class MocaIndex
    {
        private final String table;
        private final String name;
        private final String description;
        private final String keys;

        MocaIndex(final String table, final String name, final String description, final String keys)
        {
            this.table = table;
            this.name = name;
            this.description = description == null ? "" : description.toLowerCase(Locale.ROOT);
            this.keys = keys;
        }

        String getTable() { return table; }

        String getName() { return name; }

        boolean isPrimaryKey() { return description.contains("primary key"); }

        /** A primary key is unique whether or not the description bothers to say so. */
        boolean isUnique() { return description.contains("unique") || isPrimaryKey(); }

        boolean isClustered()
        {
            return description.contains("clustered") && !description.contains("nonclustered");
        }

        /** @return the key columns in index order; {@code index_keys} is a comma-separated list. */
        List<String> getKeys()
        {
            final List<String> columns = new ArrayList<>();
            if (keys == null) return columns;
            for (final String key : keys.split(","))
            {
                final String trimmed = key.trim();
                if (!trimmed.isEmpty()) columns.add(trimmed);
            }
            return columns;
        }
    }

    @Override
    public ResultSet getTableTypes() throws SQLException
    {
        return statementOf(MetaResults.of(MetaResults.TABLE_TYPES).row("TABLE").build());
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException
    {
        final MetaResults results = MetaResults.typeInfoResult();
        addType(results, "VARCHAR", Types.VARCHAR, true);
        addType(results, "INTEGER", Types.INTEGER, false);
        addType(results, "DOUBLE", Types.DOUBLE, false);
        addType(results, "BOOLEAN", Types.BOOLEAN, false);
        addType(results, "TIMESTAMP", Types.TIMESTAMP, true);
        addType(results, "BINARY", Types.BINARY, true);
        return statementOf(results.build());
    }

    private static void addType(final MetaResults results, final String name, final int type,
                                final boolean quoted)
    {
        results.row(
            /* TYPE_NAME          */ name,
            /* DATA_TYPE          */ type,
            /* PRECISION          */ 0,
            /* LITERAL_PREFIX     */ quoted ? "'" : null,
            /* LITERAL_SUFFIX     */ quoted ? "'" : null,
            /* CREATE_PARAMS      */ null,
            /* NULLABLE           */ typeNullable,
            /* CASE_SENSITIVE     */ type == Types.VARCHAR,
            /* SEARCHABLE         */ typeSearchable,
            /* UNSIGNED_ATTRIBUTE */ false,
            /* FIXED_PREC_SCALE   */ false,
            /* AUTO_INCREMENT     */ false,
            /* LOCAL_TYPE_NAME    */ name,
            /* MINIMUM_SCALE      */ 0,
            /* MAXIMUM_SCALE      */ 0,
            /* SQL_DATA_TYPE      */ 0,
            /* SQL_DATETIME_SUB   */ 0,
            /* NUM_PREC_RADIX     */ 10);
    }

    // MOCA has no catalogs or schemas of its own: a session sees one flat namespace.
    @Override public ResultSet getCatalogs() throws SQLException { return statementOf(MetaResults.empty(MetaResults.CATALOGS)); }
    @Override public ResultSet getSchemas() throws SQLException { return statementOf(MetaResults.empty(MetaResults.SCHEMAS)); }
    @Override public ResultSet getSchemas(String c, String p) throws SQLException { return getSchemas(); }

// Foreign keys, procedures and the rest are not reachable through MOCA. JDBC requires
    // an empty result set of the correct shape here, not an exception.
    @Override public ResultSet getImportedKeys(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.CROSS_REFERENCE)); }
    @Override public ResultSet getExportedKeys(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.CROSS_REFERENCE)); }
    @Override public ResultSet getCrossReference(String pc, String ps, String pt, String fc, String fs, String ft) throws SQLException { return statementOf(MetaResults.empty(MetaResults.CROSS_REFERENCE)); }
    @Override public ResultSet getProcedures(String c, String s, String p) throws SQLException { return statementOf(MetaResults.empty(MetaResults.PROCEDURES)); }
    @Override public ResultSet getProcedureColumns(String c, String s, String p, String col) throws SQLException { return statementOf(MetaResults.empty(MetaResults.PROCEDURE_COLUMNS)); }
    @Override public ResultSet getBestRowIdentifier(String c, String s, String t, int scope, boolean n) throws SQLException { return statementOf(MetaResults.empty(MetaResults.BEST_ROW_IDENTIFIER)); }
    @Override public ResultSet getVersionColumns(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.VERSION_COLUMNS)); }
    @Override public ResultSet getTablePrivileges(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.TABLE_PRIVILEGES)); }
    @Override public ResultSet getColumnPrivileges(String c, String s, String t, String col) throws SQLException { return statementOf(MetaResults.empty(MetaResults.COLUMN_PRIVILEGES)); }
    @Override public ResultSet getUDTs(String c, String s, String t, int[] types) throws SQLException { return statementOf(MetaResults.empty(MetaResults.UDTS)); }
    @Override public ResultSet getSuperTypes(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.SUPER_TYPES)); }
    @Override public ResultSet getSuperTables(String c, String s, String t) throws SQLException { return statementOf(MetaResults.empty(MetaResults.SUPER_TABLES)); }
    @Override public ResultSet getAttributes(String c, String s, String t, String a) throws SQLException { return statementOf(MetaResults.empty(MetaResults.ATTRIBUTES)); }
    @Override public ResultSet getClientInfoProperties() throws SQLException { return statementOf(MetaResults.empty(MetaResults.CLIENT_INFO_PROPERTIES)); }
    @Override public ResultSet getFunctions(String c, String s, String f) throws SQLException { return statementOf(MetaResults.empty(MetaResults.FUNCTIONS)); }
    @Override public ResultSet getFunctionColumns(String c, String s, String f, String col) throws SQLException { return statementOf(MetaResults.empty(MetaResults.FUNCTION_COLUMNS)); }
    @Override public ResultSet getPseudoColumns(String c, String s, String t, String col) throws SQLException { return statementOf(MetaResults.empty(MetaResults.PSEUDO_COLUMNS)); }

    // ------------------------------------------------------ syntax & capability

    /** @return a space — MOCA does not quote identifiers, which JDBC signals this way. */
    @Override public String getIdentifierQuoteString() { return " "; }

    @Override public String getSQLKeywords() { return MOCA_KEYWORDS; }
    @Override public String getNumericFunctions() { return ""; }
    @Override public String getStringFunctions() { return ""; }
    @Override public String getSystemFunctions() { return ""; }
    @Override public String getTimeDateFunctions() { return ""; }
    /**
     * @return the empty string. There is no LIKE escape: {@link #getTables} and
     *         {@link #getColumns} interpolate their patterns into native SQL and only
     *         escape quotes, so claiming an escape character here would mislead a caller
     *         that dutifully used it. {@link #supportsLikeEscapeClause()} is false to match.
     */
    @Override public String getSearchStringEscape() { return ""; }
    @Override public String getExtraNameCharacters() { return "_"; }
    @Override public String getCatalogTerm() { return "catalog"; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public String getProcedureTerm() { return "command"; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public boolean isCatalogAtStart() { return false; }

    @Override public boolean isReadOnly() throws SQLException { return connection.isReadOnly(); }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return false; }
    @Override public boolean nullsAreSortedHigh() { return false; }
    @Override public boolean nullsAreSortedLow() { return false; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }

    // MOCA command names are conventionally lower case and are not case-folded.
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return true; }
    @Override public boolean storesMixedCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return false; }

    @Override public boolean supportsAlterTableWithAddColumn() { return false; }
    @Override public boolean supportsAlterTableWithDropColumn() { return false; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int from, int to) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return false; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return false; }
    @Override public boolean supportsOrderByUnrelated() { return false; }
    @Override public boolean supportsGroupBy() { return false; }
    @Override public boolean supportsGroupByUnrelated() { return false; }
    @Override public boolean supportsGroupByBeyondSelect() { return false; }
    @Override public boolean supportsLikeEscapeClause() { return false; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsMultipleTransactions() { return false; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return false; }
    @Override public boolean supportsCoreSQLGrammar() { return false; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return false; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins() { return false; }
    @Override public boolean supportsFullOuterJoins() { return false; }
    @Override public boolean supportsLimitedOuterJoins() { return false; }
    @Override public boolean supportsSchemasInDataManipulation() { return false; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInTableDefinitions() { return false; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return false; }
    @Override public boolean supportsSubqueriesInExists() { return false; }
    @Override public boolean supportsSubqueriesInIns() { return false; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return false; }
    @Override public boolean supportsCorrelatedSubqueries() { return false; }
    @Override public boolean supportsUnion() { return false; }
    @Override public boolean supportsUnionAll() { return false; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }

    // A MOCA response arrives whole, so there is no server-side limit the driver knows of.
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 0; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 0; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }

    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_NONE; }
    @Override public boolean supportsTransactions() { return true; }

    @Override
    public boolean supportsTransactionIsolationLevel(final int level)
    {
        // MOCA has commit/rollback but exposes no isolation control.
        return level == Connection.TRANSACTION_NONE;
    }

    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return false; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return true; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }

    @Override
    public boolean supportsResultSetType(final int type)
    {
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsResultSetConcurrency(final int type, final int concurrency)
    {
        return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }
    @Override public boolean supportsBatchUpdates() { return false; }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return false; }
    @Override public boolean supportsResultSetHoldability(int holdability) { return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getResultSetHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getSQLStateType() { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }

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

    /**
     * Escapes a value for a single-quoted MOCA literal by doubling embedded quotes.
     *
     * <p>Table names reach us from a caller and are interpolated into the {@code where}
     * clause of a MOCA command, so this is the boundary that keeps a table name from
     * becoming a second command. MOCA's command separator is {@code &}, which is harmless
     * inside a literal but not outside one.
     */
    private static String escape(final String value)
    {
        return value.replace("'", "''");
    }

    /** @return true if {@code values} holds {@code wanted}, ignoring case. */
    private static boolean containsIgnoreCase(final String[] values, final String wanted)
    {
        for (final String value : values)
        {
            if (wanted.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /**
     * Compiles a JDBC metadata pattern into a regex.
     *
     * <p>The MOCA commands behind this class take no pattern of their own, so matching
     * happens here. {@code %} and {@code _} are the JDBC wildcards; everything else is
     * literal, which is why the rest is quoted rather than passed through. A null or empty
     * pattern means "match everything".
     */
    private static Pattern likePattern(final String pattern)
    {
        if (pattern == null || pattern.isEmpty() || "%".equals(pattern))
        {
            return MATCH_EVERYTHING;
        }

        final StringBuilder regex = new StringBuilder();
        final StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++)
        {
            final char c = pattern.charAt(i);
            if (c == '%' || c == '_')
            {
                if (literal.length() > 0)
                {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '%' ? ".*" : ".");
            }
            else
            {
                literal.append(c);
            }
        }
        if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));

        // MOCA identifiers are not case sensitive, and the commands disagree with each
        // other about casing: list table columns answers in lower case, list table indexes
        // in upper.
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** @return every table name matching a JDBC table-name pattern. */
    private List<String> tablesMatching(final String tableNamePattern)
    {
        final MocaRows rows = queryQuietly(LIST_TABLES);
        final int nameColumn = rows.indexOfColumn("table_name");
        final List<String> tables = new ArrayList<>();
        if (nameColumn < 0) return tables;

        final Pattern pattern = likePattern(tableNamePattern);
        for (int row = 0; row < rows.getRowCount(); row++)
        {
            final String name = rows.getValue(row, nameColumn);
            if (name != null && pattern.matcher(name).matches()) tables.add(name);
        }
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        return tables;
    }

    /**
     * @return the MOCA type character {@code comtyp} carries, or {@code '?'} when it is
     *         absent, which {@link MocaColumn} maps to {@code OTHER}.
     */
    private static char typeCharOf(final String comtyp)
    {
        final String trimmed = comtyp == null ? "" : comtyp.trim();
        return trimmed.isEmpty() ? '?' : Character.toUpperCase(trimmed.charAt(0));
    }

    /** MOCA sends its boolean type as 1/0; be liberal about what a flag column might hold. */
    private static boolean isTrue(final String value)
    {
        if (value == null) return false;
        final String trimmed = value.trim();
        return "1".equals(trimmed) || "Y".equalsIgnoreCase(trimmed) || Boolean.parseBoolean(trimmed);
    }

    /**
     * Runs a catalog query, swallowing failure. A user without SELECT on the data
     * dictionary, or an unexpected RDBMS, must produce an empty catalog rather than a
     * broken connection.
     */
    private MocaRows queryQuietly(final String sql)
    {
        try
        {
            return connection.getClient().execute(sql, true);
        }
        catch (final SQLException ex)
        {
            LOG.log(Level.FINE, "MOCA catalog query failed: " + sql, ex);
            return MocaRows.empty();
        }
    }

    /**
     * Metadata result sets must be attached to a {@link java.sql.Statement}, since callers
     * routinely close the statement to release them.
     */
    private ResultSet statementOf(final MocaRows rows) throws SQLException
    {
        if (metadataStatement == null || metadataStatement.isClosed())
        {
            // Goes through createStatement so the connection tracks it and closes it
            // with everything else; a fresh one per call would leak.
            metadataStatement = (MocaStatement) connection.createStatement();
        }
        return metadataStatement.resultOf(rows);
    }
}
