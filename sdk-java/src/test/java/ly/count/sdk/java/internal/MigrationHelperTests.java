package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class MigrationHelperTests {
    SDKStorage storageProvider;

    /**
     * Before the tests, clean the test state
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();

        //setup the wider config
        InternalConfig config = (new InternalConfig(TestUtils.getBaseConfig()));
        config.setLogger(mock(Log.class));
        storageProvider = (new SDKStorage()).init(config, config.getLogger());
    }

    /**
     * After each test, clean state
     */
    @After
    public void afterTest() {
        TestUtils.createCleanTestState();
    }

    @Test
    public void MigrationHelper_defaults() {
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        Assert.assertEquals(-1, migrationHelper.currentDataModelVersion);//validate the default version value
        Assert.assertNotNull(migrationHelper.logger);
        Assert.assertNull(migrationHelper.storageProvider);
        Assert.assertEquals(1, migrationHelper.latestMigrationVersion);
    }

    //let's do 3 tests where we verify the initial version acquisition:
    // fresh install
    // legacy data model
    // latest version

    //then apply migration in 2 scenarios and verify the final version
    // going from legacy to the latest one
    // being at the latest one and still remaining at the latest one

    //if we are at the latest version, no migrations should run
    //rework the file checking heuristic

    //then we need tests for the specific migration transition from 0 -> 1
    //we wanna setup a couple of scenarios that would represent version 0 and then verify that all required steps are done

    /**
     * "setupMigrations"
     * Fresh install. That means nothing in storage
     * Migration version should be 1 (latest version) before applying any migrations because the SDK is already at the latest data schema version
     */
    @Test
    public void setupMigrations_migrationFileNotExist() {
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
    }

    /**
     * "setupMigrations"
     * Upgrading from the "pre-migration" version. No new config object, just old type of data.
     * Migration version should be 0 before applying any migrations. After applying migrations we get to 1
     */
    @Test
    public void setupMigrations() {
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
    }

    /**
     * "applyMigrations" with no migration applied
     * receives mock context to set up migrations and no migration is applied
     * migration version should be -1 first, after migrations applied it should be 0
     */
    @Test
    public void applyMigrations_noMigrationApplied() throws IOException {
        TestUtils.createFile("test"); //mock old config file, to simulate migration needed
        //check migration version is -1 before and after read because no migration was applied
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(0, migrationHelper.currentDataModelVersion);

        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(1, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
    }

    /**
     * "applyMigrations" with already applied migration
     * receives mock context to set up migrations and migration file applied version is 0
     * migration version should be 0 first, after migrations applied it should not be change
     * and logger should log the expected log
     */
    @Test
    public void applyMigrations_migrationAlreadyApplied() throws IOException {
        setDataVersionInConfigFile(1);

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);

        migrationHelper.logger = Mockito.spy(migrationHelper.logger);
        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 0 after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(1, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
        Mockito.verify(migrationHelper.logger, Mockito.times(1)).d("[MigrationHelper] migration_DeleteConfigFile_01, Migration already applied");
    }

    void setDataVersionInConfigFile(final int targetDataVersion) throws IOException {
        //prepare storage in case we need to
        TestUtils.checkSdkStorageRootDirectoryExist(TestUtils.getTestSDirectory());
        File file = TestUtils.createFile(SDKStorage.JSON_FILE_NAME);

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
            writer.write(TestUtils.readJsonFile(file).put("dv", targetDataVersion).toString());
        }

        Assert.assertEquals(targetDataVersion, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
    }
}
