package com.raditha.spring;


import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

/**
 * Validates migration success through compilation, dependency checks, and property validation.
 * 
 * Validation levels:
 * 1. Compilation - mvn clean compile
 * 2. Dependency tree - check for conflicts
 * 3. Property validation - detect deprecated properties via spring-boot-properties-migrator
 * 4. Rollback instructions generation
 */
public class MigrationValidator extends MigrationPhase {

    public MigrationValidator(boolean dryRun) {
        super(dryRun);
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
        java.io.File outputFile = java.io.File.createTempFile("mvn-compile-", ".log");
        outputFile.deleteOnExit();

        ProcessBuilder pb = new ProcessBuilder("mvn", "clean", "compile", "-q", "-B");
        pb.directory(Paths.get(Settings.getBasePath()).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputFile);
        String osName = System.getProperty("os.name");
        String nullDeviceName = (osName != null && osName.toLowerCase().contains("win")) ? "NUL" : "/dev/null";
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDeviceName)));

        Process process = pb.start();

        // Use ExecutorService for more reliable timeout handling
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Integer> future = executor.submit(() -> process.waitFor());

        int exitCode;
        try {
            exitCode = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            future.cancel(true);
            result.addError("❌ Compilation timed out");
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            result.addError("❌ Compilation failed: " + e.getMessage());
            return false;
        } finally {
            executor.shutdownNow();
        }

        if (exitCode == 0) {
            result.addChange("✅ Compilation successful");
            return true;
        }

        // Read output from file on failure
        String output = java.nio.file.Files.readString(outputFile.toPath());
        result.addError("❌ Compilation failed (exit code: " + exitCode + ")");
        result.addError("Output: " + output);
        return false;
    }

    /**
     * Validate dependency tree for conflicts.
     */
    private void validateDependencies(MigrationPhaseResult result) throws IOException, InterruptedException {
        java.io.File outputFile = java.io.File.createTempFile("mvn-deps-", ".log");
        outputFile.deleteOnExit();

        ProcessBuilder pb = new ProcessBuilder("mvn", "dependency:tree", "-q", "-B");
        pb.directory(Paths.get(Settings.getBasePath()).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputFile);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        Process process = pb.start();

        // Use ExecutorService for more reliable timeout handling
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Integer> future = executor.submit(() -> process.waitFor());

        int exitCode;
        try {
            exitCode = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            future.cancel(true);
            result.addWarning("dependency:tree command timed out");
            return;
        } catch (java.util.concurrent.ExecutionException e) {
            result.addWarning("dependency:tree command failed: " + e.getMessage());
            return;
        } finally {
            executor.shutdownNow();
        }

        // Read output from file and check for conflicts
        List<String> conflicts = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(outputFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("conflict")) {
                    conflicts.add(line);
                }
            }
        }

        for (String conflict : conflicts) {
            result.addWarning("Dependency conflict: " + conflict);
        }

        if (conflicts.isEmpty()) {
            result.addChange("✅ No dependency conflicts detected");
        }

        if (exitCode != 0) {
            result.addWarning("dependency:tree command failed (exit code: " + exitCode + ")");
        }
    }

    /**
     * Validate that no deprecated properties remain by checking application startup logs.
     * This assumes spring-boot-properties-migrator is in the classpath.
     */
    private void validateProperties(MigrationPhaseResult result) {
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
