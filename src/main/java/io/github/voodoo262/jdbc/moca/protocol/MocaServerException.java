package io.github.voodoo262.jdbc.moca.protocol;

import java.sql.SQLException;

/**
 * An error returned by the MOCA server (a non-zero {@code <status>}).
 *
 * <p>This extends {@link SQLException} so server errors travel the channel JDBC
 * callers already handle, carrying the MOCA status as the vendor code. Tools such
 * as DBeaver key their error reporting off {@link #getSQLState()}, so the common
 * MOCA statuses are translated to the standard SQLState values.
 */
public class MocaServerException extends SQLException
{
    private static final long serialVersionUID = 1L;

    /** Command not found. */
    public static final int COMMAND_NOT_FOUND = 501;
    /** Syntax error in the command. */
    public static final int SYNTAX_ERROR = 505;
    /** No rows were affected/returned. Not an error in JDBC terms — see {@link MocaClient}. */
    public static final int NO_ROWS_AFFECTED = 510;
    /** Reference to a column that does not exist. */
    public static final int INVALID_COLUMN = 511;

    private final transient MocaRows results;

    public MocaServerException(final String message, final int statusCode)
    {
        this(message, statusCode, null);
    }

    public MocaServerException(final String message, final int statusCode, final MocaRows results)
    {
        super(message, sqlStateFor(statusCode), statusCode);
        this.results = results;
    }

    /** @return the MOCA status code (also available as {@link #getErrorCode()}). */
    public int getStatusCode()
    {
        return getErrorCode();
    }

    /**
     * @return the columns/rows the server sent alongside the error, or {@code null}.
     *         MOCA populates this for {@link #NO_ROWS_AFFECTED}: the column list is
     *         known even though no rows matched.
     */
    public MocaRows getResults()
    {
        return results;
    }

    private static String sqlStateFor(final int statusCode)
    {
        switch (statusCode)
        {
            case NO_ROWS_AFFECTED:  return "02000"; // no data
            case SYNTAX_ERROR:      return "42601"; // syntax error
            case INVALID_COLUMN:    return "42703"; // undefined column
            case COMMAND_NOT_FOUND: return "42000"; // syntax error or access rule violation
            default:                return "HY000"; // general error
        }
    }
}
