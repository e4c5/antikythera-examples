package com.raditha.dedup.cli;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.config.SimilarityWeights;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Command-line interface for the Duplication Detector.
 * 
 * Usage:
 * java -jar duplication-detector.jar [options] <file-or-directory>
 * 
 * Options:
 * --min-lines N Minimum lines for duplicate detection (default: 5)
 * --threshold N Similarity threshold 0-100 (default: 75)
 * --json Output results as JSON
 * --strict Use strict preset (90% threshold, 7 min lines)
 * --lenient Use lenient preset (60% threshold, 3 min lines)
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
        DuplicationConfig dupConfig = createDuplicationConfig(config);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(dupConfig);

        List<Path> javaFiles = findJavaFiles(config.targetPath);

        if (javaFiles.isEmpty()) {
            System.err.println("No Java files found in: " + config.targetPath);
            return;
        }

        System.out.printf("Analyzing %d Java files...%n", javaFiles.size());
        System.out.println();

        List<DuplicationReport> reports = new ArrayList<>();
        JavaParser parser = new JavaParser();

        for (Path file : javaFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    DuplicationReport report = analyzer.analyzeFile(
                            result.getResult().get(),
                            file);
                    reports.add(report);
                }
            } catch (Exception e) {
                System.err.println("Error analyzing " + file + ": " + e.getMessage());
            }
        }

        // Generate output
        if (config.jsonOutput) {
            printJsonReport(reports, dupConfig);
        } else {
            printTextReport(reports, dupConfig);
        }
    }

    private static List<Path> findJavaFiles(Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            return target.toString().endsWith(".java") ? List.of(target) : List.of();
        }

        if (Files.isDirectory(target)) {
            try (Stream<Path> paths = Files.walk(target)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
        }

        return List.of();
    }

    private static DuplicationConfig createDuplicationConfig(CLIConfig config) {
        if (config.preset != null) {
            return switch (config.preset) {
                case "strict" -> DuplicationConfig.strict();
                case "lenient" -> DuplicationConfig.lenient();
                default -> DuplicationConfig.moderate();
            };
        }

        double threshold = config.threshold / 100.0;
        return new DuplicationConfig(
                config.minLines,
                threshold,
                SimilarityWeights.balanced(),
                false,
                List.of());
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

        if (!config.showHelp && !config.showVersion && config.targetPath == null) {
            throw new IllegalArgumentException("No target file or directory specified");
        }

        return config;
    }

    private static void printHelp() {
        System.out.println("Duplication Detector v" + VERSION);
        System.out.println();
        System.out.println("Usage: java -jar duplication-detector.jar [options] <file-or-directory>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help, -h           Show this help message");
        System.out.println("  --version, -v        Show version information");
        System.out.println("  --min-lines N        Minimum lines for duplicate (default: 5)");
        System.out.println("  --threshold N        Similarity threshold 0-100 (default: 75)");
        System.out.println("  --strict             Use strict preset (90% threshold, 7 lines)");
        System.out.println("  --lenient            Use lenient preset (60% threshold, 3 lines)");
        System.out.println("  --json               Output results as JSON");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Analyze single file");
        System.out.println("  java -jar duplication-detector.jar MyClass.java");
        System.out.println();
        System.out.println("  # Analyze directory with custom threshold");
        System.out.println("  java -jar duplication-detector.jar --threshold 85 src/main/java");
        System.out.println();
        System.out.println("  # Strict analysis with JSON output");
        System.out.println("  java -jar duplication-detector.jar --strict --json src/");
    }

    private static class CLIConfig {
        Path targetPath;
        int minLines = 5;
        int threshold = 75;
        boolean jsonOutput = false;
        boolean showHelp = false;
        boolean showVersion = false;
        String preset = null;
    }
}
