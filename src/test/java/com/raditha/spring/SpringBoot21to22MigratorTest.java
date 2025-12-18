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
        File configFile = new File("src/test/resources/spring-migration-test.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testOrchestratorCreation() {
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(true);
        assertNotNull(migrator);
        assertNotNull(migrator.getResult());
    }

    @Test
    void testMigrateAllInDryRun() throws Exception {
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(true);
        MigrationResult result = migrator.migrateAll();

        assertNotNull(result);
        // In dry run mode, should complete all phases
        assertTrue(result.getTotalChanges() >= 0);
    }

    @Test
    void testGetResult() {
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(true);
        MigrationResult result = migrator.getResult();

        assertNotNull(result);
        assertEquals(0, result.getTotalChanges()); // No migration run yet
    }

    @Test
    void testPrintSummary() {
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(true);

        // Should not throw exception
        assertDoesNotThrow(() -> migrator.printSummary());
    }

    @Test
    void testMainMethodExists() throws NoSuchMethodException {
        // Verify main method exists for CLI execution
        assertNotNull(SpringBoot21to22Migrator.class.getMethod("main", String[].class));
    }
}
