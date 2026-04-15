package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import java.lang.reflect.Field;

@RunWith(JUnit4.class)
public class TransportTests {

    private Transport transport;

    @Before
    public void setUp() throws Exception {
        transport = new Transport();
        Log L = new Log(Config.LoggingLevel.VERBOSE, null);
        Field logField = Transport.class.getDeclaredField("L");
        logField.setAccessible(true);
        logField.set(transport, L);
    }

    // ==================== processResponse tests ====================

    /**
     * Valid JSON response with "result" key and 200 status code
     * should return true (success)
     */
    @Test
    public void processResponse_validJsonSuccess() {
        Boolean result = transport.processResponse(200, "{\"result\":\"Success\"}", 1L);
        Assert.assertTrue(result);
    }

    /**
     * Valid JSON response with "result" key and various 2xx status codes
     * should return true (success)
     */
    @Test
    public void processResponse_validJson2xxRange() {
        Assert.assertTrue(transport.processResponse(200, "{\"result\":\"ok\"}", 1L));
        Assert.assertTrue(transport.processResponse(201, "{\"result\":\"created\"}", 2L));
        Assert.assertTrue(transport.processResponse(299, "{\"result\":\"ok\"}", 3L));
    }

    /**
     * Valid JSON response with "result" key but non-2xx status code
     * should return false (failure)
     */
    @Test
    public void processResponse_validJsonNon2xxCode() {
        Assert.assertFalse(transport.processResponse(400, "{\"result\":\"Bad Request\"}", 1L));
        Assert.assertFalse(transport.processResponse(500, "{\"result\":\"Internal Server Error\"}", 2L));
        Assert.assertFalse(transport.processResponse(302, "{\"result\":\"redirect\"}", 3L));
        Assert.assertFalse(transport.processResponse(199, "{\"result\":\"ok\"}", 4L));
    }

    /**
     * Valid JSON response but missing "result" key with 200 status code
     * should return false (failure)
     */
    @Test
    public void processResponse_validJsonMissingResultKey() {
        Assert.assertFalse(transport.processResponse(200, "{\"error\":\"something\"}", 1L));
        Assert.assertFalse(transport.processResponse(200, "{}", 2L));
    }

    /**
     * Null response should return false without throwing NPE
     * This was the original bug path — response() returns null on IOException
     */
    @Test
    public void processResponse_nullResponse() {
        Boolean result = transport.processResponse(200, null, 1L);
        Assert.assertFalse(result);
    }

    /**
     * HTML response (e.g., 502/503 error page) should return false
     * This is the primary scenario from issue #264
     */
    @Test
    public void processResponse_htmlResponse() {
        String html502 = "<html><body><h1>502 Bad Gateway</h1></body></html>";
        Boolean result = transport.processResponse(502, html502, 1L);
        Assert.assertFalse(result);
    }

    /**
     * Plain text non-JSON response should return false
     */
    @Test
    public void processResponse_plainTextResponse() {
        Boolean result = transport.processResponse(200, "OK", 1L);
        Assert.assertFalse(result);
    }

    /**
     * Empty string response should return false
     */
    @Test
    public void processResponse_emptyStringResponse() {
        Boolean result = transport.processResponse(200, "", 1L);
        Assert.assertFalse(result);
    }

    /**
     * Malformed JSON should return false without propagating JSONException
     */
    @Test
    public void processResponse_malformedJson() {
        Assert.assertFalse(transport.processResponse(200, "{invalid json", 1L));
        Assert.assertFalse(transport.processResponse(200, "not json at all", 2L));
        Assert.assertFalse(transport.processResponse(200, "{{{{", 3L));
    }

    /**
     * Edge case: JSON array instead of JSON object should return false
     */
    @Test
    public void processResponse_jsonArrayResponse() {
        Boolean result = transport.processResponse(200, "[{\"result\":\"ok\"}]", 1L);
        Assert.assertFalse(result);
    }

    /**
     * Boundary status codes around the 200-299 range
     */
    @Test
    public void processResponse_boundaryStatusCodes() {
        Assert.assertTrue(transport.processResponse(200, "{\"result\":\"ok\"}", 1L));
        Assert.assertTrue(transport.processResponse(299, "{\"result\":\"ok\"}", 2L));
        Assert.assertFalse(transport.processResponse(199, "{\"result\":\"ok\"}", 3L));
        Assert.assertFalse(transport.processResponse(300, "{\"result\":\"ok\"}", 4L));
    }
}
