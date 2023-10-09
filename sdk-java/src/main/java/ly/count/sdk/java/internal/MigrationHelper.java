package ly.count.sdk.java.internal;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Migration helper class that handles migration of Countly SDK from one version to another
 * to create a new migration:
 * 1. add a new migration method:
 * - boolean "migrationX_YZ" where x is the migration name and YZ is the version
 * X sho
 * 2. add it to the setupMigrations method:
 * - migrations.add(this::migrationX_YZ);
 */
public class MigrationHelper {

    private final List<Supplier<Boolean>> migrations;
    private int appliedMigrationVersion = -1;
    private Log logger;

    protected MigrationHelper(Log logger) {
        migrations = new LinkedList<>();
        this.logger = logger;
    }

    protected void setupMigrations() {
        appliedMigrationVersion = readMigrationVersion();
    }

    protected void applyMigrations() throws IllegalStateException {
        migrations.forEach((migration) -> {
            if (migration.get()) {
                updateMigrationVersion();
            } else {
                logger.e("[MigrationHelper] applyMigrations, Failed to apply migration, exiting");
                throw new IllegalStateException("[MigrationHelper] applyMigrations, Failed to apply migration " + appliedMigrationVersion + 1);
            }
        });
    }

    private int readMigrationVersion() {
        return -1;
    }

    private void updateMigrationVersion() {
    }

    private boolean migrationDeleteConfigFile_00() {
        logger.i("[Migration] 00");
        return true;
    }
}
