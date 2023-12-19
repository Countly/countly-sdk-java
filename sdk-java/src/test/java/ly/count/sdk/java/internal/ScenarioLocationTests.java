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
public class ScenarioLocationTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * 1) dev sets location some time after init
     * init without location
     * begin_session (without location)
     * setLocation(gps) (location request with gps)
     * end_session
     * begin_session (with location - gps)
     * end_session
     * begin_session (with location - gps)
     * end_session
     * Test continues with the order given above, wait calls are added to make sure that requests are
     * sent in the correct order
     */
    @Test
    public void setLocation_noInitTimeConfigForLocation() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions));

        beginAndValidateSessionRequest(0, "begin_session", "1");

        Countly.instance().location().setLocation(null, null, "1,2", null);
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("location", "1,2"), 1);

        endAndValidateEndSessionRequest(2);
        beginAndValidateSessionRequest(3, "begin_session", "1", "location", "1,2");
        endAndValidateEndSessionRequest(4);
        beginAndValidateSessionRequest(5, "begin_session", "1", "location", "1,2");
        endAndValidateEndSessionRequest(6);
    }

    /**
     * 2) dev sets location during init and a separate call
     * init with location (city, country)
     * begin_session (with location - city, country)
     * setLocation(gps) (location request with gps)
     * end_session
     * begin_session (with location - gps)
     * end_session
     * begin_session (with location - gps)
     * end_session
     * Test continues with the order given above, wait calls are added to make sure that requests are
     * sent in the correct order
     */
    @Test
    public void setLocationOnInitAndAfterInit() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions)
            .setLocation("TR", "Izmir", null, null));
        Thread.sleep(200); // wait for first init location req to be written
        validateLocationRequestInRQ(TestUtils.map("city", "Izmir", "country_code", "TR"), 0);

        beginAndValidateSessionRequest(1, "begin_session", "1", "city", "Izmir", "country_code", "TR");

        Countly.instance().location().setLocation(null, null, "1,2", null);
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("location", "1,2"), 2);

        endAndValidateEndSessionRequest(3);
        beginAndValidateSessionRequest(4, "begin_session", "1", "location", "1,2");
        endAndValidateEndSessionRequest(5);
        beginAndValidateSessionRequest(6, "begin_session", "1", "location", "1,2");
        endAndValidateEndSessionRequest(7);
    }

    /**
     * 3) dev sets location during init and after begin_session calls
     * init with location (city, country)
     * begin_session (with location - city, country)
     * setLocation(gps) (location request with gps)
     * end_session
     * begin_session (with location - gps)
     * setLocation(ipAddress) (location request with ipAddress)
     * end_session
     * begin_session (with location - ipAddress)
     * end_session
     * Test continues with the order given above, wait calls are added to make sure that requests are
     * sent in the correct order
     */
    @Test
    public void setLocationOnInitAndAfterBeginSession() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions)
            .setLocation("TR", "Izmir", null, null));
        Thread.sleep(200); // wait for first init location req to be written
        validateLocationRequestInRQ(TestUtils.map("city", "Izmir", "country_code", "TR"), 0);

        beginAndValidateSessionRequest(1, "begin_session", "1", "city", "Izmir", "country_code", "TR");

        Countly.instance().location().setLocation(null, null, "1,2", null);
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("location", "1,2"), 2);

        endAndValidateEndSessionRequest(3);
        beginAndValidateSessionRequest(4, "begin_session", "1", "location", "1,2");

        Countly.instance().location().setLocation(null, null, null, "1.1.1.1");
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("ip", "1.1.1.1"), 5);

        endAndValidateEndSessionRequest(6);
        beginAndValidateSessionRequest(7, "begin_session", "1", "ip", "1.1.1.1");
        endAndValidateEndSessionRequest(8);
    }

    /**
     * 4) dev sets location before first begin_session
     * init with location (city, country)
     * setLocation(gps, ipAddress) (location request with gps, ipAddress)
     * begin_session (with location - gps, ipAddress)
     * setLocation(city, country, gps2) (location request with city, country, gps2)
     * end_session
     * begin_session (with location - city, country, gps2)
     * end_session
     * Test continues with the order given above, wait calls are added to make sure that requests are
     * sent in the correct order
     */
    @Test
    public void setLocationOnInitAndBeforeBeginSession() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location, Config.Feature.Sessions)
            .setLocation("TR", "Izmir", null, null));
        Thread.sleep(200); // wait for first location req to be written
        validateLocationRequestInRQ(TestUtils.map("country_code", "TR", "city", "Izmir"), 0);

        Countly.instance().location().setLocation(null, null, "1,2", "1.1.1.1");
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("ip", "1.1.1.1", "location", "1,2"), 1);

        beginAndValidateSessionRequest(2, "begin_session", "1", "ip", "1.1.1.1", "location", "1,2");

        Countly.instance().location().setLocation("TR", "Izmir", "3,4", null);
        Thread.sleep(200); // wait for location req to be written
        validateLocationRequestInRQ(TestUtils.map("country_code", "TR", "location", "3,4", "city", "Izmir"), 3);

        endAndValidateEndSessionRequest(4);
        beginAndValidateSessionRequest(5, "begin_session", "1", "country_code", "TR", "location", "3,4", "city", "Izmir");
        endAndValidateEndSessionRequest(6);
    }

    private void beginAndValidateSessionRequest(int rqIdx, Object... params) throws InterruptedException {
        Countly.session().begin();
        Thread.sleep(200); // wait for begin_session req to be written
        validateLocationRequestInRQ(TestUtils.map(params), rqIdx);
    }

    private void endAndValidateEndSessionRequest(int rqIdx) throws InterruptedException {
        Countly.session().end();
        Thread.sleep(200); // wait for end_session req to be written
        validateLocationRequestInRQ(TestUtils.map("end_session", "1"), rqIdx);
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
        } else if (expectedParams.containsKey("end_session")) {
            expectedSessionParams += 1; // we need to add 1 more param for session_duration
        }
        Assert.assertEquals(9 + expectedParams.size() + expectedSessionParams, requestsInQ[rqIdx].size()); // so we need to add expect 9 + params size
        expectedParams.forEach((key, value) -> Assert.assertEquals(value.toString(), requestsInQ[rqIdx].get(key)));
    }
}
