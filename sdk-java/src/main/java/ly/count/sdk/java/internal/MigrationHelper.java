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
 */
public class MigrationHelper {
    private final List<Supplier<Boolean>> migrations;
    private int appliedMigrationVersion = -1;
    private final Log logger;

    protected MigrationHelper(Log logger) {
        migrations = new LinkedList<>();
        this.logger = logger;
    }

    protected void setupMigrations() {
        appliedMigrationVersion = readMigrationVersion();
        logger.i("[MigrationHelper] setupMigrations, Applied migration version: " + appliedMigrationVersion);
        // add migrations below
        migrations.add(this::migration_DeleteConfigFile_00);
    }

    protected void applyMigrations() throws IllegalStateException {
        logger.i("[MigrationHelper] applyMigrations, Applying migrations");
        migrations.forEach((migration) -> {
            if (migration.get()) {
                updateMigrationVersion();
            } else {
                logger.e("[MigrationHelper] applyMigrations, Failed to apply migration version:[ " + (appliedMigrationVersion + 1) + " ]");
                throw new IllegalStateException("[MigrationHelper] applyMigrations, Failed to apply migration version:[ " + (appliedMigrationVersion + 1) + " ]");
            }
        });
    }

    private int readMigrationVersion() {
        logger.i("[MigrationHelper] readMigrationVersion, Reading migration version");

        try {
            int version = Integer.parseInt(SDKCore.instance.sdkStorage.readMigrationVersion());
            if (version > -1) {
                logger.i("[MigrationHelper] readMigrationVersion, Read migration version:[ " + version + " ]");
                return version;
            }
        } catch (Exception e) {
            logger.e("[MigrationHelper] readMigrationVersion, Failed to read migration version, error:[ " + e + " ]");
        }

        return -1;
    }

    private void updateMigrationVersion() {
        logger.i("[MigrationHelper] updateMigrationVersion, Updating migration version to version:[ " + appliedMigrationVersion + " ]");
        SDKCore.instance.sdkStorage.storeMigrationVersion(appliedMigrationVersion);
    }

    private boolean migration_DeleteConfigFile_00() {
        if (appliedMigrationVersion >= 0) {
            logger.d("[MigrationHelper] migration_DeleteConfigFile_00, Migration already applied");
            return true;
        }
        logger.i("[MigrationHelper] migration_DeleteConfigFile_00, Deleting config file migrating from 00 to 01");
        appliedMigrationVersion += 1;
        return true;
    }
}
