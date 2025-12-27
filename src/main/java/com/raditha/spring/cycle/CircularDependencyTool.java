package com.raditha.spring.cycle;

import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependency;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.EdgeSelector;
import sa.com.cloudsolutions.antikythera.depsolver.InjectionType;
import sa.com.cloudsolutions.antikythera.depsolver.InterfaceExtractionStrategy;
import sa.com.cloudsolutions.antikythera.depsolver.JohnsonCycleFinder;
import sa.com.cloudsolutions.antikythera.depsolver.LazyAnnotationStrategy;
import sa.com.cloudsolutions.antikythera.depsolver.SetterInjectionStrategy;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI tool for detecting and eliminating circular dependencies in Spring Boot
 * applications.
 *
 * <p>
 * Usage:
 * </p>
 * 
 * <pre>
 * java -jar antikythera-examples.jar cycle-detector \
 *   --config cycle-detector.yml \
 *   --dry-run
 * </pre>
 */
public class CircularDependencyTool {

    private boolean dryRun = false;
    private boolean verbose = false;
    private String strategy = "auto"; // auto, lazy, setter, interface, extract

    public static void main(String[] args) throws IOException {
        CircularDependencyTool tool = new CircularDependencyTool();
        tool.run(args);
    }

    public void run(String[] args) throws IOException {
        parseArgs(args);

        // Parse all source files
        System.out.println("Parsing source files...");
        AbstractCompiler.preProcess();

        // Build dependency graph
        System.out.println("Building dependency graph...");
        BeanDependencyGraph graph = new BeanDependencyGraph();
        graph.build();

        Map<String, Set<String>> simpleGraph = graph.getSimpleGraph();
        System.out.println("Found " + simpleGraph.size() + " Spring beans");

        // Detect cycles using Tarjan's SCC
        System.out.println("\nDetecting cycles...");
        CycleDetector detector = new CycleDetector(simpleGraph);
        List<Set<String>> sccs = detector.findCycles();

        if (sccs.isEmpty()) {
            System.out.println("\n✅ No circular dependencies detected!");
            return;
        }

        System.out.println("\n⚠️  Found " + sccs.size() + " strongly connected component(s) with cycles:");

        // Enumerate all elementary cycles
        JohnsonCycleFinder finder = new JohnsonCycleFinder(simpleGraph);
        List<List<String>> allCycles = finder.findAllCycles();

        System.out.println("   Total elementary cycles: " + allCycles.size());

        if (verbose) {
            System.out.println("\nCycle details:");
            for (int i = 0; i < allCycles.size(); i++) {
                List<String> cycle = allCycles.get(i);
                System.out.println("  " + (i + 1) + ". " + formatCycle(cycle));
            }
        }

        // Select edges to cut
        System.out.println("\nSelecting optimal edges to break cycles...");
        EdgeSelector selector = new EdgeSelector(graph.getDependencies());
        Set<BeanDependency> edgesToCut = selector.selectEdgesToCut(allCycles);

        // For bidirectional cycles (A↔B), we need to fix both sides
        // Add reverse edges for simple 2-node cycles
        Set<BeanDependency> additionalEdges = findBidirectionalEdges(edgesToCut, graph.getDependencies());
        edgesToCut.addAll(additionalEdges);

        System.out.println("\n📋 Recommended fixes (" + edgesToCut.size() + " total):");
        for (BeanDependency edge : edgesToCut) {
            String fix = recommendFix(edge);
            System.out.println("  • " + edge + " → " + fix);
        }

        if (dryRun) {
            System.out.println("\n🔍 Dry run mode - no changes made");
            return;
        }

        // Apply fixes
        System.out.println("\nApplying fixes...");
        int applied = applyFixes(edgesToCut);

        if (applied == 0) {
            System.out.println("\n⚠️  No fixes were applied. Please review the recommendations above.");
            return;
        }

        // Write changes
        String basePath = Settings.getProperty("base_path", String.class)
                .orElse("src/main/java");
        writeAllChanges(basePath);

        // Validate fixes
        validateFixes(basePath);
    }

    /**
     * Apply fixes using appropriate strategies.
     * 
     * @param edgesToCut The edges to fix
     * @return Number of successfully applied fixes
     */
    private int applyFixes(Set<BeanDependency> edgesToCut) {
        LazyAnnotationStrategy lazyStrategy = new LazyAnnotationStrategy(false);
        SetterInjectionStrategy setterStrategy = new SetterInjectionStrategy(false);
        InterfaceExtractionStrategy ifaceStrategy = new InterfaceExtractionStrategy(false);

        int applied = 0;
        int failed = 0;

        for (BeanDependency edge : edgesToCut) {
            boolean success = applyStrategy(edge, lazyStrategy, setterStrategy, ifaceStrategy);

            if (success) {
                applied++;
            } else {
                failed++;
                System.out.println("⚠️  Failed to fix: " + edge);
            }
        }

        System.out.println("\n✅ Applied " + applied + " fix(es), " + failed + " failed");

        // Store strategies for writing changes
        this.lazyStrategy = lazyStrategy;
        this.setterStrategy = setterStrategy;
        this.ifaceStrategy = ifaceStrategy;

        return applied;
    }

    /**
     * Apply the appropriate strategy based on CLI selection and edge type.
     */
    private boolean applyStrategy(BeanDependency edge, LazyAnnotationStrategy lazyStrategy,
            SetterInjectionStrategy setterStrategy, InterfaceExtractionStrategy ifaceStrategy) {

        // If user specified a strategy, use it
        return switch (strategy) {
            case "lazy" -> lazyStrategy.apply(edge);
            case "setter" -> setterStrategy.apply(edge);
            case "interface" -> ifaceStrategy.apply(edge);
            case "extract" -> {
                System.out.println("⚠️  Method extraction requires cycle-level operation, using lazy for edges");
                yield lazyStrategy.apply(edge);
            }
            default -> applyAutoStrategy(edge, lazyStrategy, setterStrategy, ifaceStrategy);
        };
    }

    /**
     * Automatically select best strategy based on injection type.
     */
    private boolean applyAutoStrategy(BeanDependency edge, LazyAnnotationStrategy lazyStrategy,
            SetterInjectionStrategy setterStrategy, InterfaceExtractionStrategy ifaceStrategy) {
        return switch (edge.injectionType()) {
            case FIELD, SETTER -> lazyStrategy.apply(edge);
            case CONSTRUCTOR -> {
                // Try @Lazy on parameter first (simpler than setter conversion)
                boolean success = lazyStrategy.apply(edge);
                if (!success) {
                    System.out.println("   Falling back to setter injection for: " + edge);
                    yield setterStrategy.apply(edge);
                }
                yield success;
            }
            case BEAN_METHOD -> {
                // Try @Lazy on @Bean method parameter first (new capability)
                boolean success = lazyStrategy.apply(edge);
                if (!success) {
                    // Fall back to interface extraction
                    yield ifaceStrategy.apply(edge);
                }
                yield success;
            }
        };
    }

    private LazyAnnotationStrategy lazyStrategy;
    private SetterInjectionStrategy setterStrategy;
    private InterfaceExtractionStrategy ifaceStrategy;

    /**
     * Write all changes to disk.
     */
    private void writeAllChanges(String basePath) throws IOException {
        if (lazyStrategy != null) {
            lazyStrategy.writeChanges(basePath);
        }
        if (setterStrategy != null) {
            setterStrategy.writeChanges(basePath);
        }
        if (ifaceStrategy != null) {
            ifaceStrategy.writeChanges(basePath);
        }
    }

    /**
     * Validate that fixes actually broke the cycles.
     */
    private void validateFixes(String basePath) throws IOException {
        System.out.println("\n🔍 Validating fixes...");

        // Re-parse and check for remaining cycles
        // Use resetAll() to clear compilation unit cache so files are re-read from disk
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        BeanDependencyGraph newGraph = new BeanDependencyGraph();
        newGraph.build();
        CycleDetector newDetector = new CycleDetector(newGraph.getSimpleGraph());
        List<Set<String>> remainingCycles = newDetector.findCycles();

        if (remainingCycles.isEmpty()) {
            System.out.println("✅ All cycles successfully broken!");
        } else {
            System.out.println("⚠️  " + remainingCycles.size() + " cycle(s) still remain:");
            for (int i = 0; i < remainingCycles.size(); i++) {
                Set<String> cycle = remainingCycles.get(i);
                System.out.println("   Cycle " + (i + 1) + ": " + cycle);
            }
            System.out.println("   Consider manual review or using a different strategy.");
        }
    }

    private void parseArgs(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> {
                    if (i + 1 < args.length) {
                        loadConfig(args[++i]);
                    }
                }
                case "--strategy", "-s" -> {
                    if (i + 1 < args.length) {
                        strategy = args[++i];
                    }
                }
                case "--dry-run", "-n" -> dryRun = true;
                case "--verbose", "-v" -> verbose = true;
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
            }
        }
    }

    private void loadConfig(String configPath) throws IOException {
        Path path = Path.of(configPath);
        if (Files.exists(path)) {
            Settings.loadConfigMap(path.toFile());
            System.out.println("Loaded config: " + configPath);
        } else {
            System.err.println("Config file not found: " + configPath);
        }
    }

    private String formatCycle(List<String> cycle) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            String node = cycle.get(i);
            sb.append(node.substring(node.lastIndexOf('.') + 1));
            if (i < cycle.size() - 1) {
                sb.append(" → ");
            }
        }
        sb.append(" → ").append(cycle.get(0).substring(cycle.get(0).lastIndexOf('.') + 1));
        return sb.toString();
    }

    private String recommendFix(BeanDependency edge) {
        return switch (edge.injectionType()) {
            case FIELD -> "Add @Lazy to field '" + edge.fieldName() + "'";
            case SETTER -> "Add @Lazy to setter parameter";
            case CONSTRUCTOR -> "Convert to setter injection with @Lazy, or extract interface";
            case BEAN_METHOD -> "Add @Lazy to @Bean method parameter, or redesign";
        };
    }

    /**
     * Find reverse edges for bidirectional cycles (A↔B).
     * For simple 2-node cycles, we need to fix both sides.
     */
    private Set<BeanDependency> findBidirectionalEdges(Set<BeanDependency> selectedEdges,
            Map<String, Set<BeanDependency>> allDependencies) {
        Set<BeanDependency> additional = new HashSet<>();

        for (BeanDependency edge : selectedEdges) {
            String from = edge.fromBean();
            String to = edge.targetBean();

            // Check if there's a reverse edge (to → from)
            Set<BeanDependency> reverseDeps = allDependencies.get(to);
            if (reverseDeps != null) {
                for (BeanDependency reverseEdge : reverseDeps) {
                    if (reverseEdge.targetBean().equals(from)) {
                        // Found bidirectional cycle - add reverse edge if not already selected
                        if (!selectedEdges.contains(reverseEdge)) {
                            additional.add(reverseEdge);
                        }
                    }
                }
            }
        }

        return additional;
    }

    private void printHelp() {
        System.out.println("""
                Circular Dependency Detector

                Usage: java -jar antikythera-examples.jar cycle-detector [options]

                Options:
                  --config, -c <path>    Path to configuration YAML file
                  --strategy, -s <type>  Resolution strategy: auto (default), lazy, setter, interface, extract
                  --dry-run, -n          Analyze only, don't modify files
                  --verbose, -v          Show detailed cycle information
                  --help, -h             Show this help message

                Strategies:
                  auto       - Automatically select best strategy per edge (default)
                  lazy       - Always use @Lazy annotation
                  setter     - Convert constructor injection to setter with @Lazy
                  interface  - Extract interface to break coupling
                  extract    - Extract methods to mediator class

                Example:
                  java -jar antikythera-examples.jar cycle-detector \\
                    --config cycle-detector.yml --strategy lazy --dry-run --verbose
                """);
    }
}
