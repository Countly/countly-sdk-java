package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Init time options of the content feature, reachable through {@link Config#content}.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public class ConfigContent {

    static final int DEFAULT_ZONE_TIMER_INTERVAL = 30;
    static final int MIN_ZONE_TIMER_INTERVAL = 15;

    private final Config config;
    protected int zoneTimerInterval = DEFAULT_ZONE_TIMER_INTERVAL;
    protected ContentCallback globalContentCallback = null;
    protected ContentUrlHandler contentUrlHandler = null;

    public ConfigContent(Config config) {
        this.config = config;
    }

    /**
     * Set how often the SDK asks the server whether there is content to show, while it is in a
     * content zone. Values below {@value #MIN_ZONE_TIMER_INTERVAL} seconds are ignored, so the
     * default of {@value #DEFAULT_ZONE_TIMER_INTERVAL} seconds stays in effect.
     *
     * @param zoneTimerIntervalSeconds the fetch interval, in seconds
     * @return the same config object for convenient linking
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public synchronized Config setZoneTimerInterval(int zoneTimerIntervalSeconds) {
        if (zoneTimerIntervalSeconds >= MIN_ZONE_TIMER_INTERVAL) {
            this.zoneTimerInterval = zoneTimerIntervalSeconds;
        }
        return config;
    }

    /**
     * Take over opening the links that content blocks and feedback widgets ask to open, instead of
     * the SDK handing them to the desktop. This is how an application routes its own deep links to
     * the right screen.
     *
     * @param handler consulted for every link, {@code null} to let the SDK open them
     * @return the same config object for convenient linking
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public synchronized Config setContentUrlHandler(ContentUrlHandler handler) {
        this.contentUrlHandler = handler;
        return config;
    }

    /**
     * Set a callback that is called whenever a content block reaches an end state, with the query
     * parameters the content sent along.
     *
     * @param callback to call when a content block ends
     * @return the same config object for convenient linking
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public synchronized Config setGlobalContentCallback(ContentCallback callback) {
        this.globalContentCallback = callback;
        return config;
    }
}
