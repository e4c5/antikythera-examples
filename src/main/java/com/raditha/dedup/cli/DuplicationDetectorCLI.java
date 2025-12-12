package com.raditha.dedup.cli;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.config.DuplicationDetectorSettings;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringVerifier;
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

            // Run detection or refactoring based on command
            if (config.command.equals("refactor")) {
                runRefactoring(config);
            } else {
                runAnalysis(config);
            }

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

    private static void runRefactoring(CLIConfig config) throws IOException {
        // First run detection
        System.out.println("=== PHASE 1: Duplicate Detection ===");
        System.out.println();

        // Initialize Settings
        Settings.loadConfigMap();
        if (config.basePath != null) {
            Settings.setProperty(Settings.BASE_PATH, config.basePath);
        }

        AbstractCompiler.preProcess();

        // Load configuration
        DuplicationConfig dupConfig = DuplicationDetectorSettings.loadConfig(
                config.minLines,
                config.threshold,
                config.preset);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(dupConfig);

        // Get compilation units
        Map<String, CompilationUnit> allCUs = AntikytheraRunTime.getResolvedCompilationUnits();
        String targetClass = DuplicationDetectorSettings.getTargetClass();

        List<Map.Entry<String, CompilationUnit>> targetCUs;
        if (targetClass != null && !targetClass.isEmpty()) {
            targetCUs = allCUs.entrySet().stream()
                    .filter(e -> e.getKey().equals(targetClass))
                    .toList();
        } else if (config.targetPath != null) {
            targetCUs = allCUs.entrySet().stream()
                    .filter(e -> matchesTargetPath(e.getKey(), config.targetPath))
                    .toList();
        } else {
            targetCUs = new ArrayList<>(allCUs.entrySet());
        }

        System.out.printf("Analyzing %d Java files...%n", targetCUs.size());
        List<DuplicationReport> reports = new ArrayList<>();

        for (var entry : targetCUs) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            try {
                Path sourceFile = Paths.get(Settings.getBasePath(), "src/main/java",
                        AbstractCompiler.classToPath(className));
                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);
                reports.add(report);
            } catch (Exception e) {
                System.err.println("Error analyzing " + className + ": " + e.getMessage());
            }
        }

        // Show detection summary
        int totalDuplicates = reports.stream()
                .mapToInt(r -> r.duplicates().size())
                .sum();
        int totalClusters = reports.stream()
                .mapToInt(r -> r.clusters().size())
                .sum();

        System.out.println();
        System.out.printf("Found %d duplicate pairs in %d clusters%n", totalDuplicates, totalClusters);
        System.out.println();

        if (totalClusters == 0) {
            System.out.println("No duplicates found. Nothing to refactor.");
            return;
        }

        // Phase 2: Refactoring
        System.out.println("=== PHASE 2: Automated Refactoring ===");
        System.out.println();

        // Determine verification level
        RefactoringVerifier.VerificationLevel verifyLevel = switch (config.verifyMode) {
            case "none" -> RefactoringVerifier.VerificationLevel.NONE;
            case "test" -> RefactoringVerifier.VerificationLevel.TEST;
            default -> RefactoringVerifier.VerificationLevel.COMPILE;
        };

        // Create refactoring engine
        Path projectRoot = Paths.get(Settings.getBasePath());
        RefactoringEngine.RefactoringMode mode = switch (config.refactorMode) {
            case "batch" -> RefactoringEngine.RefactoringMode.BATCH;
            case "dry-run" -> RefactoringEngine.RefactoringMode.DRY_RUN;
            default -> RefactoringEngine.RefactoringMode.INTERACTIVE;
        };

        RefactoringEngine engine = new RefactoringEngine(projectRoot, mode, verifyLevel);

        // Process each report
        int totalSuccess = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        for (DuplicationReport report : reports) {
            if (report.clusters().isEmpty()) {
                continue;
            }

            System.out.println("Processing file: " + report.sourceFile().getFileName());
            RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

            totalSuccess += session.getSuccessful().size();
            totalSkipped += session.getSkipped().size();
            totalFailed += session.getFailed().size();
        }

        // Final summary
        System.out.println();
        System.out.println("=== FINAL SUMMARY ===");
        System.out.printf("✓ Successful refactorings: %d%n", totalSuccess);
        System.out.printf("⊘ Skipped: %d%n", totalSkipped);
        System.out.printf("✗ Failed: %d%n", totalFailed);
        System.out.println();

        if (totalSuccess > 0) {
            System.out.println("Refactoring complete! Please review the changes and run your tests.");
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
        System.out.println("DUPLICATION DETECTION REPORT");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.printf("Files analyzed: %d%n", reports.size());
        System.out.printf("Total duplicates found: %d%n", totalDuplicates);
        System.out.printf("Duplicate clusters: %d%n", totalClusters);
        System.out.printf("Configuration: min-lines=%d, threshold=%.0f%%%n",
                config.minLines(), config.threshold() * 100);
        System.out.println();

        if (totalDuplicates == 0) {
            System.out.println("✓ No significant code duplication found!");
            System.out.println();
            return;
        }

        for (DuplicationReport report : reports) {
            if (!report.hasDuplicates()) {
                continue;
            }

            System.out.println("-".repeat(80));
            System.out.println("File: " + report.sourceFile().getFileName());
            System.out.println("-".repeat(80));
            System.out.println();

            // Show top duplicates with details
            var duplicates = report.duplicates();
            for (int i = 0; i < Math.min(10, duplicates.size()); i++) {
                var pair = duplicates.get(i);
                var seq1 = pair.seq1();
                var seq2 = pair.seq2();
                var similarity = pair.similarity();

                System.out.printf("DUPLICATE #%d (Similarity: %.1f%%)%n", i + 1,
                        similarity.overallScore() * 100);
                System.out.println();

                // Display the duplicated code segment once
                System.out.println("  Duplicated Code:");
                printFullCodeSnippet(seq1.statements());
                System.out.println();

                // List all locations where this duplication appears
                System.out.println("  Found in:");
                printLocation(report, seq1, 1);
                printLocation(report, seq2, 2);
                System.out.println();

                // Similarity breakdown
                System.out.printf("  Similarity: LCS=%.1f%%, Levenshtein=%.1f%%, Structural=%.1f%%%n",
                        similarity.lcsScore() * 100,
                        similarity.levenshteinScore() * 100,
                        similarity.structuralScore() * 100);

                // Refactoring hint
                if (similarity.canRefactor()) {
                    System.out.println("  ✓ Can be refactored - extract to helper method");
                    if (!similarity.variations().variations().isEmpty()) {
                        System.out.println("  Parameters needed: " +
                                similarity.variations().getVariationCount());
                    }
                } else {
                    System.out.println("  ⚠ Manual review needed - variations may be complex");
                }

                System.out.println();
            }

            // Show cluster summary
            if (!report.clusters().isEmpty()) {
                System.out.println("REFACTORING OPPORTUNITIES:");
                for (int i = 0; i < report.clusters().size(); i++) {
                    var cluster = report.clusters().get(i);
                    System.out.printf("  Cluster #%d: %d duplicates, potential %d LOC reduction%n",
                            i + 1,
                            cluster.duplicates().size(),
                            cluster.estimatedLOCReduction());

                    if (cluster.recommendation() != null) {
                        var rec = cluster.recommendation();
                        System.out.printf("    → Strategy: %s%n", rec.strategy());
                        System.out.printf("    → Confidence: %s%n", rec.formatConfidence());
                        if (rec.suggestedMethodName() != null) {
                            System.out.printf("    → Suggested method: %s%n", rec.suggestedMethodName());
                        }
                    }
                }
                System.out.println();
            }
        }

        // Final summary
        System.out.println("=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        int totalLOCReduction = reports.stream()
                .flatMap(r -> r.clusters().stream())
                .mapToInt(c -> c.estimatedLOCReduction())
                .sum();
        System.out.printf("Total potential LOC reduction: %d lines%n", totalLOCReduction);
        System.out.printf("Refactorable duplicates: %d%n",
                reports.stream()
                        .flatMap(r -> r.duplicates().stream())
                        .filter(p -> p.similarity().canRefactor())
                        .count());
        System.out.println();
    }

    /**
     * Print location information for a code sequence.
     */
    private static void printLocation(DuplicationReport report,
            StatementSequence seq,
            int locNum) {
        String className = extractClassName(report.sourceFile().toString());
        String methodName = seq.containingMethod() != null ? seq.containingMethod().getNameAsString() : "top-level";
        int startLine = seq.range().startLine();
        int endLine = seq.range().endLine();

        System.out.printf("    %d. Class: %s%n", locNum, className);
        System.out.printf("       Method: %s%n", methodName);
        System.out.printf("       Lines: %d-%d%n", startLine, endLine);
    }

    /**
     * Extract class name from file path.
     */
    private static String extractClassName(String filePath) {
        // Extract from path like .../com/raditha/.../ClassName.java
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        return fileName.replace(".java", "");
    }

    /**
     * Print the full code snippet without truncation.
     */
    private static void printFullCodeSnippet(List<com.github.javaparser.ast.stmt.Statement> statements) {
        if (statements.isEmpty()) {
            System.out.println("    (empty)");
            return;
        }

        for (var stmt : statements) {
            String code = stmt.toString();
            // Print each line with proper indentation
            String[] lines = code.split("\n");
            for (String line : lines) {
                System.out.println("    " + line);
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
                case "refactor" -> config.command = "refactor";
                case "--mode" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--mode requires a value");
                    String mode = args[++i];
                    if (!mode.matches("interactive|batch|dry-run")) {
                        throw new IllegalArgumentException("Mode must be: interactive, batch, or dry-run");
                    }
                    config.refactorMode = mode;
                }
                case "--verify" -> {
                    if (i + 1 >= args.length)
                        throw new IllegalArgumentException("--verify requires a value");
                    String verify = args[++i];
                    if (!verify.matches("none|compile|test")) {
                        throw new IllegalArgumentException("Verify must be: none, compile, or test");
                    }
                    config.verifyMode = verify;
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
        System.out.println("Usage: java -jar duplication-detector.jar [command] [options] [file-or-directory]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  (none)               Detect duplicates (default)");
        System.out.println("  refactor             Detect and automatically refactor duplicates");
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
        System.out.println();
        System.out.println("Refactor Options (only with 'refactor' command):");
        System.out
                .println("  --mode MODE          Refactoring mode: interactive, batch, dry-run (default: interactive)");
        System.out.println("  --verify LEVEL       Verification: none, compile, test (default: compile)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Detect duplicates");
        System.out.println("  java -jar duplication-detector.jar");
        System.out.println();
        System.out.println("  # Interactive refactoring with compilation check");
        System.out.println("  java -jar duplication-detector.jar refactor");
        System.out.println();
        System.out.println("  # Batch refactoring with test verification");
        System.out.println("  java -jar duplication-detector.jar refactor --mode batch --verify test");
    }

    private static class CLIConfig {
        String command = "detect"; // "detect" or "refactor"
        Path targetPath;
        String basePath = null;
        String outputPath = null;
        int minLines = 0; // 0 = use YAML/default
        int threshold = 0; // 0 = use YAML/default
        String preset = null;
        boolean jsonOutput = false;
        boolean showHelp = false;
        boolean showVersion = false;
        // Refactoring options
        String refactorMode = "interactive"; // "interactive", "batch", "dry-run"
        String verifyMode = "compile"; // "none", "compile", "test"
    }
}
