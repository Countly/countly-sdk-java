package ly.count.sdk.java.internal;

import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;

/**
 * View implementation for Countly Views plugin
 */

class ViewImpl implements View {
    private Log L = null;
    static final String EVENT = "[CLY]_view";
    static final String NAME = "name";
    static final String VISIT = "visit";
    static final String VISIT_VALUE = "1";
    static final String SEGMENT = "segment";
    static final String START = "start";
    static final String START_VALUE = "1";
    static final String EXIT = "exit";
    static final String EXIT_VALUE = "1";
    static final String BOUNCE = "bounce";
    static final String BOUNCE_VALUE = "1";

    private final String name;
    private final Session session;
    private EventImpl start;
    private boolean firstView;
    private boolean started, ended;

    ViewImpl(Session session, String name, Log logger) {
        this.L = logger;
        this.name = name;
        this.session = session;
    }

    @Override
    public void start(boolean firstView) {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("[ViewImpl] start: Skipping view, backend mode is enabled!");
            return;
        }

        L.d("[ViewImpl] start: firstView = " + firstView);
        if (started) {
            return;
        }
        this.started = true;
        this.firstView = firstView;

        start = (EventImpl) session.event(EVENT).addSegments(NAME, this.name, VISIT, VISIT_VALUE, SEGMENT, SDKCore.instance.config.getSdkPlatform());

        if (firstView) {
            start.addSegment(START, START_VALUE);
        }

        start.record();
    }

    @Override
    public void stop(boolean lastView) {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("[ViewImpl] stop: Skipping view, backend mode is enabled!");
            return;
        }

        if (start == null) {
            L.e("[ViewImpl] stop: We are trying to end a view that has not been started.");
            return;
        }

        L.d("[ViewImpl] stop: lastView = " + lastView);

        if (ended) {
            return;
        }
        ended = true;
        EventImpl event = (EventImpl) session.event(EVENT).addSegments(NAME, this.name, SEGMENT, SDKCore.instance.config.getSdkPlatform());

        long startTs = TimeUtils.uniqueTimestampMs();
        long endTs = start.getTimestamp();

        long viewDurationSeconds = (startTs - endTs) / 1000;

        event.setDuration(viewDurationSeconds);

        if (lastView) {
            event.addSegment(EXIT, EXIT_VALUE);
        }

        if (lastView && firstView) {
            event.addSegment(BOUNCE, BOUNCE_VALUE);
        }

        event.record();
    }

    @Override
    public String toString() {
        return "ViewImpl{" +
            "name='" + name + '\'' +
            ", session=" + session +
            ", start=" + start +
            ", firstView=" + firstView +
            ", started=" + started +
            ", ended=" + ended +
            '}';
    }
}
