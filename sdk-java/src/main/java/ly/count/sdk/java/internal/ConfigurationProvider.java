package ly.count.sdk.java.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The SDK behavior settings in effect, the server side configuration served by
 * {@code /o/sdk?method=sc}. Every value starts from the developer configuration and is overridden,
 * in increasing order of precedence, by the settings provided at init (used only while nothing is
 * stored), by the settings stored from an earlier run and by the latest server response.
 * <p>
 * {@link ModuleConfiguration} implements it for the running SDK. Reach it through
 * {@link InternalConfig#getConfigurationProvider()}, which never returns {@code null}. A server
 * response can change any value on the thread that delivered it, so read a value where it is used
 * instead of keeping a copy of it.
 */
public interface ConfigurationProvider {

    /**
     * Key {@code networking}.
     *
     * @return whether requests may be sent at all, the queued ones and the immediate ones
     */
    boolean getNetworkingEnabled();

    /**
     * Key {@code tracking}.
     *
     * @return whether anything new may be recorded
     */
    boolean getTrackingEnabled();

    /**
     * Key {@code st}.
     *
     * @return whether sessions may be tracked
     */
    boolean getSessionTrackingEnabled();

    /**
     * Key {@code vt}.
     *
     * @return whether views may be tracked
     */
    boolean getViewTrackingEnabled();

    /**
     * Key {@code cet}.
     *
     * @return whether custom events, the ones whose key does not start with {@code [CLY]_}, may be recorded
     */
    boolean getCustomEventTrackingEnabled();

    /**
     * Key {@code ecz}.
     *
     * @return whether the SDK should be in the content zone
     */
    boolean getContentZoneEnabled();

    /**
     * Key {@code crt}.
     *
     * @return whether crashes may be recorded
     */
    boolean getCrashReportingEnabled();

    /**
     * Key {@code ast}. This SDK has no automatic session tracking, so the value is only exposed.
     *
     * @return whether sessions should be tracked automatically
     */
    boolean getAutomaticSessionTrackingEnabled();

    /**
     * Key {@code avt}. This SDK has no automatic view tracking, so the value is only exposed.
     *
     * @return whether views should be tracked automatically
     */
    boolean getAutomaticViewTrackingEnabled();

    /**
     * Key {@code acr}.
     *
     * @return whether unhandled exceptions should be reported automatically
     */
    boolean getAutomaticCrashReportingEnabled();

    /**
     * Key {@code lt}.
     *
     * @return whether location may be tracked
     */
    boolean getLocationTrackingEnabled();

    /**
     * Key {@code rcz}.
     *
     * @return whether the content zone may be refreshed
     */
    boolean getRefreshContentZoneEnabled();

    /**
     * Key {@code bom}.
     *
     * @return whether the backoff mechanism is on
     */
    boolean getBOMEnabled();

    /**
     * Key {@code log}.
     *
     * @return whether the SDK should print its logs
     */
    boolean getLoggingEnabled();

    /**
     * Key {@code cr}. The SDK applies it when it initializes, so a value that arrives later takes
     * effect on the next init and can differ from {@link InternalConfig#requiresConsent()} until then.
     *
     * @return whether consent is required before anything is recorded
     */
    boolean getConsentRequired();

    /**
     * Key {@code scui}.
     *
     * @return hours between two fetches of the settings
     */
    int getServerConfigUpdateInterval();

    /**
     * Key {@code rqs}.
     *
     * @return the maximum number of queued requests
     */
    int getRequestQueueMaxSize();

    /**
     * Whether {@link #getRequestQueueMaxSize()} comes from the settings, provided, stored or
     * received, rather than from the developer configuration.
     *
     * @return {@code true} when the settings set the request queue size
     */
    boolean isRequestQueueMaxSizeFromBehaviorSettings();

    /**
     * Key {@code eqs}.
     *
     * @return how many events are held before they are sent
     */
    int getEventQueueSizeThreshold();

    /**
     * Key {@code sui}.
     *
     * @return seconds between two ticks of the SDK timer, which sends session updates and queued events
     */
    int getSessionUpdateInterval();

    /**
     * Key {@code lkl}.
     *
     * @return the maximum length of a key, {@link Integer#MAX_VALUE} for no limit
     */
    int getMaxKeyLength();

    /**
     * Key {@code lvs}.
     *
     * @return the maximum length of a value, {@link Integer#MAX_VALUE} for no limit
     */
    int getMaxValueSize();

    /**
     * Key {@code lsv}.
     *
     * @return the maximum number of segmentation entries, {@link Integer#MAX_VALUE} for no limit
     */
    int getMaxSegmentationValues();

    /**
     * Key {@code lbc}.
     *
     * @return the maximum number of breadcrumbs kept for a crash
     */
    int getMaxBreadcrumbCount();

    /**
     * Key {@code ltlpt}.
     *
     * @return the maximum number of stack trace lines per thread, {@link Integer#MAX_VALUE} for no limit
     */
    int getMaxStackTraceLinesPerThread();

    /**
     * Key {@code ltl}.
     *
     * @return the maximum length of a stack trace line, {@link Integer#MAX_VALUE} for no limit
     */
    int getMaxStackTraceLineLength();

    /**
     * Key {@code upcl}.
     *
     * @return the maximum number of custom user properties cached before they are sent,
     *     {@link Integer#MAX_VALUE} for no limit
     */
    int getUserPropertyCacheLimit();

    /**
     * Key {@code bom_at}.
     *
     * @return seconds a request may take before the backoff mechanism counts it as slow
     */
    int getBOMAcceptedTimeoutSeconds();

    /**
     * Key {@code bom_rqp}.
     *
     * @return the share of the request queue, in (0, 1), above which the backoff mechanism stays off
     */
    double getBOMRQPercentage();

    /**
     * Key {@code bom_ra}.
     *
     * @return the age, in hours, above which a request is too old for the backoff mechanism to delay it
     */
    int getBOMRequestAge();

    /**
     * Key {@code bom_d}.
     *
     * @return seconds the backoff mechanism holds the request queue back
     */
    int getBOMDuration();

    /**
     * Key {@code dort}.
     *
     * @return the age, in hours, above which a queued request is dropped, {@code 0} to keep every request
     */
    int getRequestDropAgeHours();

    /**
     * Key {@code czi}.
     *
     * @return seconds between two content fetches while in the content zone
     */
    int getContentZoneTimerInterval();

    /**
     * Keys {@code eb} and {@code ew}.
     *
     * @return the filter for custom event keys, never {@code null}
     */
    @Nonnull FilterList<Set<String>> getEventFilterList();

    /**
     * Keys {@code upb} and {@code upw}.
     *
     * @return the filter for custom user property keys, never {@code null}
     */
    @Nonnull FilterList<Set<String>> getUserPropertyFilterList();

    /**
     * Keys {@code sb} and {@code sw}.
     *
     * @return the filter for custom event segmentation keys, never {@code null}
     */
    @Nonnull FilterList<Set<String>> getSegmentationFilterList();

    /**
     * Keys {@code esb} and {@code esw}.
     *
     * @return the segmentation key filter of each custom event key, never {@code null}
     */
    @Nonnull FilterList<Map<String, Set<String>>> getEventSegmentationFilterList();

    /**
     * Key {@code jte}.
     *
     * @return the custom event keys that trigger a journey, never {@code null} and never modifiable
     */
    @Nonnull Set<String> getJourneyTriggerEvents();

    /**
     * Key {@code jtv}.
     *
     * @return the view names that trigger a journey, never {@code null} and never modifiable
     */
    @Nonnull Set<String> getJourneyTriggerViews();

    /**
     * The decision of the {@code lg} directive, which sits next to {@code c} in a live response.
     *
     * @return whether this device gathers its SDK logs for the server
     */
    @Nonnull LogGatheringState getLogGatheringState();

    /**
     * The gather id every uploaded log batch has to carry.
     *
     * @return the id, {@code null} unless {@link #getLogGatheringState()} is {@link LogGatheringState#GATHERING}
     */
    @Nullable String getLogGatheringId();

    /**
     * The log levels to gather, as level characters: e error, w warning, i info, d debug, v verbose.
     *
     * @return a non empty subset of {@code ewidv}
     */
    @Nonnull String getLogGatheringLevels();

    /**
     * How many log lines to hold before a batch is uploaded.
     *
     * @return the batch size, within [10, 500]
     */
    int getLogGatheringBatchSize();

    /**
     * Whether this device gathers its SDK logs for the server.
     */
    enum LogGatheringState {
        /**
         * No live response has decided yet: only stored or provided settings were seen.
         */
        UNDECIDED,
        /**
         * A live response enabled gathering and carried a usable gather id.
         */
        GATHERING,
        /**
         * A live response, a failed fetch or disabled settings requests decided against gathering.
         */
        NOT_GATHERING
    }

    /**
     * An immutable listing filter: names that are the only ones allowed, a whitelist, or the ones
     * rejected, a blacklist. An empty blacklist allows everything.
     *
     * @param <T> the names, as a set or as a set per event key
     */
    final class FilterList<T> {
        static final FilterList<Set<String>> NO_NAMES = new FilterList<>(Collections.<String>emptySet(), false);
        static final FilterList<Map<String, Set<String>>> NO_NAMES_PER_EVENT = new FilterList<>(Collections.<String, Set<String>>emptyMap(), false);

        private final T filterList;
        private final boolean isWhitelist;

        /**
         * Wraps names that are already immutable.
         *
         * @param filterList the names
         * @param isWhitelist {@code true} when only the names are allowed, {@code false} when they are rejected
         */
        private FilterList(@Nonnull T filterList, boolean isWhitelist) {
            this.filterList = filterList;
            this.isWhitelist = isWhitelist;
        }

        /**
         * Builds a filter over an immutable copy of the given names.
         *
         * @param names the names, kept in the given order
         * @param isWhitelist {@code true} when only the names are allowed, {@code false} when they are rejected
         * @return the filter
         */
        static FilterList<Set<String>> ofNames(@Nonnull Set<String> names, boolean isWhitelist) {
            return new FilterList<>(Collections.unmodifiableSet(new LinkedHashSet<>(names)), isWhitelist);
        }

        /**
         * Builds a filter over an immutable copy of the given names of each event key.
         *
         * @param namesPerEvent the names of each event key, kept in the given order
         * @param isWhitelist {@code true} when only the names are allowed, {@code false} when they are rejected
         * @return the filter
         */
        static FilterList<Map<String, Set<String>>> ofNamesPerEvent(@Nonnull Map<String, Set<String>> namesPerEvent, boolean isWhitelist) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : namesPerEvent.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
            return new FilterList<>(Collections.unmodifiableMap(copy), isWhitelist);
        }

        /**
         * The names of the filter.
         *
         * @return the names, never modifiable
         */
        @Nonnull
        public T getFilterList() {
            return filterList;
        }

        /**
         * Whether the names are the only ones allowed.
         *
         * @return {@code true} for a whitelist, {@code false} for a blacklist
         */
        public boolean isWhitelist() {
            return isWhitelist;
        }

        /**
         * Describes the filter for logs.
         *
         * @return the kind of the filter followed by its names
         */
        @Override
        public String toString() {
            return (isWhitelist ? "whitelist " : "blacklist ") + filterList;
        }
    }
}
