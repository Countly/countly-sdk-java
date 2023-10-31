package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Migration helper class that handles migration of Countly SDK from one version to another
 * to create a new migration:
 * 1. add a new migration method:
 * - boolean "migration_X_YZ" where x is the migration name and YZ is the version
 * X should be in pascal case. YZ should be in two digits format. For example: migration_DeleteConfigFile_00
 * the method should return true if the migration was successful, false otherwise
 * 2. add it to the setupMigrations method:
 * - migrations.add(this::migrationX_YZ);
 * 3. add test cases for the migration to MigrationHelperTests.java
 */
public class MigrationHelper {
    StorageProvider storageProvider;
    private final List<Function<Map<String, Object>, Boolean>> migrations;

    //-1 - fresh install
    // 0 - the old/legacy/initial data model
    // 1 - the first migration applied
    protected int currentDataModelVersion = -1;

    //fresh installs and fully migrated setups should have the latest version at the end
    int latestMigrationVersion;
    protected Log logger;

    protected MigrationHelper(final Log logger) {
        migrations = new ArrayList<>();
        this.logger = logger;

        // add migrations below
        //migrations.add(this::migration_DeleteConfigFile_01);
        latestMigrationVersion = migrations.size();
    }

    /**
     * Set up migration version
     */
    protected void setupMigrations(final StorageProvider storageProvider) {
        this.storageProvider = storageProvider;

        currentDataModelVersion = storageProvider.getMigrationVersion();
        if (currentDataModelVersion < 0) {
            if (storageProvider.isCountlyStorageEmpty()) { // fresh install
                logger.i("[MigrationHelper] setupMigrations, Countly storage is empty, no need to migrate");
                currentDataModelVersion = latestMigrationVersion;
            } else { // legacy data model
                currentDataModelVersion = 0;
            }
        }
        logger.i("[MigrationHelper] setupMigrations, current data model version: " + currentDataModelVersion);
    }

    /**
     * Applies all migrations one by one
     *
     * @param migrationParams parameters to pass to migrations
     */
    protected void applyMigrations(final Map<String, Object> migrationParams) {
        logger.i("[MigrationHelper] applyMigrations, Applying migrations");
        migrations.forEach((migration) -> {
            if (!migration.apply(migrationParams)) {
                logger.e("[MigrationHelper] applyMigrations, Failed to apply migration version:[ " + (currentDataModelVersion + 1) + " ]");
            }
        });
        storageProvider.setMigrationVersion(currentDataModelVersion);
    }

    protected boolean migration_DeleteConfigFile_01(final Map<String, Object> migrationParams) {
        if (currentDataModelVersion >= 1) {
            logger.d("[MigrationHelper] migration_DeleteConfigFile_01, Migration already applied");
            return true;
        }
        logger.i("[MigrationHelper] migration_DeleteConfigFile_01, Deleting config file migrating from 00 to 01");
        currentDataModelVersion += 1;
        return true;
    }
}
