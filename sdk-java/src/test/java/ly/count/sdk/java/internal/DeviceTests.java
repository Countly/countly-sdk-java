package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Assert;
import org.junit.Test;

public class DeviceTests {

    /**
     * Lame test for checking metric override
     */
    @Test
    public void metricOverride_1() {
        Map<String, String> newVals = new ConcurrentHashMap<>();
        newVals.put("a12345", "1qwer");
        newVals.put("b5678", "2sdfg");

        Device dev = new Device();
        dev.setMetricOverride(newVals);

        Params mParams = dev.buildMetrics();
        String sMetrics = mParams.toString();

        Assert.assertTrue(sMetrics.contains("a12345"));
        Assert.assertTrue(sMetrics.contains("1qwer"));
        Assert.assertTrue(sMetrics.contains("b5678"));
        Assert.assertTrue(sMetrics.contains("2sdfg"));
    }
}
