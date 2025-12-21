package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqlScriptPropertiesMigrator.
 * 
 * Tests the critical SQL script property migration logic.
 */
class SqlScriptPropertiesMigratorTest {

    @TempDir
    Path tempDir;

    private Path projectDir;
    private Path resourcesDir;

    @BeforeEach
    void setUp() throws IOException {
        // Load Settings configuration
        Settings.loadConfigMap();

        AntikytheraRunTime.reset();

        projectDir = tempDir.resolve("test-project");
        resourcesDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);

        Settings.setProperty(Settings.BASE_PATH, projectDir.toString());
    }

    @Test
    void testSqlScriptPropertyMigrationInYaml() throws Exception {
        // Given: YAML with old SQL properties
        String oldYaml = """
                spring:
                  datasource:
                    initialization-mode: always
                    schema: classpath:schema.sql
                    data: classpath:data.sql
                    platform: h2
                    continue-on-error: true
                    separator: ;
                    sql-script-encoding: UTF-8
                """;

        Files.writeString(resourcesDir.resolve("application.yml"), oldYaml);

        // When: Run migration
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Properties should be migrated
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() > 0, "Should have changes");

        // Verify new properties exist
        String newYaml = Files.readString(resourcesDir.resolve("application.yml"));
        assertTrue(newYaml.contains("spring.sql.init") || newYaml.contains("sql:\n    init:"),
                "Should contain new spring.sql.init namespace");
        assertFalse(newYaml.contains("initialization-mode"),
                "Should not contain old initialization-mode property");
    }

    @Test
    void testSqlScriptPropertyMigrationInProperties() throws Exception {
        // Given: .properties file with old SQL properties
        String oldProps = """
                spring.datasource.initialization-mode=always
                spring.datasource.schema=classpath:schema.sql
                spring.datasource.data=classpath:data.sql
                """;

        Files.writeString(resourcesDir.resolve("application.properties"), oldProps);

        // When: Run migration
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Properties should be migrated
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() > 0);

        String newProps = Files.readString(resourcesDir.resolve("application.properties"));
        assertTrue(newProps.contains("spring.sql.init.mode"),
                "Should contain new property spring.sql.init.mode");
        assertTrue(newProps.contains("spring.sql.init.schema-locations"),
                "Should contain new property spring.sql.init.schema-locations");
        assertFalse(newProps.contains("initialization-mode"),
                "Should not contain old property");
    }

    @Test
    void testJpaDeferPropertyAddition() throws Exception {
        // Given: YAML with JPA configuration and data.sql file
        String yamlWithJpa = """
                spring:
                  jpa:
                    hibernate:
                      ddl-auto: create-drop
                  datasource:
                    initialization-mode: always
                    data: classpath:data.sql
                """;

        Files.writeString(resourcesDir.resolve("application.yml"), yamlWithJpa);
        Files.writeString(resourcesDir.resolve("data.sql"), "INSERT INTO users VALUES (1, 'test');");

        // When: Run migration
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: defer-datasource-initialization should be added
        assertTrue(result.isSuccessful());

        String newYaml = Files.readString(resourcesDir.resolve("application.yml"));
        assertTrue(newYaml.contains("defer-datasource-initialization") ||
                newYaml.contains("defer-datasource-initialization: true"),
                "Should add defer-datasource-initialization property");
    }

    @Test
    void testNoMigrationWhenNoProperties() throws Exception {
        // Given: YAML without SQL properties
        String yaml = """
                spring:
                  application:
                    name: test-app
                """;

        Files.writeString(resourcesDir.resolve("application.yml"), yaml);

        // When: Run migration
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should complete but with no changes
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() == 0 ||
                result.getChanges().stream().anyMatch(c -> c.contains("No SQL script properties")));
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: YAML with old properties
        String oldYaml = """
                spring:
                  datasource:
                    initialization-mode: always
                """;

        Files.writeString(resourcesDir.resolve("application.yml"), oldYaml);

        // When: Run in dry-run mode
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should identify changes but not modify file
        assertTrue(result.isSuccessful());

        String yamlAfter = Files.readString(resourcesDir.resolve("application.yml"));
        assertTrue(yamlAfter.contains("initialization-mode"),
                "File should not be modified in dry-run mode");
    }

    @Test
    void testMultiplePropertyFiles() throws Exception {
        // Given: Multiple YAML files
        Files.writeString(resourcesDir.resolve("application.yml"),
                "spring:\n  datasource:\n    initialization-mode: always\n");
        Files.writeString(resourcesDir.resolve("application-dev.yml"),
                "spring:\n  datasource:\n    platform: h2\n");

        // When: Run migration
        SqlScriptPropertiesMigrator migrator = new SqlScriptPropertiesMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Both files should be processed
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() >= 2, "Should process multiple files");
    }
}
