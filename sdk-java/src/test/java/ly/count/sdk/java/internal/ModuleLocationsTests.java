package ly.count.sdk.java.internal;

import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleLocationsTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "disableLocation"
     * Validating that empty location parameter is sent
     * Request queue size should be 1, and it should be a location request
     */
    @Test
    public void disableLocation() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        Countly.session().begin();
        Countly.instance().location().disableLocation();
        validateLocationRequestInRQ(UserEditorTests.map("location", ""));
    }

    /**
     * "disableLocation"
     * Validating that empty location parameter is not sent because location is not enabled
     * Request queue size should be 0
     */
    @Test
    public void disableLocation_noConsent() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Assert.assertNull(Countly.instance().location());
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * "setLocation"
     * Validating that location parameters are sent
     * Request queue size should be 1, and it should be a location request
     */
    @Test
    public void setLocation() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        Countly.session().begin();
        Countly.instance().location().setLocation("US", "New York", "1,2", "1.1.1.1");
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1"));
    }

    /**
     * "setLocation"
     * Validating that location parameters are sent
     * Request queue size should be 1, and it should be a location request
     */
    @Test
    public void setLocation_cityOnly() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        Countly.session().begin();
        Countly.instance().location().setLocation(null, "New York", "1,2", "1.1.1.1");
        validateLocationRequestInRQ(UserEditorTests.map("city", "New York", "location", "1,2", "ip", "1.1.1.1"));
    }

    /**
     * "setLocation" with not began session
     * Validating that location parameters are added to the session
     * Request queue size should be 1, and it should be a location request
     */
    @Test
    public void setLocation_notBeganSession() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions));
        Countly.instance().location().setLocation("US", "New York", "1,2", "1.1.1.1");
        Countly.session().begin();
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1", "begin_session", "1"));
    }

    /**
     * Validates the location request in the request queue
     * Also validates that request queue size is requestIndex + 1
     *
     * @param expectedParams expected parameters in the request
     */
    protected void validateLocationRequestInRQ(Map<String, Object> expectedParams) {
        if (expectedParams.isEmpty()) { // nothing to validate, just return
            Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
            return;
        }
        Map<String, String>[] requestsInQ = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requestsInQ.length);

        TestUtils.validateRequiredParams(requestsInQ[0]); // this validates 9 params
        int expectedSessionParams = 0;
        if (expectedParams.containsKey("begin_session")) {
            TestUtils.validateMetrics(requestsInQ[0].get("metrics"));
            expectedSessionParams += 2; // we need to add 2 more params for metrics and session_id
        }
        Assert.assertEquals(9 + expectedParams.size() + expectedSessionParams, requestsInQ[0].size()); // so we need to add expect 9 + params size
        expectedParams.forEach((key, value) -> Assert.assertEquals(value.toString(), requestsInQ[0].get(key)));
    }
}
