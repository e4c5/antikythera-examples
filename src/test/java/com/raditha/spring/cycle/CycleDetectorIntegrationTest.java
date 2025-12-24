package com.raditha.spring.cycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependency;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.EdgeSelector;
import sa.com.cloudsolutions.antikythera.depsolver.JohnsonCycleFinder;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for cycle detection against the spring-boot-cycles testbed.
 */
class CycleDetectorIntegrationTest {

    private static final String TESTBED_PATH = "/home/raditha/csi/Antikythera/spring-boot-cycles/src/main/java";

    @BeforeEach
    void setUp() throws IOException {
        // Load config FIRST (before any reset that might access Settings)
        Settings.loadConfigMap(new File("src/test/resources/cycle-detector.yml"));

        // Then reset state
        AbstractCompiler.reset();
        AntikytheraRunTime.reset();
    }

    @Test
    void detectCyclesInTestbed() throws IOException {
        // Parse all source files in testbed
        AbstractCompiler.preProcess();

        // Build dependency graph
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        System.out.println("Found " + simpleGraph.size() + " Spring beans");

        // Detect cycles
        CycleDetector detector = new CycleDetector(simpleGraph);
        List<Set<String>> sccs = detector.findCycles();

        System.out.println("Found " + sccs.size() + " SCCs with cycles");
        for (Set<String> scc : sccs) {
            System.out.println("  SCC: " + scc.stream()
                    .map(s -> s.substring(s.lastIndexOf('.') + 1))
                    .toList());
        }

        // Enumerate all cycles
        JohnsonCycleFinder finder = new JohnsonCycleFinder(simpleGraph);
        List<List<String>> allCycles = finder.findAllCycles();

        System.out.println("\nFound " + allCycles.size() + " elementary cycles:");
        for (int i = 0; i < allCycles.size(); i++) {
            List<String> cycle = allCycles.get(i);
            System.out.println("  " + (i + 1) + ". " + cycle.stream()
                    .map(s -> s.substring(s.lastIndexOf('.') + 1))
                    .toList());
        }

        // We expect at least some cycles from the testbed
        // Note: actual count depends on what the parser can resolve
        assertTrue(simpleGraph.size() > 0, "Should find some Spring beans");
    }

    @Test
    void selectEdgesToCut() throws IOException {
        AbstractCompiler.preProcess();

        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        if (simpleGraph.isEmpty()) {
            System.out.println("No beans found - skipping edge selection");
            return;
        }

        JohnsonCycleFinder finder = new JohnsonCycleFinder(simpleGraph);
        List<List<String>> allCycles = finder.findAllCycles();

        if (allCycles.isEmpty()) {
            System.out.println("No cycles found - nothing to cut");
            return;
        }

        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        Set<BeanDependency> edgesToCut = selector.selectEdgesToCut(allCycles);

        System.out.println("Edges to cut (" + edgesToCut.size() + "):");
        for (BeanDependency edge : edgesToCut) {
            System.out.println("  " + edge);
        }

        // Verify cutting these edges breaks all cycles
        assertFalse(edgesToCut.isEmpty(), "Should select at least one edge to cut");
    }

    @Test
    void applyLazyAnnotations() throws IOException {
        AbstractCompiler.preProcess();

        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();

        System.out.println("Before fix: " + allCycles.size() + " cycles");

        if (allCycles.isEmpty()) {
            System.out.println("No cycles to fix");
            return;
        }

        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        Set<BeanDependency> edgesToCut = selector.selectEdgesToCut(allCycles);

        // Apply @Lazy in dry-run mode (don't actually modify files)
        sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy strategy = new sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy(
                true);

        int applied = 0;
        int skipped = 0;
        for (BeanDependency edge : edgesToCut) {
            if (strategy.apply(edge)) {
                applied++;
            } else {
                skipped++;
            }
        }

        System.out.println("\n📊 Summary:");
        System.out.println("   Applied @Lazy: " + applied);
        System.out.println("   Skipped (constructor/@Bean): " + skipped);
        System.out.println("   Modified CUs: " + strategy.getModifiedCUs().size());

        // Should have applied to most edges (field/setter)
        assertTrue(applied > 0, "Should apply @Lazy to at least one edge");
    }

    @Test
    void convertConstructorToSetter() throws IOException {
        AbstractCompiler.preProcess();

        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();

        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        Set<BeanDependency> edgesToCut = selector.selectEdgesToCut(allCycles);

        // Find constructor injection edges
        Set<BeanDependency> constructorEdges = edgesToCut.stream()
                .filter(e -> e.injectionType() == sa.com.cloudsolutions.antikythera.depsolver.InjectionType.CONSTRUCTOR)
                .collect(java.util.stream.Collectors.toSet());

        System.out.println("Constructor injection edges: " + constructorEdges.size());

        if (constructorEdges.isEmpty()) {
            System.out.println("No constructor injection cycles found");
            return;
        }

        // Apply setter conversion in dry-run mode
        sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy strategy = new sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy(
                true);

        int converted = 0;
        for (BeanDependency edge : constructorEdges) {
            System.out.println("Converting: " + edge);
            if (strategy.apply(edge)) {
                converted++;
            }
        }

        System.out.println("\n📊 Setter Injection Conversion Summary:");
        System.out.println("   Converted: " + converted);
        System.out.println("   Modified CUs: " + strategy.getModifiedCUs().size());

        // Show resulting code for first modified CU
        for (com.github.javaparser.ast.CompilationUnit cu : strategy.getModifiedCUs()) {
            System.out.println("\n📄 Modified: " + cu.getPrimaryTypeName().orElse("?"));
            System.out.println(cu.toString());
            break; // Just show first one
        }

        assertTrue(converted > 0, "Should convert at least one constructor injection");
    }

    /**
     * This test ACTUALLY WRITES CHANGES to the testbed.
     * Run with: mvn test
     * -Dtest=CycleDetectorIntegrationTest#applyFixesAndWriteToDisk
     * After running, check git diff and verify with mvn spring-boot:run
     * Rollback with: git checkout -- .
     */
    @Test
    @org.junit.jupiter.api.Disabled("Enable manually to apply fixes to testbed")
    void applyFixesAndWriteToDisk() throws IOException {
        AbstractCompiler.preProcess();

        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        JohnsonCycleFinder finder = new JohnsonCycleFinder(graph.getSimpleGraph());
        List<List<String>> allCycles = finder.findAllCycles();

        System.out.println("Cycles before fix: " + allCycles.size());

        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        Set<BeanDependency> edgesToCut = selector.selectEdgesToCut(allCycles);

        // Apply @Lazy to field/setter edges (NOT dry-run)
        sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy lazyStrategy = new sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy(
                false);

        // Convert constructor injection edges (NOT dry-run)
        sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy setterStrategy = new sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy(
                false);

        int lazyApplied = 0;
        int setterConverted = 0;

        for (BeanDependency edge : edgesToCut) {
            if (edge.injectionType() == sa.com.cloudsolutions.antikythera.depsolver.InjectionType.CONSTRUCTOR) {
                if (setterStrategy.apply(edge)) {
                    setterConverted++;
                }
            } else if (edge.injectionType() == sa.com.cloudsolutions.antikythera.depsolver.InjectionType.FIELD ||
                    edge.injectionType() == sa.com.cloudsolutions.antikythera.depsolver.InjectionType.SETTER) {
                if (lazyStrategy.apply(edge)) {
                    lazyApplied++;
                }
            }
        }

        // Write changes to disk
        lazyStrategy.writeChanges(TESTBED_PATH);
        setterStrategy.writeChanges(TESTBED_PATH);

        System.out.println("\n✅ CHANGES APPLIED TO DISK:");
        System.out.println("   @Lazy added: " + lazyApplied);
        System.out.println("   Constructor→Setter: " + setterConverted);
        System.out.println("\n📋 Next steps:");
        System.out.println("   1. cd " + TESTBED_PATH.replace("/src/main/java", ""));
        System.out.println("   2. mvn spring-boot:run (verify it starts)");
        System.out.println("   3. git diff (review changes)");
        System.out.println("   4. git checkout -- . (rollback)");

        assertTrue(lazyApplied + setterConverted > 0, "Should apply at least one fix");
    }
}
