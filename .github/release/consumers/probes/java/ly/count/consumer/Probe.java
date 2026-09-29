package ly.count.consumer;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

/**
 * Loads the core SDK the way an integrator would, without contacting a server.
 */
public class Probe {
    /**
     * Creates a configuration and reads the shared instance; a class the runtime cannot load stops here.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        Config config = new Config("https://consumer.invalid", "consumer-probe");
        System.out.println("Countly " + config.getSdkVersion() + " loaded on Java " + System.getProperty("java.version")
            + ", instance " + Countly.instance().getClass().getName() + ", initialized " + Countly.isInitialized());
    }
}
