package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for HazelcastCodeMigrator - aggressive transformation approach.
 */
class HazelcastCodeMigratorTest {

    @BeforeAll
    static void setUp() throws IOException {
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testStaticMethodDetection() throws IOException {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);

        // Should detect and flag static Hazelcast method calls
        assertTrue(result.getChanges().stream()
                        .anyMatch(change -> change.contains("Hazelcast patterns")),
                "Should detect Hazelcast static method patterns");

        assertTrue(result.requiresManualReview(),
                "Hazelcast migration should always require manual review");
    }

    @Test
    void testGroupConfigDetection() throws IOException {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);

        // Should detect GroupConfig usage
        assertTrue(result.getChanges().stream()
                        .anyMatch(change -> change.contains("HazelcastV3Sample") ||
                                change.contains("Hazelcast patterns")),
                "Should detect files with Hazelcast usage");
    }

    @Test
    void testTransformationCount() throws IOException {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
        assertFalse(result.getChanges().isEmpty(), "Should have migration changes");

        // Should report transformations
        assertTrue(result.getChanges().stream()
                        .anyMatch(change -> change.contains("transformation") ||
                                change.contains("Flagged")),
                "Should report transformation or flagging count");
    }

    @Test
    void testManualReviewGuidance() throws IOException {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);

        // Should provide manual review guidance
        assertTrue(result.getWarnings().stream()
                        .anyMatch(warning -> warning.contains("MANUAL REVIEW") ||
                                warning.contains("Network") ||
                                warning.contains("MapReduce")),
                "Should provide comprehensive manual review guidance");
    }

    @Test
    void testDryRunMode() throws IOException {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.getErrors().isEmpty(), "Dry-run should complete without errors");

        // In dry-run, should still detect but not write files
        assertTrue(result.getChanges().stream()
                        .anyMatch(change -> change.contains("Hazelcast") ||
                                change.contains("transformation")),
                "Should detect transformations even in dry-run mode");
    }

    @Test
    void testPhaseName() {
        HazelcastCodeMigrator migrator = new HazelcastCodeMigrator(true);
        assertEquals("Hazelcast 4.x Code Migration", migrator.getPhaseName());
        assertEquals(55, migrator.getPriority());
    }
}
