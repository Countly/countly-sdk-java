package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static ly.count.sdk.java.internal.SDKStorage.JSON_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.migration_version_key;
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
        TestUtils.createFile("test"); //mock old config file, to simulate migration needed
        //check migration version is -1 before and after read because no migration was applied
        validateMigrationVersionAndSetup(-1, false);

        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(0, migrationHelper.appliedMigrationVersion);
        Assert.assertEquals(0, TestUtils.getJsonStorageProperty(migration_version_key));
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
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(0, migrationHelper.appliedMigrationVersion);
        Assert.assertEquals(0, TestUtils.getJsonStorageProperty(migration_version_key));
        verify(migrationHelper.logger, times(1)).d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
    }

    private void writeToMvFile(Integer version) {
        TestUtils.checkSdkStorageRootDirectoryExist(TestUtils.getTestSDirectory());
        File file = new File(TestUtils.getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + JSON_FILE_NAME);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            file.createNewFile();
            writer.write(TestUtils.readJsonFile(file).put("mv", version).toString());
        } catch (IOException e) {
            Assert.fail("Failed to write migration version to file: " + e.getMessage());
        }
    }

    private void validateMigrationVersionAndSetup(Integer version, boolean isApplied) {
        Assert.assertEquals(-1, migrationHelper.appliedMigrationVersion);
        if (isApplied) {
            writeToMvFile(version);
            Assert.assertEquals(version, TestUtils.getJsonStorageProperty(migration_version_key));
        }

        initMigrationHelper();
        Assert.assertEquals(version, new Integer(migrationHelper.appliedMigrationVersion));
    }

    private void initMigrationHelper() {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig());
        config.setLogger(new Log(Config.LoggingLevel.OFF, null));
        SDKStorage sdkStorage = new SDKStorage();
        sdkStorage.init(config, config.getLogger());
        config.storageProvider = sdkStorage;

        migrationHelper.setupMigrations(config);
    }
}
