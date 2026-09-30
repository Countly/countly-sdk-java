package ly.count.sdk.java.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The switches and tunables of the SDK behavior settings, observed through the public API: what each
 * one lets into the request queue and the event queue, both when it comes from the stored or provided
 * settings at init and when a server response changes it while the SDK runs.
 * <p>
 * Settings requests go through a {@link ModuleConfigurationTests.ServerConfigResponder}, which answers
 * on the calling thread: the response of the fetch made at init is applied before init returns, and a
 * later one is pushed by fetching the way the refresh timer does. Most scenarios switch networking off
 * through the settings, so every stored request stays on disk to be read.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationGatesTests {

    private ModuleConfigurationTests.ServerConfigResponder server;
    private Thread.UncaughtExceptionHandler defaultHandlerBeforeTest;

    /**
     * Starts every test from empty storage, without a test module and with a fresh responder.
     */
    @Before
    public void beforeTest() {
        defaultHandlerBeforeTest = Thread.getDefaultUncaughtExceptionHandler();
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        server = new ModuleConfigurationTests.ServerConfigResponder();
    }

    /**
     * Stops the SDK, clearing its data, and puts back the default uncaught exception handler.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        SDKCore.testDummyModule = null;
        Thread.setDefaultUncaughtExceptionHandler(defaultHandlerBeforeTest);
    }

    // region scenarios

    /**
     * {@code tracking} off keeps everything new out of storage.
     * <p>
     * Verifies with the switch provided at init that custom and internal events, timed events, views,
     * sessions, breadcrumbs, handled and unhandled crashes, location, user properties, an A/B test
     * enrollment and a device ID merge store no request and no event and leave no crash file, that a
     * crash file left by an earlier run is still turned into a request, and that events are recorded
     * again once a response turns tracking back on.
     */
    @Test
    public void tracking_off_storesNoRequestAndNoEvent_whileACrashOfAnEarlierRunIsStillSent() {
        long crashId = TimeUtils.uniqueTimestampMs();
        TestUtils.writeToFile("crash_" + crashId, new JSONObject().put("_error", "earlier_run_crash").put("_nonfatal", false).toString());
        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Views, Config.Feature.CrashReporting,
                Config.Feature.Location, Config.Feature.UserProfiles, Config.Feature.RemoteConfig)
            .setEventQueueSizeToSend(1)
            .setSdkBehaviorSettings(new ServerConfigBuilder().tracking(false).networking(false).build()));

        Assert.assertFalse(provider().getTrackingEnabled());
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requests.length);
        Assert.assertTrue(requests[0].get("crash").contains("earlier_run_crash"));
        Assert.assertEquals(0, filesWithPrefix("[CLY]_crash_"));

        Countly.instance().events().recordEvent("custom_event");
        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Countly.instance().events().startEvent("timed_event");
        Countly.instance().events().endEvent("timed_event");
        Assert.assertNull(Countly.instance().views().startView("home"));
        Countly.session().begin();
        Countly.session().update();
        Countly.session().end();
        Countly.instance().crashes().addCrashBreadcrumb("breadcrumb");
        Countly.instance().crashes().recordHandledException(new Exception("handled_while_tracking_is_off"));
        Countly.instance().crashes().recordUnhandledException(new Exception("unhandled_while_tracking_is_off"));
        Countly.instance().location().setLocation("TR", "Izmir", "1,2", "1.1.1.1");
        Countly.instance().location().disableLocation();
        Countly.instance().userProfile().setProperty("plan", "gold");
        Countly.instance().userProfile().save();
        Countly.instance().remoteConfig().enrollIntoABTestsForKeys(new String[] { "button_color" });
        Countly.instance().deviceId().changeWithMerge("merged_device_id");

        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, filesWithPrefix("[CLY]_crash_"));
        Assert.assertEquals(0, filesWithPrefix("[CLY]_session_"));

        push(new ServerConfigBuilder().tracking(true));
        Countly.instance().events().recordEvent("custom_event_after");

        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, requests.length);
        Assert.assertEquals("merged_device_id", requests[1].get("device_id"));
        Assert.assertEquals("custom_event_after", new JSONArray(requests[1].get("events")).getJSONObject(0).getString("key"));
    }

    /**
     * {@code st} off ignores beginning, updating and ending a session, whatever calls them.
     * <p>
     * Verifies that after a response turns session tracking off, an update, an end, a timer tick and a
     * device ID change without merge send no session request while the running session keeps its
     * state, that the session file left by that run is removed at the next init without an end
     * request, that a session neither begins through the public API nor through a device ID change
     * while the stored switch is off, that consent to sessions begins nothing either, and that a
     * response turning it back on lets a session begin.
     */
    @Test
    public void sessionTracking_off_sendsNoSessionRequest_fromAnyCaller_andRecoveryDropsTheLeftoverSession() {
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Sessions));

        Countly.session().begin();
        Assert.assertEquals(1, requestsWith("begin_session"));

        push(new ServerConfigBuilder().sessionTracking(false));
        Countly.session().update();
        Countly.session().end();
        SDKCore.instance.module(ModuleSessions.class).onTimer();
        Countly.instance().deviceId().changeWithoutMerge("device_id_2");

        Assert.assertTrue(Countly.session().isActive());
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
        Assert.assertEquals(0, requestsWith("session_duration") + requestsWith("end_session"));
        Countly.instance().stop();
        Assert.assertEquals(1, filesWithPrefix("[CLY]_session_"));

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Sessions));

        Assert.assertFalse(provider().getSessionTrackingEnabled());
        Assert.assertEquals(0, filesWithPrefix("[CLY]_session_"));
        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        Assert.assertFalse(session.isActive());
        Assert.assertNull(session.update(null));
        Assert.assertNull(session.end(null, null, null));
        Countly.instance().deviceId().changeWithoutMerge("device_id_3");
        Assert.assertFalse(Countly.session().isActive());
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requests.length);
        Assert.assertEquals("1", requests[0].get("begin_session"));
        Countly.instance().halt();

        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Sessions)
            .setRequiresConsent(true)
            .setSdkBehaviorSettings(new ServerConfigBuilder().sessionTracking(false).networking(false).build()));
        Countly.onConsent(Config.Feature.Sessions);
        Assert.assertTrue(Countly.isTracking(Config.Feature.Sessions));
        Assert.assertFalse(Countly.session().isActive());
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        push(new ServerConfigBuilder().sessionTracking(true));
        Countly.session().begin();
        Assert.assertTrue(Countly.session().isActive());
        Assert.assertEquals(1, requestsWith("begin_session"));
    }

    /**
     * {@code vt} off ignores every view call, while custom events keep being recorded.
     * <p>
     * Verifies that after a response turns view tracking off, starting, stopping by name and by ID,
     * pausing, resuming and stopping all views record no {@code [CLY]_view} event, through the views
     * interface and through the deprecated session views alike, that a custom event is still
     * recorded, that the views keep running and end once a response turns view tracking back on,
     * and that the stored switch keeps views off after a restart.
     */
    @Test
    public void viewTracking_off_ignoresEveryViewCall_whileCustomEventsStillRecord() {
        Config config = TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Sessions)
            .setEventQueueSizeToSend(100);
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(config);

        String homeId = Countly.instance().views().startView("home");
        String settingsId = Countly.instance().views().startView("settings");
        Assert.assertEquals(2, viewEvents().size());

        push(new ServerConfigBuilder().viewTracking(false));
        Assert.assertNull(Countly.instance().views().startView("blocked"));
        Assert.assertNull(Countly.instance().views().startAutoStoppedView("blocked_auto_stopped"));
        Countly.instance().views().stopViewWithName("home");
        Countly.instance().views().stopViewWithID(settingsId);
        Countly.instance().views().pauseViewWithID(homeId);
        Countly.instance().views().resumeViewWithID(homeId);
        Countly.instance().views().stopAllViews(null);
        View legacyView = ((SessionImpl) Countly.session()).view("legacy_view");
        legacyView.stop(false);
        Countly.instance().events().recordEvent("custom_event");

        List<EventImpl> events = TestUtils.getCurrentEQ();
        Assert.assertEquals(3, events.size());
        Assert.assertEquals("custom_event", events.get(2).key);
        Assert.assertEquals(2, viewEvents().size());

        push(new ServerConfigBuilder().viewTracking(true));
        Countly.instance().views().stopViewWithID(homeId);
        Countly.instance().views().stopAllViews(null);

        List<EventImpl> views = viewEvents();
        Assert.assertEquals(4, views.size());
        Assert.assertEquals("home", views.get(2).segmentation.get("name"));
        Assert.assertEquals("settings", views.get(3).segmentation.get("name"));
        Assert.assertNull(views.get(2).segmentation.get("visit"));
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        push(new ServerConfigBuilder().viewTracking(false));
        Countly.instance().stop();
        server.pending();
        init(config);

        Assert.assertFalse(provider().getViewTrackingEnabled());
        Assert.assertNull(Countly.instance().views().startView("after_restart"));
        Assert.assertEquals(4, viewEvents().size());
        Assert.assertEquals(5, TestUtils.getCurrentEQ().size());
    }

    /**
     * {@code crt} and {@code acr} decide whether unhandled crashes are caught, a response can install
     * the handler while the SDK runs, and handled exceptions follow {@code crt} alone.
     * <p>
     * Verifies that the handler is not installed while {@code acr} is off, that a response turning it
     * on installs it, that a later change never installs it twice, that it records an unhandled crash
     * only while both switches are on at crash time and always hands the crash to the handler it
     * replaced, that handled and unhandled exceptions reported through the crashes interface are
     * recorded whenever {@code crt} is on, and that stopping the SDK leaves in place a handler the
     * application installed over it, which then only reaches the application's handler.
     */
    @Test
    public void crashSwitches_decideWhatIsRecorded_andAResponseInstallsTheHandlerOnce() throws InterruptedException {
        List<Throwable> reachedTheApp = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler appHandler = (thread, throwable) -> reachedTheApp.add(throwable);
        Thread.setDefaultUncaughtExceptionHandler(appHandler);
        List<String> crashesBeingRecorded = new CopyOnWriteArrayList<>();

        server.respondWith(new ServerConfigBuilder().automaticCrashReporting(false).networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting).setLogListener((message, level) -> {
            if (message.startsWith("[ModuleCrash] onCrash:")) {
                crashesBeingRecorded.add(message);
            }
        }));

        Assert.assertTrue(provider().getCrashReportingEnabled());
        Assert.assertFalse(provider().getAutomaticCrashReportingEnabled());
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        crashOnAThread(new RuntimeException("crash_while_acr_is_off"));
        Assert.assertEquals(1, reachedTheApp.size());
        Assert.assertEquals(0, crashRequests().size());

        push(new ServerConfigBuilder().automaticCrashReporting(true));
        Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
        Assert.assertNotSame(appHandler, installed);
        push(new ServerConfigBuilder().automaticCrashReporting(true).eventQueueSize(5));
        Assert.assertSame(installed, Thread.getDefaultUncaughtExceptionHandler());

        crashOnAThread(new RuntimeException("crash_while_both_are_on"));
        Assert.assertEquals(2, reachedTheApp.size());
        List<JSONObject> crashes = crashRequests();
        Assert.assertEquals(1, crashes.size());
        Assert.assertTrue(crashes.get(0).getString("_error").contains("crash_while_both_are_on"));
        Assert.assertFalse(crashes.get(0).getBoolean("_nonfatal"));

        push(new ServerConfigBuilder().crashReporting(false));
        crashOnAThread(new RuntimeException("crash_while_crt_is_off"));
        Countly.instance().crashes().recordHandledException(new Exception("handled_while_crt_is_off"));
        Assert.assertEquals(3, reachedTheApp.size());
        Assert.assertEquals(1, crashRequests().size());

        push(new ServerConfigBuilder().crashReporting(true).automaticCrashReporting(false));
        crashOnAThread(new RuntimeException("crash_while_acr_is_off_again"));
        Countly.instance().crashes().recordHandledException(new Exception("handled_while_crt_is_on"));
        Countly.instance().crashes().recordUnhandledException(new Exception("reported_while_crt_is_on"));
        Assert.assertEquals(4, reachedTheApp.size());
        Assert.assertSame(installed, Thread.getDefaultUncaughtExceptionHandler());
        crashes = crashRequests();
        Assert.assertEquals(3, crashes.size());
        Assert.assertTrue(crashes.get(1).getString("_error").contains("handled_while_crt_is_on"));
        Assert.assertTrue(crashes.get(1).getBoolean("_nonfatal"));
        Assert.assertTrue(crashes.get(2).getString("_error").contains("reported_while_crt_is_on"));
        Assert.assertFalse(crashes.get(2).getBoolean("_nonfatal"));
        Assert.assertEquals(3, crashesBeingRecorded.size());

        List<Throwable> reachedTheLaterHandler = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler laterHandler = (thread, throwable) -> {
            reachedTheLaterHandler.add(throwable);
            installed.uncaughtException(thread, throwable);
        };
        Thread.setDefaultUncaughtExceptionHandler(laterHandler);
        push(new ServerConfigBuilder().automaticCrashReporting(true));
        Countly.instance().halt();

        Assert.assertSame(laterHandler, Thread.getDefaultUncaughtExceptionHandler());
        crashOnAThread(new RuntimeException("crash_after_halt"));
        Assert.assertEquals(1, reachedTheLaterHandler.size());
        Assert.assertEquals(5, reachedTheApp.size());
        Assert.assertEquals(3, crashesBeingRecorded.size());
        Assert.assertEquals(0, filesWithPrefix("[CLY]_crash_"));
        Assert.assertEquals(0, filesWithPrefix("[CLY]_request_"));
    }

    /**
     * The handler installed at init follows the resolved {@code crt} and {@code acr} and crash consent.
     * <p>
     * Verifies that the developer default installs it, that a developer who disabled unhandled crash
     * reporting gets none unless the response of the fetch made at init turns {@code acr} on, which
     * installs it once even though the response arrives before the crash module finished its init,
     * that stored {@code crt} off keeps it out until a response turns {@code crt} on, that it waits
     * for crash consent, and that each stop puts the application's handler back.
     */
    @Test
    public void crashHandler_atInit_followsTheResolvedSwitchesAndConsent() {
        Thread.UncaughtExceptionHandler appHandler = (thread, throwable) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(appHandler);

        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting));
        Assert.assertNotSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.instance().halt();
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());

        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting).disableUnhandledCrashReporting());
        Assert.assertFalse(provider().getAutomaticCrashReportingEnabled());
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.instance().halt();

        server.respondWith(new ServerConfigBuilder().automaticCrashReporting(true));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting).disableUnhandledCrashReporting());
        Assert.assertTrue(provider().getAutomaticCrashReportingEnabled());
        Assert.assertNotSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.instance().halt();
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.CrashReporting)
            .setSdkBehaviorSettings(new ServerConfigBuilder().crashReporting(false).build()));
        Assert.assertTrue(provider().getAutomaticCrashReportingEnabled());
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        push(new ServerConfigBuilder().crashReporting(true));
        Assert.assertNotSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.instance().stop();
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting).setRequiresConsent(true));
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.onConsent(Config.Feature.CrashReporting);
        Assert.assertNotSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
        Countly.instance().halt();
        Assert.assertSame(appHandler, Thread.getDefaultUncaughtExceptionHandler());
    }

    /**
     * {@code lt} off keeps location out of every request, and turning it off erases the location
     * stored on the server.
     * <p>
     * Verifies that with location tracking on, a location is sent on its own and with the begin
     * request; that a response turning it off sends one erase request and a later change sends no
     * second one; that while it is off setting a location through the location interface, the
     * deprecated session call and the deprecated request builder sends nothing and a begin request
     * carries no location; that while session tracking is off a location is not held back for a
     * session that will not begin; that a response at init turning it off while the developer
     * disabled location sends a single erase request; and that the stored switch keeps the
     * developer's location out of the next init.
     */
    @Test
    public void locationTracking_off_keepsLocationOutOfEveryRequest_andTurningItOffErasesIt() {
        Config config = TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Location, Config.Feature.Sessions);
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(config);

        Countly.instance().location().setLocation("TR", "Izmir", "1,2", "1.1.1.1");
        Countly.session().begin();
        Countly.session().end();
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(3, requests.length);
        Assert.assertEquals("Izmir", requests[0].get("city"));
        Assert.assertEquals("1", requests[1].get("begin_session"));
        Assert.assertEquals("Izmir", requests[1].get("city"));
        Assert.assertEquals("1.1.1.1", requests[1].get("ip"));

        push(new ServerConfigBuilder().locationTracking(false));
        push(new ServerConfigBuilder().locationTracking(false).eventQueueSize(5));
        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(4, requests.length);
        Assert.assertEquals("", requests[3].get("location"));
        Assert.assertFalse(requests[3].containsKey("city"));

        Countly.instance().location().setLocation("US", "New York", "3,4", "2.2.2.2");
        Countly.session().addLocation(5.0, 6.0);
        Assert.assertNull(ModuleRequests.location(SDKCore.instance.config, 7.0, 8.0));
        Countly.session().begin();
        Countly.session().end();
        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(Arrays.toString(requests), 6, requests.length);
        Assert.assertEquals("1", requests[4].get("begin_session"));
        assertNoLocation(requests[4]);

        push(new ServerConfigBuilder().locationTracking(true).sessionTracking(false));
        Assert.assertNotNull(Countly.session());
        Countly.instance().location().setLocation("NL", "Amsterdam", null, null);
        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(7, requests.length);
        Assert.assertEquals("Amsterdam", requests[6].get("city"));
        Countly.instance().halt();

        server.respondWith(new ServerConfigBuilder().locationTracking(false).networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Location, Config.Feature.Sessions).disableLocation());
        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requests.length);
        Assert.assertEquals("", requests[0].get("location"));
        Countly.instance().stop();

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Location, Config.Feature.Sessions).setLocation("DE", "Berlin", "9,9", null));
        Assert.assertFalse(provider().getLocationTrackingEnabled());
        Countly.session().begin();
        requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, requests.length);
        Assert.assertEquals("1", requests[1].get("begin_session"));
        assertNoLocation(requests[1]);
    }

    /**
     * {@code log} decides what the SDK prints, while the log listener hears every line.
     * <p>
     * Verifies that with the developer level off, provided logging on prints every level from init
     * and a response turning it off prints nothing; that a stored off silences a developer level of
     * warnings until a response turns it on, which prints that level and not every level; and that
     * without the setting the developer level prints as it is. The listener hears the lines that are
     * not printed.
     */
    @Test
    public void logging_decidesWhatIsPrinted_whileTheListenerHearsEveryLine() {
        List<String> heard = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(printed, true));
        try {
            server.pending();
            init(configWithProbeListener(heard).setSdkBehaviorSettings(new ServerConfigBuilder().logging(true).build()));
            Log log = SDKCore.instance.config.getLogger();
            Assert.assertEquals(Config.LoggingLevel.VERBOSE, log.getPrintLevel());
            log.v("probe_verbose_printed");
            Assert.assertTrue(printedText(printed).contains("probe_verbose_printed"));

            push(new ServerConfigBuilder().logging(false));
            Assert.assertEquals(Config.LoggingLevel.OFF, log.getPrintLevel());
            log.e("probe_error_silenced");
            Assert.assertFalse(printedText(printed).contains("probe_error_silenced"));
            Assert.assertTrue(heard.contains("probe_error_silenced"));
            Countly.instance().stop();

            server.pending();
            init(configWithProbeListener(heard).setLoggingLevel(Config.LoggingLevel.WARN));
            log = SDKCore.instance.config.getLogger();
            Assert.assertEquals(Config.LoggingLevel.OFF, log.getPrintLevel());
            log.w("probe_warning_silenced");
            Assert.assertFalse(printedText(printed).contains("probe_warning_silenced"));

            push(new ServerConfigBuilder().logging(true));
            Assert.assertEquals(Config.LoggingLevel.WARN, log.getPrintLevel());
            log.d("probe_debug_below_the_level");
            log.w("probe_warning_printed");
            Assert.assertFalse(printedText(printed).contains("probe_debug_below_the_level"));
            Assert.assertTrue(printedText(printed).contains("probe_warning_printed"));
            Assert.assertTrue(heard.containsAll(Arrays.asList("probe_warning_silenced", "probe_debug_below_the_level", "probe_warning_printed")));
            Countly.instance().halt();

            server.pending();
            init(configWithProbeListener(heard).setLoggingLevel(Config.LoggingLevel.ERROR));
            Assert.assertEquals(Config.LoggingLevel.ERROR, SDKCore.instance.config.getLogger().getPrintLevel());
            Countly.instance().halt();

            init(configWithProbeListener(heard));
            Assert.assertEquals(Config.LoggingLevel.OFF, SDKCore.instance.config.getLogger().getPrintLevel());
        } finally {
            System.setOut(originalOut);
        }
    }

    /**
     * {@code rqs} caps the stored request queue only when the settings set it.
     * <p>
     * Verifies that the developer request queue size leaves the stored queue unbounded, that once a
     * response sets a size, storing a request drops the oldest ones over it with a warning saying how
     * many, and that a stored size also holds at the next init for a crash of an earlier run that is
     * turned into a request.
     */
    @Test
    public void requestQueueSize_fromTheSettings_capsTheStoredQueue_andWithoutItTheQueueIsUnbounded() {
        List<String> dropWarnings = new CopyOnWriteArrayList<>();
        Config config = TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Events)
            .setEventQueueSizeToSend(1)
            .setRequestQueueMaxSize(2)
            .setLogListener((message, level) -> {
                if (level == Config.LoggingLevel.WARN && message.startsWith("[ModuleRequests] dropOldestRequestsOverQueueLimit")) {
                    dropWarnings.add(message);
                }
            });
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(config);

        for (int i = 1; i <= 4; i++) {
            Countly.instance().events().recordEvent("event_" + i);
            letTheClockCatchUp();
        }
        Assert.assertFalse(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(Arrays.asList("event_1", "event_2", "event_3", "event_4"), contentOfEachRequest());
        Assert.assertTrue(dropWarnings.isEmpty());

        push(new ServerConfigBuilder().requestQueueSize(3));
        Countly.instance().events().recordEvent("event_5");
        Assert.assertEquals(Arrays.asList("event_3", "event_4", "event_5"), contentOfEachRequest());
        Assert.assertEquals(1, dropWarnings.size());
        Assert.assertTrue(dropWarnings.get(0), dropWarnings.get(0).contains("size of [3]") && dropWarnings.get(0).contains("dropped the [2] oldest"));

        Countly.instance().events().recordEvent("event_6");
        Assert.assertEquals(Arrays.asList("event_4", "event_5", "event_6"), contentOfEachRequest());
        Assert.assertEquals(2, dropWarnings.size());
        Assert.assertTrue(dropWarnings.get(1), dropWarnings.get(1).contains("dropped the [1] oldest"));
        Countly.instance().stop();

        TestUtils.writeToFile("crash_" + TimeUtils.uniqueTimestampMs(), new JSONObject().put("_error", "earlier_run_crash").put("_nonfatal", false).toString());
        server.pending();
        init(config);
        Assert.assertTrue(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(Arrays.asList("event_5", "event_6", "crash"), contentOfEachRequest());
        Assert.assertEquals(3, dropWarnings.size());
    }

    /**
     * {@code lbc} is read each time a breadcrumb is added.
     * <p>
     * Verifies that the limit of the response at init overrides the developer's, that the crash
     * request carries only the newest breadcrumbs within it, and that after a response shrinks the
     * limit while breadcrumbs are kept, the next breadcrumb brings them within the new limit.
     */
    @Test
    public void breadcrumbLimit_isReadWhenABreadcrumbIsAdded() {
        server.respondWith(new ServerConfigBuilder().breadcrumbLimit(3).networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.CrashReporting).setMaxBreadcrumbCount(10));
        Assert.assertEquals(3, provider().getMaxBreadcrumbCount());

        addBreadcrumbs("a", 5);
        Countly.instance().crashes().recordHandledException(new Exception("first"));

        push(new ServerConfigBuilder().breadcrumbLimit(5));
        addBreadcrumbs("b", 5);
        push(new ServerConfigBuilder().breadcrumbLimit(2));
        Countly.instance().crashes().addCrashBreadcrumb("c1");
        Countly.instance().crashes().recordHandledException(new Exception("second"));

        List<JSONObject> crashes = crashRequests();
        Assert.assertEquals(2, crashes.size());
        Assert.assertEquals("a3\na4\na5", crashes.get(0).getString("_logs"));
        Assert.assertEquals("b5\nc1", crashes.get(1).getString("_logs"));
    }

    /**
     * {@code eqs} and {@code sui} changed by a response apply while the SDK runs.
     * <p>
     * Verifies that the event queue is flushed at the new threshold the next time an event is added,
     * that a changed session update interval restarts the global timer at that interval, and that a
     * response repeating it keeps the running timer.
     */
    @Test
    public void eventQueueSizeAndSessionUpdateInterval_changedByAResponse_applyWhileTheSdkRuns() {
        server.respondWith(new ServerConfigBuilder().eventQueueSize(5).sessionUpdateInterval(30).networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(100));
        CountlyTimer timerAtInit = SDKCore.instance.countlyTimer;
        Assert.assertEquals(30, timerAtInit.getTimerDelaySeconds());

        Countly.instance().events().recordEvent("e1");
        Countly.instance().events().recordEvent("e2");
        push(new ServerConfigBuilder().eventQueueSize(3));
        TestUtils.validateEQSize(2);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().events().recordEvent("e3");
        TestUtils.validateEQSize(0);
        Assert.assertEquals(3, TestUtils.readEventsFromRequest(0, TestUtils.DEVICE_ID).size());

        push(new ServerConfigBuilder().eventQueueSize(1));
        Countly.instance().events().recordEvent("e4");
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
        Assert.assertEquals("e4", TestUtils.readEventsFromRequest(1, TestUtils.DEVICE_ID).get(0).key);

        push(new ServerConfigBuilder().sessionUpdateInterval(45));
        CountlyTimer restarted = SDKCore.instance.countlyTimer;
        Assert.assertNotSame(timerAtInit, restarted);
        Assert.assertEquals(45, restarted.getTimerDelaySeconds());

        push(new ServerConfigBuilder().sessionUpdateInterval(45).eventQueueSize(2));
        Assert.assertSame(restarted, SDKCore.instance.countlyTimer);
        Countly.instance().events().recordEvent("e5");
        TestUtils.validateEQSize(1);
        Countly.instance().events().recordEvent("e6");
        TestUtils.validateEQSize(0);
        Assert.assertEquals(3, TestUtils.getCurrentRQ().length);
    }

    // endregion
    // region helpers

    /**
     * Initializes the SDK with {@link #server} answering its settings requests.
     *
     * @param config the configuration
     */
    private void init(Config config) {
        InternalConfig internalConfig = new InternalConfig(config);
        internalConfig.immediateRequestGenerator = server;
        Countly.instance().init(internalConfig);
        letTheClockCatchUp();
    }

    /**
     * Answers the next settings request with the given payload and makes the SDK fetch, as the
     * refresh timer does.
     *
     * @param builder the payload
     */
    private void push(ServerConfigBuilder builder) {
        letTheClockCatchUp();
        server.respondWith(builder);
        SDKCore.instance.module(ModuleConfiguration.class).fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * The settings in effect in the running SDK.
     *
     * @return the provider
     */
    private static ConfigurationProvider provider() {
        return SDKCore.instance.config.getConfigurationProvider();
    }

    /**
     * Waits until the clock is past every timestamp handed out so far. A request file is named after
     * a {@link TimeUtils#uniqueTimestampMs()} value, which is unique among the last ten values only,
     * so a long burst of requests could reuse a name and overwrite a queued request.
     */
    private static void letTheClockCatchUp() {
        long latest = TimeUtils.uniqueTimestampMs();
        while (System.currentTimeMillis() <= latest) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * How many stored requests carry a parameter.
     *
     * @param param the parameter name
     * @return the number of requests
     */
    private static int requestsWith(String param) {
        int count = 0;
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey(param)) {
                count++;
            }
        }
        return count;
    }

    /**
     * What each stored request carries, in queue order: the key of its first event, or
     * {@code "crash"} for a crash request.
     *
     * @return one entry per request
     */
    private static List<String> contentOfEachRequest() {
        List<String> contents = new ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey("crash")) {
                contents.add("crash");
            } else {
                contents.add(new JSONArray(request.get("events")).getJSONObject(0).getString("key"));
            }
        }
        return contents;
    }

    /**
     * The crash objects of the stored crash requests, in queue order.
     *
     * @return the crash objects
     */
    private static List<JSONObject> crashRequests() {
        List<JSONObject> crashes = new ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey("crash")) {
                crashes.add(new JSONObject(request.get("crash")));
            }
        }
        return crashes;
    }

    /**
     * The {@code [CLY]_view} events in the event queue, in queue order.
     *
     * @return the view events
     */
    private static List<EventImpl> viewEvents() {
        List<EventImpl> views = new ArrayList<>();
        for (EventImpl event : TestUtils.getCurrentEQ()) {
            if (ModuleViews.KEY_VIEW_EVENT.equals(event.key)) {
                views.add(event);
            }
        }
        return views;
    }

    /**
     * How many files in the storage directory start with a prefix.
     *
     * @param prefix the file name prefix
     * @return the number of files
     */
    private static int filesWithPrefix(String prefix) {
        File[] files = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.startsWith(prefix));
        return files == null ? 0 : files.length;
    }

    /**
     * Asserts that a request carries no location parameter, not even the empty one that erases it.
     *
     * @param request the request parameters
     */
    private static void assertNoLocation(Map<String, String> request) {
        for (String param : Arrays.asList("location", "city", "country_code", "ip")) {
            Assert.assertFalse("carries " + param + ": " + request, request.containsKey(param));
        }
    }

    /**
     * Adds numbered breadcrumbs, {@code prefix1} to {@code prefixN}.
     *
     * @param prefix the text before the number
     * @param count how many
     */
    private static void addBreadcrumbs(String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            Countly.instance().crashes().addCrashBreadcrumb(prefix + i);
        }
    }

    /**
     * Throws on a new thread and waits for it to end, which is after the default uncaught exception
     * handler returned.
     *
     * @param crash what the thread throws
     */
    private static void crashOnAThread(RuntimeException crash) throws InterruptedException {
        Thread thread = new Thread(() -> {
            throw crash;
        });
        thread.start();
        thread.join();
    }

    /**
     * The settings test configuration with a log listener that keeps the probe lines of the logging test.
     *
     * @param heard where the probe lines go
     * @return the configuration
     */
    private static Config configWithProbeListener(List<String> heard) {
        return TestUtils.getConfigSdkBehaviorSettings().setLogListener((message, level) -> {
            if (message.startsWith("probe_")) {
                heard.add(message);
            }
        });
    }

    /**
     * What was printed so far.
     *
     * @param printed the captured output
     * @return the text
     */
    private static String printedText(ByteArrayOutputStream printed) {
        return new String(printed.toByteArray(), StandardCharsets.UTF_8);
    }

    // endregion
}
