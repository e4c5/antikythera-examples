package sa.com.cloudsolutions.antikythera.examples.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple tests to improve coverage for analysis package classes.
 * Focus on classes that can be instantiated and enum values.
 */
class AnalysisCoverageTest {

    @Test
    void testFunctionalAreaValues() {
        // Test FunctionalArea enum values
        FunctionalArea[] areas = FunctionalArea.values();
        assertNotNull(areas);
        assertTrue(areas.length > 0);
        
        // Test valueOf
        for (FunctionalArea area : areas) {
            assertEquals(area, FunctionalArea.valueOf(area.name()));
        }
    }

    @Test
    void testConsolidationStrategyValues() {
        // Test ConsolidationStrategy enum values
        ConsolidationStrategy[] strategies = ConsolidationStrategy.values();
        assertNotNull(strategies);
        assertTrue(strategies.length > 0);
        
        // Test valueOf
        for (ConsolidationStrategy strategy : strategies) {
            assertEquals(strategy, ConsolidationStrategy.valueOf(strategy.name()));
        }
    }

    @Test
    void testCodeAnalysisEngineInstantiation() {
        // Test CodeAnalysisEngine constructor
        assertDoesNotThrow(() -> {
            CodeAnalysisEngine engine = new CodeAnalysisEngine();
            assertNotNull(engine);
        });
    }

    @Test
    void testDuplicationAnalysisReporterInstantiation() {
        // Test DuplicationAnalysisReporter constructor
        assertDoesNotThrow(() -> {
            DuplicationAnalysisReporter reporter = new DuplicationAnalysisReporter();
            assertNotNull(reporter);
        });
    }

    @Test
    void testDuplicationDetectorInstantiation() {
        // Test DuplicationDetector constructor
        assertDoesNotThrow(() -> {
            DuplicationDetector detector = new DuplicationDetector();
            assertNotNull(detector);
        });
    }

    @Test
    void testPatternCategorizerInstantiation() {
        // Test PatternCategorizer constructor
        assertDoesNotThrow(() -> {
            PatternCategorizer categorizer = new PatternCategorizer();
            assertNotNull(categorizer);
        });
    }
}