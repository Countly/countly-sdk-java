package ly.count.sdk.java.backend;

import ly.count.sdk.java.backend.controller.RequestController;
import ly.count.sdk.java.backend.helper.ClyLogger;
import ly.count.sdk.java.backend.model.Configuration;
import ly.count.sdk.java.backend.module.*;

import java.util.Map;


public class BackendMood implements ClyLogger.ILogger {
    private final ClyLogger logger;
    private final ViewsModule viewsModule;
    private final CrashModule crashModule;
    private final EventModule eventsModule;
    private final DeviceModule deviceModule;
    private final UserDetailModule userDetail;
    private final SessionsModule sessionsModule;
    private final LocationModule locationModule;

    //final ConsentController consentModule;
    private final RequestController requestsController;

    public BackendMood() {
        logger = new ClyLogger(this);
        requestsController = new RequestController(logger);

        viewsModule = new ViewsModule(requestsController, logger);
        crashModule = new CrashModule(requestsController, logger);
        eventsModule = new EventModule(requestsController, logger);
        deviceModule = new DeviceModule(requestsController, logger);
        userDetail = new UserDetailModule(requestsController, logger);
        sessionsModule = new SessionsModule(requestsController, logger);
        locationModule = new LocationModule(requestsController, logger);
    }

    public void init(Configuration configuration) {
        String serverURL = configuration.getServerURL();
        if (serverURL != null && serverURL.length() > 0 && serverURL.charAt(serverURL.length() - 1) == '/') {
            configuration.setServerURL(serverURL.substring(0, serverURL.length() - 1));
        }

    }

    public void recordView(String deviceID, String key, Map<String, String> segmentation, long timestamp) {

    }

    public void recordEvent(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {

    }

    public void sessionBegin(String deviceID, long timestamp) {

    }

    public void sessionUpdate(String deviceID, double duration, long timestamp) {

    }

    public void sessionEnd(String deviceID, String duration, long timestamp) {

    }

    public void recordException(String deviceID, Throwable stacktrace, Map<String, String> segmentation, long timestamp) {

    }

    public void recordException(String deviceID, String exceptionMessage, String exceptionStacktrace, Map<String, String> segmentation, long timestamp) {

    }

    public void recordUserProperties(String deviceID, Map<String, String> userProperties) {

    }

    @Override
    public void print(ClyLogger.LogLevel level, String message) {
        String l = "[DEBUG]";
        switch (level) {
            case INFO:
                l = "[INFO]";
                break;
            case WARNING:
                l = "[WARNING]";
                break;
            case ERROR:
                l = "[ERROR]";
                break;
            case DEBUG:
                l = "[DEBUG]";
                break;
        }
        System.out.println(l + "\t" + "[Countly]" + "\t" + message);
    }
}
