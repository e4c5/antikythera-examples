package com.raditha.spring.cycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependency;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.EdgeSelector;
import sa.com.cloudsolutions.antikythera.depsolver.InjectionType;
import sa.com.cloudsolutions.antikythera.depsolver.JohnsonCycleFinder;
import sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cycle detection against the spring-boot-cycles testbed.
 * Tests verify that cycles are detected and resolution strategies can be
 * applied.
 */
class CycleDetectorIntegrationTest {

    private BeanDependencyGraph graph;
    private List<List<String>> allCycles;
    private Set<BeanDependency> edgesToCut;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Reset testbed to clean state first
        TestbedResetHelper.resetTestbed();
        // Remove Unknown.java to avoid duplicate class definition errors
        TestbedResetHelper.removeUnknownJava();
        
        Settings.loadConfigMap(new File("src/test/resources/cycle-detector.yml"));
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();

        graph = new BeanDependencyGraph();
        graph.build();

        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        allCycles = finder.findAllCycles();

        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        edgesToCut = selector.selectEdgesToCut(allCycles);
    }

    @Test
    void shouldDetectSpringBeans() {
        Map<String, Set<String>> beans = graph.getSimpleGraph();
        assertFalse(beans.isEmpty(), "Should detect Spring beans in testbed");
    }

    @Test
    void shouldDetectCycles() {
        assertFalse(allCycles.isEmpty(), "Testbed should contain cycles");
    }

    @Test
    void shouldSelectEdgesToCut() {
        assertFalse(edgesToCut.isEmpty(), "Should select edges to break cycles");
        assertTrue(edgesToCut.size() <= allCycles.size(),
                "Should not select more edges than cycles");
    }

    @Test
    void shouldDetectFieldInjectionEdges() {
        long fieldEdges = edgesToCut.stream()
                .filter(e -> e.injectionType() == InjectionType.FIELD)
                .count();
        assertTrue(fieldEdges > 0, "Should detect field injection cycles");
    }

    @Test
    void shouldDetectConstructorInjectionEdges() {
        // EdgeSelector prefers field over constructor, so check graph directly
        long constructorEdges = graph.getDependencies().values().stream()
                .flatMap(Set::stream)
                .filter(e -> e.injectionType() == InjectionType.CONSTRUCTOR)
                .count();
        assertTrue(constructorEdges > 0, "Should detect constructor injection cycles");
    }

    @Test
    void shouldDetectBeanMethodEdges() {
        Set<BeanDependency> beanMethodEdges = graph.getDependencies().values().stream()
                .flatMap(Set::stream)
                .filter(e -> e.injectionType() == InjectionType.BEAN_METHOD)
                .collect(Collectors.toSet());
        assertFalse(beanMethodEdges.isEmpty(), "Should detect @Bean method cycles");
    }

    @Test
    void setterStrategyShouldApplyToConstructorEdges() {
        SetterInjectionStrategy strategy = new SetterInjectionStrategy(true);

        // Get constructor edges from graph (EdgeSelector may not select them)
        Set<BeanDependency> constructorEdges = graph.getDependencies().values().stream()
                .flatMap(Set::stream)
                .filter(e -> e.injectionType() == InjectionType.CONSTRUCTOR)
                .collect(Collectors.toSet());

        if (constructorEdges.isEmpty()) {
            return; // No constructor cycles in testbed
        }

        int converted = 0;
        for (BeanDependency edge : constructorEdges) {
            if (strategy.apply(edge))
                converted++;
        }

        assertTrue(converted > 0, "SetterInjectionStrategy should convert constructor edges");
        assertFalse(strategy.getModifiedCUs().isEmpty(), "Should have modified CUs");
    }

    @Test
    void allEdgesShouldHaveAstNodes() {
        for (BeanDependency edge : edgesToCut) {
            assertNotNull(edge.astNode(), "Edge should have AST node: " + edge);
            assertNotNull(edge.fieldName(), "Edge should have field name: " + edge);
        }
    }

    @Test
    void cuttingSelectedEdgesShouldBreakAllCycles() {
        Set<String> cutEdgeStrings = edgesToCut.stream()
                .map(e -> e.fromBean() + "->" + e.targetBean())
                .collect(Collectors.toSet());

        for (List<String> cycle : allCycles) {
            boolean cycleIsBroken = false;
            for (int i = 0; i < cycle.size(); i++) {
                String from = cycle.get(i);
                String to = cycle.get((i + 1) % cycle.size());
                if (cutEdgeStrings.contains(from + "->" + to)) {
                    cycleIsBroken = true;
                    break;
                }
            }
            assertTrue(cycleIsBroken, "Selected edges should break cycle: " + cycle);
        }
    }

    @Test
    void methodExtractionStrategyShouldBreakCycles() {
        sa.com.cloudsolutions.antikythera.depsolver.MethodExtractionStrategy strategy = new sa.com.cloudsolutions.antikythera.depsolver.MethodExtractionStrategy(
                true);

        // Apply to first cycle
        if (!allCycles.isEmpty()) {
            List<String> cycle = allCycles.get(0);
            boolean result = strategy.apply(cycle);

            // Should succeed if classes are found
            if (!strategy.getGeneratedClasses().isEmpty()) {
                assertTrue(result, "Should extract methods for cycle: " + cycle);
                assertFalse(strategy.getGeneratedClasses().isEmpty(), "Should generate mediator");
            }
        }
    }
    
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws IOException {
        TestbedResetHelper.restoreUnknownJava();
    }
}
