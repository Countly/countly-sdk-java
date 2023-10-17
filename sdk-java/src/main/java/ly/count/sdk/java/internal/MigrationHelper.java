package ly.count.sdk.java.internal;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

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
    private final List<Supplier<Boolean>> migrations;
    protected int appliedMigrationVersion = -1;
    protected Log logger;

    protected MigrationHelper(Log logger) {
        migrations = new LinkedList<>();
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
        logger.i("[MigrationHelper] setupMigrations, Applied migration version: " + appliedMigrationVersion);
        // add migrations below
        migrations.add(this::migration_DeleteConfigFile_00);
    }

    /**
     * Applies all migrations one by one
     */
    protected void applyMigrations() {
        logger.i("[MigrationHelper] applyMigrations, Applying migrations");
        migrations.forEach((migration) -> {
            if (!migration.get()) {
                logger.e("[MigrationHelper] applyMigrations, Failed to apply migration version:[ " + (appliedMigrationVersion + 1) + " ]");
            }
        });
        internalConfig.storageProvider.setMigrationVersion(appliedMigrationVersion);
    }

    protected boolean migration_DeleteConfigFile_00() {
        if (appliedMigrationVersion >= 0) {
            logger.d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
            return true;
        }
        logger.i("[MigrationHelper] migration_DeleteConfigFile_00, Deleting config file migrating from 00 to 01");
        appliedMigrationVersion += 1;
        return true;
    }
}
