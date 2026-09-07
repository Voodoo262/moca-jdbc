package io.github.voodoo262.jdbc.moca.protocol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the {@code application/moca-xml} request document.
 *
 * <pre>{@code
 * <moca-request autocommit="True">
 *   <environment><var name="SESSION_KEY" value="..."/></environment>
 *   <query>list warehouses</query>
 *   <context><field name="usr_id" oper="EQ" type="STRING">rfaust</field></context>
 * </moca-request>
 * }</pre>
 *
 * <p>Built via DOM rather than string concatenation so that a command containing
 * {@code &}, {@code <} or a quote is escaped by the XML writer instead of
 * corrupting the document. MOCA's {@code &} command separator makes that routine,
 * not an edge case.
 */
final class MocaRequestXml
{
    private final String command;
    private boolean autoCommit = true;
    private final Map<String, String> environment = new TreeMap<>();
    private final Map<String, String> context = new TreeMap<>();

    MocaRequestXml(final String command)
    {
        this.command = command;
    }

    MocaRequestXml autoCommit(final boolean autoCommit)
    {
        this.autoCommit = autoCommit;
        return this;
    }

    MocaRequestXml environment(final String name, final String value)
    {
        this.environment.put(name, value);
        return this;
    }

    MocaRequestXml context(final String name, final String value)
    {
        this.context.put(name, value);
        return this;
    }

    String toXml() throws SQLException
    {
        try
        {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // These only ever build a document from our own strings, so there is no live
            // vector here. Set uniformly with the response side anyway, so nobody has to
            // re-derive that argument when this code is next touched.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document document = builder.newDocument();

            final Element root = document.createElement("moca-request");
            root.setAttribute("autocommit", autoCommit ? "True" : "False");
            document.appendChild(root);

            if (!environment.isEmpty())
            {
                final Element env = document.createElement("environment");
                for (final Map.Entry<String, String> entry : environment.entrySet())
                {
                    final Element var = document.createElement("var");
                    var.setAttribute("name", entry.getKey().toUpperCase(java.util.Locale.ROOT));
                    var.setAttribute("value", entry.getValue());
                    env.appendChild(var);
                }
                root.appendChild(env);
            }

            final Element query = document.createElement("query");
            query.setTextContent(command);
            root.appendChild(query);

            if (!context.isEmpty())
            {
                final Element ctx = document.createElement("context");
                for (final Map.Entry<String, String> entry : context.entrySet())
                {
                    final Element field = document.createElement("field");
                    field.setAttribute("name", entry.getKey());
                    field.setAttribute("oper", "EQ");
                    field.setAttribute("type", "STRING");
                    field.setTextContent(entry.getValue());
                    ctx.appendChild(field);
                }
                root.appendChild(ctx);
            }

            final TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            final Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            final StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        }
        catch (final Exception ex)
        {
            throw new SQLException("Failed to build MOCA request", "HY000", ex);
        }
    }
}
