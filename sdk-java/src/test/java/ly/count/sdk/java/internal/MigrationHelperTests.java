package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import org.json.JSONException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class MigrationHelperTests {

    Log L = mock(Log.class);
    MigrationHelper migrationHelper = new MigrationHelper(L);

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "setupMigrations" with no migration done
     * receives mock context to set up migrations
     * migration version should be -1 because no migration was done
     */
    @Test
    public void setupMigrations() throws JSONException {
        Assert.assertEquals(-1, migrationHelper.appliedMigrationVersion);
        migrationHelper.setupMigrations(TestUtils.getMockCtxCore());
        Assert.assertEquals(-1, migrationHelper.appliedMigrationVersion);
    }
}
