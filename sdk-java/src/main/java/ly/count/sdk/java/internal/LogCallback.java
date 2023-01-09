package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

public interface LogCallback {
    void LogHappened(String logMessage, Config.LoggingLevel logLevel);
}
