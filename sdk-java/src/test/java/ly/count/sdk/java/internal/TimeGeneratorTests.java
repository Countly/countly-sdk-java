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
}
