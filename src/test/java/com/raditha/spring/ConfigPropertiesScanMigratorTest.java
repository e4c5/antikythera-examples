package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigPropertiesScanMigrator.
 */
class ConfigPropertiesScanMigratorTest {

    @BeforeAll
    static void setup() throws IOException {
        File configFile = new File("src/test/resources/spring-migration-test.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoSpringBootApplication() {
        ConfigPropertiesScanMigrator migrator = new ConfigPropertiesScanMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertTrue(result.isSuccessful());
        // Test helper classes don't have @SpringBootApplication - reported as a change
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("@SpringBootApplication")));
    }

    @Test
    void testMigratorAccessesParsedClasses() {
        assertFalse(AntikytheraRunTime.getResolvedCompilationUnits().isEmpty());

        ConfigPropertiesScanMigrator migrator = new ConfigPropertiesScanMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
    }
}
