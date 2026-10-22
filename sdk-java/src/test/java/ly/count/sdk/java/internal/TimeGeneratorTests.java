package ly.count.sdk.java.internal;

import org.junit.Assert;
import org.junit.Test;

public class TimeGeneratorTests {
    @Test
    public void testAsIs() {
        UniqueTimeGenerator simulator = new UniqueTimeGenerator();

        long last = simulator.timestamp();

        for (int i = 0; i < 10_000; i++) {
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }
    }

    @Test
    public void testMidTimeChange() {
        UniqueTimeGenerator simulator = new UniqueTimeGenerator();

        long last = simulator.timestamp();

        for (int i = 0; i < 100; i++) {
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }

        simulator.addition = -10_000;

        for (int i = 0; i < 100; i++) {
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }

        simulator.addition = 0;

        for (int i = 0; i < 100; i++) {
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }

        simulator.addition = 10_000;

        for (int i = 0; i < 100; i++) {
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }
    }

    @Test
    public void testMidTimeRandomChange() {
        UniqueTimeGenerator simulator = new UniqueTimeGenerator();

        long last = simulator.timestamp();

        for (int i = 0; i < 100_000; i++) {
            if (i % 30 == 0) {
                simulator.addition = Math.round(Math.random() * 10_000 - 5000);
            }
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }

        simulator.addition = 0;

        for (int i = 0; i < 100_000; i++) {
            if (i % 30 == 0) {
                simulator.addition += Math.round(Math.random() * 1000 - 500);
            }
            long next = simulator.timestamp();
            Assert.assertNotSame(last, next);
        }
    }

    /**
     * Ids drawn faster than the clock moves, and across clock jumps backwards and forwards, are
     * strictly increasing, so no two stored objects ever share an id.
     */
    @Test
    public void id_neverRepeatsOrGoesBack() {
        UniqueTimeGenerator simulator = new UniqueTimeGenerator();

        long last = simulator.id();
        for (int i = 0; i < 100_000; i++) {
            if (i % 30 == 0) {
                simulator.addition = Math.round(Math.random() * 10_000 - 5000);
            }
            long next = simulator.id();
            Assert.assertTrue(next + " is not greater than the previous id " + last, next > last);
            last = next;
        }
    }

    /**
     * Once the clock has moved past the last id, the next id is the current time again, so ids
     * keep dating the objects they name.
     */
    @Test
    public void id_followsTheClockOnceItIsAhead() {
        UniqueTimeGenerator simulator = new UniqueTimeGenerator();

        long first = simulator.id();
        simulator.addition = 60_000;
        long later = simulator.id();

        Assert.assertTrue(later >= first + 60_000);
        Assert.assertTrue(later <= System.currentTimeMillis() + 60_000);
    }
}
