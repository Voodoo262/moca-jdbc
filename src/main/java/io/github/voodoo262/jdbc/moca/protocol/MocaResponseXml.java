package io.github.voodoo262.jdbc.moca.protocol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code application/moca-xml} response document.
 *
 * <pre>{@code
 * <moca-response>
 *   <status>0</status>
 *   <moca-results>
 *     <metadata>
 *       <column name="a" type="I" length="0" nullable="true"/>
 *     </metadata>
 *     <data>
 *       <row><fld>1</fld></row>
 *     </data>
 *   </moca-results>
 * </moca-response>
 * }</pre>
 */
final class MocaResponseXml
{
    private MocaResponseXml() {}

    static MocaResponse parse(final String xml) throws SQLException
    {
        final Element root;
        try
        {
            root = parseDocument(xml).getDocumentElement();
        }
        catch (final Exception ex)
        {
            throw new SQLException("Could not parse MOCA response: " + abbreviate(xml), "08S01", ex);
        }

        if (root == null || !"moca-response".equals(root.getNodeName()))
        {
            throw new SQLException("Not a MOCA response: " + abbreviate(xml), "08S01");
        }

        final int statusCode = parseInt(textOfFirst(root, "status"), 0);
        final String message = statusCode == 0 ? null : textOfFirst(root, "message");

        final MocaRows results;
        final NodeList resultNodes = root.getElementsByTagName("moca-results");
        if (resultNodes.getLength() > 0)
        {
            results = parseResults((Element) resultNodes.item(0));
        }
        else
        {
            results = MocaRows.empty();
        }

        return new MocaResponse(statusCode, message, results);
    }

    private static MocaRows parseResults(final Element mocaResults) throws SQLException
    {
        final List<MocaColumn> columns = new ArrayList<>();
        final NodeList metadataNodes = mocaResults.getElementsByTagName("metadata");
        if (metadataNodes.getLength() > 0)
        {
            for (final Element column : childElementsOf((Element) metadataNodes.item(0)))
            {
                columns.add(parseColumn(column));
            }
        }

        final List<List<String>> rows = new ArrayList<>();
        final NodeList dataNodes = mocaResults.getElementsByTagName("data");
        if (dataNodes.getLength() > 0)
        {
            for (final Element row : childElementsOf((Element) dataNodes.item(0)))
            {
                final List<Element> fields = childElementsOf(row);
                final List<String> values = new ArrayList<>(fields.size());
                for (final Element field : fields)
                {
                    values.add(valueOf(field));
                }
                rows.add(values);
            }
        }

        return new MocaRows(columns, rows);
    }

    private static MocaColumn parseColumn(final Element column) throws SQLException
    {
        final String name = column.getAttribute("name");
        if (name.isEmpty())
        {
            throw new SQLException("MOCA response has a column with no name", "08S01");
        }
        final String type = column.getAttribute("type");
        // An absent/blank type is reported as '?', which MocaColumn maps to OTHER.
        final char mocaType = type.isEmpty() ? '?' : type.charAt(0);
        final int length = parseInt(column.getAttribute("length"), 0);
        // MOCA omits the attribute rather than writing nullable="false" on some
        // commands; absent is treated as nullable, the safer assumption for a reader.
        final String nullable = column.getAttribute("nullable");
        return new MocaColumn(name, mocaType, length, nullable.isEmpty() || Boolean.parseBoolean(nullable));
    }

    /**
     * A field with no child nodes is SQL NULL. MOCA does not distinguish an empty
     * string from NULL on the wire, and NULL is the more useful of the two for a
     * client that is about to render a grid.
     */
    private static String valueOf(final Element field)
    {
        if (Boolean.parseBoolean(field.getAttribute("null")))
        {
            return null;
        }
        return field.hasChildNodes() ? field.getTextContent() : null;
    }

    private static List<Element> childElementsOf(final Element parent)
    {
        final List<Element> elements = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            final Node child = children.item(i);
            // Skip the whitespace text nodes a pretty-printed response carries.
            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                elements.add((Element) child);
            }
        }
        return elements;
    }

    private static String textOfFirst(final Element parent, final String tagName)
    {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private static int parseInt(final String value, final int fallback)
    {
        if (value == null) return fallback;
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (final NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static Document parseDocument(final String xml) throws Exception
    {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The response comes off the network. Refuse doctypes outright, which
        // closes XXE and entity-expansion without needing the individual switches.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        final DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String abbreviate(final String xml)
    {
        if (xml == null) return "<null>";
        final String trimmed = xml.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
