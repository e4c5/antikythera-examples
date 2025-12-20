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
 * Tests for SpringBootPomMigrator.
 */
class SpringBootPomMigratorTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws IOException {
        File configFile = new File("src/test/resources/spring-migration-test.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testMigrateWithNoPom() throws Exception {
        // Temporarily change base path to temp dir with no pom
        String originalPath = (String) Settings.getProperty(Settings.BASE_PATH);
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());

        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Restore original path
        Settings.setProperty(Settings.BASE_PATH, originalPath);

        // Should return result even when pom not found
        assertNotNull(result);
    }

    @Test
    void testMigrateWithInvalidPom() throws Exception {
        // Create invalid pom.xml
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, "not valid xml");

        String originalPath = (String) Settings.getProperty(Settings.BASE_PATH);
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());

        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        Settings.setProperty(Settings.BASE_PATH, originalPath);

        // Should handle invalid POM gracefully
        assertNotNull(result);
    }

    @Test
    void testDryRunMode() throws Exception {
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // In dry run mode, should not throw exceptions
        assertNotNull(result);
    }
}
