package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MocaDatabaseMetaDataTest
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

    /** {@code list user tables description} -- table_name plus a description. */
    private static String userTables(final String... namesAndDescriptions)
    {
        final StringBuilder data = new StringBuilder();
        for (int i = 0; i < namesAndDescriptions.length; i += 2)
        {
            data.append("<row><fld>").append(namesAndDescriptions[i]).append("</fld>")
                .append("<fld>").append(namesAndDescriptions[i + 1]).append("</fld></row>");
        }
        return FakeMocaServer.success(
            "<metadata>"
                + "<column name=\"table_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"description\" type=\"S\" length=\"80\" nullable=\"true\"/>"
                + "</metadata><data>" + data + "</data>");
    }

    /**
     * {@code list table columns} -- the real shape, from a BY WMS 2020.1.1 server. comtyp
     * is MOCA's own single-character type, so it maps straight onto java.sql.Types.
     */
    private static String tableColumns()
    {
        return FakeMocaServer.success(
            "<metadata>"
                + "<column name=\"table_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"column_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"lngdsc\" type=\"S\" length=\"80\" nullable=\"true\"/>"
                + "<column name=\"comtyp\" type=\"S\" length=\"1\" nullable=\"false\"/>"
                + "<column name=\"length\" type=\"I\" length=\"4\" nullable=\"true\"/>"
                + "<column name=\"null_flg\" type=\"O\" length=\"1\" nullable=\"true\"/>"
                + "<column name=\"pk_flg\" type=\"O\" length=\"1\" nullable=\"true\"/>"
                + "</metadata><data>"
                + "<row><fld>poldat</fld><fld>polcod</fld><fld>Policy Code</fld>"
                + "<fld>S</fld><fld>50</fld><fld>0</fld><fld>1</fld></row>"
                + "<row><fld>poldat</fld><fld>srtseq</fld><fld>Sort Sequence</fld>"
                + "<fld>I</fld><fld>4</fld><fld>0</fld><fld>1</fld></row>"
                + "<row><fld>poldat</fld><fld>rtflt1</fld><fld>Return Float 1</fld>"
                + "<fld>F</fld><fld>8</fld><fld>1</fld><fld>0</fld></row>"
                + "<row><fld>poldat</fld><fld>moddte</fld><fld>Date Last Modified</fld>"
                + "<fld>D</fld><fld>14</fld><fld>1</fld><fld>0</fld></row>"
                + "</data>");
    }

    /**
     * {@code list table indexes}. MOCA describes an index in prose rather than flags, which
     * is why the driver has to read "unique" and "primary key" back out of it.
     */
    private static String tableIndexes()
    {
        return FakeMocaServer.success(
            "<metadata>"
                + "<column name=\"table_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"index_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"index_description\" type=\"S\" length=\"80\" nullable=\"true\"/>"
                + "<column name=\"index_keys\" type=\"S\" length=\"200\" nullable=\"true\"/>"
                + "</metadata><data>"
                + "<row><fld>POLDAT</fld><fld>poldat_idx</fld>"
                + "<fld>nonclustered located on primary</fld>"
                + "<fld>polvar, polval, wh_id_tmpl, polcod, srtseq</fld></row>"
                + "<row><fld>POLDAT</fld><fld>poldat_pk</fld>"
                + "<fld>clustered, unique, primary key located on primary</fld>"
                + "<fld>polcod, polvar, polval, wh_id_tmpl, srtseq</fld></row>"
                + "</data>");
    }

    @Test
    void identifiesItself() throws SQLException
    {
        try (final Connection conn = connect())
        {
            final DatabaseMetaData meta = conn.getMetaData();
            assertEquals("MOCA", meta.getDatabaseProductName());
            assertEquals(MocaDriver.NAME, meta.getDriverName());
            assertEquals("rfaust", meta.getUserName());
            // MOCA does not quote identifiers; JDBC signals that with a space.
            assertEquals(" ", meta.getIdentifierQuoteString());
        }
    }

    // ------------------------------------------------------------------ tables

    @Test
    void listsTablesWithTheirDescriptions() throws SQLException
    {
        server.on("list user tables description",
                userTables("ABC_ESTIMATE", "ABC Estimate", "ACPT_PO_STS", "Acceptable Inbound Order Status"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getTables(null, null, "%", null))
        {
            assertTrue(rs.next());
            assertEquals("ABC_ESTIMATE", rs.getString("TABLE_NAME"));
            assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            assertEquals("ABC Estimate", rs.getString("REMARKS"));

            assertTrue(rs.next());
            assertEquals("ACPT_PO_STS", rs.getString("TABLE_NAME"));
            assertFalse(rs.next());
        }
    }

    @Test
    void appliesTheTableNamePatternClientSide() throws SQLException
    {
        // list user tables takes no pattern of its own, so the driver has to filter.
        server.on("list user tables description",
                userTables("AISLE", "Aisle", "AISLE_DCKLOC", "Aisle Dock Location", "POLDAT", "Policy"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getTables(null, null, "AISLE%", null))
        {
            assertEquals("AISLE", next(rs, "TABLE_NAME"));
            assertEquals("AISLE_DCKLOC", next(rs, "TABLE_NAME"));
            assertFalse(rs.next(), "POLDAT does not match AISLE%");
        }
    }

    @Test
    void underscoreInAPatternIsASingleCharacterWildcard() throws SQLException
    {
        server.on("list user tables description",
                userTables("ADR_ID", "Address id", "ADRXID", "Other", "ADRMST", "Address"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getTables(null, null, "ADR_ID", null))
        {
            // _ is a single-character wildcard in a JDBC pattern, not a literal underscore,
            // so ADRXID matches too. ADRMST does not: the pattern still anchors on "ID".
            assertEquals("ADR_ID", next(rs, "TABLE_NAME"));
            assertEquals("ADRXID", next(rs, "TABLE_NAME"));
            assertFalse(rs.next());
        }
    }

    @Test
    void aTableTypeFilterThatExcludesTablesMatchesNothing() throws SQLException
    {
        server.on("list user tables description", userTables("POLDAT", "Policy"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[] { "VIEW" }))
        {
            assertFalse(rs.next());
        }
    }

    // ----------------------------------------------------------------- columns

    @Test
    void listsColumnsWithTypesFromComtyp() throws SQLException
    {
        server.on("list user tables description", userTables("poldat", "Policy"));
        server.on("list table columns", tableColumns());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getColumns(null, null, "poldat", "%"))
        {
            assertTrue(rs.next());
            assertEquals("poldat", rs.getString("TABLE_NAME"));
            assertEquals("polcod", rs.getString("COLUMN_NAME"));
            assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
            assertEquals("Policy Code", rs.getString("REMARKS"));
            assertEquals(50, rs.getInt("COLUMN_SIZE"));
            assertEquals(1, rs.getInt("ORDINAL_POSITION"));
            // null_flg is 0, so this column is NOT NULL.
            assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
            assertEquals("NO", rs.getString("IS_NULLABLE"));

            assertEquals(Types.INTEGER, nextInt(rs, "DATA_TYPE"));   // srtseq, comtyp I
            assertEquals(Types.DOUBLE, nextInt(rs, "DATA_TYPE"));    // rtflt1, comtyp F

            assertTrue(rs.next());                                   // moddte, comtyp D
            assertEquals(Types.TIMESTAMP, rs.getInt("DATA_TYPE"));
            assertEquals(DatabaseMetaData.columnNullable, rs.getInt("NULLABLE"));
            assertFalse(rs.next());
        }
    }

    @Test
    void quotesTheTableNameIntoTheColumnsCommand() throws SQLException
    {
        server.on("list user tables description", userTables("x' or '1'='1", "injected"));
        server.on("list table columns", tableColumns());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getColumns(null, null, "x' or '1'='1", "%"))
        {
            assertTrue(rs.next());
        }

        final String sent = server.received().stream()
                .filter(command -> command.startsWith("list table columns"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the columns command was never sent"));
        // The quote has to be doubled: the name must stay one literal rather than closing
        // it and starting new command text.
        assertTrue(sent.contains("table = 'x'' or ''1''=''1'"),
                "the table name was not escaped: " + sent);
    }

    @Test
    void filtersColumnsByTheirOwnPattern() throws SQLException
    {
        server.on("list user tables description", userTables("poldat", "Policy"));
        server.on("list table columns", tableColumns());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getColumns(null, null, "poldat", "rt%"))
        {
            assertEquals("rtflt1", next(rs, "COLUMN_NAME"));
            assertFalse(rs.next());
        }
    }

    // ----------------------------------------------------------------- indexes

    @Test
    void listsIndexes() throws SQLException
    {
        server.on("list table indexes", tableIndexes());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "poldat", false, false))
        {
            // poldat_idx: nonclustered, not unique, five keys in index order.
            assertTrue(rs.next());
            assertEquals("poldat_idx", rs.getString("INDEX_NAME"));
            assertTrue(rs.getBoolean("NON_UNIQUE"));
            assertEquals(DatabaseMetaData.tableIndexOther, rs.getInt("TYPE"));
            assertEquals("polvar", rs.getString("COLUMN_NAME"));
            assertEquals(1, rs.getInt("ORDINAL_POSITION"));

            assertEquals("polval", next(rs, "COLUMN_NAME"));
            assertEquals("wh_id_tmpl", next(rs, "COLUMN_NAME"));
            assertEquals("polcod", next(rs, "COLUMN_NAME"));
            assertEquals("srtseq", next(rs, "COLUMN_NAME"));

            // poldat_pk: "clustered, unique, primary key located on primary"
            assertTrue(rs.next());
            assertEquals("poldat_pk", rs.getString("INDEX_NAME"));
            assertFalse(rs.getBoolean("NON_UNIQUE"));
            assertEquals(DatabaseMetaData.tableIndexClustered, rs.getInt("TYPE"));
        }
    }

    @Test
    void nonclusteredIsNotReadAsClustered() throws SQLException
    {
        // "nonclustered" contains "clustered"; a naive substring test gets this backwards.
        server.on("list table indexes", tableIndexes());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "poldat", false, false))
        {
            assertTrue(rs.next());
            assertEquals("poldat_idx", rs.getString("INDEX_NAME"));
            assertEquals(DatabaseMetaData.tableIndexOther, rs.getInt("TYPE"));
        }
    }

    @Test
    void uniqueOnlyDropsTheNonUniqueIndexes() throws SQLException
    {
        server.on("list table indexes", tableIndexes());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "poldat", true, false))
        {
            assertEquals("poldat_pk", next(rs, "INDEX_NAME"));
        }
    }

    @Test
    void derivesThePrimaryKeyFromTheIndexDescription() throws SQLException
    {
        server.on("list table indexes", tableIndexes());

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "poldat"))
        {
            // KEY_SEQ has to follow index_keys order, which is what makes the key usable.
            final List<String> keys = new ArrayList<>();
            int expectedSeq = 1;
            while (rs.next())
            {
                assertEquals("poldat_pk", rs.getString("PK_NAME"));
                assertEquals(expectedSeq++, rs.getShort("KEY_SEQ"));
                keys.add(rs.getString("COLUMN_NAME"));
            }
            assertEquals(List.of("polcod", "polvar", "polval", "wh_id_tmpl", "srtseq"), keys);
        }
    }

    @Test
    void aTableWithNoPrimaryKeyReportsNone() throws SQLException
    {
        server.on("list table indexes", FakeMocaServer.success(
            "<metadata>"
                + "<column name=\"table_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"index_name\" type=\"S\" length=\"30\" nullable=\"false\"/>"
                + "<column name=\"index_description\" type=\"S\" length=\"80\" nullable=\"true\"/>"
                + "<column name=\"index_keys\" type=\"S\" length=\"200\" nullable=\"true\"/>"
                + "</metadata><data>"
                + "<row><fld>SCRATCH</fld><fld>scratch_idx</fld>"
                + "<fld>nonclustered located on primary</fld><fld>a, b</fld></row>"
                + "</data>"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "scratch"))
        {
            assertFalse(rs.next());
            assertEquals(6, rs.getMetaData().getColumnCount(), "the shape is still required");
        }
    }

    // ------------------------------------------------------------- degradation

    @Test
    void catalogFailureDegradesToAnEmptyCatalog() throws SQLException
    {
        // A MOCA user whose role cannot run the command. Browsing must come back empty
        // rather than blowing up the connection.
        server.on("list user tables", FakeMocaServer.error(501, "no such command"));

        try (final Connection conn = connect();
             final ResultSet rs = conn.getMetaData().getTables(null, null, "%", null))
        {
            assertFalse(rs.next());
            assertEquals(10, rs.getMetaData().getColumnCount());
        }
    }

    @Test
    void anIndexCommandFailureDegradesToNoIndexes() throws SQLException
    {
        server.on("list table indexes", FakeMocaServer.error(501, "no such command"));

        try (final Connection conn = connect())
        {
            try (final ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "poldat", false, false))
            {
                assertFalse(rs.next());
                assertEquals(13, rs.getMetaData().getColumnCount());
            }
            try (final ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "poldat"))
            {
                assertFalse(rs.next());
            }
        }
    }

    private static String next(final ResultSet rs, final String column) throws SQLException
    {
        assertTrue(rs.next(), "expected another row");
        return rs.getString(column);
    }

    private static int nextInt(final ResultSet rs, final String column) throws SQLException
    {
        assertTrue(rs.next(), "expected another row");
        return rs.getInt(column);
    }

    @Test
    void metadataResultsAreEmptyButWellShaped() throws SQLException
    {
        try (final Connection conn = connect())
        {
            final DatabaseMetaData meta = conn.getMetaData();

            // JDBC requires an empty result set of the right shape here, never null and
            // never a throw — a tool walking the tree calls all of these.
            try (final ResultSet rs = meta.getPrimaryKeys(null, null, "POLDAT"))
            {
                assertFalse(rs.next());
                assertEquals(6, rs.getMetaData().getColumnCount());
            }
            try (final ResultSet rs = meta.getImportedKeys(null, null, "POLDAT"))
            {
                assertFalse(rs.next());
                assertEquals(14, rs.getMetaData().getColumnCount());
            }
            try (final ResultSet rs = meta.getSchemas())
            {
                assertFalse(rs.next());
            }
            try (final ResultSet rs = meta.getTableTypes())
            {
                assertTrue(rs.next());
                assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            }
            try (final ResultSet rs = meta.getTypeInfo())
            {
                assertTrue(rs.next());
            }
        }
    }
}
