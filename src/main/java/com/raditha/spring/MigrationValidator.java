package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates migration success through compilation, dependency checks, and property validation.
 * 
 * Validation levels:
 * 1. Compilation - mvn clean compile
 * 2. Dependency tree - check for conflicts
 * 3. Property validation - detect deprecated properties via spring-boot-properties-migrator
 * 4. Rollback instructions generation
 */
public class MigrationValidator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(MigrationValidator.class);

    private final boolean dryRun;

    public MigrationValidator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Validate the migration by running compilation, dependency checks, and property validation.
     */
    @Override
    public MigrationPhaseResult migrate() throws IOException, InterruptedException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        if (dryRun) {
            result.addChange("Dry-run mode - skipping validation");
            addRollbackInstructions(result);
            return result;
        }

        // Level 1: Compilation
        if (!validateCompilation(result)) {
            addRollbackInstructions(result);
            return result; // Stop if compilation fails
        }

        // Level 2: Dependency tree
        validateDependencies(result);

        // Level 3: Property validation
        validateProperties(result);

        // Add rollback instructions
        addRollbackInstructions(result);

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

    /**
     * Validate that no deprecated properties remain by checking application startup logs.
     * This assumes spring-boot-properties-migrator is in the classpath.
     */
    private void validateProperties(MigrationPhaseResult result) {
        logger.info("Checking for deprecated properties...");
        
        // Note: Full property validation would require starting the application
        // For now, we provide guidance
        result.addChange("Property validation: Run application and check logs for deprecated property warnings");
        result.addChange("spring-boot-properties-migrator will report any deprecated properties at startup");
        result.addWarning("After validation, remove spring-boot-properties-migrator dependency from POM");
    }

    /**
     * Add rollback instructions to the result.
     */
    private void addRollbackInstructions(MigrationPhaseResult result) {
        List<String> rollbackSteps = new ArrayList<>();
        rollbackSteps.add("If migration fails or causes issues, rollback using:");
        rollbackSteps.add("  1. git checkout HEAD~1 -- pom.xml");
        rollbackSteps.add("  2. git checkout HEAD~1 -- src/main/resources/application*.yml");
        rollbackSteps.add("  3. git checkout HEAD~1 -- src/main/resources/application*.properties");
        rollbackSteps.add("  4. git checkout HEAD~1 -- src/main/java/**/*.java");
        rollbackSteps.add("  5. mvn clean compile to verify rollback");
        rollbackSteps.add("");
        rollbackSteps.add("Or revert entire commit: git revert HEAD");
        rollbackSteps.add("");
        rollbackSteps.add("Backup files are available if you created them before migration.");
        
        for (String step : rollbackSteps) {
            result.addChange(step);
        }
    }

    @Override
    public String getPhaseName() {
        return "Validation";
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
