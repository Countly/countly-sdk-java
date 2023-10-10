package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
    protected static final String MIGRATION_VERSION_FILE_NAME = "migration_version";
    protected CtxCore ctx;
    private final List<Supplier<Boolean>> migrations;
    protected int appliedMigrationVersion = -1;
    protected Log logger;

    protected MigrationHelper(Log logger) {
        migrations = new LinkedList<>();
        this.logger = logger;
    }

    protected void setupMigrations(CtxCore ctx) {
        this.ctx = ctx;
        readMigrationVersion();
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

    private void readMigrationVersion() {
        logger.i("[MigrationHelper] readMigrationVersion, Reading migration version");
        File file = new File(ctx.getSdkStorageRootDirectory(), SDKStorage.FILE_NAME_PREFIX + SDKStorage.FILE_NAME_SEPARATOR + MIGRATION_VERSION_FILE_NAME);

        try {
            int version = Integer.parseInt(Utils.readFileContent(file, logger));
            if (version > -1) {
                logger.i("[MigrationHelper] readMigrationVersion, Read migration version:[ " + version + " ]");
                appliedMigrationVersion = version;
            }
        } catch (Exception e) {
            logger.e("[MigrationHelper] readMigrationVersion, Failed to read migration version, error:[ " + e.getMessage() + " ]");
        }
    }

    protected void updateMigrationVersion() {
        logger.i("[MigrationHelper] updateMigrationVersion, Updating migration version to version:[ " + appliedMigrationVersion + " ]");
        File file = new File(ctx.getSdkStorageRootDirectory(), SDKStorage.FILE_NAME_PREFIX + SDKStorage.FILE_NAME_SEPARATOR + MIGRATION_VERSION_FILE_NAME);

        try { // Write the version to the file
            writeVersionToFile(file);
            logger.v("[MigrationHelper] writeFileContent, Wrote applied migration version to file");
        } catch (IOException e) {
            // Handle the error if writing fails
            logger.e("[MigrationHelper] writeFileContent, Failed to write applied migration version to file: " + e.getMessage());
        }
    }

    protected void writeVersionToFile(File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(appliedMigrationVersion + "\n");
        }
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
