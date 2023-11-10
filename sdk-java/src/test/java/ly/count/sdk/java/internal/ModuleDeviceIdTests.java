package ly.count.sdk.java.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleDeviceIdTests {

    @After
    public void stop() {
        Countly.instance().halt();
        SDKCore.testDummyModule = null;
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * Device ID acquisition process
     * Initializing the SDK with no custom ID, that should trigger ID generation
     * The acquired device ID should start with "CLY_"
     */
    @Test
    public void generatedDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig(null));
        validateDeviceIdIsSdkGenerated();
    }

    /**
     * Device ID acquisition process
     * Initializing the SDK with a custom ID, that should not trigger ID generation
     * The acquired device ID should not contain any "CLY_"
     */
    @Test
    public void customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID));
        validateDeviceIdDeveloperSupplied(TestUtils.DEVICE_ID);
    }

    /**
     * "changeWithMerge"
     * Validating that only one began session request is created and two device id change request for two
     * "changeWithMerge" calls with different ids
     * SDK must generate an id first, then should change with developer supplied two times
     */
    @Test
    public void changeWithMerge() {
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.DEVICE_ID);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, false, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupView_Event_Session();
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length); // began session request
        // validate began session request with generated id
        validateBeganSessionRequest();
        validateDeviceIdIsSdkGenerated(); // validate device id generated by the sdk

        String oldDeviceId = Countly.instance().deviceId().getID();
        Assert.assertEquals(0, callCount.get()); // validate "deviceIdChanged" callback not called

        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request
        Countly.instance().deviceId().changeWithMerge(deviceID.value); // TestUtils.DEVICE_ID
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request

        Assert.assertEquals(1, callCount.get());
        validateDeviceIdWithMergeChange(oldDeviceId, 1, 2);

        deviceID.value += "1";
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request
        Countly.instance().deviceId().changeWithMerge(deviceID.value); // TestUtils.DEVICE_ID + "1"
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request

        Assert.assertEquals(2, callCount.get());
        validateDeviceIdWithMergeChange(TestUtils.DEVICE_ID, 2, 3);
    }

    /**
     * "changeWithoutMerge" with custom device id
     * Validating that new id set and callback is called, and existing events,timed events and session must end, new session must begin
     * request order should be first began session, 1 events, 1 end session, second began session, second end session, third began session
     */
    @Test
    public void changeWithoutMerge() throws InterruptedException {
        //why atomic string? Because changing it should also trigger dummy module callback asserts.
        //so it should be modifiable from outside
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.keysValues[0]);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(TestUtils.DEVICE_ID)); //custom id given
        setupView_Event_Session(); // setup view, event and session to simulate a device id change
        validateBeganSessionRequest(); // also validates rq size is 1

        validateDeviceIdDeveloperSupplied(TestUtils.DEVICE_ID);
        Assert.assertEquals(0, callCount.get());

        Thread.sleep(1000); // waiting for timed event duration
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should be 2 one view and a casual event
        Countly.instance().deviceId().changeWithoutMerge(deviceID.value);
        Assert.assertEquals(0, TestUtils.getCurrentEQ().size()); // size should be zero because queue is flushed
        Assert.assertEquals(1, callCount.get());
        validateDeviceIdWithoutMergeChange(4, TestUtils.DEVICE_ID); // there should be 2 began, 1 end, 1 events request
        TestUtils.flushCurrentRQWithOldDeviceId(TestUtils.DEVICE_ID); // clean current rq with old device id requests

        deviceID.value += "1"; //change device id
        Countly.instance().deviceId().changeWithoutMerge(deviceID.value);
        Assert.assertEquals(2, callCount.get());
        //if device id is not merged, then device id change request should not exist
        validateDeviceIdWithoutMergeChange(3, TestUtils.keysValues[0]); // additional 1 session end 1 session begin, no events because no events exist
    }

    /**
     * "changeWithoutMerge" multiple calls with same id
     * Validating that only one request added, and it is first began session request, also events queue not flushed and callback should not be called
     * SDK must generate an id first, call must not change anything
     */
    @Test
    public void changeWithoutMerge_sameDeviceId() {
        //why atomic string? Because changing it should also trigger dummy module callback asserts.
        //so it should be modifiable from outside
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.keysValues[0]);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); //let sdk generate
        setupView_Event_Session(); // setup view, event and session to simulate a device id change
        validateBeganSessionRequest(); // also validates rq size is 1
        validateDeviceIdIsSdkGenerated();

        Assert.assertEquals(0, callCount.get());
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should be 2 one view and a casual event
        Countly.instance().deviceId().changeWithoutMerge(Countly.instance().deviceId().getID()); // same device id
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // nothing should change
        Assert.assertEquals(0, callCount.get());
        validateBeganSessionRequest(); // also validates rq size is 1 and it is first began session request
    }

    /**
     * "changeWithoutMerge" with null device id
     * Validating that only one request added, and it should be a began session request, callback must not be called, and event queue should not be flushed
     * SDK must generate an id first, calling with null given id should not trigger anything
     */
    @Test
    public void changeWithoutMerge_nullDeviceId() {
        //why atomic string? Because changing it should also trigger dummy module callback asserts.
        //so it should be modifiable from outside
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.keysValues[0]);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); //let sdk generate
        setupView_Event_Session(); // setup view, event and session to simulate a device id change
        validateBeganSessionRequest(); // also validates rq size is 1
        validateDeviceIdIsSdkGenerated();

        Assert.assertEquals(0, callCount.get());
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should be 2 one view and a casual event
        Countly.instance().deviceId().changeWithoutMerge(null); // null device id
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // nothing should change
        Assert.assertEquals(0, callCount.get());
        validateBeganSessionRequest(); // also validates rq size is 1 and it is first began session request
    }

    /**
     * "changeWithoutMerge" with empty device id
     * Validating that only one request added, and it should be a began session request, callback must not be called, and event queue should not be flushed
     * SDK must generate an id first, calling with null given id should not trigger anything
     */
    @Test
    public void changeWithoutMerge_emptyDeviceId() {
        //why atomic string? Because changing it should also trigger dummy module callback asserts.
        //so it should be modifiable from outside
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.keysValues[0]);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); //let sdk generate
        setupView_Event_Session(); // setup view, event and session to simulate a device id change
        validateBeganSessionRequest(); // also validates rq size is 1
        validateDeviceIdIsSdkGenerated();

        Assert.assertEquals(0, callCount.get());
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should be 2 one view and a casual event
        Countly.instance().deviceId().changeWithoutMerge(""); // null device id
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // nothing should change
        Assert.assertEquals(0, callCount.get());
        validateBeganSessionRequest(); // also validates rq size is 1 and it is first began session request
    }

    /**
     * "changeWithMerge" with null device id
     * Validating that only one request added, and it should be a began session request, callback must not be called
     * SDK must generate an id first, calling with null given id should not trigger anything
     */
    @Test
    public void changeWithMerge_nullDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(null, false, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupView_Event_Session();

        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request
        Countly.instance().deviceId().changeWithMerge(null);
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request

        Assert.assertEquals(0, callCount.get());// validate callback not called
        validateBeganSessionRequest(); // also validates rq size is 1
    }

    /**
     * "changeWithMerge" with empty device id
     * Validating that only one request added, and it should be a began session request, callback must not be called
     * SDK must generate an id first, calling with empty given id should not trigger anything
     */
    @Test
    public void changeWithMerge_emptyDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(""), false, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupView_Event_Session();

        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request
        Countly.instance().deviceId().changeWithMerge("");
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request

        Assert.assertEquals(0, callCount.get()); // validate callback not called
        validateBeganSessionRequest(); // also validates rq size is 1
    }

    /**
     * "changeWithMerge" multiple calls with same id
     * Validating that only two request added, one is session began, and other should be device id change request
     * and callback should be called only once
     * SDK must generate an id first, then should change with developer supplied. Second call must not change anything
     */
    @Test
    public void changeWithMerge_sameDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(TestUtils.DEVICE_ID), false, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupView_Event_Session();
        validateBeganSessionRequest(); // also validates rq size is 1

        String oldDeviceId = Countly.instance().deviceId().getID();
        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // one view and one casual event
        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request
        Assert.assertEquals(1, callCount.get());
        validateDeviceIdWithMergeChange(oldDeviceId, 1, 2);

        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size()); // size should not change because it is merge request

        validateDeviceIdWithMergeChange(oldDeviceId, 1, 2);
    }

    /**
     * "changeWithMerge" with not started session
     * Validating that only one request must exist and it should be a device id change request and callback should be called only once
     * SDK must generate an id first, then should change with developer supplied. Only one request must exist
     */
    @Test
    public void changeWithMerge_sessionNotStarted() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(TestUtils.DEVICE_ID), false, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupEvent();

        String oldDeviceId = Countly.instance().deviceId().getID();
        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Assert.assertEquals(1, TestUtils.getCurrentEQ().size()); //  one casual event no timed event because not end
        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, TestUtils.getCurrentEQ().size()); //  one casual event no timed event because not end

        Assert.assertEquals(1, callCount.get());
        validateDeviceIdWithMergeChange(oldDeviceId, 0, 1);
    }

    /**
     * "changeWithoutMerge" with not started session
     * Validating that only one request must exist and it should be the second begin session request and callback should be called only once
     * SDK must generate an id first, then should change with developer supplied. Only one request must exist
     */
    @Test
    public void changeWithoutMerge_sessionNotStarted() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(TestUtils.DEVICE_ID), true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id

        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithoutMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());
        validateBeganSessionRequest(); // only began session request must exist for new device id, no event created
    }

    /**
     * "changeWithoutMerge" with not started session, and with created events
     * Validating that only two request must exist one is events and the other should be the second begin session request and callback should be called only once
     * SDK must generate an id first, then should change with developer supplied. Only two request must exist
     */
    @Test
    public void changeWithoutMerge_sessionNotStarted_withEvents() throws InterruptedException {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(TestUtils.DEVICE_ID), true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupEvent(); // two events created one is timed event it is started, the other is casual event
        String oldDeviceId = Countly.instance().deviceId().getID();
        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());

        Assert.assertEquals(1, TestUtils.getCurrentEQ().size()); // should be one because timed event is not end
        Thread.sleep(1000); // wait for timed event
        Countly.instance().deviceId().changeWithoutMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());
        Assert.assertEquals(0, TestUtils.getCurrentEQ().size()); // should flush because without merge request

        validateEvents(0, oldDeviceId, 1); // validate events exist with old device id
        validateBeganSessionRequest(1); // this also validates request queue size it is index + 1
    }

    /**
     * "getID", "getType"
     * Custom id is not given, validating that device id is sdk generated
     * Type must be 'SDK_GENERATED' and generated id should be a valid UUID
     */
    @Test
    public void getID_getType() {
        Countly.instance().init(TestUtils.getBaseConfig(null)); // no custom id given
        validateDeviceIdIsSdkGenerated(); // validate id is a valid UUID
    }

    /**
     * "getID", "getType"
     * Custom id is given, validating that device id is developer supplied
     * Type must be 'DEVELOPER_SUPPLIED' and generated id should be same with the given id
     */
    @Test
    public void getID_getType_customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID)); // custom id given
        validateDeviceIdDeveloperSupplied(TestUtils.DEVICE_ID);
    }

    /**
     * "logout"
     * Validating that all required requests are added, order should be first began session, 1 events, 1 end session, second began session
     * uuid strategy generates a new id
     */
    @Test
    public void logout_sdkGenerated() throws InterruptedException {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(null, true, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getConfigDeviceId(null)); // to create sdk generated device id
        setupView_Event_Session();

        validateDeviceIdIsSdkGenerated();
        Assert.assertEquals(0, callCount.get());
        String oldDeviceId = Countly.instance().deviceId().getID();

        Thread.sleep(1000);  // waiting for timed event duration
        Countly.instance().logout();
        Assert.assertEquals(1, callCount.get());
        validateDeviceIdWithoutMergeChange(4, oldDeviceId);
    }

    /**
     * "logout" with custom device id
     * Validating that no request added other than first began session request
     * custom strategy does not generate a new id because it is always same
     */
    @Test
    public void logout_developerSupplied() {
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.DEVICE_ID);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getConfigDeviceId(TestUtils.DEVICE_ID)); // to create sdk generated device id
        setupView_Event_Session();

        validateDeviceIdDeveloperSupplied(TestUtils.DEVICE_ID);
        Assert.assertEquals(0, callCount.get());

        Countly.instance().logout();
        Assert.assertEquals(0, callCount.get()); // custom strategy only generates same id
        validateBeganSessionRequest();// only began request exist
    }

    /**
     * "acquireId"
     * Validating that acquired id is sdk generated and UUID
     * Acquired id must comply to UUID structure
     */
    @Test
    public void acquireId_sdkGenerated() {
        Countly.instance().init(TestUtils.getBaseConfig(null)); // no custom id provided
        validateDeviceIdIsSdkGenerated(); // validate id exist and sdk generated

        Config.DID did = SDKCore.instance.module(ModuleDeviceIdCore.class).acquireId();
        validateDeviceIdIsUUID(did.id);
    }

    /**
     * "acquireId"
     * Validating that acquired id is developer supplied
     * Acquired id must be same with the given id
     */
    @Test
    public void acquireId_customId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID)); // custom id provided
        validateDeviceIdDeveloperSupplied(TestUtils.DEVICE_ID); // validate id exist and developer supplied

        Config.DID did = SDKCore.instance.module(ModuleDeviceIdCore.class).acquireId();
        Assert.assertEquals(TestUtils.DEVICE_ID, did.id);
    }

    /**
     * validates that requests in RQ are valid for without merge request
     * It validates the requests after a without merge device id change
     * There are 4 requests possibility:
     * - First begin session request (required) #has old device id
     * - An event queue request which contains views, timed events and events (not required) #has old device id
     * - An end session request (required) #has old device id
     * - Second begin session request (required) #has new device id
     * <p>
     * So at least 3 requests must exist in the RQ
     *
     * This validator assumes that a session was started at the start and automatic sessions are enabled (which will start the session afterwards)
     *
     * @param rqSize expected RQ size
     * @param oldDeviceId to validate device id in requests before device id change
     */
    private void validateDeviceIdWithoutMergeChange(final int rqSize, String oldDeviceId) {
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(rqSize, TestUtils.getCurrentRQ().length);

        // validate first begin session request
        TestUtils.validateRequiredParams(requests[0], oldDeviceId); // first request must be began session request
        TestUtils.validateMetrics(requests[0].get("metrics")); // validate metrics exist in the first session request
        Assert.assertEquals("1", requests[0].get("begin_session")); // validate begin session value is 1

        int remainingRequestIndex = 1; // if there is no event request, then this will be 1 to continue checking

        // validate event request if exists
        try {
            List<EventImpl> existingEvents = validateEvents(1, oldDeviceId, 2);
            if (!existingEvents.isEmpty()) {
                Map<String, Object> viewSegmentation = new ConcurrentHashMap<>();
                viewSegmentation.put("segment", Device.dev.getOS());
                viewSegmentation.put("name", TestUtils.keysValues[1]);
                viewSegmentation.put("start", "1");
                viewSegmentation.put("visit", "1");
                TestUtils.validateEvent(existingEvents.get(1), "[CLY]_view", viewSegmentation, 1, null, null); // view start event
                viewSegmentation.remove("start");
                viewSegmentation.remove("visit");
                TestUtils.validateEvent(existingEvents.get(3), "[CLY]_view", viewSegmentation, 1, null, 1.0); // view stop event
                remainingRequestIndex++;
            }
        } catch (NullPointerException ignored) {
            //do nothing
        }

        // validate end session request
        TestUtils.validateRequiredParams(requests[remainingRequestIndex], oldDeviceId); // second begin session request must have new device id
        Assert.assertEquals("1", requests[remainingRequestIndex].get("end_session")); // validate begin session value is 1
        remainingRequestIndex++;
        // validate second begin session request
        TestUtils.validateRequiredParams(requests[remainingRequestIndex], Countly.instance().deviceId().getID()); // second begin session request must have new device id
        TestUtils.validateMetrics(requests[remainingRequestIndex].get("metrics")); // validate metrics exist in the second session request
        Assert.assertEquals("1", requests[remainingRequestIndex].get("begin_session")); // validate begin session value is 1
    }

    /**
     * Validates that the device id change request is valid
     *
     * @param oldDeviceId expected value of "old_device_id" param
     * @param rqIdx index of the request in the RQ
     * @param rqSize expected RQ size
     */
    private void validateDeviceIdWithMergeChange(String oldDeviceId, final int rqIdx, final int rqSize) {
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(rqSize, TestUtils.getCurrentRQ().length);

        TestUtils.validateRequiredParams(requests[rqIdx], Countly.instance().deviceId().getID());
        Assert.assertEquals(oldDeviceId, requests[rqIdx].get("old_device_id"));
    }

    /**
     * Initializes the dummy module to listen for device id change callbacks
     *
     * @param deviceId to validate expected device id is set
     * @param withoutMerge to validate its value
     * @param type to validate by types
     * @return call count of the callback
     */
    private AtomicInteger initDummyModuleForDeviceIdChangedCallback(TestUtils.AtomicString deviceId, boolean withoutMerge, DeviceIdType type) {
        AtomicInteger callCount = new AtomicInteger(0);
        SDKCore.testDummyModule = new ModuleBase() {
            @Override
            protected void deviceIdChanged(String oldDeviceId, boolean withMerge) {
                super.deviceIdChanged(oldDeviceId, withMerge);
                callCount.incrementAndGet();
                Assert.assertEquals(!withoutMerge, withMerge);
                if (type == DeviceIdType.SDK_GENERATED) {
                    validateDeviceIdIsSdkGenerated();
                } else {
                    validateDeviceIdDeveloperSupplied(deviceId.value);
                }
                Assert.assertEquals(type.index, internalConfig.getDeviceId().strategy);
            }
        };

        return callCount;
    }

    private void validateDeviceIdDeveloperSupplied(String expectedDeviceId) {
        Assert.assertEquals(expectedDeviceId, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());
    }

    private void validateDeviceIdIsSdkGenerated() {
        String deviceId = Countly.instance().deviceId().getID();
        try {
            validateDeviceIdIsUUID(deviceId);
            Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        } catch (IllegalArgumentException e) {
            Assert.fail("Device id is not a valid UUID");
        }
    }

    /**
     * Validates that the device id is a valid UUID and starts with "CLY_"
     */
    private void validateDeviceIdIsUUID(String deviceId) {
        try {
            Assert.assertTrue(deviceId.startsWith("CLY_"));
            String[] parts = deviceId.split("CLY_");
            UUID uuid = UUID.fromString(parts[1]);
            Assert.assertEquals(parts[1], uuid.toString());
        } catch (IllegalArgumentException e) {
            Assert.fail("Device id is not a valid UUID");
        }
    }

    /**
     * Validates that the began session request is valid
     * and it should be the first request
     */
    private void validateBeganSessionRequest() {
        validateBeganSessionRequest(0); // always 0 because it is at the first request
    }

    /**
     * Validates that the began session request is valid
     * and it should be at the index request
     */
    private void validateBeganSessionRequest(int index) {
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(index + 1, requests.length); // always 1 because it is the first request
        TestUtils.validateRequiredParams(requests[index], Countly.instance().deviceId().getID());
    }

    /**
     * Creates a view, a session and an event to simulate what
     * happens when a device id change occurs
     */
    private void setupView_Event_Session() {
        Countly.session().begin();
        setupEvent();
        Countly.instance().view(TestUtils.keysValues[1]).start(true);
    }

    private void setupEvent() {
        Countly.instance().events().startEvent(TestUtils.keysValues[0]);
        Countly.instance().events().recordEvent(TestUtils.keysValues[2]);
    }

    /**
     * This function validates pre-defined things
     * There should be two events, one is timed event it is started, the other is casual event
     * All keys are validated like in the function body
     */
    private List<EventImpl> validateEvents(int requestIndex, String deviceId, int timedEventIdx) {
        List<EventImpl> existingEvents = TestUtils.readEventsFromRequest(requestIndex, deviceId);
        if (!existingEvents.isEmpty()) {
            TestUtils.validateEvent(existingEvents.get(0), TestUtils.keysValues[2], null, 1, null, null); // casual event
            TestUtils.validateEvent(existingEvents.get(timedEventIdx), TestUtils.keysValues[0], null, 1, null, 1.0); // timed event
        }

        return existingEvents;
    }
}
