package ly.count.sdk.java.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies the listing filters of the SDK behavior settings: the event filter, the user property
 * filter, the segmentation filter and the segmentation filter of each event. A filter without names
 * allows everything; otherwise a whitelist keeps only its names and a blacklist removes its names.
 */
final class UtilsListingFilters {

    /**
     * Not instantiated: the filters are static helpers.
     */
    private UtilsListingFilters() {
    }

    /**
     * Whether the event filter, {@code eb} or {@code ew}, lets a custom event be recorded.
     *
     * @param eventKey the key of the custom event
     * @param configProvider the settings in effect
     * @return {@code true} when the event may be recorded
     */
    static boolean applyEventFilter(@Nonnull String eventKey, @Nonnull ConfigurationProvider configProvider) {
        return applyListFilter(eventKey, configProvider.getEventFilterList());
    }

    /**
     * Whether the user property filter, {@code upb} or {@code upw}, lets a custom user property be
     * set or modified.
     *
     * @param propertyKey the key of the custom user property
     * @param configProvider the settings in effect
     * @return {@code true} when the property may be set or modified
     */
    static boolean applyUserPropertyFilter(@Nullable String propertyKey, @Nonnull ConfigurationProvider configProvider) {
        return applyListFilter(propertyKey, configProvider.getUserPropertyFilterList());
    }

    /**
     * Removes from a segmentation the keys the segmentation filter, {@code sb} or {@code sw}, does not allow.
     *
     * @param segmentation the segmentation, changed in place
     * @param configProvider the settings in effect
     * @param L logger
     */
    static void applySegmentationFilter(@Nonnull Map<String, Object> segmentation, @Nonnull ConfigurationProvider configProvider, @Nonnull Log L) {
        if (segmentation.isEmpty()) {
            return;
        }

        ConfigurationProvider.FilterList<Set<String>> filter = configProvider.getSegmentationFilterList();
        applyMapFilter(segmentation, filter.getFilterList(), filter.isWhitelist(), L);
    }

    /**
     * Removes from the segmentation of an event the keys that the segmentation filter of that event,
     * {@code esb} or {@code esw}, does not allow. An event without names in the filter keeps every key.
     *
     * @param eventKey the key of the event
     * @param segmentation the segmentation, changed in place
     * @param configProvider the settings in effect
     * @param L logger
     */
    static void applyEventSegmentationFilter(@Nonnull String eventKey, @Nonnull Map<String, Object> segmentation, @Nonnull ConfigurationProvider configProvider, @Nonnull Log L) {
        ConfigurationProvider.FilterList<Map<String, Set<String>>> filter = configProvider.getEventSegmentationFilterList();
        if (segmentation.isEmpty() || filter.getFilterList().isEmpty()) {
            return;
        }

        Set<String> names = filter.getFilterList().get(eventKey);
        if (names == null) {
            return;
        }

        applyMapFilter(segmentation, names, filter.isWhitelist(), L);
    }

    /**
     * Removes the keys of a map that a filter does not allow.
     *
     * @param map the map, changed in place
     * @param names the names of the filter
     * @param isWhitelist {@code true} when only the names are allowed, {@code false} when they are rejected
     * @param L logger
     */
    private static void applyMapFilter(@Nonnull Map<String, Object> map, @Nonnull Set<String> names, boolean isWhitelist, @Nonnull Log L) {
        if (names.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<String, Object>> entries = map.entrySet().iterator();
        while (entries.hasNext()) {
            String key = entries.next().getKey();
            if (isWhitelist != names.contains(key)) {
                entries.remove();
                L.d("[UtilsListingFilters] applyMapFilter, removed the segmentation key [" + key + "], " + (isWhitelist ? "it is not in the whitelist" : "it is in the blacklist"));
            }
        }
    }

    /**
     * Whether a filter allows a name.
     *
     * @param name the name
     * @param filter the filter
     * @return {@code true} when the filter has no names or allows this one
     */
    private static boolean applyListFilter(@Nullable String name, @Nonnull ConfigurationProvider.FilterList<Set<String>> filter) {
        Set<String> names = filter.getFilterList();
        if (names.isEmpty()) {
            return true;
        }

        return filter.isWhitelist() == names.contains(name);
    }
}
