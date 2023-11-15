package ly.count.sdk.java.internal;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.User;
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
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicturePath(imgFileWebUrl).commit());
        validatePictureAndPath(imgFileWebUrl, null);
        validateUserDetailsRequestInRQ(map("user_details", "{\"picture\":\"" + imgFileWebUrl + "\"}"));
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
        File imgFile = TestUtils.createFile(imgFileName);
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicturePath(imgFile.getAbsolutePath()).commit());
        validatePictureAndPath(imgFile.getAbsolutePath(), null);
        validateUserDetailsRequestInRQ(map("user_details", "{}", "picturePath", imgFile.getAbsolutePath()));
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
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicturePath(null).commit());
        validatePictureAndPath(null, null);
        validateUserDetailsRequestInRQ(map("user_details", "{\"picture\":null}"));
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
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicturePath("garbage_thing/.txt").commit());
        validatePictureAndPath(null, null);
        validateUserDetailsRequestInRQ(map("user_details", "{}"));
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
        byte[] imgData = new byte[] { 10, 13, 34, 12 };
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicture(imgData).commit());
        validatePictureAndPath(null, imgData);
        validateUserDetailsRequestInRQ(map("user_details", "{}", "picturePath", UserEditorImpl.PICTURE_IN_USER_PROFILE));
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
        //set profile picture url and commit it
        sessionHandler(() -> Countly.instance().user().edit().setPicture(null).commit());
        validatePictureAndPath(null, null);
        validateUserDetailsRequestInRQ(map("user_details", "{\"picture\":null}"));
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
        validateUserDetailsRequestInRQ(map("user_details", c(opJson(TestUtils.eKeys[0], UserEditorImpl.Op.SET_ONCE, TestUtils.eKeys[1]))));
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
        validateUserDetailsRequestInRQ(map("user_details", "{}"));
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
        validateUserDetailsRequestInRQ(map("user_details", c(opJson(TestUtils.eKeys[0], UserEditorImpl.Op.SET_ONCE, ""))));
    }

    /**
     * "setLocale", "setCountry", "setCity", "setLocation" with valid parameters
     * Validating that all the methods are working properly
     * Request should contain all the parameters directly also in "user_details" json and body
     */
    @Test
    public void setLocationBasics() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit()
            .setLocale("en")
            .setCountry("US")
            .setCity("New York")
            .setLocation(40.7128, -74.0060)
            .commit());

        validateUserDetailsRequestInRQ(map(
            "user_details", json(),
            "country_code", "US",
            "city", "New York",
            "location", "40.7128,-74.006",
            "locale", "en"));
    }

    /**
     * "setLocale", "setCountry", "setCity", "setLocation" with null parameters
     * Validating that all the methods are working properly
     * Request should contain all the parameters directly also in "user_details" json and body
     */
    @Test
    public void setLocationBasics_null() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit()
            .setLocale(null)
            .setCountry(null)
            .setCity(null)
            .setLocation(null)
            .commit());

        validateUserDetailsRequestInRQ(map(
            "user_details", json(),
            "country_code", JSONObject.NULL,
            "city", JSONObject.NULL,
            "location", JSONObject.NULL,
            "locale", JSONObject.NULL));
    }

    /**
     * "setLocale", "setCountry", "setCity", "setLocation" with valid parameters
     * Validating that all the methods are working properly
     * Request should contain all the parameters directly also in "user_details" json and body
     */
    @Test
    public void setLocationBasics_noConsent() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .setLocale("tr")
            .setCountry("TR")
            .setCity("Izmir")
            .setLocation(38.4237, 27.1428)
            .commit());

        validateUserDetailsRequestInRQ(map(
            "user_details", json(),
            "locale", "tr"));
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
            return opFunction.apply(TestUtils.eKeys[0], "").commit();
        });

        validateUserDetailsRequestInRQ(map("user_details", c(
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

        validateUserDetailsRequestInRQ(map("user_details", c(map(
            TestUtils.eKeys[0], TestUtils.eKeys[1],
            TestUtils.eKeys[5], "",
            TestUtils.eKeys[6], 128.987,
            "tags", new Object[] { "tag1", "tag2", 34, 67.8, null, "" })
        )));
    }

    /**
     * "max" with multiple calls
     * Validating that multiple calls to "max" will result in the request queue
     * All added values must be form inside the "custom" json in the request
     */
    @Test
    public void max() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .max(TestUtils.eKeys[0], 56)
            .max(TestUtils.eKeys[1], -1)
            .max(TestUtils.eKeys[2], 0)
            .max(TestUtils.eKeys[0], 128) // this should override previous value
            .max(TestUtils.eKeys[0], 45) // this should not override previous value because it is less than
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", c(
            opJson(TestUtils.eKeys[2], UserEditorImpl.Op.MAX, 0),
            opJson(TestUtils.eKeys[1], UserEditorImpl.Op.MAX, -1),
            opJson(TestUtils.eKeys[0], UserEditorImpl.Op.MAX, 128)))
        );
    }

    /**
     * "min" with multiple calls
     * Validating that multiple calls to "min" will result in the request queue
     * All added values must be form inside the "custom" json in the request
     */
    @Test
    public void min() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .min(TestUtils.eKeys[0], 213)
            .min(TestUtils.eKeys[1], -155.9)
            .min(TestUtils.eKeys[2], 0)
            .min(TestUtils.eKeys[0], 122) // this should override previous value
            .min(TestUtils.eKeys[0], 122.0001) // this should not override previous value because it is greater than
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", c(
            opJson(TestUtils.eKeys[2], UserEditorImpl.Op.MIN, 0),
            opJson(TestUtils.eKeys[1], UserEditorImpl.Op.MIN, -155.9),
            opJson(TestUtils.eKeys[0], UserEditorImpl.Op.MIN, 122)))
        );
    }

    /**
     * "inc" with multiple calls
     * Validating that multiple calls to "inc" will result in the request queue
     * All added values must be form inside the "custom" json in the request
     */
    @Test
    public void inc() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .inc(TestUtils.eKeys[0], 213)
            .inc(TestUtils.eKeys[1], -155)
            .inc(TestUtils.eKeys[2], 0)
            .inc(TestUtils.eKeys[0], -214) // this should result in -1 for the key 'TestUtils.eKeys[0]'
            .inc(TestUtils.eKeys[0], 1) // this should result in 0 for the key 'TestUtils.eKeys[0]'
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", c(
            opJson(TestUtils.eKeys[2], UserEditorImpl.Op.INC, 0),
            opJson(TestUtils.eKeys[1], UserEditorImpl.Op.INC, -155),
            opJson(TestUtils.eKeys[0], UserEditorImpl.Op.INC, 0)))
        );
    }

    /**
     * "mul" with multiple calls
     * Validating that multiple calls to "mul" will result in the request queue
     * All added values must be form inside the "custom" json in the request
     */
    @Test
    public void mul() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .mul(TestUtils.eKeys[0], 3)
            .mul(TestUtils.eKeys[1], -5.28)
            .mul(TestUtils.eKeys[2], 0)
            .mul(TestUtils.eKeys[3], 45)
            .mul(TestUtils.eKeys[0], 0) // this should result in 0 for the key 'TestUtils.eKeys[0]'
            .mul(TestUtils.eKeys[0], 1) // this should result in 0 for the key 'TestUtils.eKeys[0]'
            .mul(TestUtils.eKeys[2], 45) // this shouldn't change anything because the value is 0
            .mul(TestUtils.eKeys[3], -2) // this should result int -90 for the 'TestUtils.eKeys[3]'
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", c(
            opJson(TestUtils.eKeys[3], UserEditorImpl.Op.MUL, -90),
            opJson(TestUtils.eKeys[2], UserEditorImpl.Op.MUL, 0),
            opJson(TestUtils.eKeys[1], UserEditorImpl.Op.MUL, -5.28),
            opJson(TestUtils.eKeys[0], UserEditorImpl.Op.MUL, 0)))
        );
    }

    /**
     * "setBirthyear", "setEmail", "setGender", "setName", "setOrg", "setPhone", "setUsername" with valid parameters
     * Validating that all the methods are working properly
     * Request should contain all the parameters in "user_details" json
     */
    @Test
    public void setUserBasics() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .setBirthyear(1999)
            .setEmail("test@test.test")
            .setGender(User.Gender.MALE)
            .setName("Test")
            .setOrg("TestOrg")
            .setPhone("123456789")
            .setUsername("TestUsername")
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", json(
            "name", "Test",
            "username", "TestUsername",
            "email", "test@test.test",
            "org", "TestOrg",
            "phone", "123456789",
            "byear", 1999,
            "gender", "M"
        )));
    }

    /**
     * "setBirthyear", "setEmail", "setGender", "setName", "setOrg", "setPhone", "setUsername" with valid parameters
     * Validating that all the methods are working properly with 'null' values
     * Request should contain all the parameters in "user_details" json and all the values should be null
     */
    @Test
    public void setUserBasics_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .setBirthyear(null)
            .setEmail(null)
            .setGender(null)
            .setName(null)
            .setOrg(null)
            .setPhone(null)
            .setUsername(null)
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", json(
            "name", JSONObject.NULL,
            "username", JSONObject.NULL,
            "email", JSONObject.NULL,
            "org", JSONObject.NULL,
            "phone", JSONObject.NULL,
            "byear", JSONObject.NULL,
            "gender", JSONObject.NULL
        )));
    }

    /**
     * "setBirthyear" with non integer value
     * Validating that value is not added to the request,
     * Request should not contain "byear" parameter in "user_details" json
     */
    @Test
    public void setBirthYear_invalidParam() {
        setBirthYear_base(TestUtils.eKeys[0], json());
    }

    /**
     * "setBirthyear" with string integer
     * Validating that value is parsed to integer and added to the request,
     * Request should contain "byear" parameter in "user_details" json
     */
    @Test
    public void setBirthYear_stringInteger() {
        setBirthYear_base("1999", json("byear", 1999));
    }

    /**
     * "setBirthyear" with number but not integer
     * Validating that value is not added to the request,
     * Request should not contain "byear" parameter in "user_details" json
     */
    @Test
    public void setBirthYear_stringNotInteger() {
        setBirthYear_base("1999.0", json());
    }

    private void setBirthYear_base(String value, String expectedValue) {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit().setBirthyear(value).commit());
        validateUserDetailsRequestInRQ(map("user_details", expectedValue));
    }

    /**
     * "setGender" with not supported gender
     * Validating that value is not added to the request,
     * Request should not contain "gender" parameter in "user_details" json
     */
    @Test
    public void setGender_invalid() {
        setGender_base("Non-Binary", json());
    }

    /**
     * "setGender" with number
     * Validating that value is not added to the request,
     * Request should not contain "gender" parameter in "user_details" json
     */
    @Test
    public void setGender_number() {
        setGender_base(1, json());
    }

    /**
     * "setGender" with string supported gender
     * Validating that value is added to the request,
     * Request should contain "gender" parameter in "user_details" json
     */
    @Test
    public void setGender_string() {
        setGender_base("M", json("gender", "M"));
    }

    private void setGender_base(Object gender, String expectedValue) {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit().setGender(gender).commit());
        validateUserDetailsRequestInRQ(map("user_details", expectedValue));
    }

    /**
     * "setLocation" from string
     * Validating that values is correctly parsed to the long and added to the request,
     * Request should contain "location" parameter in "user_details" json and "location" parameter in the request
     */
    @Test
    public void setLocation_fromString() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit().setLocation("-40.7128, 74.0060").commit());
        validateUserDetailsRequestInRQ(map("user_details", json(), "location", "-40.7128,74.006"));
    }

    /**
     * "setLocation" from string
     * Validating that values is correctly parsed to the long and added to the request,
     * Request should contain "location" parameter in "user_details" json and "location" parameter in the request
     */
    @Test
    public void setLocation_fromString_noConsent() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit().setLocation("32.78, 28.01").commit());
        validateUserDetailsRequestInRQ(map("user_details", json(), "location", "32.78,28.01"));
    }

    /**
     * "setLocation" from string - invalid location pairs
     * Validating that values is not exist inside the request,
     * Request should not contain "location" parameter in "user_details" json
     */
    @Test
    public void setLocation_fromString_invalid() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit().setLocation(",28.34").commit());
        validateUserDetailsRequestInRQ(map("user_details", json()));
    }

    /**
     * "setLocation" from string - invalid location pairs
     * Validating that values is not exist inside the request,
     * Request should not contain "location" parameter in "user_details" json
     */
    @Test
    public void setLocation_fromString_onePair() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit().setLocation("61.32,").commit());
        validateUserDetailsRequestInRQ(map("user_details", json()));
    }

    /**
     * "setLocation" from string - null
     * Validating that location is nullified
     * Request should contain "location" parameter in "user_details" json and request body and should be null
     */
    @Test
    public void setLocation_fromString_null() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Location));
        sessionHandler(() -> Countly.instance().user().edit().setLocation(null).commit());
        validateUserDetailsRequestInRQ(map("user_details", json(), "location", JSONObject.NULL));
    }

    public void set_multipleCalls() {
        Countly.instance().init(TestUtils.getBaseConfig());
        sessionHandler(() -> Countly.instance().user().edit()
            .setBirthyear(null)
            .setEmail(null)
            .setGender(null)
            .setName(null)
            .setOrg(null)
            .setPhone(null)
            .setUsername(null)
            .commit()
        );

        validateUserDetailsRequestInRQ(map("user_details", json(
            "name", JSONObject.NULL,
            "username", JSONObject.NULL,
            "email", JSONObject.NULL,
            "org", JSONObject.NULL,
            "phone", JSONObject.NULL,
            "byear", JSONObject.NULL,
            "gender", JSONObject.NULL
        )));
    }

    private void validatePictureAndPath(String picturePath, byte[] picture) {
        Assert.assertEquals(picturePath, Countly.instance().user().picturePath());
        Assert.assertEquals(picture, Countly.instance().user().picture());
    }

    private void validateUserDetailsRequestInRQ(Map<String, Object> expectedParams) {
        Map<String, String>[] requestsInQ = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requestsInQ.length);
        TestUtils.validateRequiredParams(requestsInQ[0]); // this validates 9 params
        requestsInQ[0].forEach((key, value) -> System.out.println("key: " + key + " value: " + value));
        Assert.assertEquals(9 + expectedParams.size(), requestsInQ[0].size()); // so we need to add expect 9 + params size
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
        JSONObject obj = new JSONObject();
        if (values.length == 1) {
            obj.put(op, values[0]);
        } else {
            obj.put(op, new JSONArray(values));
        }
        return "\"" + key + "\":" + obj;
    }

    /**
     * This function is for reducing
     * Method invocation 'begin' may produce 'NullPointerException' warning
     * to only one place
     *
     * @param process to run
     */
    private void sessionHandler(Supplier<User> process) {
        Countly.session().begin();
        Assert.assertNotNull(process.get());
        Countly.session().end();
    }

    /**
     * Converts a map to json object
     *
     * @param entries map to convert
     * @return json string
     */
    private String json(Map<String, Object> entries) {
        JSONObject json = new JSONObject();
        entries.forEach(json::put);
        return json.toString();
    }

    /**
     * Converts array of objects to json string
     * Returns empty json if array is null or empty
     *
     * @param args array of objects
     * @return json string
     */
    private String json(Object... args) {
        if (args == null || args.length == 0) {
            return "{}";
        }
        return json(map(args));
    }

    /**
     * Converts array of objects to a 'String, Object' map
     *
     * @param args array of objects
     * @return map
     */
    private Map<String, Object> map(Object... args) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        if (args.length % 2 == 0) {
            map = new ConcurrentHashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                map.put(args[i].toString(), args[i + 1]);
            }
        }
        return map;
    }
}
