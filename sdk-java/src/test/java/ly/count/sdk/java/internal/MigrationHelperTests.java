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
import static org.mockito.Mockito.spy;

@RunWith(JUnit4.class)
public class MigrationHelperTests {
    SDKStorage storageProvider;

    /**
     * Before the tests, clean the test state
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * This should be called manually before writing to json store,
     * because json store is read when storage is initialized
     * and it is read once
     */
    private void initStorage() {
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
    public void migrationHelper_defaults() {
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        Assert.assertEquals(-1, migrationHelper.currentDataModelVersion);//validate the default version value
        Assert.assertNotNull(migrationHelper.logger);
        Assert.assertNull(migrationHelper.storageProvider);
        Assert.assertEquals(1, migrationHelper.latestMigrationVersion);
    }

    /**
     * "setupMigrations"
     * Fresh-install. That means nothing in storage
     * Migration version should be 1 (latest version) before applying any migrations because the SDK is already at the latest data schema version
     */
    @Test
    public void setupMigrations_freshInstall() {
        initStorage(); // to initialize json storage
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
    }

    /**
     * "setupMigrations"
     * Upgrading from the "pre-migration" version. No new config object, just old type of data.
     * Migration version should be 0 before applying any migrations.
     */
    @Test
    public void setupMigrations_legacyState() {
        TestUtils.createFile("test"); //mock a sdk file, to simulate storage is not empty
        initStorage(); // to initialize json storage after mock sdk file is created

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(0, migrationHelper.currentDataModelVersion);
    }

    /**
     * "setupMigrations"
     * Already in the latest version.
     * Migration version should be at the latest version before applying any migrations.
     */
    @Test
    public void setupMigrations_latestVersion() throws IOException {
        setDataVersionInConfigFile(1);
        initStorage(); // to initialize json storage after data version is set to 1

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.logger = spy(migrationHelper.logger);
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);

        Mockito.verify(migrationHelper.logger, Mockito.never()).i("[MigrationHelper] setupMigrations, Countly storage is empty, no need to migrate");
    }

    /**
     * "applyMigrations" in a legacy state
     * Upgrading from legacy state to latest version, no new config object, just old type of data.
     * Migration version should be 0 before applying migrations, and 1 after applying migrations.
     */
    @Test
    public void applyMigrations_legacyToLatest() {
        TestUtils.createFile("test"); //mock a sdk file, to simulate storage is not empty
        initStorage(); // to initialize json storage after mock sdk file is created

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(0, migrationHelper.currentDataModelVersion); //legacy state

        //apply migrations
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is 1 after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(1, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
    }

    /**
     * "applyMigrations" with already at the latest version
     * All migrations are already applied, setting data version to latest
     * Migration version should be 1 before applying migrations, and 1 after applying migrations and expected log must be logged.
     */
    @Test
    public void applyMigrations_latestToLatest() throws IOException {
        setDataVersionInConfigFile(1); //set data version to latest
        initStorage(); // to initialize json storage after data version is set to 1

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion); //latest state

        migrationHelper.logger = Mockito.spy(migrationHelper.logger);
        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is at the latest after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(1, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
        Mockito.verify(migrationHelper.logger, Mockito.times(1)).d("[MigrationHelper] migration_DeleteConfigFile_01, Migration already applied");
    }

    /**
     * "applyMigrations" from 0 to 1
     * Upgrading from legacy state to latest version, mock config file, just old type of data.
     * Data version must be 1 after applying migrations and expected log must be logged.
     */
    @Test
    public void applyMigrations_0to1() {
        TestUtils.createFile("config"); //mock a sdk file, to simulate storage is not empty
        initStorage(); // to initialize json storage after mock sdk file is created

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(0, migrationHelper.currentDataModelVersion); //legacy state

        migrationHelper.logger = Mockito.spy(migrationHelper.logger);
        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is at the latest after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(1, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
        Mockito.verify(migrationHelper.logger, Mockito.times(1)).i("[MigrationHelper] migration_DeleteConfigFile_01, Deleting config file migrating from 00 to 01");
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
