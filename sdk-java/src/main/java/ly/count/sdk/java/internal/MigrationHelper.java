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
    InternalConfig internalConfig;
    private final List<Function<Map<String, Object>, Boolean>> migrations;
    protected int appliedMigrationVersion = -1;
    protected Log logger;

    protected MigrationHelper(Log logger) {
        migrations = new ArrayList<>();
        this.logger = logger;
    }

    /**
     * Set up migration version, register migrations
     *
     * @param internalConfig to configure
     */
    protected void setupMigrations(InternalConfig internalConfig) {
        this.internalConfig = internalConfig;

        appliedMigrationVersion = internalConfig.storageProvider.getMigrationVersion();
        if (appliedMigrationVersion < 0) {
            if (internalConfig.storageProvider.isCountlyStorageEmpty()) {
                logger.i("[MigrationHelper] setupMigrations, Countly storage is empty, no need to migrate");
                return;
            }
        }
        logger.i("[MigrationHelper] setupMigrations, Applied migration version: " + appliedMigrationVersion);
        // add migrations below
        migrations.add(this::migration_DeleteConfigFile_00);
    }

    /**
     * Applies all migrations one by one
     *
     * @param migrationParams parameters to pass to migrations
     */
    protected void applyMigrations(Map<String, Object> migrationParams) {
        logger.i("[MigrationHelper] applyMigrations, Applying migrations");
        migrations.forEach((migration) -> {
            if (!migration.apply(migrationParams)) {
                logger.e("[MigrationHelper] applyMigrations, Failed to apply migration version:[ " + (appliedMigrationVersion + 1) + " ]");
            }
        });
        internalConfig.storageProvider.setMigrationVersion(appliedMigrationVersion);
    }

    protected boolean migration_DeleteConfigFile_00(Map<String, Object> migrationParams) {
        if (appliedMigrationVersion >= 0) {
            logger.d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
            return true;
        }
        logger.i("[MigrationHelper] migration_DeleteConfigFile_00, Deleting config file migrating from 00 to 01");
        appliedMigrationVersion += 1;
        return true;
    }
}
