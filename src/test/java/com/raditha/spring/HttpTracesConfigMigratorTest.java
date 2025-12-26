package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpTracesConfigMigrator.
 * Tests HTTP trace configuration detection for Spring Boot 2.4.
 */
class HttpTracesConfigMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src/main/resources"));
    }

    @Test
    void testHttpTraceDetectionInYaml() throws Exception {
        // Given: A YAML file with HTTP trace configuration
        String yamlContent = """
            management:
              trace:
                http:
                  include:
                    - request-headers
                    - response-headers
            """;

        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, yamlContent);

        // When: Running HTTP traces migrator
        HttpTracesConfigMigrator migrator = new HttpTracesConfigMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect HTTP trace configuration and warn
        assertNotNull(result, "Result should not be null");
        assertTrue(result.requiresManualReview(), "Should require manual review");
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("HTTP_TRACES") || w.contains("cookie")),
            "Should warn about cookie exclusion behavior. Warnings: " + result.getWarnings());
    }

    @Test
    void testHttpTraceDetectionInProfileSpecificMultiDocYaml() throws Exception {
        // Given: A multi-document YAML where HTTP trace config is in a later (profile-specific) document
        String yamlContent = """
            spring:
              application:
                name: myapp
            ---
            spring:
              config:
                activate:
                  on-profile: dev
            management:
              trace:
                http:
                  include:
                    - cookie-headers
                    - request-headers
            """;

        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, yamlContent);

        // When: Running HTTP traces migrator
        HttpTracesConfigMigrator migrator = new HttpTracesConfigMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect HTTP trace configuration even if it appears after a '---'
        assertNotNull(result, "Result should not be null");
        assertTrue(result.requiresManualReview(), "Should require manual review");
        assertTrue(result.getChanges().stream()
                        .anyMatch(c -> c.contains("HTTP trace configuration detected")),
                "Should report HTTP trace configuration was detected. Changes: " + result.getChanges());
        assertTrue(result.getWarnings().stream()
                        .anyMatch(w -> w.contains("HTTP_TRACES")),
                "Should warn about HTTP trace changes. Warnings: " + result.getWarnings());
    }

    @Test
    void testHttpTraceDetectionInProperties() throws Exception {
        // Given: A properties file with HTTP trace configuration
        String propertiesContent = """
            management.trace.http.include=request-headers,response-headers
            """;

        Path propPath = tempDir.resolve("src/main/resources/application.properties");
        Files.writeString(propPath, propertiesContent);

        // When: Running HTTP traces migrator
        HttpTracesConfigMigrator migrator = new HttpTracesConfigMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect HTTP trace configuration
        assertNotNull(result, "Result should not be null");
        assertTrue(result.requiresManualReview(), "Should require manual review");
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("HTTP_TRACES")),
            "Should warn about HTTP trace changes");
    }

    @Test
    void testNoHttpTraceConfig() throws Exception {
        // Given: No HTTP trace configuration
        String yamlContent = """
            spring:
              application:
                name: myapp
            """;

        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, yamlContent);

        // When: Running HTTP traces migrator
        HttpTracesConfigMigrator migrator = new HttpTracesConfigMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report no HTTP trace config found
        assertNotNull(result, "Result should not be null");
        assertFalse(result.requiresManualReview(), "Should not require manual review");
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No HTTP trace")),
            "Should report no HTTP trace configuration");
    }

    @Test
    void testGetPhaseName() {
        HttpTracesConfigMigrator migrator = new HttpTracesConfigMigrator(false);
        assertEquals("HTTP Trace Configuration Detection", migrator.getPhaseName());
        assertEquals(70, migrator.getPriority());
    }
}
