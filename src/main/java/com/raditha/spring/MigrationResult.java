package com.raditha.spring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the overall result of a Spring Boot migration.
 * Contains results from all migration phases and can generate a comprehensive
 * report.
 */
public class MigrationResult {
    private final Map<String, MigrationPhaseResult> phases = new HashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * Add a migration phase result.
     */
    public void addPhase(String phaseName, MigrationPhaseResult phaseResult) {
        phases.put(phaseName, phaseResult);
    }

    /**
     * Add a global error.
     */
    public void addError(String error) {
        errors.add(error);
    }

    /**
     * Add a global warning.
     */
    public void addWarning(String warning) {
        warnings.add(warning);
    }

    /**
     * Check if migration was successful.
     */
    public boolean isSuccessful() {
        return errors.isEmpty() && phases.values().stream()
                .noneMatch(MigrationPhaseResult::hasCriticalErrors);
    }

    /**
     * Get total number of changes across all phases.
     */
    public int getTotalChanges() {
        return phases.values().stream()
                .mapToInt(MigrationPhaseResult::getChangeCount)
                .sum();
    }

    /**
     * Generate a comprehensive migration report.
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();

        report.append("╔════════════════════════════════════════════════════════╗\n");
        report.append("║  Spring Boot 2.1 → 2.2 Migration Report                ║\n");
        report.append("╚════════════════════════════════════════════════════════╝\n\n");

        // Overall status
        String status = isSuccessful() ? "✅ SUCCESS" : "❌ FAILED";
        report.append("Status: ").append(status).append("\n");
        report.append("Total Changes: ").append(getTotalChanges()).append("\n\n");

        // Phase results
        for (Map.Entry<String, MigrationPhaseResult> entry : phases.entrySet()) {
            MigrationPhaseResult phaseResult = entry.getValue();
            String phaseStatus = phaseResult.isSuccessful() ? "✅" : "❌";

            report.append(phaseStatus).append(" ")
                    .append(entry.getKey()).append(": ")
                    .append(phaseResult.getChangeCount()).append(" changes");
            
            if (phaseResult.requiresManualReview()) {
                report.append(" ⚠️  [REQUIRES MANUAL REVIEW]");
            }
            report.append("\n");

            // Show phase details
            for (String change : phaseResult.getChanges()) {
                report.append("   - ").append(change).append("\n");
            }

            // Show warnings
            for (String warning : phaseResult.getWarnings()) {
                report.append("   ⚠️  ").append(warning).append("\n");
            }

            // Show errors
            for (String error : phaseResult.getErrors()) {
                report.append("   ❌ ").append(error).append("\n");
            }

            report.append("\n");
        }

        // Manual review section
        List<String> allManualReviewItems = new ArrayList<>();
        for (Map.Entry<String, MigrationPhaseResult> entry : phases.entrySet()) {
            MigrationPhaseResult phaseResult = entry.getValue();
            if (phaseResult.requiresManualReview()) {
                for (String item : phaseResult.getManualReviewItems()) {
                    allManualReviewItems.add(entry.getKey() + ": " + item);
                }
            }
        }

        if (!allManualReviewItems.isEmpty()) {
            report.append("┌─────────────────────────────────────────────────────────┐\n");
            report.append("│ ⚠️  MANUAL REVIEW REQUIRED                              │\n");
            report.append("└─────────────────────────────────────────────────────────┘\n");
            report.append("The following items were generated but require manual completion:\n\n");
            for (String item : allManualReviewItems) {
                report.append("  • ").append(item).append("\n");
            }
            report.append("\n");
        }

        // Global warnings and errors
        if (!warnings.isEmpty()) {
            report.append("Warnings:\n");
            for (String warning : warnings) {
                report.append("⚠️  ").append(warning).append("\n");
            }
            report.append("\n");
        }

        if (!errors.isEmpty()) {
            report.append("Errors:\n");
            for (String error : errors) {
                report.append("❌ ").append(error).append("\n");
            }
            report.append("\n");
        }

        // Summary
        if (isSuccessful()) {
            if (allManualReviewItems.isEmpty()) {
                report.append("✅ Migration Status: COMPLETE - All changes automated\n");
            } else {
                report.append("✅ Migration Status: COMPLETE - Review manual items above\n");
            }
        } else {
            report.append("❌ Migration Status: FAILED - Review errors above\n");
        }

        return report.toString();
    }

    /**
     * Get a concise summary of the migration.
     */
    public String getSummary() {
        long totalChanges = phases.values().stream()
                .mapToLong(MigrationPhaseResult::getChangeCount)
                .sum();

        long totalWarnings = phases.values().stream()
                .mapToLong(p -> p.getWarnings().size())
                .sum();

        long totalErrors = phases.values().stream()
                .mapToLong(p -> p.getErrors().size())
                .sum();

        return String.format("Phases: %d | Changes: %d | Warnings: %d | Errors: %d | Status: %s",
                phases.size(), totalChanges, totalWarnings, totalErrors,
                isSuccessful() ? "SUCCESS" : "FAILED");
    }
}
