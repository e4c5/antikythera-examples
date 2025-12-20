package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpringBoot21to22Migrator - the main orchestrator.
 */
class SpringBoot21to22MigratorTest {

    @BeforeAll
    static void setup() throws IOException {
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }


    @Test
    void testMigrateAllInDryRun() {
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(true);
        assertThrows(IOException.class, migrator::migrateAll);
    }

    @Test
    void testMainMethodExists() throws NoSuchMethodException {
        // Verify main method exists for CLI execution
        assertNotNull(SpringBoot21to22Migrator.class.getMethod("main", String[].class));
    }
}
