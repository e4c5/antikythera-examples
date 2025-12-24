package com.raditha.spring.cycle;

import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependency;
import sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph;
import sa.com.cloudsolutions.antikythera.depsolver.CycleDetector;
import sa.com.cloudsolutions.antikythera.depsolver.EdgeSelector;
import sa.com.cloudsolutions.antikythera.depsolver.JohnsonCycleFinder;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        System.out.println("\n📋 Recommended fixes (" + edgesToCut.size() + " total):");
        for (BeanDependency edge : edgesToCut) {
            String fix = recommendFix(edge);
            System.out.println("  • " + edge + " → " + fix);
        }

        if (dryRun) {
            System.out.println("\n🔍 Dry run mode - no changes made");
            return;
        }

        // TODO: Apply fixes in Phase 1 (add @Lazy)
        System.out.println("\n⚠️  Automatic fix application not yet implemented");
        System.out.println("   Please manually apply @Lazy to the recommended injection points,");
        System.out.println("   or consider interface extraction for complex cycles.");
    }

    private void parseArgs(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> {
                    if (i + 1 < args.length) {
                        loadConfig(args[++i]);
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

    private void printHelp() {
        System.out.println("""
                Circular Dependency Detector

                Usage: java -jar antikythera-examples.jar cycle-detector [options]

                Options:
                  --config, -c <path>  Path to configuration YAML file
                  --dry-run, -n        Analyze only, don't modify files
                  --verbose, -v        Show detailed cycle information
                  --help, -h           Show this help message

                Example:
                  java -jar antikythera-examples.jar cycle-detector \\
                    --config cycle-detector.yml --dry-run --verbose
                """);
    }
}
