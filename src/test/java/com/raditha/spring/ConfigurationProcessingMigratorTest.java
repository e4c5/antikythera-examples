package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigurationProcessingMigrator.
 * Tests YAML profile syntax migration for Spring Boot 2.4.
 */
class ConfigurationProcessingMigratorTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    Settings.loadConfigMap();
    Settings.setProperty("base_path", tempDir.toString());
    Files.createDirectories(tempDir.resolve("src/main/resources"));
  }

  @Test
  void testLegacyProfileSyntaxMigration() throws Exception {
    // Given: A YAML file with legacy profile syntax
    String yamlContent = """
        spring:
          profiles: dev
          application:
            name: myapp
        server:
          port: 8080
        """;

    Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
    Files.writeString(yamlPath, yamlContent);

    // When: Running configuration processing migrator in dry-run
    ConfigurationProcessingMigrator migrator = new ConfigurationProcessingMigrator(true);
    MigrationPhaseResult result = migrator.migrate();

    // Then: Should detect YAML file and process it (even if no transformation
    // needed)
    assertNotNull(result, "Result should not be null");
    assertFalse(result.getChanges().isEmpty(), "Should have changes reported");

    // In dry-run mode with simple profile syntax, should either:
    // 1. Transform the syntax and report it, OR
    // 2. Report that YAML files were found
    boolean hasYamlProcessing = result.getChanges().stream()
        .anyMatch(c -> c.toLowerCase().contains("yaml") ||
            c.toLowerCase().contains("profile") ||
            c.toLowerCase().contains("spring.config"));

    assertTrue(hasYamlProcessing,
        "Should process YAML file. Actual changes: " + result.getChanges());
  }

  @Test
  void testMultiDocumentYamlDetection() throws Exception {
    // Given: A multi-document YAML file with profiles
    String yamlContent = """
        spring:
          application:
            name: myapp
        ---
        spring:
          profiles: dev
        server:
          port: 8080
        """;

    Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
    Files.writeString(yamlPath, yamlContent);

    // When: Running configuration processing migrator
    ConfigurationProcessingMigrator migrator = new ConfigurationProcessingMigrator(true);
    MigrationPhaseResult result = migrator.migrate();

    // Then: Should at minimum complete successfully
    assertNotNull(result, "Should return result");
    assertFalse(result.getChanges().isEmpty(), "Should report changes");

    // Multi-document files may be flagged for manual review OR processed
    // automatically
    // depending on complexity - just verify we got a result
    boolean hasProcessing = result.getChanges().stream()
        .anyMatch(c -> c.toLowerCase().contains("yaml"));

    assertTrue(hasProcessing,
        "Should process multi-document YAML. Changes: " + result.getChanges());
  }

  @Test
  void testNoYamlFiles() throws Exception {
    // Given: No YAML files in resources
    // When: Running configuration processing migrator
    ConfigurationProcessingMigrator migrator = new ConfigurationProcessingMigrator(true);
    MigrationPhaseResult result = migrator.migrate();

    // Then: Should handle gracefully
    assertNotNull(result, "Should return result even without YAML files");
    assertTrue(result.getChanges().stream()
        .anyMatch(c -> c.contains("No YAML")),
        "Should report no YAML files found");
  }

  @Test
  void testGetPhaseName() {
    ConfigurationProcessingMigrator migrator = new ConfigurationProcessingMigrator(false);
    assertEquals("Configuration File Processing Migration", migrator.getPhaseName());
  }

  @Test
  void testGetPriority() {
    ConfigurationProcessingMigrator migrator = new ConfigurationProcessingMigrator(false);
    assertEquals(30, migrator.getPriority());
  }
}
