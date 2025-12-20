package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KafkaCodeMigrator.
 * Uses test helper classes to validate Kafka code transformations.
 */
class KafkaCodeMigratorTest {

    @BeforeAll
    static void setup() throws IOException {
        // Load configuration
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);

        // Reset and initialize parser
        AbstractCompiler.reset();

        // Parse all classes in test-helper
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoKafkaClasses() {
        KafkaCodeMigrator migrator = new KafkaCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertTrue(result.isSuccessful());
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No Kafka migrations needed")));
    }

    @Test
    void testMigratorScansAllCompilationUnits() {
        KafkaCodeMigrator migrator = new KafkaCodeMigrator(true);

        // Verify we have parsed compilation units available
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        assertNotNull(units);
        assertFalse(units.isEmpty(), "Should have parsed some compilation units");

        // Run migration - should scan all units
        MigrationPhaseResult result = migrator.migrate();
        assertNotNull(result);
    }
}
