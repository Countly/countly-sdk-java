package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Countly;
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
    final int expectedLatestSchemaVersion = 1;

    static final byte[] EXAMPLE_CONFIG = {
        -84, -19, 0, 5, 119, 78, 0, 21, 104, 116, 116, 112, 115, 58, 47, 47, 120, 120, 120, 46, 115, 101, 114, 118, 101, 114, 46, 108, 121, 0, 15, 67, 79, 85, 78, 84, 76, 89, 95, 65, 80, 80, 95, 75, 69, 89, 0, -128, 0, 126, 0, 7, 67, 111, 117, 110, 116, 108, 121, 0, 0, 0, 1, 0, 11, 106, 97, 118, 97,
        45, 110, 97, 116, 105, 118, 101, 0, 6, 50, 51, 46, 56, 46, 48, 116, 0, 4, 110, 97, 109, 101, 116, 0, 8, 49, 50, 51, 46, 53, 54, 46, 104, 119, 1, 0, 112, 119, 33, 0, 0, 0, 30, 0, 0, 0, 30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 60, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 5, 112, 119, 69, 0, 0, 0, 0, 0,
        0, 0, 1, 0, 0, 0, 57, -84, -19, 0, 5, 119, 8, 0, 0, 0, 0, 0, 0, 0, 0, 116, 0, 40, 67, 76, 89, 95, 48, 99, 53, 52, 101, 53, 101, 55, 45, 101, 98, 56, 54, 45, 52, 99, 49, 55, 45, 56, 49, 102, 48, 45, 52, 100, 55, 57, 49, 48, 100, 56, 97, 98, 48, 101
    };

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
        storageProvider = (new SDKStorage()).init(config);
    }

    /**
     * After each test, clean state
     */
    @After
    public void afterTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * "MigrationHelper" constructor
     * Validate default values
     * Current data model version should be -1, logger should not be null, storage provider should be null, latest migration version should be expected latest schema version
     */
    @Test
    public void migrationHelper_defaults() {
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        Assert.assertEquals(-1, migrationHelper.currentDataModelVersion);//validate the default version value
        Assert.assertNotNull(migrationHelper.logger);
        Assert.assertNull(migrationHelper.storageProvider);
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.latestMigrationVersion);
    }

    /**
     * "setupMigrations"
     * Fresh-install. That means nothing in storage
     * Migration version should be 1 (the latest version), before calling "applyMigrations" because the SDK is already at the latest data schema version
     */
    @Test
    public void setupMigrations_freshInstall() {
        initStorage(); // to initialize json storage
        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.currentDataModelVersion);
    }

    /**
     * "setupMigrations"
     * Upgrading from the "pre-migration" version. No new config object, just old type of data.
     * Migration version should be 0 before calling "applyMigrations"
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
        setDataVersionInConfigFile(expectedLatestSchemaVersion);
        initStorage(); // to initialize json storage after data version is set to 1

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.logger = spy(migrationHelper.logger);
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.currentDataModelVersion);

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
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(expectedLatestSchemaVersion, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
    }

    /**
     * "applyMigrations" with already at the latest version
     * All migrations are already applied, setting data version to the latest
     * Migration version should be 1 before applying migrations, and 1 after applying migrations and expected log must be logged.
     */
    @Test
    public void applyMigrations_latestToLatest() throws IOException {
        setDataVersionInConfigFile(expectedLatestSchemaVersion); //set data version to latest
        initStorage(); // to initialize json storage after data version is set to 1

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.currentDataModelVersion); //latest state
        //run migration helper apply
        migrationHelper.applyMigrations(new HashMap<>());
        //check migration version is at the latest after apply both from class and file
        Assert.assertEquals(expectedLatestSchemaVersion, migrationHelper.currentDataModelVersion);
        Assert.assertEquals(expectedLatestSchemaVersion, TestUtils.getJsonStorageProperty(SDKStorage.key_migration_version));
    }

    /**
     * "applyMigrations" from 0 to 1
     * Upgrading from legacy state to the latest version, mock config file, just old type of data.
     * Data version must be 1 after applying migrations and expected log must be logged. and expected device id type must match
     */
    @Test
    public void applyMigrations_0to1() throws IOException {
        Files.write(TestUtils.createFile("config_0").toPath(), EXAMPLE_CONFIG); //mock a sdk config file, to simulate storage is not empty
        initStorage(); // to initialize json storage after mock sdk file is created

        MigrationHelper migrationHelper = new MigrationHelper(mock(Log.class));
        migrationHelper.setupMigrations(storageProvider);
        Assert.assertEquals(0, migrationHelper.currentDataModelVersion); //legacy state

        migrationHelper.logger = Mockito.spy(migrationHelper.logger);
        //run migration helper
        Assert.assertNull(storageProvider.getDeviceID());
        Assert.assertNull(storageProvider.getDeviceIdType());
        Map<String, Object> migrationParams = new HashMap<>();
        migrationParams.put("sdk_path", TestUtils.getTestSDirectory());
        Assert.assertTrue(migrationHelper.migration_DeleteConfigFile_01(migrationParams));

        Assert.assertEquals("CLY_0c54e5e7-eb86-4c17-81f0-4d7910d8ab0e", storageProvider.getDeviceID());
        Assert.assertEquals(DeviceIdType.SDK_GENERATED.name(), storageProvider.getDeviceIdType());

        //check migration version is at the latest after apply both from class and file
        Assert.assertEquals(1, migrationHelper.currentDataModelVersion);
        Mockito.verify(migrationHelper.logger, Mockito.times(1)).i("[MigrationHelper] migration_DeleteConfigFile_01, Deleting config file migrating from 00 to 01");
    }

    /**
     * "applyMigrations" from 0 to 1 by init Countly
     * Upgrading from legacy state to the latest version, mock config file, just old type of data.
     * Data version must be 1 after applying migrations and expected log must be logged. and expected device id type must match
     */
    @Test
    public void applyMigrations_0to1_initCountly() throws IOException {
        Files.write(TestUtils.createFile("config_0").toPath(), EXAMPLE_CONFIG); //mock a sdk config file, to simulate storage is not empty
        Countly.instance().init(TestUtils.getBaseConfig(null));
        Assert.assertEquals("CLY_0c54e5e7-eb86-4c17-81f0-4d7910d8ab0e", Countly.instance().getDeviceId());
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().getDeviceIdType());
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
