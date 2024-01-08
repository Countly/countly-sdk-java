package ly.count.sdk.java.internal;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ScenarioUtilsTests {

    /**
     * "safeRandomVal"
     * ### 001_validatingIDGenerator
     * <p>
     * testing the ID generator function that is used for events and views
     * <p>
     * Generate 2 values
     * <p>
     * they should be different.
     * They should be 21 chars long.
     * They should contain on base64 characters. first 8 one is base64 string and last 13 one is timestamp
     *
     * @throws NumberFormatException for parsing part 2
     */
    @Test
    public void _001_validatingIDGenerator() throws NumberFormatException {
        String val1 = Utils.safeRandomVal();
        String val2 = Utils.safeRandomVal();

        Assert.assertNotEquals(val2, val1);

        TestUtils.validateSafeRandomVal(val1);
        TestUtils.validateSafeRandomVal(val2);
    }
}
