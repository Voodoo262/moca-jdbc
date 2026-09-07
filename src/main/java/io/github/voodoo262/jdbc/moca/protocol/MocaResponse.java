package io.github.voodoo262.jdbc.moca.protocol;

/**
 * A parsed {@code <moca-response>}: the server's status, its message (on failure),
 * and whatever rows it published.
 *
 * <p>This is the raw outcome. Deciding which non-zero statuses are actually errors
 * in JDBC terms is {@link MocaClient}'s job, not the parser's.
 */
public final class MocaResponse
{
    private final int statusCode;
    private final String message;
    private final MocaRows results;

    MocaResponse(final int statusCode, final String message, final MocaRows results)
    {
        this.statusCode = statusCode;
        this.message = message;
        this.results = results;
    }

    /** @return 0 on success. */
    public int getStatusCode() { return statusCode; }

    /** @return the server's error text, or {@code null} when the status is 0. */
    public String getMessage() { return message; }

    /** @return the published rows; never {@code null}, but may be {@link MocaRows#empty()}. */
    public MocaRows getResults() { return results; }

    public boolean isSuccess() { return statusCode == 0; }
}
