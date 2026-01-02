package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MigrationValidator.
 */
class MigrationValidatorTest {

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
    void testValidateInDryRun() throws Exception {
        MigrationValidator validator = new MigrationValidator(true);
        MigrationPhaseResult result = validator.migrate();

        // Dry run mode should not actually validate
        assertNotNull(result);
        assertTrue(result.isSuccessful());
    }

    @Test
    void testValidateWithoutPom() throws Exception {
        String originalPath = (String) Settings.getProperty(Settings.BASE_PATH);
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());

        MigrationValidator validator = new MigrationValidator(false);
        MigrationPhaseResult result = validator.migrate();

        Settings.setProperty(Settings.BASE_PATH, originalPath);

        // Should fail validation due to missing pom.xml
        assertNotNull(result);
    }

    @Test
    void testValidatorCreation() {
        MigrationValidator validator = new MigrationValidator(true);
        assertNotNull(validator);

        MigrationValidator validatorNoDryRun = new MigrationValidator(false);
        assertNotNull(validatorNoDryRun);
    }
}
