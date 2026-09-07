package io.github.voodoo262.jdbc.moca;

import io.github.voodoo262.jdbc.moca.protocol.MocaRows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;

/**
 * Executes MOCA commands.
 *
 * <p>MOCA has no notion of an update count: every command publishes rows, or publishes
 * nothing. So {@link #execute} reports a result set whenever the server returned columns,
 * and {@link #getUpdateCount} is only ever {@code 0} (for a command that published no
 * columns) or {@code -1} (for one that did).
 */
class MocaStatement implements Statement
{
    private final MocaConnection connection;

    private MocaResultSet resultSet;
    /** -1 means "the last command produced a result set", per {@link #getUpdateCount()}. */
    private int updateCount = -1;
    private int maxRows;
    private int queryTimeoutSeconds;
    private boolean closed;

    MocaStatement(final MocaConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException
    {
        return doExecuteQuery(sql);
    }

    /**
     * The real implementation, reachable by {@link MocaPreparedStatement} after it has
     * rendered its parameters. Deliberately not overridable: the public
     * {@code executeQuery(String)} is one of the methods a PreparedStatement must refuse,
     * so the internal path cannot route through it.
     */
    final ResultSet doExecuteQuery(final String sql) throws SQLException
    {
        doExecute(sql);
        if (resultSet == null)
        {
            // Plenty of MOCA commands publish nothing at all ("commit", "remove data ...").
            // An empty result set still satisfies executeQuery's contract — it returns a
            // single ResultSet — and is friendlier than an exception to anyone typing
            // commands into a console.
            return resultOf(MocaRows.empty());
        }
        return resultSet;
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException
    {
        return doExecuteUpdate(sql);
    }

    /** @see #doExecuteQuery(String) */
    final int doExecuteUpdate(final String sql) throws SQLException
    {
        doExecute(sql);
        // MOCA does not report affected-row counts, so there is nothing honest to
        // return but zero. Callers wanting the rows a command published must use
        // executeQuery.
        return 0;
    }

    @Override
    public boolean execute(final String sql) throws SQLException
    {
        return doExecute(sql);
    }

    /** @see #doExecuteQuery(String) */
    final boolean doExecute(final String sql) throws SQLException
    {
        requireOpen();
        closeCurrentResults();

        final Duration timeout = queryTimeoutSeconds > 0 ? Duration.ofSeconds(queryTimeoutSeconds) : null;
        final MocaRows rows = connection.getClient()
                .execute(bracketBareSelect(sql), connection.getAutoCommit(), timeout)
                .limit(maxRows);
        connection.noteExecuted();

        if (rows.getColumnCount() == 0)
        {
            updateCount = 0;
            return false;
        }
        resultSet = new MocaResultSet(rows, this);
        updateCount = -1;
        return true;
    }

    @Override
    public ResultSet getResultSet() throws SQLException
    {
        requireOpen();
        return resultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException
    {
        requireOpen();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException
    {
        requireOpen();
        // A MOCA response carries exactly one result. Chained commands ("a & b")
        // are merged by the server into that single result before it reaches us.
        closeCurrentResults();
        updateCount = -1;
        return false;
    }

    @Override
    public boolean getMoreResults(final int current) throws SQLException
    {
        return getMoreResults();
    }

    @Override
    public void close()
    {
        if (closed) return;
        closeCurrentResults();
        closed = true;
        connection.forget(this);
    }

    @Override
    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public Connection getConnection() throws SQLException
    {
        requireOpen();
        return connection;
    }

    // ------------------------------------------------------------- knobs

    @Override public int getMaxRows() throws SQLException { requireOpen(); return maxRows; }

    @Override
    public void setMaxRows(final int max) throws SQLException
    {
        requireOpen();
        if (max < 0)
        {
            throw new SQLException("maxRows must not be negative", "HY000");
        }
        // The server sends the whole result regardless; this truncates it on arrival,
        // which still bounds what a client has to render.
        this.maxRows = max;
    }

    @Override public int getQueryTimeout() throws SQLException { requireOpen(); return queryTimeoutSeconds; }

    @Override
    public void setQueryTimeout(final int seconds) throws SQLException
    {
        requireOpen();
        if (seconds < 0)
        {
            throw new SQLException("queryTimeout must not be negative", "HY000");
        }
        this.queryTimeoutSeconds = seconds;
    }

    @Override public int getFetchSize() throws SQLException { requireOpen(); return 0; }
    @Override public void setFetchSize(final int rows) throws SQLException { requireOpen(); /* advisory; responses arrive whole */ }
    @Override public int getFetchDirection() throws SQLException { requireOpen(); return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchDirection(final int direction) throws SQLException { requireOpen(); /* advisory */ }
    @Override public int getResultSetType() throws SQLException { requireOpen(); return ResultSet.TYPE_SCROLL_INSENSITIVE; }
    @Override public int getResultSetConcurrency() throws SQLException { requireOpen(); return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetHoldability() throws SQLException { requireOpen(); return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getMaxFieldSize() throws SQLException { requireOpen(); return 0; }
    @Override public void setMaxFieldSize(final int max) throws SQLException { requireOpen(); /* no field-size cap */ }
    @Override public void setEscapeProcessing(final boolean enable) throws SQLException { requireOpen(); /* MOCA is not SQL; no escape syntax */ }
    @Override public void setPoolable(final boolean poolable) throws SQLException { requireOpen(); /* not pooled */ }
    @Override public boolean isPoolable() throws SQLException { requireOpen(); return false; }
    @Override public void closeOnCompletion() throws SQLException { requireOpen(); throw unsupported("closeOnCompletion"); }
    @Override public boolean isCloseOnCompletion() throws SQLException { requireOpen(); return false; }

    @Override public SQLWarning getWarnings() throws SQLException { requireOpen(); return null; }
    @Override public void clearWarnings() throws SQLException { requireOpen(); }

    @Override
    public void cancel() throws SQLException
    {
        // The MOCA HTTP protocol has no cancel channel: once a command is posted
        // there is no way to tell the server to stop. Say so rather than pretend.
        throw unsupported("Cancelling a running command");
    }

    @Override
    public void setCursorName(final String name) throws SQLException
    {
        throw unsupported("Named cursors");
    }

    // MOCA has no generated keys, and no batch protocol.
    @Override public ResultSet getGeneratedKeys() throws SQLException { throw unsupported("Generated keys"); }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { throw unsupported("Generated keys"); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { throw unsupported("Generated keys"); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { throw unsupported("Generated keys"); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { throw unsupported("Generated keys"); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { throw unsupported("Generated keys"); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { throw unsupported("Generated keys"); }
    @Override public void addBatch(String sql) throws SQLException { throw unsupported("Batch execution"); }
    @Override public void clearBatch() throws SQLException { throw unsupported("Batch execution"); }
    @Override public int[] executeBatch() throws SQLException { throw unsupported("Batch execution"); }

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
     * Wraps a bare {@code SELECT} in MOCA's native-SQL brackets.
     *
     * <p>MOCA is not SQL, but it will pass a bracketed statement straight through to the
     * database underneath ({@code [select * from poldat]}). A JDBC tool does not know that:
     * DBeaver's "view table data" generates a plain {@code SELECT * FROM poldat}, which
     * MOCA rejects as an unknown command. Adding the brackets here is what makes
     * double-clicking a table in the navigator work at all.
     *
     * <p>Only a command that <em>starts</em> with {@code select} is touched, and only when
     * it is not already bracketed. Every real MOCA command starts with a verb
     * ({@code list}, {@code publish}, {@code get}, ...), so there is nothing for this to
     * collide with.
     *
     * @return {@code sql} unchanged, or wrapped in {@code [ ]}
     */
    static String bracketBareSelect(final String sql)
    {
        if (sql == null) return null;

        final String trimmed = sql.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '[')
        {
            return sql;
        }
        if (!startsWithWord(trimmed, "select"))
        {
            return sql;
        }
        // A trailing semicolon is idiomatic in a SQL editor and meaningless inside the
        // brackets, so drop it rather than pass it to the database.
        final String body = trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1).stripTrailing()
                : trimmed;
        return "[" + body + "]";
    }

    /** @return true if {@code text} begins with {@code word} followed by whitespace. */
    private static boolean startsWithWord(final String text, final String word)
    {
        if (text.length() <= word.length()) return false;
        if (!text.regionMatches(true, 0, word, 0, word.length())) return false;
        return Character.isWhitespace(text.charAt(word.length()));
    }

    /** Publishes a server-built result directly, bypassing execution. Used by {@link MocaDatabaseMetaData}. */
    ResultSet resultOf(final MocaRows rows)
    {
        closeCurrentResults();
        resultSet = new MocaResultSet(rows, this);
        updateCount = -1;
        return resultSet;
    }

    private void closeCurrentResults()
    {
        if (resultSet != null)
        {
            resultSet.close();
            resultSet = null;
        }
    }

    void requireOpen() throws SQLException
    {
        if (closed)
        {
            throw new SQLException("Statement is closed", "HY010");
        }
    }

    static SQLFeatureNotSupportedException unsupported(final String what)
    {
        return new SQLFeatureNotSupportedException(what + " is not supported by the MOCA JDBC driver");
    }
}
