package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DataSqlMigrator.
 * Tests data.sql processing migration for Spring Boot 2.4.
 */
class DataSqlMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src/main/resources"));
    }

    @Test
    void testDataSqlWithHibernateDdlAuto() throws Exception {
        // Given: data.sql file and Hibernate DDL auto configured
        Path dataSql = tempDir.resolve("src/main/resources/data.sql");
        Files.writeString(dataSql, "INSERT INTO users VALUES (1, 'test');");

        String yamlContent = """
                spring:
                  jpa:
                    hibernate:
                      ddl-auto: create
                """;

        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, yamlContent);

        // When: Running data.sql migrator in dry-run
        DataSqlMigrator migrator = new DataSqlMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect risky combination and add defer-datasource-initialization
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("data.sql") || w.contains("Hibernate")),
                "Should warn about data.sql + Hibernate DDL auto");
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("defer-datasource-initialization")),
                "Should add defer-datasource-initialization property");
    }

    @Test
    void testDataSqlWithoutHibernateDdlAuto() throws Exception {
        // Given: data.sql without Hibernate DDL auto
        Path dataSql = tempDir.resolve("src/main/resources/data.sql");
        Files.writeString(dataSql, "INSERT INTO users VALUES (1, 'test');");

        // When: Running data.sql migrator
        DataSqlMigrator migrator = new DataSqlMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should not require changes
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("no action needed") || c.contains("not detected")),
                "Should not require changes without Hibernate DDL auto");
    }

    @Test
    void testNoDataSql() throws Exception {
        // Given: No data.sql file
        // When: Running data.sql migrator
        DataSqlMigrator migrator = new DataSqlMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result");
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No data.sql")),
                "Should report no data.sql found");
    }

    @Test
    void testGetPhaseName() {
        DataSqlMigrator migrator = new DataSqlMigrator(false);
        assertEquals("Data.sql Processing Migration", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        DataSqlMigrator migrator = new DataSqlMigrator(false);
        assertEquals(35, migrator.getPriority());
    }
}
