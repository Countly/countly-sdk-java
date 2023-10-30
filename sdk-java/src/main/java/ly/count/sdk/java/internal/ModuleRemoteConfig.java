package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;

public class ModuleRemoteConfig extends ModuleBase {
    boolean updateRemoteConfigAfterIdChange = false;
    RemoteConfig remoteConfigInterface = null;
    //if set to true, it will automatically download remote configs on module startup
    boolean automaticDownloadTriggersEnabled;
    // if set to true we should add 'oi=1' to our RC download call
    boolean autoEnrollEnabled;
    boolean remoteConfigValuesShouldBeCached = false;
    List<RCDownloadCallback> downloadCallbacks = new ArrayList<>(2);

    ModuleRemoteConfig() {
    }

    @Override
    public void init(final InternalConfig config) {
        super.init(config);
        L.v("[ModuleRemoteConfig] init, Initialising");
        L.d("[ModuleRemoteConfig] init, Setting if remote config Automatic triggers enabled, " + config.isRemoteConfigAutomaticDownloadTriggersEnabled() + ", caching enabled: " + config.isRemoteConfigValueCachingEnabled() + ", auto enroll enabled: " + config.isAutoEnrollFlagEnabled());
        automaticDownloadTriggersEnabled = config.isRemoteConfigAutomaticDownloadTriggersEnabled();
        remoteConfigValuesShouldBeCached = config.isRemoteConfigValueCachingEnabled();
        autoEnrollEnabled = config.isAutoEnrollFlagEnabled();

        downloadCallbacks.addAll(config.getRemoteConfigGlobalCallbackList());

        remoteConfigInterface = new RemoteConfig();
    }

    /**
     * Internal call for updating remote config keys
     *
     * @param keysOnly set if these are the only keys to update
     * @param keysExcept set if these keys should be ignored from the update
     * @param devProvidedCallback dev provided callback that is called after the update is done
     */
    void updateRemoteConfigValues(@Nullable final String[] keysOnly, @Nullable final String[] keysExcept, @Nullable final RCDownloadCallback devProvidedCallback) {

        String[] preparedKeys = RemoteConfigHelper.prepareKeysIncludeExclude(keysOnly, keysExcept, L);
        boolean fullUpdate = (preparedKeys[0] == null || preparedKeys[0].isEmpty()) && (preparedKeys[1] == null || preparedKeys[1].isEmpty());

        try {
            // checks
            if (internalConfig.getDeviceId() == null) {
                //device ID is null, abort
                L.d("[ModuleRemoteConfig] updateRemoteConfigValues, RemoteConfig value update was aborted, deviceID is null");
                notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Can't complete call, device ID is null", fullUpdate, null);
                return;
            }

            if (internalConfig.isTemporaryIdEnabled()) {
                //temporary id mode enabled, abort
                L.d("[ModuleRemoteConfig] updateRemoteConfigValues, RemoteConfig value update was aborted, temporary device ID mode is set");
                notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Can't complete call, temporary device ID is set", fullUpdate, null);
                return;
            }

            //prepare metrics and request data
            String preparedMetrics = Device.dev.buildMetrics().toString();

            String requestData = prepareRemoteConfigRequest(preparedKeys[0], preparedKeys[1], preparedMetrics, autoEnrollEnabled);

            L.d("[ModuleRemoteConfig] updateRemoteConfigValues, RemoteConfig requestData:[" + requestData + "]");

            Transport transport = internalConfig.sdk.networking.getTransport();
            final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

            internalConfig.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk", transport, false, networkingIsEnabled, checkResponse -> {
                L.d("[ModuleRemoteConfig] updateRemoteConfigValues, Processing remote config received response, received response is null:[" + (checkResponse == null) + "]");
                if (checkResponse == null) {
                    notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Encountered problem while trying to reach the server, possibly no internet connection", fullUpdate, null);
                    return;
                }

                String error = null;
                Map<String, RCData> newRC = RemoteConfigHelper.downloadedValuesIntoMap(checkResponse, L);

                try {
                    boolean clearOldValues = keysExcept == null && keysOnly == null;
                    mergeCheckResponseIntoCurrentValues(clearOldValues, newRC);
                } catch (Exception ex) {
                    L.e("[ModuleRemoteConfig] updateRemoteConfigValues, Encountered internal issue while trying to download remote config information from the server, [" + ex + "]");
                    error = "Encountered internal issue while trying to download remote config information from the server, [" + ex + "]";
                }

                notifyDownloadCallbacks(devProvidedCallback, error == null ? RequestResult.Success : RequestResult.Error, error, fullUpdate, newRC);
            }, L);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] updateRemoteConfigValues, Encountered internal error while trying to perform a remote config update. " + ex);
            notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Encountered internal error while trying to perform a remote config update", fullUpdate, null);
        }
    }

    private String prepareRemoteConfigRequest(@Nullable String keysInclude, @Nullable String keysExclude, @Nonnull String preparedMetrics, boolean autoEnroll) {

        Params params = ModuleRequests.prepareRequiredParams(internalConfig).add("method", "rc");

        if (Countly.session() != null) {
            //add session data if consent given
            params.add("metrics", preparedMetrics);
        }

        //add key filters
        if (keysInclude != null) {
            params.add("keys", keysInclude);
        } else if (keysExclude != null) {
            params.add("omit_keys", keysExclude);
        }

        // if auto enroll was enabled add oi=1 to the request
        if (autoEnroll) {
            params.add("oi", "1");
        }

        return params.toString();
    }

    /**
     * Internal function to form and send a request to enroll user for given keys
     *
     * @param keys - keys for which the user should be enrolled
     */
    void enrollIntoABTestsForKeysInternal(@Nonnull String[] keys) {
        L.d("[ModuleRemoteConfig] enrollIntoABTestsForKeysInternal, Enrolling user for the given keys:" + Arrays.toString(keys));

        if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
            L.d("[ModuleRemoteConfig] enrollIntoABTestsForKeysInternal, Enrolling user was aborted, temporary device ID mode is set or device ID is null.");
            return;
        }
        Params params = new Params();
        params.add("method", "ab");
        params.add("new_end_point", "/o/sdk");

        if (keys.length > 0) { // exits all otherwise
            params.arr("keys").put(Arrays.asList(keys)).add();
        }

        ModuleRequests.pushAsync(internalConfig, new Request(params));
    }

    /**
     * Internal function to form and send the request to remove user from A/B tests for given keys
     *
     * @param keys - keys for which the user should be removed from keys
     */
    void exitABTestsForKeysInternal(@Nonnull String[] keys) {
        L.d("[ModuleRemoteConfig] exitABTestsForKeysInternal, Removing user for the tests with given keys:" + Arrays.toString(keys));

        if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
            L.d("[ModuleRemoteConfig] exitABTestsForKeysInternal, Removing user from tests was aborted, temporary device ID mode is set or device ID is null.");
            return;
        }

        Params params = new Params();

        params.add("method", "ab_opt_out");
        if (keys.length > 0) { // exits all otherwise
            params.arr("keys").put(Arrays.asList(keys)).add();
        }

        ModuleRequests.pushAsync(internalConfig, new Request(params));
    }

    /**
     * Merge the values acquired from the server into the current values.
     * Clear if needed.
     */
    void mergeCheckResponseIntoCurrentValues(boolean clearOldValues, @Nonnull Map<String, RCData> newRC) {
        //merge the new values into the current ones
        RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
        rcvs.mergeValues(newRC, clearOldValues);

        L.d("[ModuleRemoteConfig] mergeCheckResponseIntoCurrentValues, Finished remote config processing, starting saving");

        saveRCValues(rcvs);

        L.d("[ModuleRemoteConfig] mergeCheckResponseIntoCurrentValues, Finished remote config saving");
    }

    RCData getRCValue(@Nonnull String key) {
        try {
            RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
            return rcvs.getValue(key);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] getValue, Call failed:[" + ex + "]");
            return new RCData(null, true);
        }
    }

    void saveRCValues(@Nonnull RemoteConfigValueStore rcvs) {
        internalConfig.storageProvider.setRemoteConfigValues(rcvs.values.toString());
    }

    /**
     * Loads the remote config values from the storage
     *
     * @return see {@link RemoteConfigValueStore}
     */
    @Nonnull RemoteConfigValueStore loadRCValuesFromStorage() {
        String rcvsString = internalConfig.storageProvider.getRemoteConfigValues();
        return RemoteConfigValueStore.dataFromString(rcvsString, remoteConfigValuesShouldBeCached, L);
    }

    void clearValueStoreInternal() {
        internalConfig.storageProvider.setRemoteConfigValues("");
    }

    @Nonnull Map<String, RCData> getAllRemoteConfigValuesInternal() {
        try {
            RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
            return rcvs.getAllValues();
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] getAllRemoteConfigValuesInternal, Call failed:[" + ex + "]");
            return new HashMap<>();
        }
    }

    void clearAndDownloadAfterIdChange(boolean valuesShouldBeCacheCleared) {
        L.v("[RemoteConfig] Clearing remote config values and preparing to download after ID update, " + valuesShouldBeCacheCleared);

        if (valuesShouldBeCacheCleared) {
            cacheOrClearRCValuesIfNeeded();
        }
        if (automaticDownloadTriggersEnabled) {
            updateRemoteConfigAfterIdChange = true;
        }
    }

    void cacheOrClearRCValuesIfNeeded() {
        L.v("[ModuleRemoteConfig] cacheOrClearRCValuesIfNeeded, cache-clearing values");

        RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
        rcvs.cacheClearValues();
        saveRCValues(rcvs);
    }

    void notifyDownloadCallbacks(RCDownloadCallback devProvidedCallback, RequestResult requestResult, String message, boolean fullUpdate, Map<String, RCData> downloadedValues) {
        downloadCallbacks.forEach(callback -> callback.callback(requestResult, message, fullUpdate, downloadedValues));

        if (devProvidedCallback != null) {
            devProvidedCallback.callback(requestResult, message, fullUpdate, downloadedValues);
        }
    }

    void rcAutomaticDownloadTrigger(boolean cacheClearOldValues) {
        if (cacheClearOldValues) {
            cacheOrClearRCValuesIfNeeded();
        }

        if (automaticDownloadTriggersEnabled) {
            L.d("[ModuleRemoteConfig] rcAutomaticDownloadTrigger, Automatically updating remote config values");
            updateRemoteConfigValues(null, null, null);
        } else {
            L.v("[ModuleRemoteConfig] rcAutomaticDownloadTrigger, Automatic RC update trigger skipped");
        }
    }

    @Override
    public void onDeviceId(InternalConfig config, Config.DID deviceId, Config.DID oldDeviceId) {
        L.v("[ModuleRemoteConfig] onDeviceId, Device ID changed will update values: [" + updateRemoteConfigAfterIdChange + "]");

        if (updateRemoteConfigAfterIdChange) {
            updateRemoteConfigAfterIdChange = false;
            rcAutomaticDownloadTrigger(true);
        }
    }

    @Override
    public void initFinished(@Nonnull InternalConfig config) {
        //update remote config values if automatic update is enabled, and we are not in temporary id mode
        rcAutomaticDownloadTrigger(false);
    }

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        super.stop(config, clear);
        remoteConfigInterface = null;
        if (clear) {
            clearValueStoreInternal();
        }
    }

    // ==================================================================
    // ==================================================================
    // INTERFACE
    // ==================================================================
    // ==================================================================

    public class RemoteConfig {

        /**
         * Manual remote config call that will initiate a download of all except the given remote config keys.
         * If no keys are provided then it will download all available RC values
         *
         * @param keysToOmit A list of keys that need to be downloaded
         * @param callback This is called when the operation concludes
         */
        public void downloadOmittingKeys(@Nullable String[] keysToOmit, @Nullable RCDownloadCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] downloadOmittingKeys");

                if (keysToOmit == null) {
                    L.w("[RemoteConfig] downloadOmittingKeys, passed 'keys to ignore' array is null");
                }

                updateRemoteConfigValues(null, keysToOmit, callback);
            }
        }

        /**
         * Manual remote config call that will initiate a download of only the given remote config keys.
         * If no keys are provided then it will download all available RC values
         *
         * @param keysToInclude Keys for which the RC should be initialized
         * @param callback This is called when the operation concludes
         */
        public void downloadSpecificKeys(@Nullable String[] keysToInclude, @Nullable RCDownloadCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] downloadSpecificKeys");

                if (keysToInclude == null) {
                    L.w("[RemoteConfig] downloadSpecificKeys, passed 'keys to include' array is null");
                }

                updateRemoteConfigValues(keysToInclude, null, callback);
            }
        }

        /**
         * Manual remote config call that will initiate a download of all available remote config keys.
         *
         * @param callback This is called when the operation concludes
         */
        public void downloadAllKeys(@Nullable RCDownloadCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] downloadAllKeys");
                updateRemoteConfigValues(null, null, callback);
            }
        }

        /**
         * Returns all available remote config values
         *
         * @return The available RC values
         */
        public @Nonnull Map<String, RCData> getValues() {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] getValues");
                return getAllRemoteConfigValuesInternal();
            }
        }

        /**
         * Returns all available remote config values and enrolls to A/B tests for those values
         *
         * @return The available RC values
         */
        public @Nonnull Map<String, RCData> getAllValuesAndEnroll() {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] getAllValuesAndEnroll");
                Map<String, RCData> values = getAllRemoteConfigValuesInternal();

                if (values.isEmpty()) {
                    L.i("[RemoteConfig] getAllValuesAndEnroll, No value to enroll");
                } else {
                    // assuming the values is not empty enroll for the keys
                    enrollIntoABTestsForKeys(values.keySet().toArray(new String[0]));// enroll
                }

                return values;
            }
        }

        /**
         * Return the remote config value for a specific key
         *
         * @param key Key for which the remote config value needs to be returned
         * @return The returned value. If no value existed for the key then the inner object (value) will be returned as "null"
         */
        public @Nonnull RCData getValue(final @Nullable String key) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] getValue, key:[" + key + "]");

                if (Utils.isEmptyOrNull(key)) {
                    L.i("[RemoteConfig] getValue, A valid key should be provided to get its value.");
                    return new RCData(null, true);
                }

                return getRCValue(key);
            }
        }

        /**
         * Returns the remote config value for a specific key and enrolls to A/B tests for it
         *
         * @param key Key for which the remote config value needs to be returned
         * @return The returned value. If no value existed for the key then the inner object will be returned as "null"
         */
        public @Nonnull RCData getValueAndEnroll(@Nullable String key) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] getValueAndEnroll, key:[" + key + "]");

                if (Utils.isEmptyOrNull(key)) {
                    L.i("[RemoteConfig] getValueAndEnroll, A valid key should be provided to get its value.");
                    return new RCData(null, true);
                }

                RCData data = getRCValue(key);

                if (data.value == null) {
                    L.i("[RemoteConfig] getValueAndEnroll, No value to enroll");
                } else {
                    // assuming value is not null enroll to key
                    enrollIntoABTestsForKeys(new String[] { key });
                }

                return data;
            }
        }

        /**
         * Enrolls user to AB tests of the given keys.
         *
         * @param keys - String array of keys (parameters)
         */
        public void enrollIntoABTestsForKeys(@Nullable String[] keys) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] enrollIntoABTestsForKeys");

                if (keys == null || keys.length == 0) {
                    L.w("[RemoteConfig] enrollIntoABTestsForKeys, A key should be provided to enroll the user.");
                    return;
                }

                enrollIntoABTestsForKeysInternal(keys);
            }
        }

        /**
         * Removes user from A/B tests for the given keys. If no key provided would remove the user from all tests.
         *
         * @param keys - String array of keys (parameters)
         */
        public void exitABTestsForKeys(@Nullable String[] keys) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] exitABTestsForKeys");

                if (keys == null) {
                    keys = new String[0];
                }

                exitABTestsForKeysInternal(keys);
            }
        }

        /**
         * Register a global callback for when download operations have finished
         *
         * @param callback The callback that should be added
         */
        public void registerDownloadCallback(@Nullable RCDownloadCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] registerDownloadCallback");
                downloadCallbacks.add(callback);
            }
        }

        /**
         * Unregister a global download callback
         *
         * @param callback The callback that should be removed
         */
        public void removeDownloadCallback(@Nullable RCDownloadCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] removeDownloadCallback");
                downloadCallbacks.remove(callback);
            }
        }

        /**
         * Clear all stored remote config values.
         */
        public void clearAll() {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] clearAll");
                clearValueStoreInternal();
            }
        }
    }
}
