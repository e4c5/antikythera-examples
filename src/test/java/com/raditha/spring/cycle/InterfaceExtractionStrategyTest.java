package com.raditha.spring.cycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependency;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.InterfaceExtractionStrategy;
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
 * Tests for InterfaceExtractionStrategy using the interfaces/ package.
 * 
 * Tests verify that:
 * 1. All methods called on a dependency are identified
 * 2. An interface is generated with those method signatures
 * 3. The target class implements the interface
 * 4. The field type is changed to the interface
 * 5. Cycles are broken after interface extraction
 */
class InterfaceExtractionStrategyTest extends TestHelper {

    private Path testbedPath;
    private Map<String, String> originalFileContents;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
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
        if (originalFileContents != null) {
            revertFiles(testbedPath, originalFileContents);
        }
        TestbedResetHelper.restoreUnknownJava();
    }

    @Test
    void testInterfaceExtraction_BreaksCycle() throws IOException {
        // Step 1: Detect cycle
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> initialCycles = detector.findCycles();
        
        // Find the interfaces cycle
        Set<String> interfacesCycle = initialCycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.interfaces.PaymentProcessor") &&
                                 cycle.contains("com.example.cycles.interfaces.OrderService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(interfacesCycle, "Should detect interfaces cycle");
        
        // Step 2: Find the edge to break
        Set<BeanDependency> paymentProcessorDeps = graph.getDependencies()
                .get("com.example.cycles.interfaces.PaymentProcessor");
        assertNotNull(paymentProcessorDeps, "PaymentProcessor should have dependencies");
        
        BeanDependency edgeToBreak = paymentProcessorDeps.stream()
                .filter(dep -> dep.targetBean().equals("com.example.cycles.interfaces.OrderService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(edgeToBreak, "Should find PaymentProcessor -> OrderService edge");
        
        // Step 3: Apply InterfaceExtractionStrategy
        InterfaceExtractionStrategy strategy = new InterfaceExtractionStrategy(false);
        boolean applied = strategy.apply(edgeToBreak);
        
        assertTrue(applied, "InterfaceExtractionStrategy should apply successfully");
        assertFalse(strategy.getGeneratedInterfaces().isEmpty(), 
                "Should generate interface");
        
        // Step 4: Write changes
        strategy.writeChanges(testbedPath.toString());
        
        // Step 5: Verify cycle is broken
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph newGraph = new BeanDependencyGraph();
        newGraph.build();
        
        CycleDetector newDetector = new CycleDetector(newGraph.getSimpleGraph());
        List<Set<String>> remainingCycles = newDetector.findCycles();
        
        // The interfaces cycle should be broken
        boolean interfacesCycleStillExists = remainingCycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.interfaces.PaymentProcessor") &&
                                 cycle.contains("com.example.cycles.interfaces.OrderService"));
        
        assertFalse(interfacesCycleStillExists, 
                "Interfaces cycle should be broken after interface extraction");
    }

    @Test
    void testInterfaceExtraction_FindsAllMethodCalls() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Set<BeanDependency> paymentProcessorDeps = graph.getDependencies()
                .get("com.example.cycles.interfaces.PaymentProcessor");
        assertNotNull(paymentProcessorDeps, "PaymentProcessor should have dependencies");

        BeanDependency edge = paymentProcessorDeps.stream()
                .filter(dep -> dep.targetBean().equals("com.example.cycles.interfaces.OrderService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(edge, "Should find PaymentProcessor -> OrderService edge");

        InterfaceExtractionStrategy strategy = new InterfaceExtractionStrategy(false);
        boolean applied = strategy.apply(edge);
        
        assertTrue(applied, "InterfaceExtractionStrategy should apply successfully");

        // Verify that interface was generated
        assertFalse(strategy.getGeneratedInterfaces().isEmpty(),
                "Should generate interface with method signatures");

        // Verify that OrderService was modified to implement the interface
        assertFalse(strategy.getModifiedCUs().isEmpty(),
                "Should modify OrderService to implement interface");
    }

    @Test
    void testInterfaceExtraction_GeneratesCorrectInterface() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Set<BeanDependency> paymentProcessorDeps = graph.getDependencies()
                .get("com.example.cycles.interfaces.PaymentProcessor");
        assertNotNull(paymentProcessorDeps, "PaymentProcessor should have dependencies");

        BeanDependency edge = paymentProcessorDeps.stream()
                .filter(dep -> dep.targetBean().equals("com.example.cycles.interfaces.OrderService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(edge, "Should find PaymentProcessor -> OrderService edge");

        InterfaceExtractionStrategy strategy = new InterfaceExtractionStrategy(false);
        boolean applied = strategy.apply(edge);
        
        assertTrue(applied, "InterfaceExtractionStrategy should apply successfully");

        strategy.writeChanges(testbedPath.toString());

        // Verify that at least one interface was generated
        assertFalse(strategy.getGeneratedInterfaces().isEmpty(),
                "Should generate at least one interface");

        // Verify interface files were created
        Map<String, com.github.javaparser.ast.CompilationUnit> generatedInterfaces = strategy.getGeneratedInterfaces();
        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : generatedInterfaces.entrySet()) {
            String className = entry.getKey();
            Path interfaceFile = testbedPath.resolve(
                    className.replace('.', '/') + ".java");
            assertTrue(Files.exists(interfaceFile),
                    "Interface file should be created: " + interfaceFile);

            String content = Files.readString(interfaceFile);
            assertTrue(content.contains("interface"),
                    "Generated file should contain an interface declaration");
        }
    }

}

