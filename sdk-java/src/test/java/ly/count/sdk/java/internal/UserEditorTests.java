package ly.count.sdk.java.internal;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.UserEditor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UserEditorTests {

    private final static String imgFileName = "test.jpeg";
    private final static String imgFileWebUrl = TestUtils.SERVER_URL + "/" + imgFileName;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "setPicturePath" with web url
     * A test web url is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set and, 'picture' parameter in the 'user_details' should be same,
     * 'picturePath' parameter in the request should not exist
     */
    @Test
    public void setPicturePath_webUrl() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> {
            //set profile picture url and commit it
            Countly.instance().user().edit().setPicturePath(imgFileWebUrl).commit();
            validatePictureAndPath(imgFileWebUrl, null);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{\"picture\":\"" + imgFileWebUrl + "\"}"));
    }

    /**
     * "setPicturePath" with local path
     * A test local path is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set and, 'picturePath' parameter in the user_details should be null,
     * and the request should be equal to file name
     */
    @Test
    public void setPicturePath_localPath() {
        Countly.instance().init(TestUtils.getBaseConfig());
        AtomicReference<String> imgFilePath = new AtomicReference<>("test.jpeg");
        sessionHandler(() -> {
            File imgFile = TestUtils.createFile(imgFileName);
            imgFilePath.set(imgFile.getAbsolutePath());
            //set profile picture url and commit it
            Countly.instance().user().edit().setPicturePath(imgFile.getAbsolutePath()).commit();
            validatePictureAndPath(imgFile.getAbsolutePath(), null);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{}", "picturePath", imgFilePath));
    }

    /**
     * "setPicturePath" with null,
     * null value is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set to null, 'picturePath' parameter in request should not exist
     * and 'picture' parameter in the 'user_details' should be null
     */
    @Test
    public void setPicturePath_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> {
            //set profile picture url and commit it
            Countly.instance().user().edit().setPicturePath(null).commit();
            validatePictureAndPath(null, null);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{\"picture\":null}"));
    }

    /**
     * "setPicturePath" with garbage local path/web url,
     * garbage value is given to the method, session manually began and end to create a request
     * 'picturePath' in user should not be set, 'picturePath' parameter in request should not exist
     * and 'picturePath' parameter in the 'user_details' should not exist
     */
    @Test
    public void setPicturePath_garbage() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> {
            //set profile picture url and commit it
            Countly.instance().user().edit().setPicturePath("garbage_thing/.txt").commit();
            validatePictureAndPath(null, null);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{}"));
    }

    /**
     * "setPicture" with binary data,
     * Binary data is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be null and picture should be defined binary data,
     * 'picturePath' parameter in the user_details should be null and the request 'picturePath'
     * parameter should equal to '[CLY]_USER_PROFILE_PICTURE'
     */
    @Test
    public void setPicture_binaryData() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> {
            //set profile picture url and commit it
            byte[] imgData = new byte[] { 10, 13, 34, 12 };
            Countly.instance().user().edit().setPicture(imgData).commit();
            validatePictureAndPath(null, imgData);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{}", "picturePath", UserEditorImpl.PICTURE_IN_USER_PROFILE));
    }

    /**
     * "setPicture" with null,
     * null value is given to the method, session manually began and end to create a request
     * 'picture' in user should be set to null, 'picturePath' parameter in request should not exist
     * and 'picturePath' parameter in the 'user_details' should be null
     */
    @Test
    public void setPicture_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> {
            //set profile picture url and commit it
            Countly.instance().user().edit().setPicture(null).commit();
            validatePictureAndPath(null, null);
        });
        validateUserDetailsRequestInRQ(toMap("user_details", "{\"picture\":null}"));
    }

    /**
     * "setOnce" with multiple calls
     * Validating that multiple calls to setOnce with same key will result in only one key in the request
     * Last calls' value should be the one in the request
     */
    @Test
    public void setOnce() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .setOnce(TestUtils.eKeys[0], 56)
            .setOnce(TestUtils.eKeys[0], TestUtils.eKeys[1])
            .commit());
        validateUserDetailsRequestInRQ(toMap("user_details", c(opJson(TestUtils.eKeys[0], UserEditorImpl.Op.SET_ONCE, TestUtils.eKeys[1]))));
    }

    /**
     * "setOnce" with null
     * Validating that null value is not added to the request
     * "user_details" should be empty
     */
    @Test
    public void setOnce_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit().setOnce(TestUtils.eKeys[0], null).commit());
        validateUserDetailsRequestInRQ(toMap("user_details", "{}"));
    }

    /**
     * "setOnce" with empty string
     * Validating that empty string value is added to the request
     * "user_details" should contain the key with empty string value
     */
    @Test
    public void setOnce_empty() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit().setOnce(TestUtils.eKeys[0], "").commit());
        validateUserDetailsRequestInRQ(toMap("user_details", c(opJson(TestUtils.eKeys[0], UserEditorImpl.Op.SET_ONCE, ""))));
    }

    /**
     * "commit" with valid parameters
     * Validating that all the methods are working properly
     * Request should contain all the parameters directly also in "user_details" json and body
     */
    @Test
    public void commit() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit()
            .setLocale("en")
            .setCountry("US")
            .setCity("New York")
            .setLocation(40.7128, 74.0060)
            .commit());

        validateUserDetailsRequestInRQ(toMap(
            "user_details", "{\"country\":\"US\",\"city\":\"New York\",\"location\":\"40.7128,74.006\",\"locale\":\"en\"}",
            "country_code", "US",
            "city", "New York",
            "location", "40.7128,74.006"));
    }

    /**
     * "pushUnique" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request except null
     */
    @Test
    public void pushUnique() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base(UserEditorImpl.Op.PUSH_UNIQUE, Countly.instance().user().edit()::pushUnique);
    }

    /**
     * "pull" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request
     */
    @Test
    public void pull() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base(UserEditorImpl.Op.PULL, Countly.instance().user().edit()::pull);
    }

    /**
     * "push" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request
     */
    @Test
    public void push() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base(UserEditorImpl.Op.PUSH, Countly.instance().user().edit()::push);
    }

    private void pullPush_base(String op, BiFunction<String, Object, UserEditor> opFunction) {
        sessionHandler(() -> {
            opFunction.apply(TestUtils.eKeys[0], TestUtils.eKeys[1]);
            opFunction.apply(TestUtils.eKeys[0], TestUtils.eKeys[2]);
            opFunction.apply(TestUtils.eKeys[0], 89);
            opFunction.apply(TestUtils.eKeys[0], TestUtils.eKeys[2]);
            opFunction.apply(TestUtils.eKeys[3], TestUtils.eKeys[2]);
            opFunction.apply(TestUtils.eKeys[0], null);
            opFunction.apply(TestUtils.eKeys[0], "").commit();
        });

        validateUserDetailsRequestInRQ(toMap("user_details", c(
                opJson(TestUtils.eKeys[3], op, TestUtils.eKeys[2]),
                opJson(TestUtils.eKeys[0], op, TestUtils.eKeys[1], TestUtils.eKeys[2], 89, TestUtils.eKeys[2], "")
            )
        ));
    }

    /**
     * "setCustom" with multiple calls
     * Validating that multiple calls to setCustom with same key will result in adding it to the request
     * All added values must be form inside the "custom" json in the request except null param
     */
    @Test
    public void setCustom() {
        Countly.instance().init(TestUtils.getBaseConfig());

        sessionHandler(() -> {

        });

        sessionHandler(() -> Countly.instance().user().edit()
            .setCustom(TestUtils.eKeys[0], TestUtils.eKeys[1])
            .setCustom(TestUtils.eKeys[2], TestUtils.eKeys[3])
            .setCustom(TestUtils.eKeys[4], null)
            .setCustom(TestUtils.eKeys[5], "")
            .setCustom(TestUtils.eKeys[2], null) // must remove from requests
            .setCustom(TestUtils.eKeys[6], 128.987)
            .setCustom("invalid", new HashMap<>()) // should not be added to request
            .setCustom("tags", new Object[] { "tag1", "tag2", 34, 67.8, null, "" })
            .commit());

        validateUserDetailsRequestInRQ(toMap("user_details", c(map(
            TestUtils.eKeys[0], TestUtils.eKeys[1],
            TestUtils.eKeys[5], "",
            TestUtils.eKeys[6], 128.987,
            "tags", new Object[] { "tag1", "tag2", 34, 67.8, null, "" })
        )));
    }

    private void validatePictureAndPath(String picturePath, byte[] picture) {
        Assert.assertEquals(picturePath, Countly.instance().user().picturePath());
        Assert.assertEquals(picture, Countly.instance().user().picture());
    }

    private void validateUserDetailsRequestInRQ(Map<String, String> expectedParams) {
        Map<String, String>[] requestsInQ = TestUtils.getCurrentRQ();
        if (!expectedParams.containsKey("picturePath")) {
            Assert.assertFalse(requestsInQ[0].containsKey("picturePath"));
        }
        expectedParams.forEach((key, value) -> Assert.assertEquals(value, requestsInQ[0].get(key)));
    }

    /**
     * "custom" json tag wrapper
     *
     * @param entries json entries
     * @return wrapped json
     */
    private String c(String... entries) {
        return "{\"custom\":{" + String.join(",", entries) + "}}";
    }

    private String c(Map<String, Object> entries) {
        return "{\"custom\":" + json(entries) + "}";
    }

    private String opJson(String key, String op, Object... values) {
        String opValue;
        if (values.length == 1) {
            opValue = "\"" + values[0] + "\"";
        } else {
            opValue = new JSONArray(values).toString();
        }
        return "\"" + key + "\":{\"" + op + "\":" + opValue + "}";
    }

    /**
     * This function is for reducing
     * Method invocation 'begin' may produce 'NullPointerException' warning
     * to only one place
     *
     * @param process to run
     */
    private void sessionHandler(Runnable process) {
        Countly.session().begin();
        process.run();
        Countly.session().end();
    }

    private String json(Map<String, Object> entries) {
        JSONObject json = new JSONObject();
        entries.forEach(json::put);
        return json.toString();
    }

    private Map<String, String> toMap(Object... args) {
        return new Params(args).map();
    }

    private Map<String, Object> map(Object... args) {
        if (args.length % 2 == 0) {
            Map<String, Object> map = new ConcurrentHashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                map.put(args[i].toString(), args[i + 1]);
            }
            return map;
        }
        return null;
    }
}
