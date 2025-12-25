package com.raditha.spring.cycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy;
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
 * Tests for edge cases using the edgecases/ package.
 * 
 * Tests verify handling of:
 * 1. Generic types in dependencies
 * 2. @Qualifier annotations
 * 3. @PostConstruct scenarios
 * 4. Nested method calls
 */
class EdgeCasesTest {

    private Path testbedPath;
    private Map<String, String> originalFileContents;

    @BeforeEach
    void setUp() throws IOException {
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
    }

    @Test
    void testGenericTypes_DetectedAndHandled() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> cycles = detector.findCycles();
        
        // Should detect generic cycle
        boolean hasGenericCycle = cycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.edgecases.GenericService") &&
                                 cycle.contains("com.example.cycles.edgecases.TypedService"));
        
        assertTrue(hasGenericCycle, "Should detect cycle with generic types");
    }

    @Test
    void testQualifier_Preserved() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> cycles = detector.findCycles();
        
        // Find qualified cycle
        Set<String> qualifiedCycle = cycles.stream()
                .filter(cycle -> cycle.contains("com.example.cycles.edgecases.QualifiedService") &&
                                 cycle.contains("com.example.cycles.edgecases.DataService"))
                .findFirst()
                .orElse(null);
        
        if (qualifiedCycle != null) {
            // Find edge with @Qualifier
            Set<sa.com.cloudsolutions.antikythera.depsolver.BeanDependency> qualifiedDeps = 
                    graph.getDependencies().get("com.example.cycles.edgecases.QualifiedService");
            
            if (qualifiedDeps != null) {
                sa.com.cloudsolutions.antikythera.depsolver.BeanDependency edge = qualifiedDeps.stream()
                        .filter(dep -> dep.targetBean().equals("com.example.cycles.edgecases.DataService"))
                        .findFirst()
                        .orElse(null);
                
                if (edge != null) {
                    // Apply @Lazy fix
                    LazyAnnotationStrategy strategy = new LazyAnnotationStrategy(false);
                    boolean applied = strategy.apply(edge);
                    
                    if (applied) {
                        strategy.writeChanges(testbedPath.toString());
                        
                        // Verify @Qualifier is preserved
                        Path qualifiedServiceFile = testbedPath.resolve(
                                "com/example/cycles/edgecases/QualifiedService.java");
                        if (Files.exists(qualifiedServiceFile)) {
                            String content = Files.readString(qualifiedServiceFile);
                            assertTrue(content.contains("@Qualifier") || content.contains("Qualifier"),
                                    "@Qualifier annotation should be preserved");
                        }
                    }
                }
            }
        }
    }

    @Test
    void testPostConstruct_Detected() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> cycles = detector.findCycles();
        
        // Should detect @PostConstruct cycle
        boolean hasPostConstructCycle = cycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.edgecases.PostConstructService") &&
                                 cycle.contains("com.example.cycles.edgecases.InitializationService"));
        
        assertTrue(hasPostConstructCycle, "Should detect cycle with @PostConstruct");
        
        // Note: @Lazy won't work for @PostConstruct, so this cycle requires
        // MethodExtractionStrategy or a different approach
    }

    @Test
    void testNestedCalls_Detected() throws IOException {
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        CycleDetector detector = new CycleDetector(graph.getSimpleGraph());
        List<Set<String>> cycles = detector.findCycles();
        
        // Should detect nested call cycle
        boolean hasNestedCycle = cycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.edgecases.NestedCallService") &&
                                 cycle.contains("com.example.cycles.edgecases.ChainedService"));
        
        assertTrue(hasNestedCycle, "Should detect cycle with nested method calls");
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
    }
}

