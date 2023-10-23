package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import ly.count.sdk.java.Config;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class MigrationHelperTests {

    MigrationHelper migrationHelper;

    /**
     * Before the tests, clean the test state
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        migrationHelper = new MigrationHelper(Mockito.mock(Log.class));
    }

    /**
     * After each test, clean state
     */
    @After
    public void afterTest() {
        TestUtils.createCleanTestState();
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
        Assert.assertEquals(0, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
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

        migrationHelper.logger = Mockito.spy(migrationHelper.logger);
        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(0, migrationHelper.appliedMigrationVersion);
        Assert.assertEquals(0, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
        Mockito.verify(migrationHelper.logger, Mockito.times(1)).d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
    }

    private void writeToMvFile(final Integer version) {
        TestUtils.checkSdkStorageRootDirectoryExist(TestUtils.getTestSDirectory());
        File file = TestUtils.createFile(SDKStorage.JSON_FILE_NAME);

        try {
            Assert.assertNotNull(file);
            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
                writer.write(TestUtils.readJsonFile(file).put("mv", version).toString());
            }
        } catch (IOException e) {
            Assert.fail("Failed to write migration version to file: " + e.getMessage());
        }
    }

    private void validateMigrationVersionAndSetup(final Integer version, final boolean isApplied) {
        Assert.assertEquals(-1, migrationHelper.appliedMigrationVersion);
        if (isApplied) {
            writeToMvFile(version);
            Assert.assertEquals(version, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
        }

        initMigrationHelper();
        Assert.assertEquals(version, Integer.valueOf(migrationHelper.appliedMigrationVersion));
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
