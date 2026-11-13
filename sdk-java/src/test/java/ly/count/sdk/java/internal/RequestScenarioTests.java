package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * In each request scenario the SDK queues the requests sdk-java/api/requests/[scenario].json records, with the values
 * that change from run to run masked. The scenarios make the calls the other Countly SDKs make in their own request
 * tests. A deliberate change: run COUNTLY_UPDATE_REQUESTS=1 ./gradlew :sdk-java:test --tests '*RequestScenarioTests'
 * --rerun, which writes the records, check the difference and add a line to CHANGELOG.md.
 */
@RunWith(JUnit4.class)
public class RequestScenarioTests {

    private static final String MASK = "<masked>";
    private static final List<String> MASKED_PARAMS = Arrays.asList("timestamp", "hour", "dow", "tz", "sdk_version", "av", "rr", "checksum", "checksum256", "session_duration", "session_id");
    private static final List<String> JSON_PARAMS = Arrays.asList("events", "metrics", "user_details", "crash", "consent", "hc");
    private static final List<String> MASKED_EVENT_FIELDS = Arrays.asList("timestamp", "hour", "dow", "id", "pvid", "cvid", "peid");
    private static final List<String> MASKED_CRASH_FIELDS = Arrays.asList("_error", "_run", "_os", "_os_version", "_resolution", "_density", "_locale", "_device", "_device_type", "_manufacture", "_cpu", "_opengl", "_ram_current", "_ram_total", "_disk_current", "_disk_total", "_bat", "_orientation", "_root", "_online", "_muted", "_background");
    // a view's "segment" is the operating system the SDK runs on
    private static final List<String> MASKED_VIEW_SEGMENTS = Arrays.asList("segment");
    private static final int MAX_INT = 2147483647;
    private static final File RECORDS = new File("api/requests");

    /**
     * The calls of a scenario.
     */
    private interface Scenario {
        /**
         * Makes the calls of the scenario.
         *
         * @throws InterruptedException when waiting for the SDK is interrupted
         */
        void run() throws InterruptedException;
    }

    /**
     * Starts every scenario from an empty SDK storage, with fixed device details and without the timer of a test that
     * shortened it.
     */
    @Before
    public void setUp() {
        TestUtils.createCleanTestState();
        CountlyTimer.TIMER_DELAY_MS = 0;
        TestUtils.setAdditionalDeviceMetrics();
    }

    /**
     * Stops the SDK and empties its storage.
     */
    @After
    public void tearDown() {
        TestUtils.createCleanTestState();
    }

    /**
     * A session with an event of every segmentation value type, the user details and a session update.
     *
     * @throws Exception when the requests cannot be read or compared
     */
    @Test
    public void parity() throws Exception {
        assertRequests("parity", config(), () -> {
            Countly.session().begin();
            Countly.instance().events().recordEvent("purchase", paritySegmentation(), 2, 9.99, 5.0);
            Map<String, Object> properties = new HashMap<>();
            properties.put("name", "John Doe");
            properties.put("username", "john_d");
            properties.put("email", "john@example.com");
            properties.put("organization", "Countly");
            properties.put("phone", "1234567890");
            properties.put("picture", "https://example.com/picture.jpg");
            properties.put("gender", "M");
            properties.put("byear", 1980);
            properties.put("tier", "gold");
            properties.put("age", 30);
            Countly.instance().userProfile().setProperties(properties);
            Countly.instance().userProfile().save();
            // a session update reports elapsed seconds, so at least one has to pass for it to be sent
            Thread.sleep(1100);
            Countly.session().update();
            Countly.session().end();
        });
    }

    /**
     * Two views in a session, the first with segmentation.
     *
     * @throws Exception when the requests cannot be read or compared
     */
    @Test
    public void views() throws Exception {
        assertRequests("views", config(), () -> {
            Countly.session().begin();
            Map<String, Object> segmentation = new HashMap<>();
            segmentation.put("source", "scenario");
            Countly.instance().views().startView("Home", segmentation);
            Countly.instance().views().stopViewWithName("Home");
            Countly.instance().views().startView("Settings");
            Countly.session().end();
        });
    }

    /**
     * A handled exception with a breadcrumb and segmentation.
     *
     * @throws Exception when the requests cannot be read or compared
     */
    @Test
    public void crash() throws Exception {
        assertRequests("crash", config(), () -> {
            Countly.instance().crashes().addCrashBreadcrumb("breadcrumb one");
            Map<String, Object> segmentation = new HashMap<>();
            segmentation.put("tier", "gold");
            Countly.instance().crashes().recordHandledException(new Exception("parity crash"), segmentation);
        });
    }

    /**
     * Consent given for sessions and events, then taken back for events.
     *
     * @throws Exception when the requests cannot be read or compared
     */
    @Test
    public void consent() throws Exception {
        assertRequests("consent", config().setRequiresConsent(true), () -> {
            Countly.onConsent(Config.Feature.Sessions, Config.Feature.Events);
            Countly.session().begin();
            Countly.instance().events().recordEvent("consented");
            settle();
            Countly.onConsentRemoval(Config.Feature.Events);
            Countly.instance().events().recordEvent("not_consented");
            Countly.session().end();
        });
    }

    /**
     * A device ID change with merge, then one without.
     *
     * @throws Exception when the requests cannot be read or compared
     */
    @Test
    public void deviceId() throws Exception {
        assertRequests("device-id", config(), () -> {
            Countly.session().begin();
            Countly.instance().deviceId().changeWithMerge("parity-device-002");
            Countly.instance().events().recordEvent("after_merge");
            settle();
            Countly.instance().deviceId().changeWithoutMerge("parity-device-003");
            Countly.instance().events().recordEvent("after_new_id");
            Countly.session().end();
        });
    }

    /**
     * The init config every scenario starts from: the parity app key and device ID, a server that cannot be reached so
     * that every request stays queued, and the features the scenarios use.
     *
     * @return config
     */
    private Config config() {
        return new Config("https://parity.example", "parity-app-key", TestUtils.getTestSDirectory())
            .setCustomDeviceId("parity-device-001")
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Views, Config.Feature.CrashReporting, Config.Feature.UserProfiles)
            .setLoggingLevel(Config.LoggingLevel.VERBOSE);
    }

    /**
     * The event segmentation of the parity scenario, with one value of every type.
     *
     * @return segmentation
     */
    private Map<String, Object> paritySegmentation() {
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("nar", "uto");
        segmentation.put("sasuke", MAX_INT);
        segmentation.put("sakura", false);
        segmentation.put("itachi", Double.MAX_VALUE);
        segmentation.put("kakaski", Double.MIN_VALUE);
        segmentation.put("hashirama", -MAX_INT);
        Map<String, Object> tobi = new HashMap<>();
        tobi.put("obito", "uchiha");
        tobi.put("zetsu", "white");
        segmentation.put("tobi", tobi);
        segmentation.put("stringList", Arrays.asList("value1", "value2", "value3"));
        segmentation.put("intList", Arrays.asList(1, 2, 3));
        segmentation.put("doubleList", Arrays.asList(1.1, 2.2, 3.3));
        segmentation.put("boolList", Arrays.asList(true, false, true));
        segmentation.put("mixedList", Arrays.asList("value1", 2, 3.3, true));
        List<Map<String, Object>> mapList = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("key1", "value" + i);
            entry.put("key2", i + 1);
            mapList.add(entry);
        }
        segmentation.put("mapList", mapList);
        return segmentation;
    }

    /**
     * Moves the recorded events into the request queue and waits until the queue stops growing, so that what the calls
     * before queued stays apart from what follows.
     *
     * @throws InterruptedException when waiting is interrupted
     */
    private void settle() throws InterruptedException {
        // without the events consent the module is not loaded
        ModuleEvents events = SDKCore.instance.module(ModuleEvents.class);
        if (events != null) {
            events.onTimer();
        }
        int count = -1;
        long quietSince = System.currentTimeMillis();
        long started = quietSince;
        while (System.currentTimeMillis() - started < 10_000) {
            Thread.sleep(50);
            int queued = TestUtils.getCurrentRQ().length;
            if (queued != count) {
                count = queued;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince > 500) {
                return;
            }
        }
        Assert.fail("the request queue kept changing for 10 seconds");
    }

    /**
     * Runs a scenario on a new SDK instance and compares the requests it queued with the record of the scenario, or
     * writes the record when COUNTLY_UPDATE_REQUESTS is set.
     *
     * @param name scenario name, the record file name without .json
     * @param config init config
     * @param scenario the calls
     * @throws Exception when the record cannot be read or written, or the requests cannot be parsed
     */
    private void assertRequests(String name, Config config, Scenario scenario) throws Exception {
        Countly.instance().init(config);
        scenario.run();
        settle();
        String current = report(TestUtils.getCurrentRQ());
        File file = new File(RECORDS, name + ".json");
        if (System.getenv("COUNTLY_UPDATE_REQUESTS") != null) {
            Files.createDirectories(RECORDS.toPath());
            Files.write(file.toPath(), current.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String committed = file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n") : "(no sdk-java/api/requests/" + name + ".json)\n";
        Assert.assertEquals("the requests of the " + name + " scenario differ from sdk-java/api/requests/" + name + ".json. If that is deliberate, run COUNTLY_UPDATE_REQUESTS=1 ./gradlew :sdk-java:test --tests '*RequestScenarioTests' --rerun, check the difference and add a line to CHANGELOG.md.", committed, current);
    }

    /**
     * The record of queued requests as the SDK sends them: each with its path and its parameters, the values that
     * change from run to run masked, the JSON parameters parsed and the keys of every object sorted.
     *
     * @param requests the request queue, as TestUtils.getCurrentRQ gives it
     * @return file content
     * @throws JSONException when a JSON parameter cannot be parsed
     */
    private String report(Map<String, String>[] requests) throws JSONException {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < requests.length; i++) {
            String path = "/i";
            JSONObject params = new JSONObject();
            for (Map.Entry<String, String> param : requests[i].entrySet()) {
                String name = param.getKey();
                Object value = param.getValue();
                // the SDK takes both out of a queued request before it sends it
                if (name.equals(Request.MODULE)) {
                    continue;
                }
                if (name.equals(Request.ENDPOINT)) {
                    path = param.getValue().replaceAll("\\?$", "");
                    continue;
                }
                if (MASKED_PARAMS.contains(name)) {
                    value = MASK;
                } else if (JSON_PARAMS.contains(name) && !param.getValue().isEmpty()) {
                    value = maskedJson(name, new JSONTokener(param.getValue()).nextValue());
                }
                params.put(name, value);
            }
            out.append(i == 0 ? "\n" : ",\n").append("  {\n    \"path\": ");
            string(out, path);
            out.append(",\n    \"params\": ");
            write(out, params, "    ");
            out.append("\n  }");
        }
        return out.append(requests.length == 0 ? "]\n" : "\n]\n").toString();
    }

    /**
     * The value of a JSON parameter with the values that change from run to run masked.
     *
     * @param name parameter name
     * @param value parsed parameter value
     * @return the masked value
     * @throws JSONException when the value cannot be read
     */
    private Object maskedJson(String name, Object value) throws JSONException {
        if ((name.equals("metrics") || name.equals("hc")) && value instanceof JSONObject) {
            return masked((JSONObject) value, keys((JSONObject) value));
        }
        if (name.equals("events") && value instanceof JSONArray) {
            JSONArray events = (JSONArray) value;
            JSONArray result = new JSONArray();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                List<String> fields = new ArrayList<>(MASKED_EVENT_FIELDS);
                boolean view = "[CLY]_view".equals(event.optString("key"));
                if (view) {
                    fields.add("dur");
                }
                JSONObject maskedEvent = masked(event, fields);
                JSONObject segmentation = maskedEvent.optJSONObject("segmentation");
                if (view && segmentation != null) {
                    maskedEvent.put("segmentation", masked(segmentation, MASKED_VIEW_SEGMENTS));
                }
                result.put(maskedEvent);
            }
            return result;
        }
        if (name.equals("crash") && value instanceof JSONObject) {
            return masked((JSONObject) value, MASKED_CRASH_FIELDS);
        }
        return value;
    }

    /**
     * A copy of an object with the values of the given keys masked.
     *
     * @param value object
     * @param fields keys to mask
     * @return the masked copy
     * @throws JSONException when the object cannot be read
     */
    private JSONObject masked(JSONObject value, List<String> fields) throws JSONException {
        JSONObject copy = new JSONObject();
        for (String key : keys(value)) {
            copy.put(key, fields.contains(key) ? MASK : value.get(key));
        }
        return copy;
    }

    /**
     * The keys of an object, sorted.
     *
     * @param value object
     * @return keys
     */
    private List<String> keys(JSONObject value) {
        TreeSet<String> keys = new TreeSet<>();
        Iterator<String> it = value.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        return new ArrayList<>(keys);
    }

    /**
     * Writes a JSON value with two-space indentation and sorted object keys, escaping only what JSON requires.
     *
     * @param out where to write
     * @param value JSON value
     * @param indent indentation of the line the value starts on
     * @throws JSONException when the value cannot be read
     */
    private void write(StringBuilder out, Object value, String indent) throws JSONException {
        if (value instanceof JSONObject) {
            List<String> keys = keys((JSONObject) value);
            if (keys.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{");
            for (int i = 0; i < keys.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append(indent).append("  ");
                string(out, keys.get(i));
                out.append(": ");
                write(out, ((JSONObject) value).get(keys.get(i)), indent + "  ");
            }
            out.append("\n").append(indent).append("}");
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() == 0) {
                out.append("[]");
                return;
            }
            out.append("[");
            for (int i = 0; i < array.length(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append(indent).append("  ");
                write(out, array.get(i), indent + "  ");
            }
            out.append("\n").append(indent).append("]");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value == null || value == JSONObject.NULL) {
            out.append("null");
        } else {
            out.append(value);
        }
    }

    /**
     * Writes a JSON string, escaping quotes, backslashes and control characters.
     *
     * @param out where to write
     * @param value text
     */
    private void string(StringBuilder out, String value) {
        out.append('"');
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}
