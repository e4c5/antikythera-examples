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
        File configFile = new File("src/test/resources/spring-boot-2.1.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testMigratorAccessesParsedClasses() {
        assertFalse(AntikytheraRunTime.getResolvedCompilationUnits().isEmpty());

        ConfigPropertiesScanMigrator migrator = new ConfigPropertiesScanMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
    }
}
