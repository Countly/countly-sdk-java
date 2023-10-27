package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONException;
import org.json.JSONObject;

public class ModuleRemoteConfig extends ModuleBase {
    boolean updateRemoteConfigAfterIdChange = false;
    Map<String, String[]> variantContainer = new HashMap<>(); // Stores the fetched A/B test variants
    Map<String, ExperimentInformation> experimentContainer = new HashMap<>(); // Stores the fetched A/B test information (includes exp ID, description etc.)
    RemoteConfig remoteConfigInterface = null;

    //if set to true, it will automatically download remote configs on module startup
    boolean automaticDownloadTriggersEnabled;

    // if set to true we should add 'oi=1' to our RC download call
    boolean autoEnrollEnabled;

    boolean remoteConfigValuesShouldBeCached = false;

    List<RCDownloadCallback> downloadCallbacks = new ArrayList<>(2);

    public static String variantObjectNameKey = "name";

    ModuleRemoteConfig() {
    }

    @Override
    public void init(final InternalConfig config) {
        super.init(config);
        L.v("[ModuleRemoteConfig] Initialising");

        L.d("[ModuleRemoteConfig] Setting if remote config Automatic triggers enabled, " + config.isRemoteConfigAutomaticDownloadTriggersEnabled() + ", caching enabled: " + config.isRemoteConfigValueCachingEnabled() + ", auto enroll enabled: " + config.isAutoEnrollFlagEnabled());
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
                L.d("[ModuleRemoteConfig] RemoteConfig value update was aborted, deviceID is null");
                notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Can't complete call, device ID is null", fullUpdate, null);
                return;
            }

            if (internalConfig.isTemporaryIdEnabled()) {
                //temporary id mode enabled, abort
                L.d("[ModuleRemoteConfig] RemoteConfig value update was aborted, temporary device ID mode is set");
                notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Can't complete call, temporary device ID is set", fullUpdate, null);
                return;
            }

            //prepare metrics and request data
            String preparedMetrics = Device.dev.buildMetrics().toString();

            String requestData;

            requestData = ""; //TODO requestQueueProvider.prepareRemoteConfigRequest(preparedKeys[0], preparedKeys[1], preparedMetrics, autoEnrollEnabled);

            L.d("[ModuleRemoteConfig] RemoteConfig requestData:[" + requestData + "]");

            Transport transport = internalConfig.sdk.networking.getTransport();
            final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

            internalConfig.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk", transport, false, networkingIsEnabled, checkResponse -> {
                L.d("[ModuleRemoteConfig] Processing remote config received response, received response is null:[" + (checkResponse == null) + "]");
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
                    L.e("[ModuleRemoteConfig] updateRemoteConfigValues - execute, Encountered internal issue while trying to download remote config information from the server, [" + ex.toString() + "]");
                    error = "Encountered internal issue while trying to download remote config information from the server, [" + ex.toString() + "]";
                }

                notifyDownloadCallbacks(devProvidedCallback, error == null ? RequestResult.Success : RequestResult.Error, error, fullUpdate, newRC);
            }, L);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] Encountered internal error while trying to perform a remote config update. " + ex.toString());
            notifyDownloadCallbacks(devProvidedCallback, RequestResult.Error, "Encountered internal error while trying to perform a remote config update", fullUpdate, null);
        }
    }

    /**
     * Internal function to form and send a request to enroll user for given keys
     *
     * @param keys
     */
    void enrollIntoABTestsForKeysInternal(@Nonnull String[] keys) {
        L.d("[ModuleRemoteConfig] Enrolling user for the given keys:" + keys);

        if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
            L.d("[ModuleRemoteConfig] Enrolling user was aborted, temporary device ID mode is set or device ID is null.");
            return;
        }

        //TODO enroll requestQueueProvider.enrollToKeys(keys);
    }

    /**
     * Internal function to form and send the request to remove user from A/B testes for given keys
     *
     * @param keys
     */
    void exitABTestsForKeysInternal(@Nonnull String[] keys) {
        L.d("[ModuleRemoteConfig] Removing user for the tests with given keys:" + keys);

        if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
            L.d("[ModuleRemoteConfig] Removing user from tests was aborted, temporary device ID mode is set or device ID is null.");
            return;
        }

        //TODO exit requestQueueProvider.exitForKeys(keys);
    }

    /**
     * Internal call for fetching all variants of A/B test experiments
     * There are 2 endpoints that can be used:
     *
     * @param callback called after the fetch is done
     * @param shouldFetchExperimentInfo if true this call would fetch experiment information including the variants
     */
    void testingFetchVariantInformationInternal(@Nonnull final RCVariantCallback callback, @Nonnull final boolean shouldFetchExperimentInfo) {
        try {
            L.d("[ModuleRemoteConfig] Fetching all A/B test variants/info");

            if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
                L.d("[ModuleRemoteConfig] Fetching all A/B test variants was aborted, temporary device ID mode is set or device ID is null.");
                callback.callback(RequestResult.Error, "Temporary device ID mode is set or device ID is null.");
                return;
            }

            // prepare request data
            String requestData = "";// TODO shouldFetchExperimentInfo ? requestQueueProvider.prepareFetchAllExperiments() : requestQueueProvider.prepareFetchAllVariants();

            L.d("[ModuleRemoteConfig] Fetching all A/B test variants/info requestData:[" + requestData + "]");

            Transport transport = internalConfig.sdk.networking.getTransport();
            final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

            internalConfig.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk", transport, false, networkingIsEnabled, checkResponse -> {
                L.d("[ModuleRemoteConfig] Processing Fetching all A/B test variants/info received response, received response is null:[" + (checkResponse == null) + "]");
                if (checkResponse == null) {
                    callback.callback(RequestResult.NetworkIssue, "Encountered problem while trying to reach the server, possibly no internet connection");
                    return;
                }

                if (shouldFetchExperimentInfo) {
                    experimentContainer = RemoteConfigHelper.convertExperimentInfoJsonToMap(checkResponse, L);
                } else {
                    variantContainer = RemoteConfigHelper.convertVariantsJsonToMap(checkResponse, L);
                }

                callback.callback(RequestResult.Success, null);
            }, L);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] Encountered internal error while trying to fetch all A/B test variants/info. " + ex.toString());
            callback.callback(RequestResult.Error, "Encountered internal error while trying to fetch all A/B test variants/info.");
        }
    }

    void testingEnrollIntoVariantInternal(@Nonnull final String key, @Nonnull final String variant, @Nonnull final RCVariantCallback callback) {
        try {
            L.d("[ModuleRemoteConfig] Enrolling A/B test variants, Key/Variant pairs:[" + key + "][" + variant + "]");

            if (internalConfig.isTemporaryIdEnabled() || internalConfig.getDeviceId() == null) {
                L.d("[ModuleRemoteConfig] Enrolling A/B test variants was aborted, temporary device ID mode is set or device ID is null.");
                callback.callback(RequestResult.Error, "Temporary device ID mode is set or device ID is null.");
                return;
            }

            // check Key and Variant
            if (Utils.isEmptyOrNull(key) || Utils.isEmptyOrNull(variant)) {
                L.w("[ModuleRemoteConfig] Enrolling A/B test variants, Key/Variant pair is invalid. Aborting.");
                callback.callback(RequestResult.Error, "Provided key/variant pair is invalid.");
                return;
            }

            // prepare request data
            String requestData = ""; //todo prep req requestQueueProvider.prepareEnrollVariant(key, variant);

            L.d("[ModuleRemoteConfig] Enrolling A/B test variants requestData:[" + requestData + "]");

            Transport transport = internalConfig.sdk.networking.getTransport();
            final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

            internalConfig.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/i", transport, false, networkingIsEnabled, checkResponse -> {
                L.d("[ModuleRemoteConfig] Processing Fetching all A/B test variants received response, received response is null:[" + (checkResponse == null) + "]");
                if (checkResponse == null) {
                    callback.callback(RequestResult.NetworkIssue, "Encountered problem while trying to reach the server, possibly no internet connection");
                    return;
                }

                try {
                    if (!isResponseValid(checkResponse)) {
                        callback.callback(RequestResult.NetworkIssue, "Bad response from the server:" + checkResponse.toString());
                        return;
                    }

                    rcAutomaticDownloadTrigger(true);

                    callback.callback(RequestResult.Success, null);
                } catch (Exception ex) {
                    L.e("[ModuleRemoteConfig] testingEnrollIntoVariantInternal - execute, Encountered internal issue while trying to enroll to the variant, [" + ex.toString() + "]");
                    callback.callback(RequestResult.Error, "Encountered internal error while trying to take care of the A/B test variant enrolment.");
                }
            }, L);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] Encountered internal error while trying to enroll A/B test variants. " + ex.toString());
            callback.callback(RequestResult.Error, "Encountered internal error while trying to enroll A/B test variants.");
        }
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

    /**
     * Checks and evaluates the response from the server
     *
     * @param responseJson - JSONObject response
     * @return true if the response is valid
     */
    boolean isResponseValid(@Nonnull JSONObject responseJson) {
        boolean result = false;

        try {
            if (responseJson.get("result").equals("Success")) {
                result = true;
            }
        } catch (JSONException e) {
            L.e("[ModuleRemoteConfig] isResponseValid, encountered issue, " + e);
            return false;
        }

        return result;
    }

    RCData getRCValue(@Nonnull String key) {
        try {
            RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
            return rcvs.getValue(key);
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] getValue, Call failed:[" + ex.toString() + "]");
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

    /**
     * Gets all AB testing variants stored in the memory
     *
     * @return
     */
    @Nonnull Map<String, String[]> testingGetAllVariantsInternal() {
        return variantContainer;
    }

    /**
     * Get all variants for a given key if exists. Else returns an empty array.
     *
     * @param key
     * @return
     */
    @Nullable String[] testingGetVariantsForKeyInternal(@Nonnull String key) {
        String[] variantResponse = null;
        if (variantContainer.containsKey(key)) {
            variantResponse = variantContainer.get(key);
        }

        return variantResponse;
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
        L.v("[RemoteConfig] CacheOrClearRCValuesIfNeeded, cacheclearing values");

        RemoteConfigValueStore rcvs = loadRCValuesFromStorage();
        rcvs.cacheClearValues();
        saveRCValues(rcvs);
    }

    void notifyDownloadCallbacks(RCDownloadCallback devProvidedCallback, RequestResult requestResult, String message, boolean fullUpdate, Map<String, RCData> downloadedValues) {
        for (RCDownloadCallback callback : downloadCallbacks) {
            callback.callback(requestResult, message, fullUpdate, downloadedValues);
        }

        if (devProvidedCallback != null) {
            devProvidedCallback.callback(requestResult, message, fullUpdate, downloadedValues);
        }
    }

    void rcAutomaticDownloadTrigger(boolean cacheClearOldValues) {
        if (cacheClearOldValues) {
            cacheOrClearRCValuesIfNeeded();
        }

        if (automaticDownloadTriggersEnabled) {
            L.d("[RemoteConfig] Automatically updating remote config values");
            updateRemoteConfigValues(null, null, null);
        } else {
            L.v("[RemoteConfig] Automatic RC update trigger skipped");
        }
    }

    @Override
    public void onDeviceId(InternalConfig config, Config.DID deviceId, Config.DID oldDeviceId) {
        L.v("[RemoteConfig] Device ID changed will update values: [" + updateRemoteConfigAfterIdChange + "]");

        if (updateRemoteConfigAfterIdChange) {
            updateRemoteConfigAfterIdChange = false;
            rcAutomaticDownloadTrigger(true);
        }
    }

    @Override
    public void initFinished(@Nonnull InternalConfig config) {
        //update remote config_ values if automatic update is enabled and we are not in temporary id mode
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
                    L.w("[RemoteConfig] downloadOmittingKeys passed 'keys to ignore' array is null");
                }

                if (callback == null) {
                    callback = (downloadResult, error, fullValueUpdate, downloadedValues) -> {
                    };
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
                    L.w("[RemoteConfig] downloadSpecificKeys passed 'keys to include' array is null");
                }

                if (callback == null) {
                    callback = (downloadResult, error, fullValueUpdate, downloadedValues) -> {
                    };
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

                if (callback == null) {
                    callback = (downloadResult, error, fullValueUpdate, downloadedValues) -> {
                    };
                }

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
                    Set<String> setOfKeys = values.keySet();
                    String[] arrayOfKeys = new String[setOfKeys.size()];

                    // set to array
                    int i = 0;
                    for (String key : setOfKeys) {
                        arrayOfKeys[i++] = key;
                    }

                    // enroll
                    enrollIntoABTestsForKeys(arrayOfKeys);
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

                if (key == null || key.equals("")) {
                    L.i("[RemoteConfig] getValueAndEnroll, A valid key should be provided to get its value.");
                    return new RCData(null, true);
                }

                RCData data = getRCValue(key);

                if (data.value == null) {
                    L.i("[RemoteConfig] getValueAndEnroll, No value to enroll");
                } else {
                    // assuming value is not null enroll to key
                    String[] arrayOfKeys = { key };
                    enrollIntoABTestsForKeys(arrayOfKeys);
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

        /**
         * Returns all variant information as a Map<String, String[]>
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @return Return the information of all available variants
         */
        public @Nonnull Map<String, String[]> testingGetAllVariants() {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingGetAllVariants");

                return testingGetAllVariantsInternal();
            }
        }

        /**
         * Returns all experiment information as a Map<String, ExperimentInformation>
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @return Return the information of all available variants
         */
        public @Nonnull Map<String, ExperimentInformation> testingGetAllExperimentInfo() {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingGetAllExperimentInfo");

                return experimentContainer;
            }
        }

        /**
         * Returns variant information for a key as a String[]
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @param key - key value to get variant information for
         * @return If returns the stored variants for the given key. Returns "null" if there are no variants for that key.
         */
        public @Nullable String[] testingGetVariantsForKey(@Nullable String key) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingGetVariantsForKey");

                if (key == null) {
                    L.i("[RemoteConfig] testingGetVariantsForKey, provided variant key can not be null");
                    return null;
                }

                return testingGetVariantsForKeyInternal(key);
            }
        }

        /**
         * Download all variants of A/B testing experiments
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @param completionCallback this callback will be called when the network request finished
         */
        public void testingDownloadVariantInformation(@Nullable RCVariantCallback completionCallback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingFetchVariantInformation");

                if (completionCallback == null) {
                    completionCallback = (result, error) -> {
                    };
                }

                testingFetchVariantInformationInternal(completionCallback, false);
            }
        }

        /**
         * Download all A/B testing experiments information
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @param completionCallback this callback will be called when the network request finished
         */
        public void testingDownloadExperimentInformation(@Nullable RCVariantCallback completionCallback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingDownloadExperimentInformation");

                if (completionCallback == null) {
                    completionCallback = (result, error) -> {
                    };
                }

                testingFetchVariantInformationInternal(completionCallback, true);
            }
        }

        /**
         * Enrolls user for a specific variant of A/B testing experiment
         *
         * This call is not meant for production. It should only be used to facilitate testing of A/B test experiments.
         *
         * @param keyName - key value retrieved from the fetched variants
         * @param variantName - name of the variant for the key to enroll
         * @param completionCallback
         */
        public void testingEnrollIntoVariant(@Nullable String keyName, String variantName, @Nullable RCVariantCallback completionCallback) {
            synchronized (Countly.instance()) {
                L.i("[RemoteConfig] testingEnrollIntoVariant");

                if (keyName == null || variantName == null) {
                    L.w("[RemoteConfig] testEnrollIntoVariant, passed key or variant is null. Aborting.");
                    return;
                }

                if (completionCallback == null) {
                    completionCallback = (result, error) -> {
                    };
                }

                testingEnrollIntoVariantInternal(keyName, variantName, completionCallback);
            }
        }
    }
}
