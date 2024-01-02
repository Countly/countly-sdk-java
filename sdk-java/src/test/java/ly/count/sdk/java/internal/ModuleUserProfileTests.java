package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleUserProfileTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "setProperties", "setProperty" with user basics
     * Validating new calls to "setProperties" and "setProperty" with user basics
     * Values should be under "user_details" key and request must generate
     */
    @Test
    public void setUserBasics() {
        Countly.instance().init(TestUtils.getBaseConfig());

        Map<String, Object> basics = new ConcurrentHashMap<>();
        basics.put("name", "Test");
        basics.put("username", "TestUsername");
        basics.put("email", "test@test.test");
        basics.put("organization", "TestOrg");
        basics.put("phone", "123456789");

        Countly.instance().userProfile().setProperties(basics);
        Countly.instance().userProfile().setProperty("byear", 1999);
        Countly.instance().userProfile().setProperty("gender", User.Gender.MALE);

        Countly.instance().userProfile().save();

        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details", TestUtils.json(
            "name", "Test",
            "username", "TestUsername",
            "email", "test@test.test",
            "organization", "TestOrg",
            "phone", "123456789",
            "byear", 1999,
            "gender", "M"
        )));
    }

    /**
     * "clear"
     * Validating that after "clear" call, registered user details are cleared
     * Values should be under "user_details" key and request must generate and only first saved props must exist
     */
    @Test
    public void clear() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Map<String, Object> mixed = new ConcurrentHashMap<>();
        mixed.put("name", "Test");
        mixed.put("username", "TestUsername");
        mixed.put("level", 56);

        Countly.instance().userProfile().setProperties(mixed);
        Countly.instance().userProfile().save();
        mixed.clear();
        Countly.instance().userProfile().setProperty("byear", 1999);
        Countly.instance().userProfile().setProperty("gender", User.Gender.MALE);
        Countly.instance().userProfile().clear();
        Countly.instance().userProfile().save();

        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details", TestUtils.json(
            "name", "Test",
            "username", "TestUsername",
            "custom", TestUtils.map("level", 56)
        )));
    }

    /**
     * "setProperties" with null and empty maps
     * Validating that no request is generated with null and empty maps
     * No request must be generated
     */
    @Test
    public void setProperties_empty_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().userProfile().setProperties(null);
        Countly.instance().userProfile().setProperties(new ConcurrentHashMap<>());
        Countly.instance().userProfile().save();
        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map());
    }

    /**
     * "increment", "incrementBy"
     * Validating that "increment" and "incrementBy" calls are generating correct requests
     * Values should be under "user_details" key and request must generate
     */
    @Test
    public void increment() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().userProfile().increment("test");
        Countly.instance().userProfile().incrementBy("test", 2);
        Countly.instance().userProfile().save();
        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details",
            UserEditorTests.c(UserEditorTests.opJson("test", "$inc", 3))
        ));
    }

    /**
     * "saveMax", "saveMin"
     * Validating that "saveMax" and "saveMin" calls are generating correct requests
     * Values should be under "user_details" key and request must generate
     */
    @Test
    public void saveMax_Min() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().userProfile().saveMax(TestUtils.eKeys[0], 6);
        Countly.instance().userProfile().saveMax(TestUtils.eKeys[0], 9.62);

        Countly.instance().userProfile().saveMin(TestUtils.eKeys[1], 2);
        Countly.instance().userProfile().saveMin(TestUtils.eKeys[1], 0.002);
        Countly.instance().userProfile().save();
        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details", UserEditorTests.c(
            UserEditorTests.opJson(TestUtils.eKeys[1], "$min", 0.002),
            UserEditorTests.opJson(TestUtils.eKeys[0], "$max", 9.62)
        )));
    }

    /**
     * "multiply"
     * Validating that "multiply" call are generating correct requests
     * Values should be under "user_details" key and request must generate
     */
    @Test
    public void multiply() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().userProfile().multiply("test", 2);
        Countly.instance().userProfile().save();
        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details",
            UserEditorTests.c(UserEditorTests.opJson("test", "$mul", 2))
        ));
    }

    /**
     * "pushUnique" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request except null
     */
    @Test
    public void pushUnique() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base("$addToSet", Countly.instance().userProfile()::pushUnique);
    }

    /**
     * "pull" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request
     */
    @Test
    public void pull() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base("$pull", Countly.instance().userProfile()::pull);
    }

    /**
     * "push" with multiple calls
     * Validating that multiple calls to pushUnique with same key will result in only one key in the request
     * All added values must be form an array in the request
     */
    @Test
    public void push() {
        Countly.instance().init(TestUtils.getBaseConfig());
        pullPush_base("$push", Countly.instance().userProfile()::push);
    }

    public void pullPush_base(String op, BiConsumer<String, Object> opFunction) {
        opFunction.accept(TestUtils.eKeys[0], TestUtils.eKeys[1]);
        opFunction.accept(TestUtils.eKeys[0], TestUtils.eKeys[2]);
        opFunction.accept(TestUtils.eKeys[0], 89);
        opFunction.accept(TestUtils.eKeys[0], TestUtils.eKeys[2]);
        opFunction.accept(TestUtils.eKeys[3], TestUtils.eKeys[2]);
        opFunction.accept(TestUtils.eKeys[0], null);
        opFunction.accept(TestUtils.eKeys[0], "");

        Countly.instance().userProfile().save();

        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details", UserEditorTests.c(
                UserEditorTests.opJson(TestUtils.eKeys[3], op, TestUtils.eKeys[2]),
                UserEditorTests.opJson(TestUtils.eKeys[0], op, TestUtils.eKeys[1], TestUtils.eKeys[2], 89, TestUtils.eKeys[2], "")
            )
        ));
    }

    /**
     * "setOnce" with multiple calls
     * Validating that multiple calls to setOnce with same key will result in only one key in the request
     * Last calls' value should be the one in the request
     */
    @Test
    public void setOnce() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.instance().userProfile().setOnce(TestUtils.eKeys[0], 56);
        Countly.instance().userProfile().setOnce(TestUtils.eKeys[0], TestUtils.eKeys[1]);
        Countly.instance().userProfile().save();
        UserEditorTests.validateUserDetailsRequestInRQ(TestUtils.map("user_details", UserEditorTests.c(
            UserEditorTests.opJson(TestUtils.eKeys[0], "$setOnce", TestUtils.eKeys[1]))));
    }
}
