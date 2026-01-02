package com.raditha.spring;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ActuatorConfigDetector - detects Actuator features disabled by
 * default in Spring Boot 2.2.
 * 
 * Uses the Spring PetClinic testbed projects to test realistic Actuator usage
 * detection.
 */
class ActuatorConfigDetectorTest {

    private static final String TESTBED_PATH = System.getProperty("user.home") +
            "/csi/Antikythera/antikythera-examples/testbeds/spring-boot-2.1";

    @BeforeAll
    static void beforeClass() throws IOException, XmlPullParserException, InterruptedException {
        // Reset testbed to clean state
        Path testbedPath = Paths.get(TESTBED_PATH);
        ProcessBuilder pb = new ProcessBuilder("git", "checkout", "HEAD", ".");
        pb.directory(testbedPath.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();

        // Load configuration pointing to testbed
        Settings.loadConfigMap(new File("src/test/resources/generator2.yml"));
        Settings.setProperty("base_path", TESTBED_PATH + "/src/main/java");

        MockingRegistry.reset();
        MavenHelper mavenHelper = new MavenHelper();
        mavenHelper.readPomFile();
        mavenHelper.buildJarPaths();

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        // Reset any state if needed
    }

    @Test
    void testNoActuatorUsageInBasicProject() {
        // Given: PetClinic project loaded
        // When: Running Actuator config detector
        ActuatorConfigDetector detector = new ActuatorConfigDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should report no HTTP trace or audit event usage
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("No Actuator") ||
                        change.contains("not detected") ||
                        change.contains("no usage")),
                "Should report no Actuator usage in basic PetClinic");
    }

    @Test
    void testGetPhaseName() {
        ActuatorConfigDetector detector = new ActuatorConfigDetector(false);
        assertEquals("Actuator Configuration Detection", detector.getPhaseName());
    }

    @Test
    void testGetPriority() {
        ActuatorConfigDetector detector = new ActuatorConfigDetector(false);
        assertEquals(50, detector.getPriority());
    }

    @Test
    void testDetectorHandlesEmptyCodebase() {
        // Given: Detector with empty codebase (no compilation units loaded)
        AntikytheraRunTime.getResolvedCompilationUnits().clear();

        // When: Running detector
        ActuatorConfigDetector detector = new ActuatorConfigDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Detector should handle empty codebase gracefully");
        assertNotNull(result.getChanges(), "Should have changes list");
    }

    @Test
    void testDryRunMode() {
        // Given: Detector in dry-run mode
        ActuatorConfigDetector detector = new ActuatorConfigDetector(true);

        // When: Running migration
        MigrationPhaseResult result = detector.migrate();

        // Then: Should not modify any files
        assertNotNull(result, "Should return result in dry-run mode");
        assertFalse(result.getChanges().stream()
                .anyMatch(change -> change.startsWith("Modified") ||
                        change.startsWith("Updated")),
                "Dry-run should not report modifications");
    }

    @Test
    void testNonDryRunMode() {
        // Given: Detector in non-dry-run mode
        ActuatorConfigDetector detector = new ActuatorConfigDetector(false);

        // When: Running migration
        MigrationPhaseResult result = detector.migrate();

        // Then: Should return result
        assertNotNull(result, "Should return result in non-dry-run mode");
    }

    @Test
    void testResultContainsChanges() {
        // Given: Actuator detector
        ActuatorConfigDetector detector = new ActuatorConfigDetector(true);

        // When: Running migration
        MigrationPhaseResult result = detector.migrate();

        // Then: Result should have changes
        assertNotNull(result.getChanges(), "Result should have changes list");
        assertFalse(result.getChanges().isEmpty(), "Result should contain at least one change");
    }
}
