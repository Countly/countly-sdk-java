package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class MigrationHelperTests {

    Log L = mock(Log.class);
    MigrationHelper migrationHelper;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        migrationHelper = new MigrationHelper(L);
    }

    /**
     * "setupMigrations" with no migration done
     * receives mock context to set up migrations
     * migration version should be -1 because no migration was done
     */
    @Test
    public void setupMigrations_migrationFileNotExist() {
        validateMigrationVersionAndSetup(-1, false);
    }

    /**
     * "setupMigrations"
     * receives mock context to set up migrations
     * migration version should be 0 because first migration done previously
     */
    @Test
    public void setupMigrations() {
        validateMigrationVersionAndSetup(0, true);
    }

    /**
     * "applyMigrations" with no migration applied
     * receives mock context to set up migrations and no migration is applied
     * migration version should be -1 first, after migrations applied it should be 0
     */
    @Test
    public void applyMigrations_noMigrationApplied() {
        //check migration version is -1 before and after read because no migration was applied
        validateMigrationVersionAndSetup(-1, false);

        //run migration helper apply
        migrationHelper.applyMigrations();
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(0, migrationHelper.appliedMigrationVersion);
        Assert.assertEquals("0", TestUtils.getCurrentMV());
    }

    /**
     * "applyMigrations" with already applied migration
     * receives mock context to set up migrations and migration file applied version is 0
     * migration version should be 0 first, after migrations applied it should not be change
     * and logger should log the expected log
     */
    @Test
    public void applyMigrations_migrationAlreadyApplied() {
        validateMigrationVersionAndSetup(0, true);

        migrationHelper.logger = spy(migrationHelper.logger);
        //run migration helper apply
        migrationHelper.applyMigrations();
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(0, migrationHelper.appliedMigrationVersion);
        Assert.assertEquals("0", TestUtils.getCurrentMV());
        verify(migrationHelper.logger, times(1)).d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
    }

    /**
     * "updateMigrationVersion" with IO exception
     * receives mock context and simulated function to throw IOException
     * logger should log expected log
     */
    @Test
    public void updateMigrationVersion_IOException() throws IOException {
        //prepare mock object
        migrationHelper = spy(migrationHelper);
        migrationHelper.ctx = TestUtils.getMockCtxCore();
        //simulate function to throw exception
        doThrow(new IOException("Simulated IOException")).when(migrationHelper).writeVersionToFile(any(File.class));
        // Call the method that you want to test
        migrationHelper.updateMigrationVersion();

        // Verify that the logger's error method was called with the expected message
        verify(migrationHelper.logger).e("[MigrationHelper] writeFileContent, Failed to write applied migration version to file: Simulated IOException");
    }

    private void writeToMvFile(Integer version) {
        File file = new File(TestUtils.getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + MigrationHelper.MIGRATION_VERSION_FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(String.valueOf(version));
        } catch (IOException ignored) {
            //do nothing
        }
    }

    private void validateMigrationVersionAndSetup(Integer version, boolean isApplied) {
        Assert.assertEquals(-1, migrationHelper.appliedMigrationVersion);
        if (isApplied) {
            writeToMvFile(version);
            Assert.assertEquals(version.toString(), TestUtils.getCurrentMV());
        }
        migrationHelper.setupMigrations(TestUtils.getMockCtxCore());
        Assert.assertEquals(version, new Integer(migrationHelper.appliedMigrationVersion));
    }
}
