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
 * Tests for JmxConfigDetector.
 * Uses test helper classes to validate JMX detection logic.
 */
class JmxConfigDetectorTest {

    @BeforeAll
    static void setup() throws IOException {
        // Load configuration
        File configFile = new File("src/test/resources/spring-migration-test.yml");
        Settings.loadConfigMap(configFile);

        // Reset and initialize parser
        AbstractCompiler.reset();

        // Parse all classes in test-helper
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoJmxUsage() throws IOException {
        JmxConfigDetector detector = new JmxConfigDetector(true);
        MigrationPhaseResult result = detector.migrate();

        assertTrue(result.isSuccessful());
        // Should not find JMX in test helper classes
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No JMX usage detected")));
    }

    @Test
    void testDetectorScansAllClasses() throws IOException {
        JmxConfigDetector detector = new JmxConfigDetector(true);

        // Verify we have parsed compilation units
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        assertNotNull(units);
        assertFalse(units.isEmpty());

        // Run detection
        MigrationPhaseResult result = detector.migrate();
        assertNotNull(result);
    }
}
