package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PropertyFileMigrator.
 */
class PropertyFileMigratorTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws IOException {
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoPropertyFiles() throws Exception {
        String originalPath = (String) Settings.getProperty(Settings.BASE_PATH);
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());

        PropertyFileMigrator migrator = new PropertyFileMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        Settings.setProperty(Settings.BASE_PATH, originalPath);

        // Should succeed even with no property files to migrate
        assertNotNull(result);
        assertTrue(result.isSuccessful());
    }

    @Test
    void testMigrateYamlFile() throws Exception {
        // Create resources directory
        Path resourcesDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);

        // Create test YAML with old properties
        String yamlContent = """
                logging:
                  file: /var/log/app.log
                server:
                  connection-timeout: 60000
                """;
        Files.writeString(resourcesDir.resolve("application.yml"), yamlContent);

        String originalPath = (String) Settings.getProperty(Settings.BASE_PATH);
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());

        PropertyFileMigrator migrator = new PropertyFileMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        Settings.setProperty(Settings.BASE_PATH, originalPath);

        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() > 0);
    }

    @Test
    void testDryRunMode() {
        PropertyFileMigrator migrator = new PropertyFileMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.isSuccessful());
    }
}
