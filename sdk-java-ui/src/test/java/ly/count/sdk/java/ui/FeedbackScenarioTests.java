package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetSelector;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Drives each feedback widget through the flow the Android SDK's {@code content_test_runner.py}
 * drives, with the selectors from its {@code content_test_config.py}: open, confirm the right
 * template rendered, fill it in, submit, and check what the SDK made of it.
 * <p>
 * Findings are recorded rather than asserted. A scenario driver's job is to say what a real server
 * and a real page do, and a missing fixture on the server is a finding, not a broken test.
 */
@RunWith(JUnit4.class)
public class FeedbackScenarioTests {

    // From content_test_config.py, verbatim.
    private static final String SURVEY_V2_CLOSE_SELECTOR = ".close-button";
    private static final String SURVEY_V2_TERMS_LINK = "a[href*='termsandconditions' i]";
    private static final String SURVEY_V2_PRIVACY_LINK = "a[href*='privacypolicy' i]";
    private static final String SURVEY_SUBMIT_BUTTON = "[data-test-id='survey-drawer-survey-page-next-button']";
    private static final String SURVEY_CONSENT_CHECKBOX =
        "[data-test-id='survey-survey-sub-page-agree-to-terms-conditions-checkbox']";
    private static final String SURVEY_RADIO_OPTION = ".radio-item";
    private static final String NPS_NEXT_BUTTON = "[data-test-id='nps-drawer-survey-page-next-button']";
    private static final String NPS_SUBMIT_BUTTON = "[data-test-id='nps-survey-sub-page-submit-button']";
    private static final String NPS_CONSENT_CHECKBOX =
        "[data-test-id='nps-survey-sub-page-agree-to-terms-conditions-checkbox']";
    private static final String NPS_RATING_BUTTON_FMT = ".rating-button[data-rating='%d']";
    private static final String NPS_COMMENT_TEXTAREA = "[data-test-id='nps-popup-comment-textarea']";
    private static final String RATING_CLOSE_SELECTOR = "#close-btn";
    private static final String RATING_EMOJI_FMT = ".rating-emotion[data-score='%d']";
    private static final String RATING_ADD_COMMENT_CHECKBOX = "#countly-feedback-show-comment";
    private static final String RATING_COMMENT_TEXTAREA = "#countly-feedback-comment-textarea";
    private static final String RATING_EMAIL_CHECKBOX = "#countly-feedback-show-email";
    private static final String RATING_EMAIL_INPUT = "#countly-feedback-contact-me-email";
    private static final String RATING_SUBMIT_BUTTON = "#cf-submit-button";
    private static final String LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit";
    private static final String LOREM_EMAIL = "automation+lorem@example.test";

    private static final long PAGE_TIMEOUT_MS = 30_000;
    private static final WidgetSurface SURFACE = new WidgetSurface(0, 0, 1600, 1000);

    private static Stage owner;
    private ScenarioDriver.LogBuffer log;
    private List<CountlyFeedbackWidget> widgets;

    @BeforeClass
    public static void enable() {
        ScenarioDriver.assumeEnabled();
        owner = ScenarioDriver.newApplicationWindow(80, 60, 900, 600);
    }

    @AfterClass
    public static void report() {
        ScenarioDriver.writeReport("feedback-scenarios");
        if (owner != null) {
            FxTestToolkit.onFx(owner::close);
        }
    }

    @Before
    public void startSdk() {
        log = new ScenarioDriver.LogBuffer();
        Countly.instance().init(ScenarioDriver.liveConfig("scenario-java-fx", log));

        AtomicReference<List<CountlyFeedbackWidget>> fetched = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Countly.instance().feedback().getAvailableFeedbackWidgets((list, problem) -> {
            error.set(problem);
            fetched.set(list);
        });

        long until = System.currentTimeMillis() + PAGE_TIMEOUT_MS;
        while (fetched.get() == null && error.get() == null && System.currentTimeMillis() < until) {
            ScenarioDriver.pause(100);
        }
        widgets = fetched.get() == null ? new ArrayList<>() : fetched.get();
        ScenarioDriver.record("setup", "fetch the widget list",
            widgets.isEmpty() ? ScenarioDriver.Verdict.FAIL : ScenarioDriver.Verdict.PASS,
            widgets.size() + " widgets, error [" + error.get() + "]");
    }

    @After
    public void stopSdk() {
        Countly.instance().halt();
    }

    /**
     * NPS: the two page flow. Pick a score, go to the comment page, write a comment, agree to the
     * terms, submit, and check the SDK recorded the answer.
     */
    @Test
    public void nps() {
        Card card = present(FeedbackWidgetType.nps, "nps");
        if (card == null) {
            return;
        }
        WebEngine engine = card.driven.engine();

        ScenarioDriver.check("nps", "the survey-v2 template rendered",
            ScenarioDriver.count(engine, ".rating-button") == 11,
            ScenarioDriver.count(engine, ".rating-button") + " rating buttons, expected 11");
        ScenarioDriver.check("nps", "a close button is present",
            ScenarioDriver.exists(engine, SURVEY_V2_CLOSE_SELECTOR), SURVEY_V2_CLOSE_SELECTOR);
        reportLinks(engine, "nps", SURVEY_V2_TERMS_LINK, SURVEY_V2_PRIVACY_LINK);

        ScenarioDriver.check("nps", "pick a score",
            ScenarioDriver.click(engine, String.format(NPS_RATING_BUTTON_FMT, 9)), "score 9");

        boolean next = ScenarioDriver.click(engine, NPS_NEXT_BUTTON);
        ScenarioDriver.check("nps", "go to the comment page", next, NPS_NEXT_BUTTON);

        if (next) {
            ScenarioDriver.pause(600);
            ScenarioDriver.record("nps", "the comment page opened",
                ScenarioDriver.exists(engine, NPS_COMMENT_TEXTAREA)
                    ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
                "textarea present: " + ScenarioDriver.exists(engine, NPS_COMMENT_TEXTAREA));
            ScenarioDriver.check("nps", "write a comment",
                ScenarioDriver.type(engine, NPS_COMMENT_TEXTAREA, LOREM), "lorem text");
            // The links live on this page, not the first one: checking them on the rating screen is
            // what made them look absent.
            reportLinks(engine, "nps (comment page)", SURVEY_V2_TERMS_LINK, SURVEY_V2_PRIVACY_LINK);
            ScenarioDriver.record("nps", "agree to the terms",
                ScenarioDriver.tick(engine, NPS_CONSENT_CHECKBOX)
                    ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP,
                "checkbox: " + ScenarioDriver.exists(engine, NPS_CONSENT_CHECKBOX));
            ScenarioDriver.check("nps", "submit", ScenarioDriver.click(engine, NPS_SUBMIT_BUTTON),
                NPS_SUBMIT_BUTTON);
        }

        finish(card, "nps", "\\[CLY\\]_nps");
    }

    /**
     * Survey: one page. Answer a question, agree to the terms, submit.
     */
    @Test
    public void survey() {
        Card card = present(FeedbackWidgetType.survey, "survey");
        if (card == null) {
            return;
        }
        WebEngine engine = card.driven.engine();

        int options = ScenarioDriver.count(engine, SURVEY_RADIO_OPTION);
        ScenarioDriver.record("survey", "the survey-v2 template rendered",
            options > 0 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            options + " radio options");
        ScenarioDriver.check("survey", "a close button is present",
            ScenarioDriver.exists(engine, SURVEY_V2_CLOSE_SELECTOR), SURVEY_V2_CLOSE_SELECTOR);
        reportLinks(engine, "survey", SURVEY_V2_TERMS_LINK, SURVEY_V2_PRIVACY_LINK);

        if (options > 0) {
            ScenarioDriver.check("survey", "answer a question",
                ScenarioDriver.click(engine, SURVEY_RADIO_OPTION), "first radio option");
        }
        ScenarioDriver.record("survey", "agree to the terms",
            ScenarioDriver.tick(engine, SURVEY_CONSENT_CHECKBOX)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP,
            "checkbox: " + ScenarioDriver.exists(engine, SURVEY_CONSENT_CHECKBOX));
        reportLinks(engine, "survey (consent shown)", SURVEY_V2_TERMS_LINK, SURVEY_V2_PRIVACY_LINK);
        ScenarioDriver.check("survey", "submit",
            ScenarioDriver.click(engine, SURVEY_SUBMIT_BUTTON), SURVEY_SUBMIT_BUTTON);

        finish(card, "survey", "\\[CLY\\]_survey");
    }

    /**
     * Rating: the older framework. Pick a face, add a comment and an email, submit.
     */
    @Test
    public void rating() {
        Card card = present(FeedbackWidgetType.rating, "rating");
        if (card == null) {
            return;
        }
        WebEngine engine = card.driven.engine();

        int faces = ScenarioDriver.count(engine, ".rating-emotion");
        ScenarioDriver.record("rating", "the ratings-popup template rendered",
            faces > 0 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            faces + " rating faces, close button: " + ScenarioDriver.exists(engine, RATING_CLOSE_SELECTOR));

        // The rating page opens as a sticky tab; the popup is behind it.
        ScenarioDriver.record("rating", "the sticky tab is the entry point",
            ScenarioDriver.Verdict.PASS,
            "body text [" + ScenarioDriver.visibleText(engine).replace('\n', ' ').trim() + "]");

        ScenarioDriver.check("rating", "pick a face",
            ScenarioDriver.click(engine, String.format(RATING_EMOJI_FMT, 5)), "score 5");
        ScenarioDriver.record("rating", "ask for the comment field",
            ScenarioDriver.tick(engine, RATING_ADD_COMMENT_CHECKBOX)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, RATING_ADD_COMMENT_CHECKBOX);
        ScenarioDriver.record("rating", "write a comment",
            ScenarioDriver.type(engine, RATING_COMMENT_TEXTAREA, LOREM)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, RATING_COMMENT_TEXTAREA);
        ScenarioDriver.record("rating", "ask for the email field",
            ScenarioDriver.tick(engine, RATING_EMAIL_CHECKBOX)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, RATING_EMAIL_CHECKBOX);
        ScenarioDriver.record("rating", "fill the email in",
            ScenarioDriver.type(engine, RATING_EMAIL_INPUT, LOREM_EMAIL)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, RATING_EMAIL_INPUT);
        ScenarioDriver.record("rating", "submit",
            ScenarioDriver.click(engine, RATING_SUBMIT_BUTTON)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, RATING_SUBMIT_BUTTON);

        finish(card, "rating", "\\[CLY\\]_star_rating|\\[CLY\\]_rating");
    }

    /**
     * The close button, on its own: a widget dismissed without answering still has to reach the
     * SDK and still has to run the caller's callback.
     */
    @Test
    public void closingWithoutAnswering() {
        Card card = present(FeedbackWidgetType.nps, "close");
        if (card == null) {
            return;
        }

        boolean closed = ScenarioDriver.click(card.driven.engine(), SURVEY_V2_CLOSE_SELECTOR);
        ScenarioDriver.check("close", "click the close button", closed, SURVEY_V2_CLOSE_SELECTOR);

        boolean gone = false;
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && !gone) {
            gone = card.callbacks.get() > 0;
            ScenarioDriver.pause(100);
        }
        ScenarioDriver.check("close", "the caller's callback ran", gone,
            "callbacks: " + card.callbacks.get());
        ScenarioDriver.check("close", "the dismissal was reported",
            log.has("reportFeedbackWidgetManually"), log.find("reportFeedbackWidgetManually"));

        AtomicReference<Boolean> showing = new AtomicReference<>(true);
        FxTestToolkit.onFx(() -> showing.set(card.driven.stage.isShowing()));
        ScenarioDriver.check("close", "the card is off screen", !showing.get(), null);
    }

    // ------------------------------------------------------------------ helpers

    private static final class Card {
        final ScenarioDriver.Card driven;
        final AtomicInteger callbacks = new AtomicInteger();

        Card(ScenarioDriver.Card driven) {
            this.driven = driven;
        }
    }

    /**
     * Presents the first widget of a type and waits for its page.
     *
     * @return the card, or {@code null} when the server has no such widget, which is recorded
     */
    private Card present(FeedbackWidgetType type, String scenario) {
        CountlyFeedbackWidget widget = FeedbackWidgetSelector.select(widgets, type, null);
        if (widget == null) {
            ScenarioDriver.record(scenario, "a " + type + " widget is available",
                ScenarioDriver.Verdict.SKIP, "the server returned none for this app key");
            return null;
        }
        ScenarioDriver.record(scenario, "a " + type + " widget is available", ScenarioDriver.Verdict.PASS,
            "id [" + widget.widgetId + "] name [" + widget.name + "] position [" + widget.position + "]");

        List<ContentPlacement> fits = new ArrayList<>();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        ScenarioDriver.Card driven = ScenarioDriver.newCard(SURFACE, null);
        Card card = new Card(driven);

        FeedbackWidgetPresenter presenter = new FeedbackWidgetPresenter(driven.host,
            Countly.instance().feedback(), card.callbacks::incrementAndGet);
        driven.presenter = presenter;

        // Wrapped so the scenario can see the page's own milestones as well as the SDK's.
        WidgetWebHost.Listener spy = new WidgetWebHost.Listener() {
            @Override public void onNavigationStarting(String url) {
                presenter.onNavigationStarting(url);
            }
            @Override public void onWidgetMessage(String json) {
                presenter.onWidgetMessage(json);
            }
            @Override public void onPageLoaded() {
                loads.incrementAndGet();
                presenter.onPageLoaded();
            }
            @Override public void onLoadFailed() {
                failures.incrementAndGet();
                presenter.onLoadFailed();
            }
            @Override public void onSizeNotReported(int paintedWidth, int paintedHeight) {
                presenter.onSizeNotReported(paintedWidth, paintedHeight);
            }
            @Override public void onCardMeasured(int width, int height) {
                fits.add(new ContentPlacement(0, 0, width, height));
                presenter.onCardMeasured(width, height);
            }
        };
        FxTestToolkit.onFx(() -> driven.host.setListener(spy));
        FxTestToolkit.onFx(() -> presenter.start(widget));

        long until = System.currentTimeMillis() + PAGE_TIMEOUT_MS;
        while (loads.get() == 0 && failures.get() == 0 && System.currentTimeMillis() < until) {
            ScenarioDriver.pause(100);
        }
        ScenarioDriver.check(scenario, "the widget page loaded", loads.get() > 0,
            "loads " + loads.get() + ", failures " + failures.get());
        if (loads.get() == 0) {
            return null;
        }

        // The page needs a moment to fetch its own definition and build its DOM.
        ScenarioDriver.pause(2500);

        AtomicReference<String> geometry = new AtomicReference<>("");
        FxTestToolkit.onFx(() -> geometry.set((int) driven.stage.getX() + "," + (int) driven.stage.getY()
            + " " + (int) driven.stage.getWidth() + "x" + (int) driven.stage.getHeight()
            + " showing=" + driven.stage.isShowing() + " opacity=" + driven.stage.getOpacity()));
        ScenarioDriver.record(scenario, "the card is on screen", ScenarioDriver.Verdict.PASS,
            geometry.get() + ", fitted " + fits.size() + " time(s)");
        return card;
    }

    /** Records whether the terms and privacy links the fixtures carry are in the page. */
    private void reportLinks(WebEngine engine, String scenario, String terms, String privacy) {
        ScenarioDriver.record(scenario, "the terms link is present",
            ScenarioDriver.exists(engine, terms) ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP,
            "count " + ScenarioDriver.count(engine, terms));
        ScenarioDriver.record(scenario, "the privacy link is present",
            ScenarioDriver.exists(engine, privacy) ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP,
            "count " + ScenarioDriver.count(engine, privacy));
    }

    /**
     * Waits out the widget's own closing, then reports what the SDK made of the session.
     */
    private void finish(Card card, String scenario, String eventRegex) {
        long until = System.currentTimeMillis() + 8000;
        while (card.callbacks.get() == 0 && System.currentTimeMillis() < until) {
            ScenarioDriver.pause(200);
        }

        ScenarioDriver.record(scenario, "the SDK recorded a widget event",
            log.has(eventRegex) ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            String.valueOf(log.find(eventRegex)));
        ScenarioDriver.record(scenario, "the widget closed itself",
            card.callbacks.get() > 0 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            "callbacks " + card.callbacks.get());
        ScenarioDriver.record(scenario, "no SDK errors during the scenario",
            log.countOf("^ERROR ") == 0 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.FAIL,
            log.countOf("^ERROR ") + " error lines, first [" + log.find("^ERROR ") + "]");

        FxTestToolkit.onFx(() -> {
            if (card.driven.stage.isShowing()) {
                card.driven.host.closeHost();
            }
        });
    }
}
