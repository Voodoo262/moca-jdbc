package io.github.voodoo262.jdbc.moca;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in MOCA server, built on the JDK's own {@link HttpServer} so the tests need no
 * dependency and no live MOCA instance.
 *
 * <p>It speaks enough of the protocol to be real: it accepts {@code application/moca-xml},
 * answers {@code login user} with a session key, and returns whatever response has been
 * scripted for a given command. It also records every command it receives, which is how
 * the tests assert on what the driver actually put on the wire — the thing that matters
 * for {@link MocaPreparedStatement}'s literal substitution.
 */
final class FakeMocaServer implements AutoCloseable
{
    static final String SESSION_KEY = "test-session-key";

    private static final Pattern QUERY = Pattern.compile("<query>(.*?)</query>", Pattern.DOTALL);

    private final HttpServer server;
    /** Command substring to response XML, in insertion order: first match wins. */
    private final Map<String, String> scripted = new LinkedHashMap<>();
    private final List<String> received = new ArrayList<>();
    /** Stalls every response by this long, so a read timeout can actually be observed. */
    private volatile long delayMillis;

    FakeMocaServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/service", this::handle);
        server.start();
    }

    String url()
    {
        return "jdbc:moca:http://127.0.0.1:" + server.getAddress().getPort() + "/service";
    }

    /** Scripts a response for any command containing {@code commandSubstring}. */
    FakeMocaServer on(final String commandSubstring, final String responseXml)
    {
        scripted.put(commandSubstring, responseXml);
        return this;
    }

    /** Makes every subsequent response take at least {@code millis}, for timeout tests. */
    FakeMocaServer delayResponses(final long millis)
    {
        this.delayMillis = millis;
        return this;
    }

    /** @return every command the driver has sent, in order. */
    List<String> received()
    {
        return received;
    }

    String lastCommand()
    {
        return received.isEmpty() ? null : received.get(received.size() - 1);
    }

    private void handle(final HttpExchange exchange) throws IOException
    {
        final String request;
        try (final InputStream in = exchange.getRequestBody())
        {
            request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        final Matcher matcher = QUERY.matcher(request);
        final String command = matcher.find() ? unescape(matcher.group(1)) : "";
        received.add(command);

        if (delayMillis > 0)
        {
            try
            {
                Thread.sleep(delayMillis);
            }
            catch (final InterruptedException ex)
            {
                Thread.currentThread().interrupt();
            }
        }

        final String response = responseFor(command);
        final byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/moca-xml");
        exchange.sendResponseHeaders(200, body.length);
        try (final OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    private String responseFor(final String command)
    {
        if (command.contains("login user"))
        {
            return success(
                "<metadata><column name=\"session_key\" type=\"S\" length=\"99\" nullable=\"false\"/></metadata>"
                    + "<data><row><fld>" + SESSION_KEY + "</fld></row></data>");
        }
        for (final Map.Entry<String, String> entry : scripted.entrySet())
        {
            if (command.contains(entry.getKey()))
            {
                return entry.getValue();
            }
        }
        // Anything unscripted (logout, commit, rollback, ...) simply succeeds with no rows.
        return "<moca-response><status>0</status></moca-response>";
    }

    /** The XML the driver's own request builder would have escaped on the way out. */
    private static String unescape(final String xml)
    {
        return xml.replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&quot;", "\"")
                  .replace("&apos;", "'")
                  .replace("&amp;", "&");
    }

    // ------------------------------------------------------ response fixtures

    static String success(final String mocaResults)
    {
        return "<moca-response><status>0</status><moca-results>" + mocaResults
                + "</moca-results></moca-response>";
    }

    static String error(final int status, final String message)
    {
        return "<moca-response><status>" + status + "</status><message>" + message
                + "</message></moca-response>";
    }

    /**
     * An error that still carries a column list. MOCA does this for status 510 ("no rows
     * affected"): nothing matched, but the shape of what would have matched is known.
     */
    static String errorWithColumns(final int status, final String message, final String... columnNames)
    {
        final StringBuilder metadata = new StringBuilder("<metadata>");
        for (final String name : columnNames)
        {
            metadata.append("<column name=\"").append(name)
                    .append("\" type=\"S\" length=\"30\" nullable=\"true\"/>");
        }
        metadata.append("</metadata>");

        return "<moca-response><status>" + status + "</status><message>" + message
                + "</message><moca-results>" + metadata + "<data/></moca-results></moca-response>";
    }

    /**
     * A two-column, two-row result. Deliberately pretty-printed with newlines and indentation:
     * a parser that walks child nodes without filtering to elements will read the whitespace
     * text nodes as columns and produce garbage, so this shape is load-bearing.
     */
    static String twoByTwo()
    {
        return success(
            "\n  <metadata>\n"
                + "    <column name=\"a\" type=\"I\" length=\"0\" nullable=\"true\"/>\n"
                + "    <column name=\"b\" type=\"S\" length=\"10\" nullable=\"true\"/>\n"
                + "  </metadata>\n"
                + "  <data>\n"
                + "    <row><fld>1</fld><fld>one</fld></row>\n"
                + "    <row><fld>2</fld><fld/></row>\n"
                + "  </data>\n");
    }

    @Override
    public void close()
    {
        server.stop(0);
    }
}
