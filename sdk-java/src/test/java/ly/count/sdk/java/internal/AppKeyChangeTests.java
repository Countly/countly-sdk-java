package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Changing the app key while the SDK runs and the request queue calls, asserted on the request
 * queue and the event queue the SDK stores.
 */
@RunWith(JUnit4.class)
public class AppKeyChangeTests {

    private static final String NEW_APP_KEY = "NEW_APP_KEY";
    private static final String VIEW_EVENT = "[CLY]_view";

    /**
     * Starts every test without SDK files.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * Stops the SDK and deletes its data.
     */
    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * The running view, the queued events, the pending user profile changes and the running session
     * of the old app go out under the old key, then a session begins under the new key. The timed
     * event keeps running and goes out under the new key, and the device ID stays.
     */
    @Test
    public void changeAppKey_closesTheOldAppAndBeginsTheNewOne() {
        Countly.instance().init(config(Config.Feature.Sessions, Config.Feature.Views, Config.Feature.Events, Config.Feature.UserProfiles));
        Countly.session().begin();
        Countly.instance().views().startView("home");
        Countly.instance().events().startEvent("timed");
        Countly.instance().events().recordEvent("before_change");
        Countly.instance().userProfile().setProperty("plan", "pro");
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);

        Countly.instance().changeAppKey(NEW_APP_KEY);

        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(NEW_APP_KEY, SDKCore.instance.config.getServerAppKey());
        Assert.assertEquals(0, TestUtils.getCurrentEQ().size());
        Assert.assertEquals("1", rq[0].get("begin_session"));
        Assert.assertEquals("1", rq[rq.length - 2].get("end_session"));
        Assert.assertEquals("1", rq[rq.length - 1].get("begin_session"));
        Assert.assertEquals(NEW_APP_KEY, rq[rq.length - 1].get("app_key"));
        Assert.assertNull(rq[rq.length - 1].get("events"));
        for (int i = 0; i < rq.length - 1; i++) {
            Assert.assertEquals(TestUtils.SERVER_APP_KEY, rq[i].get("app_key"));
        }
        for (Map<String, String> request : rq) {
            Assert.assertEquals(TestUtils.DEVICE_ID, request.get("device_id"));
        }

        List<JSONObject> oldAppEvents = events(rq, TestUtils.SERVER_APP_KEY);
        Assert.assertEquals(1, count(oldAppEvents, "before_change"));
        Assert.assertEquals(0, count(oldAppEvents, "timed"));
        Assert.assertTrue("the running view is stopped under the old key", oldAppEvents.stream().anyMatch(e -> VIEW_EVENT.equals(e.getString("key")) && e.has("dur")));
        Assert.assertEquals(1, requestsWith(rq, "user_details", TestUtils.SERVER_APP_KEY));

        Countly.instance().events().endEvent("timed");
        Countly.instance().attemptToSendStoredRequests();
        Map<String, String>[] after = TestUtils.getCurrentRQ();
        Assert.assertEquals(rq.length + 1, after.length);
        List<JSONObject> newAppEvents = events(after, NEW_APP_KEY);
        Assert.assertEquals(1, count(newAppEvents, "timed"));
        Assert.assertEquals("the stopped view is not restarted", 0, count(newAppEvents, VIEW_EVENT));
    }

    /**
     * An empty or placeholder app key is ignored with log code e103, and the key in use is ignored
     * too: nothing is closed or sent, and the key stays.
     */
    @Test
    public void changeAppKey_invalidPlaceholderAndSameKeyAreIgnored() {
        Countly.instance().init(config(Config.Feature.Sessions, Config.Feature.Events));
        Countly.session().begin();
        Countly.instance().events().recordEvent("stays_queued");

        Countly.instance().changeAppKey(null);
        Countly.instance().changeAppKey("");
        Countly.instance().changeAppKey("YOUR_APP_KEY");
        Countly.instance().changeAppKey(TestUtils.SERVER_APP_KEY);

        Assert.assertEquals(TestUtils.SERVER_APP_KEY, SDKCore.instance.config.getServerAppKey());
        Assert.assertEquals(3, logCode("e103"));
        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, rq.length);
        Assert.assertEquals("1", rq[0].get("begin_session"));
        Assert.assertEquals(1, TestUtils.getCurrentEQ().size());
        Assert.assertTrue(Countly.session().isActive());
    }

    /**
     * Without a running session no session is ended or begun: only the queued events are sent under
     * the old key, and what is recorded afterwards carries the new key.
     */
    @Test
    public void changeAppKey_withoutSession_beginsNoSession() {
        Countly.instance().init(config(Config.Feature.Sessions, Config.Feature.Events));
        Countly.instance().events().recordEvent("old_app");

        Countly.instance().changeAppKey(NEW_APP_KEY);
        Countly.instance().events().recordEvent("new_app");
        Countly.instance().attemptToSendStoredRequests();

        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, rq.length);
        Assert.assertEquals(1, count(events(rq, TestUtils.SERVER_APP_KEY), "old_app"));
        Assert.assertEquals(1, count(events(rq, NEW_APP_KEY), "new_app"));
        for (Map<String, String> request : rq) {
            Assert.assertNull(request.get("begin_session"));
            Assert.assertNull(request.get("end_session"));
        }
    }

    /**
     * Queued requests keep the key they were created with until the queue call rewrites them all
     * to the current key, the rest of each request untouched.
     */
    @Test
    public void replaceAllAppKeysInQueueWithCurrentAppKey_rewritesTheOldRequests() {
        Map<String, String>[] before = queueOneRequestPerAppKey();
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, before[0].get("app_key"));
        Assert.assertEquals(NEW_APP_KEY, before[1].get("app_key"));

        Countly.instance().replaceAllAppKeysInQueueWithCurrentAppKey();

        Map<String, String>[] after = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, after.length);
        for (int i = 0; i < after.length; i++) {
            Assert.assertEquals(NEW_APP_KEY, after[i].get("app_key"));
            Assert.assertEquals(before[i].get("events"), after[i].get("events"));
            Assert.assertEquals(before[i].get("timestamp"), after[i].get("timestamp"));
            Assert.assertEquals(before[i].size(), after[i].size());
        }
    }

    /**
     * The queue call removes the requests created under another key and keeps the ones of the
     * current key.
     */
    @Test
    public void removeDifferentAppKeysFromQueue_dropsTheOldRequests() {
        Map<String, String>[] before = queueOneRequestPerAppKey();

        Countly.instance().removeDifferentAppKeysFromQueue();

        Map<String, String>[] after = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, after.length);
        Assert.assertEquals(NEW_APP_KEY, after[0].get("app_key"));
        Assert.assertEquals(before[1].get("events"), after[0].get("events"));
    }

    /**
     * Flushing drops the queued requests and events. The timed event, the running view, the pending
     * user profile change and the health check counters are kept, and what follows is queued again.
     */
    @Test
    public void flushQueues_dropsQueuedDataAndKeepsWhatIsNotQueued() {
        Countly.instance().init(config(Config.Feature.Sessions, Config.Feature.Views, Config.Feature.Events, Config.Feature.UserProfiles));
        Countly.session().begin();
        Countly.instance().views().startView("running");
        Countly.instance().events().startEvent("timed");
        Countly.instance().events().recordEvent("flushed_request");
        Countly.instance().attemptToSendStoredRequests();
        Countly.instance().events().recordEvent("flushed_event");
        Countly.instance().userProfile().setProperty("kept", "yes");
        Countly.instance().changeAppKey("");
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
        Assert.assertEquals(1, TestUtils.getCurrentEQ().size());

        Countly.instance().flushQueues();

        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
        Assert.assertEquals(0, TestUtils.getCurrentEQ().size());
        Assert.assertEquals(1, logCode("e103"));

        Countly.instance().userProfile().save();
        Countly.instance().events().endEvent("timed");
        Countly.instance().views().stopViewWithName("running");
        Countly.instance().attemptToSendStoredRequests();
        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requestsWith(rq, "user_details", TestUtils.SERVER_APP_KEY));
        List<JSONObject> events = events(rq, TestUtils.SERVER_APP_KEY);
        Assert.assertEquals(1, count(events, "timed"));
        Assert.assertTrue(events.stream().anyMatch(e -> VIEW_EVENT.equals(e.getString("key")) && e.has("dur")));
        Assert.assertEquals(0, count(events, "flushed_request") + count(events, "flushed_event"));
    }

    /**
     * The settings and remote config answers to the requests of the old app that arrive after the
     * app key changed are not applied; the answers to the requests of the new app are.
     */
    @Test
    public void changeAppKey_lateAnswersForTheOldAppAreNotApplied() {
        List<Object[]> requests = new CopyOnWriteArrayList<>();
        InternalConfig config = new InternalConfig(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.RemoteConfig).enableRemoteConfigAutomaticTriggers());
        config.immediateRequestGenerator = () -> (requestData, customEndpoint, cp, delayed, networkingIsEnabled, callback, log) -> requests.add(new Object[] { requestData, callback });
        Countly.instance().init(config);
        int eventQueueSize = SDKCore.instance.config.getConfigurationProvider().getEventQueueSizeThreshold();
        Assert.assertNotEquals(7, eventQueueSize);

        Countly.instance().changeAppKey(NEW_APP_KEY);
        answer(requests, "sc", TestUtils.SERVER_APP_KEY, new ServerConfigBuilder().eventQueueSize(7).build());
        answer(requests, "rc", TestUtils.SERVER_APP_KEY, "{\"color\":\"red\"}");

        Assert.assertEquals(eventQueueSize, SDKCore.instance.config.getConfigurationProvider().getEventQueueSizeThreshold());
        Assert.assertTrue(Countly.instance().remoteConfig().getValues().isEmpty());

        answer(requests, "sc", NEW_APP_KEY, new ServerConfigBuilder().eventQueueSize(7).build());
        answer(requests, "rc", NEW_APP_KEY, "{\"size\":\"big\"}");

        Assert.assertEquals(7, SDKCore.instance.config.getConfigurationProvider().getEventQueueSizeThreshold());
        Assert.assertEquals(1, Countly.instance().remoteConfig().getValues().size());
        Assert.assertTrue(Countly.instance().remoteConfig().getValues().containsKey("size"));
    }

    /**
     * Backend mode keeps the app key the application set on each request: the app key change and
     * the queue calls that rewrite or drop requests are ignored. The server URL change and sending
     * the stored requests work.
     */
    @Test
    public void backendMode_ignoresTheAppKeyCalls() throws Exception {
        Countly.instance().init(TestUtils.getBaseConfig().enableBackendMode());
        ModuleRequests.pushAsync(SDKCore.instance.config, new Request("app_key", "OTHER", "k", "v")).get();
        Countly.instance().backendM().sessionBegin("backend_device", null, null, null);
        Countly.instance().backendM().recordEvent("backend_device", "backend_event", 1, null, null, null, null);
        int queued = SDKCore.instance.requestQueueMemory.size();
        Assert.assertTrue(queued > 0);

        Countly.instance().changeAppKey(NEW_APP_KEY);
        Countly.instance().flushQueues();
        Countly.instance().replaceAllAppKeysInQueueWithCurrentAppKey();
        Countly.instance().removeDifferentAppKeysFromQueue();

        Assert.assertEquals(TestUtils.SERVER_APP_KEY, SDKCore.instance.config.getServerAppKey());
        Map<String, String>[] stored = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, stored.length);
        Assert.assertEquals("OTHER", stored[0].get("app_key"));
        Assert.assertEquals(queued, SDKCore.instance.requestQueueMemory.size());
        for (Request request : SDKCore.instance.requestQueueMemory) {
            Assert.assertEquals(TestUtils.SERVER_APP_KEY, request.params.get("app_key"));
        }

        Countly.instance().attemptToSendStoredRequests();
        Assert.assertEquals(queued + 1, SDKCore.instance.requestQueueMemory.size());
        Assert.assertTrue(((ModuleBackendMode) Countly.instance().backendM().getModule()).eventQueues.isEmpty());

        Countly.instance().changeServerUrl("https://other.test.server.com");
        Assert.assertEquals("https://other.test.server.com", SDKCore.instance.config.getServerURL().toString());
    }

    /**
     * Every call made before init does nothing and is counted as log code w101. The URL and key of
     * a change are kept in memory only, so the next init uses the ones of the configuration.
     */
    @Test
    public void beforeInitAndNextInit() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().halt();

        Countly.instance().changeServerUrl("https://other.test.server.com");
        Countly.instance().changeAppKey(NEW_APP_KEY);
        Countly.instance().flushQueues();
        Countly.instance().replaceAllAppKeysInQueueWithCurrentAppKey();
        Countly.instance().removeDifferentAppKeysFromQueue();
        Countly.instance().attemptToSendStoredRequests();

        Config config = TestUtils.getBaseConfig();
        Countly.instance().init(config);
        Assert.assertEquals(6, logCode("w101"));
        Assert.assertEquals(TestUtils.SERVER_URL, SDKCore.instance.config.getServerURL().toString());
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, SDKCore.instance.config.getServerAppKey());

        Countly.instance().changeServerUrl("https://other.test.server.com");
        Countly.instance().changeAppKey(NEW_APP_KEY);
        Countly.instance().stop();
        Countly.instance().init(config);
        Assert.assertEquals(TestUtils.SERVER_URL, SDKCore.instance.config.getServerURL().toString());
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, SDKCore.instance.config.getServerAppKey());
        Assert.assertEquals(TestUtils.SERVER_URL, config.getServerURL().toString());
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, config.getServerAppKey());
    }

    // region helpers

    /**
     * The base test configuration with the given features and an event queue that holds ten events.
     */
    private static Config config(Config.Feature... features) {
        return TestUtils.getBaseConfig().enableFeatures(features).setEventQueueSizeToSend(10);
    }

    /**
     * Queues one event request under the configured key, changes the key, then queues one under the
     * new key.
     *
     * @return the request queue
     */
    private static Map<String, String>[] queueOneRequestPerAppKey() {
        Countly.instance().init(config(Config.Feature.Events));
        Countly.instance().events().recordEvent("old_app");
        Countly.instance().attemptToSendStoredRequests();
        Countly.instance().changeAppKey(NEW_APP_KEY);
        Countly.instance().events().recordEvent("new_app");
        Countly.instance().attemptToSendStoredRequests();

        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, rq.length);
        return rq;
    }

    /**
     * Answers every captured immediate request of a method and app key with the given response.
     */
    private static void answer(List<Object[]> requests, String method, String appKey, String response) {
        int answered = 0;
        for (Object[] request : requests) {
            String data = (String) request[0];
            if (data.contains("method=" + method) && data.contains("app_key=" + appKey)) {
                ((ImmediateRequestMaker.InternalImmediateRequestCallback) request[1]).callback(new JSONObject(response));
                answered++;
            }
        }
        Assert.assertEquals(1, answered);
    }

    /**
     * How often the running health check counter recorded a log code.
     */
    private static int logCode(String code) {
        return SDKCore.instance.healthCheckCounter.snapshot().getJSONObject("lc").optInt(code, 0);
    }

    /**
     * The events of the queued requests that carry the given app key, in queue order.
     */
    private static List<JSONObject> events(Map<String, String>[] rq, String appKey) {
        List<JSONObject> events = new ArrayList<>();
        for (Map<String, String> request : rq) {
            if (appKey.equals(request.get("app_key")) && request.containsKey("events")) {
                JSONArray array = new JSONArray(request.get("events"));
                for (int i = 0; i < array.length(); i++) {
                    events.add(array.getJSONObject(i));
                }
            }
        }
        return events;
    }

    /**
     * How many of the events have the given key.
     */
    private static int count(List<JSONObject> events, String key) {
        return (int) events.stream().filter(e -> key.equals(e.getString("key"))).count();
    }

    /**
     * How many queued requests with the given app key carry the given parameter.
     */
    private static int requestsWith(Map<String, String>[] rq, String param, String appKey) {
        int found = 0;
        for (Map<String, String> request : rq) {
            if (appKey.equals(request.get("app_key")) && request.containsKey(param)) {
                found++;
            }
        }
        return found;
    }

    // endregion
}
