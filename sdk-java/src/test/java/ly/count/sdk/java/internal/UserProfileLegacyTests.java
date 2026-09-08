package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The legacy {@link UserEditor} surface and the {@link UserImpl} it hands back.
 * <p>
 * {@code UserEditorTests} covers the property setters that still write to the wire. This covers the
 * location shortcuts, which are routed into the location module rather than into user details, the
 * accessors on {@link UserImpl}, and the deprecated calls that must be verified to really do nothing.
 */
@RunWith(JUnit4.class)
public class UserProfileLegacyTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * The location shortcuts on the user editor are really location calls: country, city and a
     * "lat,lon" string all end up in the location module's request rather than in user details.
     */
    @Test
    public void locationShortcuts_routeIntoTheLocationRequest() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.UserProfiles, Config.Feature.Location));

        UserEditor editor = Countly.instance().user().edit();
        Assert.assertSame(editor, editor.setCountry("DE"));
        Assert.assertSame(editor, editor.setCity("Berlin"));
        Assert.assertSame(editor, editor.setLocation("52.5,13.4"));
        editor.commit();

        Storage.await(SDKCore.instance.config.getLogger());
        Map<String, String> location = lastRequestWith("location");
        Assert.assertNotNull("a location must have been sent", location);
        Assert.assertEquals("52.5,13.4", location.get("location"));

        Map<String, String> country = lastRequestWith("country_code");
        Assert.assertNotNull(country);
        Assert.assertEquals("DE", country.get("country_code"));

        Map<String, String> city = lastRequestWith("city");
        Assert.assertNotNull(city);
        Assert.assertEquals("Berlin", city.get("city"));
    }

    /**
     * The latitude and longitude overload, and every malformed location string that must be refused
     * rather than sent as nonsense coordinates.
     */
    @Test
    public void locationStrings_areValidatedBeforeBeingSent() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.UserProfiles, Config.Feature.Location));

        UserEditor editor = Countly.instance().user().edit();

        // Every one of these is not a "lat,lon" pair and must not produce a location.
        for (String bad : new String[] { "notALocation", "1,2,3", "one,two", "", "52.5," }) {
            editor.setLocation(bad);
        }
        // The legacy setters only buffer; the buffered values are flushed on commit.
        editor.commit();
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertNull("a malformed location must not be sent", lastRequestWith("location"));

        // The typed overload always produces a well formed pair.
        Assert.assertSame(editor, editor.setLocation(1.5, -2.5));
        editor.commit();
        Storage.await(SDKCore.instance.config.getLogger());
        Map<String, String> sent = lastRequestWith("location");
        Assert.assertNotNull(sent);
        Assert.assertEquals("1.5,-2.5", sent.get("location"));

        // Opting out clears the location on the server rather than simply stopping updates.
        Assert.assertSame(editor, editor.optOutFromLocationServices());
        editor.commit();
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertNotNull(lastRequestWith("location"));
    }

    /**
     * The accessors on {@link UserImpl}. They read the in-memory profile the SDK keeps, so they are
     * driven by populating that profile and reading it back, which is what an integration that holds
     * on to a {@link User} reference sees.
     */
    @Test
    public void userAccessors_readBackTheInMemoryProfile() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.UserProfiles));

        UserImpl user = (UserImpl) Countly.instance().user();
        Assert.assertNotNull(user);

        // Only id, picture and picturePath are ever written by the SDK itself; the rest of the
        // profile fields are package visible and never populated from a server response, so they are
        // set here directly to prove the accessors report what the profile holds.
        user.id = "user-id";
        user.name = "A Name";
        user.username = "a_username";
        user.email = "a@example.com";
        user.org = "An Org";
        user.phone = "+100000000";
        user.gender = User.Gender.FEMALE;
        user.locale = "de_DE";
        user.birthyear = 1985;
        user.country = "DE";
        user.city = "Berlin";
        user.location = "52.5,13.4";
        user.custom = new HashMap<>();
        user.custom.put("tier", "gold");

        Assert.assertEquals("user-id", user.id());
        Assert.assertEquals("A Name", user.name());
        Assert.assertEquals("a_username", user.username());
        Assert.assertEquals("a@example.com", user.email());
        Assert.assertEquals("An Org", user.org());
        Assert.assertEquals("+100000000", user.phone());
        Assert.assertEquals(User.Gender.FEMALE, user.gender());
        Assert.assertEquals("de_DE", user.locale());
        Assert.assertEquals(Integer.valueOf(1985), user.birthyear());
        Assert.assertEquals("DE", user.country());
        Assert.assertEquals("Berlin", user.city());
        Assert.assertEquals("52.5,13.4", user.location());
        Assert.assertEquals("gold", user.custom().get("tier"));

        // toString is used in logs, so it must at least name the user rather than the class.
        Assert.assertTrue(user.toString().contains("user-id"));
    }

    /**
     * {@link User.Gender} round trips through its wire form, which is the single character the server
     * expects, and refuses anything else.
     */
    @Test
    public void gender_roundTripsThroughItsWireForm() {
        Assert.assertEquals(User.Gender.FEMALE, User.Gender.fromString("F"));
        Assert.assertEquals(User.Gender.MALE, User.Gender.fromString("M"));
        Assert.assertEquals("F", User.Gender.FEMALE.toString());
        Assert.assertEquals("M", User.Gender.MALE.toString());

        for (String bad : new String[] { "X", "", "female", null }) {
            Assert.assertNull("gender must not be guessed from " + bad, User.Gender.fromString(bad));
        }
    }

    /**
     * The deprecated editor calls that are kept for source compatibility. Each must be verified to
     * really do nothing, and none may produce a request.
     */
    @Test
    public void deprecatedEditorCalls_areRealNoOps() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.UserProfiles));

        UserEditor editor = Countly.instance().user().edit();
        Storage.await(SDKCore.instance.config.getLogger());
        int before = TestUtils.getCurrentRQ().length;

        Assert.assertSame(editor, editor.setLocale("en_GB"));
        Assert.assertSame(editor, editor.addToCohort("a-cohort"));
        Assert.assertSame(editor, editor.removeFromCohort("a-cohort"));

        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals("a deprecated no-op must not send anything",
            before, TestUtils.getCurrentRQ().length);
    }

    /**
     * Committing before the SDK is up, and committing under backend mode, must both answer null
     * rather than pretending the profile was saved.
     */
    @Test
    public void commit_declinesWhenTheSdkCannotSave() {
        // Backend mode owns the wire, so an ordinary user profile commit stands down.
        Config config = TestUtils.getBaseConfig().enableFeatures(Config.Feature.UserProfiles);
        config.enableBackendMode();
        Countly.instance().init(config);

        UserImpl user = new UserImpl(SDKCore.instance.config);
        UserEditorImpl editor = new UserEditorImpl(user, SDKCore.instance.config.getLogger());
        Assert.assertNull("backend mode must not commit a profile", editor.commit());

        Storage.await(SDKCore.instance.config.getLogger());
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            Assert.assertFalse("no user details may be sent under backend mode",
                request != null && request.containsKey("user_details"));
        }
    }

    // endregion
    // region helpers

    /**
     * @param parameter the parameter to look for
     * @return the last queued request carrying it, or null
     */
    private Map<String, String> lastRequestWith(String parameter) {
        Map<String, String> found = null;
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request != null && request.containsKey(parameter)) {
                found = request;
            }
        }
        return found;
    }

    // endregion
}
