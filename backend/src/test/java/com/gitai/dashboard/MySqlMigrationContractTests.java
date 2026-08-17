package com.gitai.dashboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the production MySQL migration set intentionally separate from the H2 demo/test migrations.
 * The project cannot safely change the historical H2 files because Flyway checks their checksums.
 */
class MySqlMigrationContractTests {

    private static final Path H2_MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path MYSQL_MIGRATIONS = Path.of("src/main/resources/db/migration-mysql");

    @Test
    void mysqlMigrationVersionsMatchTheMainMigrationHistory() throws IOException {
        assertEquals(migrationNames(H2_MIGRATIONS), migrationNames(MYSQL_MIGRATIONS),
                "MySQL and H2 migration version/name lists must stay aligned");
    }

    @Test
    void mysqlBaselineUsesExplicitProductionTableOptionsAndNoDevelopmentRepositoryPaths() throws IOException {
        String baseline = read("V1__baseline.sql");
        String allMigrations = migrationNames(MYSQL_MIGRATIONS).stream()
                .map(this::readUnchecked)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(baseline.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"));
        assertFalse(allMigrations.contains("D:/code/"));
        assertFalse(allMigrations.contains("test-repos"));
        assertFalse(allMigrations.contains("CURRENT_DATE -"));
    }

    @Test
    void mysqlProfileSelectsTheDedicatedMigrationSet() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertTrue(applicationYaml.contains("on-profile: mysql"));
        assertTrue(applicationYaml.contains("locations: classpath:db/migration-mysql"));
        assertTrue(applicationYaml.contains("connectionCollation=utf8mb4_0900_ai_ci"));
    }

    private List<String> migrationNames(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private String read(String name) throws IOException {
        return Files.readString(MYSQL_MIGRATIONS.resolve(name), StandardCharsets.UTF_8);
    }

    private String readUnchecked(String name) {
        try {
            return read(name);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
