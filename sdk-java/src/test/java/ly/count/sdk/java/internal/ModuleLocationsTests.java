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
        validateLocationRequestInRQ(UserEditorTests.map("location", ""), 0);
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
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1"), 0);
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
        validateLocationRequestInRQ(UserEditorTests.map("city", "New York", "location", "1,2", "ip", "1.1.1.1"), 0);
    }

    /**
     * "setLocation" with not began session
     * Validating that location parameters are added to the session
     * Request queue size should be 2, one for config set location a began session request
     */
    @Test
    public void setLocation_notBeganSession() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions));
        Countly.instance().location().setLocation("US", "New York", "1,2", "1.1.1.1");
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1"), 0);
        Countly.session().begin();
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1", "begin_session", "1"), 1);
    }

    /**
     * "setLocation" with not began session with config setup
     * Validating that location parameters are added to the session
     * Request queue size should be 1, and it should be a began session request
     */
    @Test
    public void setLocation_notBeganSession_withConfig() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions)
            .setLocation("US", "New York", "1,2", "1.1.1.1"));
        Thread.sleep(100);
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1"), 0);
        Countly.session().begin();
        Thread.sleep(100);
        validateLocationRequestInRQ(UserEditorTests.map("country_code", "US", "city", "New York", "location", "1,2", "ip", "1.1.1.1", "begin_session", "1"), 1);
    }

    /**
     * "setLocation" with not began session with config disable location
     * Validating that location parameters are not added to the session and only empty location is sent
     * Request queue size should be 2, one for init and one for a began session request
     */
    @Test
    public void disableLocation_notBeganSession_withConfig() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions)
            .setLocation("US", "New York", "1,2", "1.1.1.1")
            .disableLocation());
        validateLocationRequestInRQ(UserEditorTests.map("location", ""), 0);
        Countly.session().begin();
        validateLocationRequestInRQ(UserEditorTests.map("location", "", "begin_session", "1"), 1);
    }

    /**
     * Validates the location request in the request queue
     * Also validates that request queue size is requestIndex + 1
     *
     * @param expectedParams expected parameters in the request
     */
    protected void validateLocationRequestInRQ(Map<String, Object> expectedParams, int rqIdx) {
        if (expectedParams.isEmpty()) { // nothing to validate, just return
            Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
            return;
        }
        Map<String, String>[] requestsInQ = TestUtils.getCurrentRQ();
        Assert.assertEquals(rqIdx + 1, requestsInQ.length);

        TestUtils.validateRequiredParams(requestsInQ[rqIdx]); // this validates 9 params
        int expectedSessionParams = 0;
        if (expectedParams.containsKey("begin_session")) {
            TestUtils.validateMetrics(requestsInQ[rqIdx].get("metrics"));
            expectedSessionParams += 2; // we need to add 2 more params for metrics and session_id
        }
        Assert.assertEquals(9 + expectedParams.size() + expectedSessionParams, requestsInQ[rqIdx].size()); // so we need to add expect 9 + params size
        expectedParams.forEach((key, value) -> Assert.assertEquals(value.toString(), requestsInQ[rqIdx].get(key)));
    }
}
