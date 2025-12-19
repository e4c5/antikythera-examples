package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for H2ConfigurationMigrator.
 * Tests H2 console detection and datasource naming configuration.
 */
class H2ConfigurationMigratorTest {

    @TempDir
    Path tempDir;

    private Path resourcesDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());

        // Create src/main/resources directory structure
        resourcesDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
    }

    @Test
    void testDetectH2ConsoleEnabledInYaml() throws Exception {
        // Given: application.yml with H2 console enabled
        String yamlContent = """
                spring:
                  h2:
                    console:
                      enabled: true
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running H2 configuration migrator in dry-run
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect H2 console and report needed configuration
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Would add") || change.contains("datasource")),
                "Should detect H2 console enabled");
    }

    @Test
    void testDetectH2ConsoleEnabledInProperties() throws Exception {
        // Given: application.properties with H2 console enabled
        String propertiesContent = """
                spring.h2.console.enabled=true
                """;

        Path propertiesFile = resourcesDir.resolve("application.properties");
        Files.writeString(propertiesFile, propertiesContent);

        // When: Running H2 configuration migrator in dry-run
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect H2 console
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("h2") ||
                        change.toLowerCase().contains("datasource")),
                "Should detect H2 console in properties");
    }

    @Test
    void testNoH2Usage() throws Exception {
        // Given: application.yml without H2 console
        String yamlContent = """
                spring:
                  application:
                    name: test-app
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running H2 configuration migrator
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report no action needed
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("not enabled") ||
                        change.contains("not needed") ||
                        change.contains("skipped")),
                "Should report H2 not used");
    }

    @Test
    void testConfigurationAlreadyPresent() throws Exception {
        // Given: YAML with H2 console enabled AND datasource naming already configured
        String yamlContent = """
                spring:
                  h2:
                    console:
                      enabled: true
                  datasource:
                    generate-unique-name: false
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running H2 configuration migrator
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report configuration already present
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("already configured") ||
                        change.contains("already present")),
                "Should detect configuration already present");
    }

    @Test
    void testAddDatasourceNameToYamlDryRun() throws Exception {
        // Given: YAML with H2 console enabled but no datasource naming
        String yamlContent = """
                spring:
                  h2:
                    console:
                      enabled: true
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Files.writeString(yamlFile, yamlContent);

        // When: Running in dry-run mode
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report what would be added
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Would add")),
                "Should report planned addition in dry-run");

        // Verify file not modified
        String yamlAfter = Files.readString(yamlFile);
        assertFalse(yamlAfter.contains("generate-unique-name"),
                "File should not be modified in dry-run mode");
    }

    @Test
    void testAddDatasourceNameToPropertiesDryRun() throws Exception {
        // Given: Properties with H2 console enabled but no datasource naming
        String propertiesContent = """
                spring.h2.console.enabled=true
                """;

        Path propertiesFile = resourcesDir.resolve("application.properties");
        Files.writeString(propertiesFile, propertiesContent);

        // When: Running in dry-run mode
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report planned addition
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Would add")),
                "Should report planned property addition");

        // Verify file not modified
        String propertiesAfter = Files.readString(propertiesFile);
        assertFalse(propertiesAfter.contains("generate-unique-name"),
                "File should not be modified in dry-run mode");
    }

    @Test
    void testNoPropertyFiles() throws Exception {
        // Given: No application.yml or application.properties files exist
        // (empty resources directory)

        // When: Running H2 configuration migrator
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result even without property files");
    }

    @Test
    void testMultiplePropertyFiles() throws Exception {
        // Given: Both YAML and properties files with H2 console enabled
        String yamlContent = """
                spring:
                  h2:
                    console:
                      enabled: true
                """;

        String propertiesContent = """
                spring.h2.console.enabled=true
                """;

        Path yamlFile = resourcesDir.resolve("application.yml");
        Path propertiesFile = resourcesDir.resolve("application.properties");
        Files.writeString(yamlFile, yamlContent);
        Files.writeString(propertiesFile, propertiesContent);

        // When: Running H2 configuration migrator in dry-run
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect H2 and plan to add to first found file
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Would add")),
                "Should plan to add datasource configuration");
    }

    @Test
    void testGetPhaseName() {
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(false);
        assertEquals("H2 Console Configuration", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        H2ConfigurationMigrator migrator = new H2ConfigurationMigrator(false);
        assertEquals(15, migrator.getPriority());
    }
}
