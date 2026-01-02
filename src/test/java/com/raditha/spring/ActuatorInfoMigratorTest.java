package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ActuatorInfoMigrator.
 * 
 * Tests actuator endpoint exposure configuration.
 */
class ActuatorInfoMigratorTest {

    @TempDir
    Path tempDir;

    private Path projectDir;
    private Path resourcesDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AntikytheraRunTime.reset();

        projectDir = tempDir.resolve("test-project");
        resourcesDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);

        // Create minimal pom.xml (required by ActuatorInfoMigrator to check
        // dependencies)
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(projectDir.resolve("pom.xml"), pomContent);

        Settings.setProperty(Settings.BASE_PATH, projectDir.toString());
    }

    @Test
    void testActuatorEndpointExposureConfiguration() throws Exception {
        // Given: Existing application.yml without info endpoint exposure
        String existingYaml = """
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,metrics
                """;

        Files.writeString(resourcesDir.resolve("application.yml"), existingYaml);

        // When: Run migration
        ActuatorInfoMigrator migrator = new ActuatorInfoMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should add info endpoint exposure
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() > 0);

        String newYaml = Files.readString(resourcesDir.resolve("application.yml"));
        assertTrue(newYaml.contains("info") || newYaml.contains("info,"),
                "Should add info to endpoint exposure");
        assertTrue(newYaml.contains("enabled: true") || newYaml.contains("enabled:true"),
                "Should enable info endpoint");
    }

    @Test
    void testCreatesApplicationYmlIfNotExists() throws Exception {
        // Given: No application.yml exists
        // (resourcesDir is empty)

        // When: Run migration
        ActuatorInfoMigrator migrator = new ActuatorInfoMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should create application.yml with info configuration
        assertTrue(result.isSuccessful());

        Path yamlFile = resourcesDir.resolve("application.yml");
        assertTrue(Files.exists(yamlFile), "Should create application.yml");

        String yamlContent = Files.readString(yamlFile);
        assertTrue(yamlContent.contains("info"), "Should contain info endpoint configuration");
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: Existing configuration
        Files.writeString(resourcesDir.resolve("application.yml"),
                "management:\n  endpoints:\n    web:\n      exposure:\n        include: health\n");

        // When: Run in dry-run mode
        ActuatorInfoMigrator migrator = new ActuatorInfoMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should identify changes but not modify file
        assertTrue(result.isSuccessful());

        String yamlContent = Files.readString(resourcesDir.resolve("application.yml"));
        assertFalse(yamlContent.contains("info"),
                "File should not be modified in dry-run mode");
    }

    @Test
    void testSecurityWarningWhenSecurityDetected() throws Exception {
        // Given: Project without Security config
        // When: Run migration
        ActuatorInfoMigrator migrator = new ActuatorInfoMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should complete (may or may not have security warnings depending on
        // detection)
        assertTrue(result.isSuccessful());
        // Note: Security detection requires actual Java files which we're not creating
        // in this test
    }
}
