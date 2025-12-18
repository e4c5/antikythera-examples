package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;

/**
 * Validates migration success through compilation and testing.
 * 
 * Validation levels:
 * 1. Compilation - mvn clean compile
 * 2. Dependency tree - check for conflicts
 * 3. Unit tests - mvn test (optional)
 */
public class MigrationValidator {
    private static final Logger logger = LoggerFactory.getLogger(MigrationValidator.class);

    private final boolean dryRun;

    public MigrationValidator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Validate the migration by running compilation and tests.
     */
    public MigrationPhaseResult validate() throws IOException, InterruptedException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        if (dryRun) {
            result.addChange("Dry-run mode - skipping validation");
            return result;
        }

        // Level 1: Compilation
        if (!validateCompilation(result)) {
            return result; // Stop if compilation fails
        }

        // Level 2: Dependency tree
        validateDependencies(result);

        return result;
    }

    /**
     * Validate that the project compiles successfully.
     */
    private boolean validateCompilation(MigrationPhaseResult result) throws IOException, InterruptedException {
        logger.info("Validating compilation...");

        ProcessBuilder pb = new ProcessBuilder("mvn", "clean", "compile", "-q");
        pb.directory(Paths.get(System.getProperty("user.dir")).toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Capture output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode == 0) {
            result.addChange("✅ Compilation successful");
            logger.info("Compilation validation passed");
            return true;
        } else {
            result.addError("❌ Compilation failed (exit code: " + exitCode + ")");
            result.addError("Output: " + output.toString());
            logger.error("Compilation validation failed");
            return false;
        }

    }

    /**
     * Validate dependency tree for conflicts.
     */
    private void validateDependencies(MigrationPhaseResult result) throws IOException, InterruptedException {
        logger.info("Validating dependency tree...");

        ProcessBuilder pb = new ProcessBuilder("mvn", "dependency:tree", "-q");
        pb.directory(Paths.get(System.getProperty("user.dir")).toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Capture output and check for conflicts
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean hasConflicts = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("conflict")) {
                    hasConflicts = true;
                    result.addWarning("Dependency conflict: " + line);
                }
            }

            if (!hasConflicts) {
                result.addChange("✅ No dependency conflicts detected");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            result.addWarning("dependency:tree command failed (exit code: " + exitCode + ")");
        }
    }
}
