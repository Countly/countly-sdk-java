package ly.count.sdk.java.internal;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/**
 * Async task for making immediate server requests
 */
class ImmediateRequestMaker implements ImmediateRequestI {

    private InternalImmediateRequestCallback callback;
    private Log L;

    @Override
    public void doWork(String requestData, String customEndpoint, Transport cp, boolean requestShouldBeDelayed, boolean networkingIsEnabled, InternalImmediateRequestCallback callback, Log log) {
        CompletableFuture.supplyAsync(() -> doInBackground(requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log))
            .thenAcceptAsync(this::onFinished);
    }

    /**
     * Used for callback from async task
     */
    protected interface InternalImmediateRequestCallback {
        void callback(JSONObject checkResponse);
    }

    /**
     * params fields:
     * 0 - requestData
     * 1 - custom endpoint
     * 2 - connection processor
     * 3 - requestShouldBeDelayed
     * 4 - networkingIsEnabled
     * 5 - callback
     * 6 - log module
     */
    private JSONObject doInBackground(String requestData, String customEndpoint, Transport cp, boolean requestShouldBeDelayed, boolean networkingIsEnabled, InternalImmediateRequestCallback callback, Log log) {
        this.callback = callback;
        L = log;
        if (!networkingIsEnabled) {
            L.w("[ImmediateRequestMaker] ImmediateRequestMaker, Networking config is disabled, request cancelled. Endpoint[" + customEndpoint + "] request[" + requestData + "]");

            return null;
        }

        L.v("[ImmediateRequestMaker] Starting request");

        HttpURLConnection connection = null;

        try {
            L.d("[ImmediateRequestMaker] delayed[" + requestShouldBeDelayed + "] hasCallback[" + (callback != null) + "] endpoint[" + customEndpoint + "] request[" + requestData + "]");

            if (requestShouldBeDelayed) {
                //used in cases after something has to be done after a device id change
                L.v("[ImmediateRequestMaker] request should be delayed, waiting for 0.5 seconds");

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    L.w("[ImmediateRequestMaker] While waiting for 0.5 seconds in ImmediateRequestMaker, sleep was interrupted");
                }
            }
            Request request = new Request();
            request.params.add(requestData);
            request.endpoint(customEndpoint);
            //getting connection ready
            try {
                connection = cp.connection(request);
            } catch (IOException e) {
                L.e("[ImmediateRequestMaker] IOException while preparing remote config update request :[" + e + "]");
                return null;
            }
            //connecting
            connection.connect();

            int code = connection.getResponseCode();
            String receivedBuffer = cp.response(connection);

            if (receivedBuffer == null) {
                L.e("[ImmediateRequestMaker] Encountered problem while making a immediate server request, received result was null");
                return null;
            }

            if (code >= 200 && code < 300) {
                L.d("[ImmediateRequestMaker] Received the following response, :[" + receivedBuffer + "]");

                // we check if the result was a json array or json object and convert the array into an object if necessary
                if (receivedBuffer.trim().charAt(0) == '[') {
                    return new JSONObject("{\"jsonArray\":" + receivedBuffer + "}");
                }
                return new JSONObject(receivedBuffer);
            } else {
                L.e("[ImmediateRequestMaker] Encountered problem while making a immediate server request, :[" + receivedBuffer + "]");
                return null;
            }
        } catch (Exception e) {
            L.e("[ImmediateRequestMaker] Received exception while making a immediate server request " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        L.v("[ImmediateRequestMaker] Finished request");
        return null;
    }

    private void onFinished(JSONObject result) {
        L.v("[ImmediateRequestMaker] onPostExecute");
        if (callback != null) {
            callback.callback(result);
        }
    }
}