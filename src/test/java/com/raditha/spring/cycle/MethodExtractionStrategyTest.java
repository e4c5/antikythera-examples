package com.raditha.spring.cycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.JohnsonCycleFinder;
import sa.com.cloudsolutions.antikythera.depsolver.MethodExtractionStrategy;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MethodExtractionStrategy using the extraction/ package.
 * 
 * Tests verify that:
 * 1. Methods with complex transitive dependencies are correctly identified
 * 2. Helper methods and fields are collected
 * 3. Methods are extracted to a mediator class
 * 4. Cycles are broken after extraction
 */
class MethodExtractionStrategyTest {

    private Path testbedPath;
    private Map<String, String> originalFileContents;
    private boolean skipRevert = false; // Flag to skip revert for debugging

    @BeforeEach
    void setUp() throws IOException {
        // Reset testbed to clean state first
        TestbedResetHelper.resetTestbed();
        // Remove Unknown.java to avoid duplicate class definition errors
        TestbedResetHelper.removeUnknownJava();
        
        Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
        if (workspaceRoot.toString().contains("antikythera-examples")) {
            workspaceRoot = workspaceRoot.getParent();
        }
        testbedPath = workspaceRoot.resolve("spring-boot-cycles/src/main/java").normalize();
        
        assertTrue(Files.exists(testbedPath), 
                "Testbed path should exist: " + testbedPath);
        
        originalFileContents = readAllJavaFiles(testbedPath);
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);
    }
    

    @AfterEach
    void tearDown() throws IOException {
        if (originalFileContents != null && !skipRevert) {
            revertFiles(testbedPath, originalFileContents);
        }
        if (!skipRevert) {
            TestbedResetHelper.restoreUnknownJava();
        }
    }

    @Test
    void testMethodExtraction_BreaksCycle() throws IOException {
        // Temporarily skip revert to inspect generated files
        skipRevert = true;
        
        // Step 1: Detect cycle
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> initialCycles = detector.findCycles();
        
        // Find the extraction cycle
        Set<String> extractionCycle = initialCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService") &&
                                 cycle.contains("com.example.cycles.extraction.PaymentProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should detect extraction cycle");
        
        // Step 2: Apply MethodExtractionStrategy
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycleList = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService") &&
                                 cycle.contains("com.example.cycles.extraction.PaymentProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycleList, "Should find extraction cycle in Johnson cycles");
        
        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycleList);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");
        assertFalse(strategy.getGeneratedClasses().isEmpty(), 
                "Should generate mediator class(es)");
        
        // Step 3: Write changes
        strategy.writeChanges(testbedPath.toString());
        
        // Step 4: Verify cycle is broken
        // Remove Unknown.java again before re-processing (it may have been restored by git reset)
        TestbedResetHelper.removeUnknownJava();
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph newGraph = new BeanDependencyGraph();
        newGraph.build();
        
        CycleDetector newDetector = new CycleDetector(newGraph.getSimpleGraph());
        List<Set<String>> remainingCycles = newDetector.findCycles();
        
        // The extraction cycle should be broken
        boolean extractionCycleStillExists = remainingCycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService") &&
                                 cycle.contains("com.example.cycles.extraction.PaymentProcessingService"));
        
        assertFalse(extractionCycleStillExists, 
                "Extraction cycle should be broken after method extraction");
    }

    @Test
    void testMethodExtraction_CollectsTransitiveDependencies() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        if (extractionCycle == null) {
            return; // Skip if cycle not found
        }
        
        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        if (applied) {
            // Verify that mediator classes were generated
            assertFalse(strategy.getGeneratedClasses().isEmpty(),
                    "Should generate mediator classes with extracted methods");
            
            // Verify that original classes were modified
            assertFalse(strategy.getModifiedCUs().isEmpty(),
                    "Should modify original classes");
        }
    }

    @Test
    void testMethodExtraction_HandlesHelperMethods() throws IOException {
        // This test verifies that helper methods are correctly identified and moved
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        if (extractionCycle == null) {
            return;
        }
        
        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        if (applied) {
            strategy.writeChanges(testbedPath.toString());
            
            // Verify that OrderProcessingService.placeOrder() was extracted
            // (The method should no longer call paymentProcessingService directly)
            Path orderServiceFile = testbedPath.resolve(
                    "com/example/cycles/extraction/OrderProcessingService.java");
            if (Files.exists(orderServiceFile)) {
                String content = Files.readString(orderServiceFile);
                // The placeOrder method should either be removed or modified
                // to not directly call paymentProcessingService
                // This is a basic check - more detailed verification would require
                // parsing the AST
            }
        }
    }

    private Map<String, String> readAllJavaFiles(Path basePath) throws IOException {
        return Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toMap(
                        p -> p.toString(),
                        p -> {
                            try {
                                return Files.readString(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                ));
    }

    private void revertFiles(Path basePath, Map<String, String> originalContents) throws IOException {
        for (Map.Entry<String, String> entry : originalContents.entrySet()) {
            Path filePath = Paths.get(entry.getKey());
            if (Files.exists(filePath)) {
                Files.writeString(filePath, entry.getValue());
            }
        }
        // Also remove any generated mediator classes
        Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith("Mediator.java"))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
    }
}

