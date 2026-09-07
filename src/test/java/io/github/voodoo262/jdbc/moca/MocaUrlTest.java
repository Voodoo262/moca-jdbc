package io.github.voodoo262.jdbc.moca;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MocaUrlTest
{
    @Test
    void parsesAnExplicitScheme() throws SQLException
    {
        final MocaUrl url = MocaUrl.parse("jdbc:moca:http://host:4500/service", null);
        assertEquals("http://host:4500/service", url.getEndpoint().toString());
    }

    @Test
    void parsesHttps() throws SQLException
    {
        final MocaUrl url = MocaUrl.parse("jdbc:moca:https://host/service", null);
        assertEquals("https://host/service", url.getEndpoint().toString());
    }

    @Test
    void defaultsTheSchemeToHttp() throws SQLException
    {
        final MocaUrl url = MocaUrl.parse("jdbc:moca://host:4500/service", null);
        assertEquals("http://host:4500/service", url.getEndpoint().toString());
    }

    @Test
    void readsQueryParametersAsProperties() throws SQLException
    {
        final MocaUrl url = MocaUrl.parse(
                "jdbc:moca:http://host/service?localeId=US_ENGLISH&readTimeout=5000", null);

        assertEquals("US_ENGLISH", url.getLocaleId());
        assertEquals(Duration.ofSeconds(5), url.getReadTimeout());
        // The query carried driver properties, not something the server should see.
        assertEquals("http://host/service", url.getEndpoint().toString());
    }

    @Test
    void callerPropertiesBeatTheUrl() throws SQLException
    {
        final Properties given = new Properties();
        given.setProperty("localeId", "FR_FRENCH");

        final MocaUrl url = MocaUrl.parse("jdbc:moca:http://host/service?localeId=US_ENGLISH", given);
        assertEquals("FR_FRENCH", url.getLocaleId());
    }

    @Test
    void appliesDefaultTimeouts() throws SQLException
    {
        final MocaUrl url = MocaUrl.parse("jdbc:moca:http://host/service", null);
        assertEquals(Duration.ofSeconds(10), url.getConnectTimeout());
        assertEquals(Duration.ofSeconds(60), url.getReadTimeout());
    }

    @Test
    void rejectsRubbish()
    {
        assertFalse(MocaUrl.acceptsUrl("jdbc:oracle:thin:@host"));
        assertFalse(MocaUrl.acceptsUrl(null));
        assertTrue(MocaUrl.acceptsUrl("jdbc:moca:http://host/service"));

        assertThrows(SQLException.class, () -> MocaUrl.parse("jdbc:moca:", null), "no host");
        assertThrows(SQLException.class, () -> MocaUrl.parse("jdbc:moca:ftp://host/service", null), "not http");

        // A bad timeout is a malformed URL, and parse() is what rejects it.
        assertThrows(SQLException.class,
                () -> MocaUrl.parse("jdbc:moca:http://host/service?readTimeout=soon", null));
        assertThrows(SQLException.class,
                () -> MocaUrl.parse("jdbc:moca:http://host/service?connectTimeout=-1", null));
    }
}
