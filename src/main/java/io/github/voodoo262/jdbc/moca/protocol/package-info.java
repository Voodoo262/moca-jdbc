/**
 * The MOCA wire protocol: HTTP transport, request/response XML, and the in-memory result
 * carrier.
 *
 * <p><strong>Internal.</strong> Everything in this package except
 * {@link io.github.voodoo262.jdbc.moca.protocol.MocaServerException} is an implementation
 * detail. The classes are {@code public} only so the JDBC layer in the parent package can
 * reach them across the package boundary, and they are <em>not</em> covered by this
 * project's version guarantees — they may change in any release.
 *
 * <p>{@code MocaServerException} is the exception: it is thrown at callers, who catch it to
 * read {@code getStatusCode()}, so it is public API.
 *
 * <p>The supported entry point is {@link java.sql.DriverManager}.
 */
package io.github.voodoo262.jdbc.moca.protocol;
