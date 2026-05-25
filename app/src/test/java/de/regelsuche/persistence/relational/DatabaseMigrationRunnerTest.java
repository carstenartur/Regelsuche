package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseMigrationRunnerTest {

    @Test
    void defaultMigrationsAreVersionedAndPackaged() {
        List<DatabaseMigration> migrations = DatabaseMigrationRunner.DEFAULT_MIGRATIONS;

        assertEquals(List.of(1, 2, 3), migrations.stream().map(DatabaseMigration::version).toList());
        for (DatabaseMigration migration : migrations) {
            assertNotNull(DatabaseMigrationRunner.class.getClassLoader().getResource(migration.resourcePath()),
                () -> "missing " + migration.resourcePath());
        }
    }
}
