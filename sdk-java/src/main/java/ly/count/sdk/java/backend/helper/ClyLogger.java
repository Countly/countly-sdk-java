package ly.count.sdk.java.backend.helper;

import ly.count.sdk.java.Config;

public class ClyLogger {
    public interface ILogger {
        void print(LogLevel level, String message);
    }

    public enum LogLevel {
        INFO,
        ERROR,
        DEBUG,
        WARNING,
    }
    private ILogger logger;

    public ClyLogger(ILogger logger) {
        this.logger = logger;
    }

    public void print(ClyLogger.LogLevel level, String message) {
        logger.print(level, message);
    }
}
