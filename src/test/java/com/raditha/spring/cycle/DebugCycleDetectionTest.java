package com.raditha.spring.cycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug tests to identify why certain cycles aren't being detected.
 * These tests include debug output to help diagnose issues.
 */
class DebugCycleDetectionTest {

    private Path testbedPath;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
        if (workspaceRoot.toString().contains("antikythera-examples")) {
            workspaceRoot = workspaceRoot.getParent();
        }
        testbedPath = workspaceRoot.resolve("spring-boot-cycles/src/main/java").normalize();
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);
    }

    @Test
    void debug_ExtractionPackage_BeansDetected() throws IOException {
        System.out.println("\n=== DEBUG: Extraction Package Beans ===");
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        // Check what's in AntikytheraRunTime
        System.out.println("\nAll types in AntikytheraRunTime:");
        sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes().keySet().stream()
                .filter(key -> key.contains("extraction"))
                .forEach(key -> {
                    sa.com.cloudsolutions.antikythera.generator.TypeWrapper wrapper = 
                            sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes().get(key);
                    System.out.println("  - " + key + " (isService: " + wrapper.isService() + 
                                     ", isComponent: " + wrapper.isComponent() + ")");
                });
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        System.out.println("\nTotal beans detected: " + simpleGraph.size());
        System.out.println("\nAll beans:");
        simpleGraph.keySet().forEach(bean -> System.out.println("  - " + bean));
        
        System.out.println("\nExtraction package beans:");
        simpleGraph.keySet().stream()
                .filter(bean -> bean.contains("extraction"))
                .forEach(bean -> {
                    System.out.println("  - " + bean);
                    Set<String> deps = simpleGraph.get(bean);
                    if (deps != null && !deps.isEmpty()) {
                        System.out.println("    Dependencies: " + deps);
                    }
                });
        
        // Check if extraction beans are detected
        long extractionBeans = simpleGraph.keySet().stream()
                .filter(bean -> bean.contains("extraction"))
                .count();
        
        System.out.println("\nExtraction beans found: " + extractionBeans);
        
        // Check what's missing
        System.out.println("\nMissing beans check:");
        System.out.println("  OrderProcessingService in resolvedTypes: " + 
                sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes()
                        .containsKey("com.example.cycles.extraction.OrderProcessingService"));
        System.out.println("  PaymentProcessingService in resolvedTypes: " + 
                sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes()
                        .containsKey("com.example.cycles.extraction.PaymentProcessingService"));
        
        assertTrue(extractionBeans >= 2, 
                "Should detect at least 2 beans in extraction package");
    }

    @Test
    void debug_ExtractionPackage_CycleDetection() throws IOException {
        System.out.println("\n=== DEBUG: Extraction Package Cycle Detection ===");
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        
        // Check dependencies
        System.out.println("\nOrderProcessingService dependencies:");
        Set<String> orderDeps = simpleGraph.get("com.example.cycles.extraction.OrderProcessingService");
        if (orderDeps != null) {
            System.out.println("  " + orderDeps);
        } else {
            System.out.println("  NOT FOUND in graph");
        }
        
        System.out.println("\nPaymentProcessingService dependencies:");
        Set<String> paymentDeps = simpleGraph.get("com.example.cycles.extraction.PaymentProcessingService");
        if (paymentDeps != null) {
            System.out.println("  " + paymentDeps);
        } else {
            System.out.println("  NOT FOUND in graph");
        }
        
        // Check full dependency map
        System.out.println("\nFull dependency map for extraction package:");
        graph.getDependencies().entrySet().stream()
                .filter(e -> e.getKey().contains("extraction"))
                .forEach(e -> {
                    System.out.println("  " + e.getKey() + " -> " + e.getValue());
                });
        
        CycleDetector detector = new CycleDetector(simpleGraph);
        List<Set<String>> cycles = detector.findCycles();
        
        System.out.println("\nTotal cycles detected: " + cycles.size());
        System.out.println("\nAll cycles:");
        cycles.forEach(cycle -> System.out.println("  " + cycle));
        
        // Check for extraction cycle
        boolean hasExtractionCycle = cycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.extraction.OrderProcessingService") &&
                                 cycle.contains("com.example.cycles.extraction.PaymentProcessingService"));
        
        System.out.println("\nExtraction cycle found: " + hasExtractionCycle);
        
        if (!hasExtractionCycle) {
            System.out.println("\n⚠️  PROBLEM: Extraction cycle not detected!");
            System.out.println("   OrderProcessingService in graph: " + 
                    simpleGraph.containsKey("com.example.cycles.extraction.OrderProcessingService"));
            System.out.println("   PaymentProcessingService in graph: " + 
                    simpleGraph.containsKey("com.example.cycles.extraction.PaymentProcessingService"));
        }
    }

    @Test
    void debug_EdgeCasesPackage_BeansDetected() throws IOException {
        System.out.println("\n=== DEBUG: Edge Cases Package Beans ===");
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        System.out.println("Total beans detected: " + simpleGraph.size());
        
        System.out.println("\nEdge cases package beans:");
        simpleGraph.keySet().stream()
                .filter(bean -> bean.contains("edgecases"))
                .forEach(bean -> {
                    System.out.println("  - " + bean);
                    Set<String> deps = simpleGraph.get(bean);
                    if (deps != null && !deps.isEmpty()) {
                        System.out.println("    Dependencies: " + deps);
                    }
                });
        
        // Check if edge cases beans are detected
        long edgeCaseBeans = simpleGraph.keySet().stream()
                .filter(bean -> bean.contains("edgecases"))
                .count();
        
        System.out.println("\nEdge case beans found: " + edgeCaseBeans);
        assertTrue(edgeCaseBeans >= 2, 
                "Should detect at least 2 beans in edgecases package");
    }

    @Test
    void debug_EdgeCasesPackage_GenericCycleDetection() throws IOException {
        System.out.println("\n=== DEBUG: Generic Types Cycle Detection ===");
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        // Check what's in AntikytheraRunTime for generic services
        System.out.println("\nAll types in AntikytheraRunTime (edgecases):");
        sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes().keySet().stream()
                .filter(key -> key.contains("edgecases") && (key.contains("Generic") || key.contains("Typed")))
                .forEach(key -> {
                    sa.com.cloudsolutions.antikythera.generator.TypeWrapper wrapper = 
                            sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedTypes().get(key);
                    System.out.println("  - " + key + " (isService: " + wrapper.isService() + 
                                     ", isComponent: " + wrapper.isComponent() + ")");
                });
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        
        // Check generic service dependencies
        System.out.println("\nGenericService dependencies:");
        Set<String> genericDeps = simpleGraph.get("com.example.cycles.edgecases.GenericService");
        if (genericDeps != null) {
            System.out.println("  " + genericDeps);
        } else {
            System.out.println("  NOT FOUND in graph");
        }
        
        System.out.println("\nTypedService dependencies:");
        Set<String> typedDeps = simpleGraph.get("com.example.cycles.edgecases.TypedService");
        if (typedDeps != null) {
            System.out.println("  " + typedDeps);
        } else {
            System.out.println("  NOT FOUND in graph");
        }
        
        // Check full dependency map
        System.out.println("\nFull dependency map for generic services:");
        graph.getDependencies().entrySet().stream()
                .filter(e -> e.getKey().contains("GenericService") || e.getKey().contains("TypedService"))
                .forEach(e -> {
                    System.out.println("  " + e.getKey() + " -> " + e.getValue());
                });
        
        CycleDetector detector = new CycleDetector(simpleGraph);
        List<Set<String>> cycles = detector.findCycles();
        
        System.out.println("\nTotal cycles detected: " + cycles.size());
        System.out.println("\nAll cycles:");
        cycles.forEach(cycle -> System.out.println("  " + cycle));
        
        // Check for generic cycle
        boolean hasGenericCycle = cycles.stream()
                .anyMatch(cycle -> cycle.contains("com.example.cycles.edgecases.GenericService") &&
                                 cycle.contains("com.example.cycles.edgecases.TypedService"));
        
        System.out.println("\nGeneric cycle found: " + hasGenericCycle);
        
        if (!hasGenericCycle) {
            System.out.println("\n⚠️  PROBLEM: Generic cycle not detected!");
            System.out.println("   GenericService in graph: " + 
                    simpleGraph.containsKey("com.example.cycles.edgecases.GenericService"));
            System.out.println("   TypedService in graph: " + 
                    simpleGraph.containsKey("com.example.cycles.edgecases.TypedService"));
        }
    }

    @Test
    void debug_AllPackages_Summary() throws IOException {
        System.out.println("\n=== DEBUG: All Packages Summary ===");
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();
        
        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        
        System.out.println("\nBeans by package:");
        simpleGraph.keySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        bean -> {
                            int lastDot = bean.lastIndexOf('.');
                            return lastDot > 0 ? bean.substring(0, bean.lastIndexOf('.', lastDot - 1)) : "root";
                        }))
                .forEach((pkg, beans) -> {
                    System.out.println("  " + pkg + ": " + beans.size() + " beans");
                    beans.forEach(bean -> System.out.println("    - " + bean));
                });
        
        CycleDetector detector = new CycleDetector(simpleGraph);
        List<Set<String>> cycles = detector.findCycles();
        
        System.out.println("\nTotal cycles: " + cycles.size());
        cycles.forEach(cycle -> System.out.println("  " + cycle));
    }
}

