package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing table data from a JDBC tool: the SQL a grid editor generates when a row is added,
 * duplicated, edited or deleted, and how it reaches MOCA.
 *
 * <p>The statements are DBeaver's own shapes, byte for byte &mdash; including the newline and
 * tab it puts before {@code SET}, {@code WHERE} and {@code VALUES} &mdash; because they are
 * what the driver has to recognise. What each test asserts is the command on the wire.
 */
class MocaSqlPassthroughTest
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

    // ------------------------------------------------------------- saving a grid

    @Test
    void insertingARowPublishesItsValuesAndBindsThemByName() throws SQLException
    {
        // Unbracketed, this is the "Syntax error at line 1.20: Unexpected token: (" a grid
        // save used to fail with: MOCA reads INSERT INTO POLDAT as the words of a command
        // name, and the parenthesis is the first thing that cannot be one.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO poldat (polcod,polvar,polval,srtseq)\n\tVALUES (?,?,?,?)"))
        {
            stmt.setString(1, "ALLOCATE-INV");
            stmt.setString(2, "CREATE-ORDER-TO-INV");
            stmt.setString(3, "ORDLIN-test");
            stmt.setInt(4, 0);
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 'ALLOCATE-INV' and moca_jdbc_2 = 'CREATE-ORDER-TO-INV'"
                + " and moca_jdbc_3 = 'ORDLIN-test' and moca_jdbc_4 = 0"
                + " | [INSERT INTO poldat (polcod,polvar,polval,srtseq)\n\tVALUES"
                + " (@moca_jdbc_1,@moca_jdbc_2,@moca_jdbc_3,@moca_jdbc_4)]",
            server.received().get(1));
    }

    @Test
    void duplicatingARowKeepsValuesThatLookLikeMocaSyntaxAsData() throws SQLException
    {
        // Duplicating a row is an INSERT of every value it holds, and policy data contains
        // MOCA syntax as a matter of course: this rtstr1 is a real sort expression. Spliced
        // into the brackets, MOCA would see an @variable, a closing bracket and a pipe. As a
        // published literal it is one quoted string, whatever is in it.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO poldat (polcod,rtstr1,rtstr2)\n\tVALUES (?,?,?)"))
        {
            stmt.setString(1, "ALLOCATE-INV");
            stmt.setString(2, "abs(invsum.untqty - @pckqty) asc");
            stmt.setString(3, "O'Brien] & remove data where 1 = 1 | [delete from poldat]");
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 'ALLOCATE-INV'"
                + " and moca_jdbc_2 = 'abs(invsum.untqty - @pckqty) asc'"
                + " and moca_jdbc_3 = 'O''Brien] & remove data where 1 = 1 | [delete from poldat]'"
                + " | [INSERT INTO poldat (polcod,rtstr1,rtstr2)\n\tVALUES (@moca_jdbc_1,@moca_jdbc_2,@moca_jdbc_3)]",
            server.received().get(1));
    }

    @Test
    void editingARowBindsTheNewValueAndTheKey() throws SQLException
    {
        // A NULL key column arrives as IS NULL with no placeholder of its own, so there is
        // nothing to publish for it.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE poldat\n\tSET polval=?\n\tWHERE polcod=? AND polvar=? AND wh_id_tmpl IS NULL"))
        {
            stmt.setString(1, "ORDLIN-test");
            stmt.setString(2, "ALLOCATE-INV");
            stmt.setString(3, "CREATE-ORDER-TO-INV");
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 'ORDLIN-test' and moca_jdbc_2 = 'ALLOCATE-INV'"
                + " and moca_jdbc_3 = 'CREATE-ORDER-TO-INV'"
                + " | [UPDATE poldat\n\tSET polval=@moca_jdbc_1"
                + "\n\tWHERE polcod=@moca_jdbc_2 AND polvar=@moca_jdbc_3 AND wh_id_tmpl IS NULL]",
            server.received().get(1));
    }

    @Test
    void deletingARowBindsItsKey() throws SQLException
    {
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM poldat\n\tWHERE polcod=? AND srtseq=?"))
        {
            stmt.setString(1, "ALLOCATE-INV");
            stmt.setLong(2, 3L);
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 'ALLOCATE-INV' and moca_jdbc_2 = 3"
                + " | [DELETE FROM poldat\n\tWHERE polcod=@moca_jdbc_1 AND srtseq=@moca_jdbc_2]",
            server.received().get(1));
    }

    @Test
    void theRowRefreshAfterASaveIsBoundTheSameWayAndReturnsItsRows() throws SQLException
    {
        server.on("[SELECT", FakeMocaServer.twoByTwo());

        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("SELECT a,b FROM poldat WHERE polcod=?"))
        {
            stmt.setString(1, "ALLOCATE-INV");
            try (final ResultSet rs = stmt.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("a"));
            }
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 'ALLOCATE-INV' | [SELECT a,b FROM poldat WHERE polcod=@moca_jdbc_1]",
            server.received().get(1));
    }

    // ---------------------------------------------- values written rather than bound

    @Test
    void aNullIsWrittenAsTheSqlKeywordRatherThanPublished() throws SQLException
    {
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE poldat\n\tSET rtstr1=?\n\tWHERE polcod=?"))
        {
            stmt.setNull(1, Types.VARCHAR);
            stmt.setString(2, "ALLOCATE-INV");
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_2 = 'ALLOCATE-INV'"
                + " | [UPDATE poldat\n\tSET rtstr1=null\n\tWHERE polcod=@moca_jdbc_2]",
            server.received().get(1));
    }

    @Test
    void aStatementWhoseOnlyParametersAreNullNeedsNoPublish() throws SQLException
    {
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE poldat\n\tSET rtstr1=?\n\tWHERE polcod IS NULL"))
        {
            stmt.setString(1, null);
            stmt.executeUpdate();
        }

        assertEquals("[UPDATE poldat\n\tSET rtstr1=null\n\tWHERE polcod IS NULL]", server.received().get(1));
    }

    @Test
    void aTimestampIsPublishedAsAMocaDateAndConvertedOnTheDatabase() throws SQLException
    {
        // Duplicating a row carries its audit dates with it.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE poldat\n\tSET last_upd_dt=?\n\tWHERE polcod=?"))
        {
            stmt.setTimestamp(1, Timestamp.valueOf("2026-09-14 13:21:10"));
            stmt.setString(2, "ALLOCATE-INV");
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = '20260914132110' and moca_jdbc_2 = 'ALLOCATE-INV'"
                + " | [UPDATE poldat\n\tSET last_upd_dt=to_date(@moca_jdbc_1, 'YYYYMMDDHH24MISS')"
                + "\n\tWHERE polcod=@moca_jdbc_2]",
            server.received().get(1));
    }

    @Test
    void aBooleanIsBoundAsANumericFlag() throws SQLException
    {
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("UPDATE poldat\n\tSET rtnum1=?\n\tWHERE polcod=?"))
        {
            stmt.setBoolean(1, true);
            stmt.setString(2, "ALLOCATE-INV");
            stmt.executeUpdate();
        }

        assertEquals(
            "publish data where moca_jdbc_1 = 1 and moca_jdbc_2 = 'ALLOCATE-INV'"
                + " | [UPDATE poldat\n\tSET rtnum1=@moca_jdbc_1\n\tWHERE polcod=@moca_jdbc_2]",
            server.received().get(1));
    }

    @Test
    void aMocaCommandThatContainsSqlStillInlinesItsParameters() throws SQLException
    {
        // Only a statement that is SQL and nothing else is bound. Once a pipe leads into MOCA,
        // the whole thing is a MOCA command, and a MOCA command inlines as it always has.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement(
                     "[select polval from poldat where polcod = ?] | publish data where v = @polval"))
        {
            stmt.setString(1, "ALLOCATE-INV");
            stmt.executeQuery();
        }

        assertEquals(
            "[select polval from poldat where polcod = 'ALLOCATE-INV'] | publish data where v = @polval",
            server.received().get(1));
    }

    @Test
    void aGroovyBlockIsNotBoundLikeSql() throws SQLException
    {
        // [[ ]] is MOCA's Groovy block. An @variable is not Groovy syntax, so its parameters
        // keep being inlined rather than bound by name.
        try (final Connection conn = connect();
             final PreparedStatement stmt = conn.prepareStatement("[[ polval = ? ]]"))
        {
            stmt.setString(1, "ORDLIN");
            stmt.executeQuery();
        }

        assertEquals("[[ polval = 'ORDLIN' ]]", server.received().get(1));
    }

    // --------------------------------------------------- which SQL gets bracketed

    @Test
    void bracketsBareInsertUpdateAndDelete() throws SQLException
    {
        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            stmt.executeUpdate("insert into poldat (polcod) values ('X')");
            stmt.executeUpdate("update poldat set polval = 'Y' where polcod = 'X'");
            stmt.executeUpdate("  delete from poldat where polcod = 'X';  ");
        }

        assertEquals("[insert into poldat (polcod) values ('X')]", server.received().get(1));
        assertEquals("[update poldat set polval = 'Y' where polcod = 'X']", server.received().get(2));
        assertEquals("[delete from poldat where polcod = 'X']", server.received().get(3));
    }

    @Test
    void leavesMocaCommandsNamedLikeDmlAlone() throws SQLException
    {
        // A site's own commands can start with these verbs. Only the SQL shape is bracketed.
        final String[] commands = {
            "update inventory status where lodnum = 'L1'",
            "delete usr pick where wrkref = 'W1'",
            "insert usr audit where msg = 'x'",
        };

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            for (final String command : commands)
            {
                stmt.executeUpdate(command);
            }
        }

        for (int i = 0; i < commands.length; i++)
        {
            assertEquals(commands[i], server.received().get(i + 1));
        }
    }

    @Test
    void nativeSqlReportsTheDmlTransformation() throws SQLException
    {
        try (final Connection conn = connect())
        {
            assertEquals("[delete from poldat where polcod = 'X']",
                    conn.nativeSQL("delete from poldat where polcod = 'X'"));
            assertEquals("delete usr pick where wrkref = 'W1'",
                    conn.nativeSQL("delete usr pick where wrkref = 'W1'"));
        }
    }

    // ------------------------------------------------------------------ no rows

    @Test
    void aNoRowsStatusFromTheDatabaseIsNotAnError() throws SQLException
    {
        // -1403 is the database layer's "no rows", which MOCA code conventionally catches
        // alongside 510. Saving a row that someone else already changed matches nothing, and
        // in JDBC that is an update count of zero, not an error dialog.
        server.on("[UPDATE", FakeMocaServer.error(-1403, "No rows affected"));
        server.on("[select", FakeMocaServer.error(-1403, "No rows affected"));

        try (final Connection conn = connect();
             final Statement stmt = conn.createStatement())
        {
            assertEquals(0, stmt.executeUpdate("UPDATE poldat SET polval = 'Y' WHERE polcod = 'gone'"));
            try (final ResultSet rs = stmt.executeQuery("select * from poldat where polcod = 'gone'"))
            {
                assertFalse(rs.next());
            }
        }
    }
}
