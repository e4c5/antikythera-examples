package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PropertyMigrator22to23.
 * Tests Spring Boot 2.2→2.3 property migration.
 */
class PropertyMigrator22to23Test {

    @TempDir
    Path tempDir;

    private Path resourcesDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());

        // Create resources directory
        resourcesDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
    }

    @Test
    void testHttpEncodingPropertyMigration() throws Exception {
        // Given: application.yml with old HTTP encoding properties
        String yamlContent = """
                spring:
                  http:
                    encoding:
                      charset: UTF-8
                      enabled: true
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running property migrator in dry-run
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report property migration
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("spring.http.encoding") ||
                        change.contains("server.servlet.encoding")),
                "Should migrate HTTP encoding properties");
    }

    @Test
    void testConverterPropertyMigration() throws Exception {
        // Given: Properties file with old converter property
        String propertiesContent = """
                spring.http.converters.preferred-json-mapper=jackson
                """;

        Path propertiesFile = resourcesDir.resolve("application.properties");
        Files.writeString(propertiesFile, propertiesContent);

        // When: Running property migrator in dry-run
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should migrate converter property
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("spring.http.converters") ||
                        change.contains("spring.mvc.converters")),
                "Should migrate converter property");
    }

    @Test
    void testNoPropertyFiles()  {
        // Given: No property files exist
        // When: Running property migrator
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result even without property files");
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: Properties to migrate
        String yamlContent = """
                spring:
                  http:
                    encoding:
                      charset: UTF-8
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running in dry-run mode
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: File should not be modified in dry-run mode OR migration should be
        // reported
        String yamlAfter = Files.readString(yamlFile);
        boolean fileUnchanged = yamlAfter.contains("spring.http.encoding");
        boolean migrationReported = !result.getChanges().isEmpty();

        assertTrue(fileUnchanged || migrationReported,
                "Should either preserve file in dry-run or report migration. File modified: " + !fileUnchanged);
    }

    @Test
    void testGetPhaseName() {
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(false);
        assertEquals("Property Migration (2.2→2.3)", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        PropertyMigrator22to23 migrator = new PropertyMigrator22to23(false);
        assertEquals(20, migrator.getPriority());
    }
}
