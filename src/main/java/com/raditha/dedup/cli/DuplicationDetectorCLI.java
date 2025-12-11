package com.raditha.dedup.cli;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.config.DuplicationDetectorSettings;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Command-line interface for the Duplication Detector.
 * 
 * Usage:
 * java -jar duplication-detector.jar [options] <file-or-directory>
 * 
 * Configuration priority: CLI arguments > generator.yml > defaults
 */
public class DuplicationDetectorCLI {

    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        try {
            CLIConfig config = parseArguments(args);

            if (config.showHelp) {
                printHelp();
                return;
            }

            if (config.showVersion) {
                System.out.println("Duplication Detector v" + VERSION);
                return;
            }

            runAnalysis(config);

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --help for usage information");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void runAnalysis(CLIConfig config) throws IOException {
        // Initialize Settings and parse source files (same as Logger.java,
        // TestFixer.java)
        Settings.loadConfigMap();

        // Apply CLI overrides to Settings
        if (config.basePath != null) {
            Settings.setProperty(Settings.BASE_PATH, config.basePath);
        }
        if (config.outputPath != null) {
            Settings.setProperty(Settings.OUTPUT_PATH, config.outputPath);
        }

        // Parse all source files
        AbstractCompiler.preProcess();

        // Load configuration (generator.yml + CLI overrides)
        DuplicationConfig dupConfig = DuplicationDetectorSettings.loadConfig(
                config.minLines,
                config.threshold,
                config.preset);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(dupConfig);

        // Get all compilation units from AntikytheraRuntime
        Map<String, CompilationUnit> allCUs = AntikytheraRunTime.getResolvedCompilationUnits();

        // Check for target_class in YAML (highest priority)
        String targetClass = DuplicationDetectorSettings.getTargetClass();

        // Filter to target class or target path if specified, otherwise process all
        List<Map.Entry<String, CompilationUnit>> targetCUs;
        if (targetClass != null && !targetClass.isEmpty()) {
            // Single class analysis from YAML
            targetCUs = allCUs.entrySet().stream()
                    .filter(e -> e.getKey().equals(targetClass))
                    .toList();

            if (targetCUs.isEmpty()) {
                System.err.println("Target class not found: " + targetClass);
                System.err.println("Make sure the class is in your base_path and properly loaded");
                return;
            }
            System.out.println("Analyzing single class from YAML: " + targetClass);
        } else if (config.targetPath != null) {
            // CLI target path filter
            targetCUs = allCUs.entrySet().stream()
                    .filter(e -> matchesTargetPath(e.getKey(), config.targetPath))
                    .toList();

            if (targetCUs.isEmpty()) {
                System.err.println("No Java files found matching: " + config.targetPath);
                return;
            }
        } else {
            // Process all files from Antikythera runtime (like Logger, TestFixer,
            // QueryOptimizer)
            targetCUs = new ArrayList<>(allCUs.entrySet());
        }

        System.out.printf("Analyzing %d Java files...%n", targetCUs.size());
        System.out.println();

        List<DuplicationReport> reports = new ArrayList<>();
        int fileCount = 0;

        for (var entry : targetCUs) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();
            fileCount++;

            try {
                // Show progress every 10 files or for large files
                if (fileCount % 10 == 0 || fileCount == 1) {
                    System.out.printf("Progress: %d/%d files (%.1f%%) - Current: %s%n",
                            fileCount, targetCUs.size(),
                            (fileCount * 100.0 / targetCUs.size()),
                            className.substring(className.lastIndexOf('.') + 1));
                }

                // Build source file path (like Logger.java does)
                Path sourceFile = Paths.get(Settings.getBasePath(), "src/main/java",
                        AbstractCompiler.classToPath(className));
                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);
                reports.add(report);
            } catch (Exception e) {
                System.err.println("Error analyzing " + className + ": " + e.getMessage());
            }
        }

        // Generate output
        if (config.jsonOutput) {
            printJsonReport(reports, dupConfig);
        } else {
            printTextReport(reports, dupConfig);
        }
    }

    /**
     * Check if a class name matches the target path filter.
     */
    private static boolean matchesTargetPath(String className, Path targetPath) {
        if (targetPath == null) {
            return true;
        }

        String targetStr = targetPath.toString();

        // Check if class name contains target path (package or path segment)
        return className.contains(targetStr.replace("/", ".")) ||
                className.replace(".", "/").contains(targetStr);
    }

    private static void printTextReport(List<DuplicationReport> reports, DuplicationConfig config) {
        int totalDuplicates = reports.stream()
                .mapToInt(DuplicationReport::getDuplicateCount)
                .sum();

        int totalClusters = reports.stream()
                .mapToInt(r -> r.clusters().size())
                .sum();

        System.out.println("=".repeat(80));
        System.out.println("DUPLICATION DETECTION SUMMARY");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.printf("Files analyzed: %d%n", reports.size());
        System.out.printf("Total duplicates found: %d in %d clusters%n", totalDuplicates, totalClusters);
        System.out.printf("Configuration: min-lines=%d, threshold=%.0f%%%n",
                config.minLines(), config.threshold() * 100);
        System.out.println();

        for (DuplicationReport report : reports) {
            if (report.hasDuplicates()) {
                System.out.println("-".repeat(80));
                System.out.println("File: " + report.sourceFile());
                System.out.println(report.getSummary());
                System.out.println();

                // Print cluster info
                if (!report.clusters().isEmpty()) {
                    System.out.println("Clusters:");
                    for (int i = 0; i < report.clusters().size(); i++) {
                        var cluster = report.clusters().get(i);
                        System.out.printf("  #%d: %s%n", i + 1, cluster.formatSummary());
                        if (cluster.recommendation() != null) {
                            System.out.printf("      Strategy: %s (confidence: %s)%n",
                                    cluster.recommendation().strategy(),
                                    cluster.recommendation().formatConfidence());
                        }
                    }
                    System.out.println();
                }
            }
        }
    }

    private static void printJsonReport(List<DuplicationReport> reports, DuplicationConfig config) {
        // Simple JSON output (would use proper JSON library in production)
        System.out.println("{");
        System.out.printf("  \"version\": \"%s\",%n", VERSION);
        System.out.printf("  \"filesAnalyzed\": %d,%n", reports.size());
        System.out.printf("  \"totalDuplicates\": %d,%n",
                reports.stream().mapToInt(DuplicationReport::getDuplicateCount).sum());
        System.out.println("  \"files\": [");

        for (int i = 0; i < reports.size(); i++) {
            DuplicationReport report = reports.get(i);
            if (report.hasDuplicates()) {
                System.out.println("    {");
                System.out.printf("      \"path\": \"%s\",%n", report.sourceFile());
                System.out.printf("      \"duplicates\": %d,%n", report.getDuplicateCount());
                System.out.printf("      \"clusters\": %d%n", report.clusters().size());
                System.out.print("    }");
                if (i < reports.size() - 1)
                    System.out.print(",");
                System.out.println();
            }
        }

        System.out.println("  ]");
        System.out.println("}");
    }

    private static CLIConfig parseArguments(String[] args) {
        CLIConfig config = new CLIConfig();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--help", "-h" -> config.showHelp = true;
                case "--version", "-v" -> config.showVersion = true;
                case "--json" -> config.jsonOutput = true;
                case "--strict" -> config.preset = "strict";
                case "--lenient" -> config.preset = "lenient";
                case "--base-path" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--base-path requires a value");
                    config.basePath = args[++i];
                }
                case "--output" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--output requires a value");
                    config.outputPath = args[++i];
                }
                case "--min-lines" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--min-lines requires a value");
                    config.minLines = Integer.parseInt(args[++i]);
                }
                case "--threshold" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--threshold requires a value");
                    config.threshold = Integer.parseInt(args[++i]);
                    if (config.threshold < 0 || config.threshold > 100) {
                        throw new IllegalArgumentException("Threshold must be 0-100");
                    }
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    config.targetPath = Paths.get(arg);
                }
            }
        }

        return config;
    }

    private static void printHelp() {
        System.out.println("Duplication Detector v" + VERSION);
        System.out.println();
        System.out.println("Usage: java -jar duplication-detector.jar [options] [file-or-directory]");
        System.out.println();
        System.out.println("If no target is specified, analyzes all files from generator.yml");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help, -h           Show this help message");
        System.out.println("  --version, -v        Show version information");
        System.out.println("  --base-path PATH     Source directory (overrides generator.yml)");
        System.out.println("  --output PATH        Output directory for reports");
        System.out.println("  --min-lines N        Minimum lines for duplicate detection (default: 5)");
        System.out.println("  --threshold N        Similarity threshold 0-100 (default: 75)");
        System.out.println("  --json               Output results as JSON");
        System.out.println("  --strict             Use strict preset (90% threshold, 7 lines)");
        System.out.println("  --lenient            Use lenient preset (60% threshold, 3 lines)");
    }

    private static class CLIConfig {
        Path targetPath;
        String basePath = null;
        String outputPath = null;
        int minLines = 0; // 0 = use YAML/default
        int threshold = 0; // 0 = use YAML/default
        String preset = null;
        boolean jsonOutput = false;
        boolean showHelp = false;
        boolean showVersion = false;
    }
}
