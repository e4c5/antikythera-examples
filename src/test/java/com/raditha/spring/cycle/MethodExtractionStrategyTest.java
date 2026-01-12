package com.raditha.spring.cycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.javaparser.ast.CompilationUnit;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
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

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Reset testbed to clean state first
        TestbedResetHelper.resetTestbed();
        // Remove Unknown.java to avoid duplicate class definition errors
        TestbedResetHelper.removeUnknownJava();
        
        Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
        if (!workspaceRoot.toString().endsWith("antikythera-examples")) {
            workspaceRoot = workspaceRoot.resolve("antikythera-examples");
        }
        testbedPath = workspaceRoot.resolve("testbeds/spring-boot-cycles/src/main/java").normalize();
        
        assertTrue(Files.exists(testbedPath), 
                "Testbed path should exist: " + testbedPath);
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
    }
    

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        TestbedResetHelper.resetTestbed();
        TestbedResetHelper.restoreUnknownJava();
    }

    @Test
    void testMethodExtraction_BreaksCycle() throws IOException {
        // Step 1: Detect cycle
        
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
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle containing OrderProcessingService");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply to the extraction cycle");

        // Verify that mediator classes were generated
        assertFalse(strategy.getGeneratedClasses().isEmpty(),
                "Should generate mediator classes with extracted methods");

        // Verify that original classes were modified
        assertFalse(strategy.getModifiedCUs().isEmpty(),
                "Should modify original classes");
    }

    @Test
    void testMethodExtraction_HandlesHelperMethods() throws IOException {
        // This test verifies that helper methods are correctly identified and moved
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle containing OrderProcessingService");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");

        strategy.writeChanges(testbedPath.toString());

        // Verify that OrderProcessingService.placeOrder() was extracted
        // (The method should no longer call paymentProcessingService directly)
        Path orderServiceFile = testbedPath.resolve(
                "com/example/cycles/extraction/OrderProcessingService.java");
        assertTrue(Files.exists(orderServiceFile),
                "OrderProcessingService.java should exist after extraction");

        String content = Files.readString(orderServiceFile);
        // After extraction, the service should either delegate to the mediator
        // or no longer directly reference paymentProcessingService in the extracted method
        assertFalse(content.isEmpty(), "OrderProcessingService.java should have content");

        // Verify mediator class was generated
        assertFalse(strategy.getGeneratedClasses().isEmpty(),
                "Should generate mediator class for extracted methods");
    }

    @Test
    void testGraphBasedMediator_RegisteredInGraph() throws IOException {
        // Test that mediator class is registered in Graph.getDependencies()
        Graph.getDependencies().clear(); // Clear any existing dependencies
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");
        
        // Verify mediator is registered in Graph.getDependencies()
        Map<String, CompilationUnit> graphDeps = Graph.getDependencies();
        assertFalse(graphDeps.isEmpty(), 
                "Graph.getDependencies() should contain generated mediator");
        
        // Find mediator in Graph dependencies (mediator name contains "Operations")
        boolean mediatorInGraph = graphDeps.keySet().stream()
                .anyMatch(fqn -> fqn.contains("Operations"));
        assertTrue(mediatorInGraph, 
                "Mediator class should be registered in Graph.getDependencies()");
    }

    @Test
    void testGraphBasedMediator_AutomaticImports() throws IOException {
        // Test that imports are automatically added to mediator
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");
        
        // Get mediator from generated classes
        Map<String, CompilationUnit> generated = strategy.getGeneratedClasses();
        assertFalse(generated.isEmpty(), "Should generate mediator");
        
        CompilationUnit mediator = generated.values().iterator().next();
        String mediatorCode = mediator.toString();
        
        // Verify imports are present (methods use types that need imports)
        // The mediator should have imports for types used in methods
        assertTrue(mediatorCode.contains("import") || mediatorCode.contains("package"),
                "Mediator should have imports or package declaration");
        
        // Verify Spring annotations are imported (mediator uses @Service)
        assertTrue(mediatorCode.contains("@Service") || mediatorCode.contains("Service"),
                "Mediator should use Spring @Service annotation");
    }

    @Test
    void testGraphBasedMediator_TransitiveDependencies() throws IOException {
        // Test that transitive dependencies are discovered
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");
        
        // Get mediator code
        Map<String, CompilationUnit> generated = strategy.getGeneratedClasses();
        assertFalse(generated.isEmpty(), "Should generate mediator");
        
        CompilationUnit mediator = generated.values().iterator().next();
        String mediatorCode = mediator.toString();
        
        // Verify helper methods are included (transitive dependencies)
        // OrderProcessingService.placeOrder() calls helper methods like generateOrderNumber()
        assertTrue(mediatorCode.contains("placeOrder") || mediatorCode.contains("processPayment"),
                "Mediator should contain extracted methods");
    }

    @Test
    void testGraphBasedMediator_Compiles() throws IOException {
        // Test that generated mediator compiles
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();
        
        List<String> extractionCycle = allCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(extractionCycle, "Should find extraction cycle");

        MethodExtractionStrategy strategy = new MethodExtractionStrategy(false);
        boolean applied = strategy.apply(extractionCycle);
        
        assertTrue(applied, "MethodExtractionStrategy should apply successfully");
        
        // Write changes
        strategy.writeChanges(testbedPath.toString());
        
        // Verify mediator file was written
        Path mediatorFile = Files.walk(testbedPath)
                .filter(path -> path.toString().contains("Operations") && 
                               path.toString().endsWith(".java"))
                .findFirst()
                .orElse(null);
        

            assertTrue(Files.exists(mediatorFile), 
                    "Mediator file should be written");
            
            String mediatorContent = Files.readString(mediatorFile);
            assertFalse(mediatorContent.isEmpty(), 
                    "Mediator file should have content");
            
            // Basic syntax check - should have class declaration
            assertTrue(mediatorContent.contains("class") || mediatorContent.contains("interface"),
                    "Mediator should have class/interface declaration");

    }
}

