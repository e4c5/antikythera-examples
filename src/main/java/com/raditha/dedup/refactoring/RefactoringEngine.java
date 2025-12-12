package com.raditha.dedup.refactoring;

import com.raditha.dedup.model.*;
import com.raditha.dedup.analyzer.DuplicationReport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for automated refactoring.
 * Coordinates validation, refactoring application, and verification.
 */
public class RefactoringEngine {

    private final SafetyValidator validator;
    private final RefactoringVerifier verifier;
    private final DiffGenerator diffGenerator;
    private final RefactoringMode mode;
    private final Path projectRoot;

    public RefactoringEngine(Path projectRoot, RefactoringMode mode) {
        this(projectRoot, mode, RefactoringVerifier.VerificationLevel.COMPILE);
    }

    public RefactoringEngine(Path projectRoot, RefactoringMode mode,
            RefactoringVerifier.VerificationLevel verificationLevel) {
        this.projectRoot = projectRoot;
        this.mode = mode;
        this.validator = new SafetyValidator();
        this.verifier = new RefactoringVerifier(projectRoot, verificationLevel);
        this.diffGenerator = new DiffGenerator();
    }

    /**
     * Refactor all duplicates in a report.
     */
    public RefactoringSession refactorAll(DuplicationReport report) {
        RefactoringSession session = new RefactoringSession();

        System.out.println("\n=== Refactoring Session Started ===");
        System.out.println("Mode: " + mode);
        System.out.println("Clusters to process: " + report.clusters().size());
        System.out.println();

        for (int i = 0; i < report.clusters().size(); i++) {
            DuplicateCluster cluster = report.clusters().get(i);
            RefactoringRecommendation recommendation = cluster.recommendation();

            if (recommendation == null) {
                session.addSkipped(cluster, "No recommendation generated");
                continue;
            }

            System.out.printf("Processing cluster #%d (Strategy: %s, Confidence: %.0f%%)%n",
                    i + 1, recommendation.strategy(), recommendation.confidenceScore() * 100);

            // Pre-validation
            SafetyValidator.ValidationResult validation = validator.validate(cluster, recommendation);
            if (!validation.isValid()) {
                System.out.println("  ❌ Validation failed:");
                validation.getErrors().forEach(e -> System.out.println("     - " + e));
                session.addSkipped(cluster, String.join("; ", validation.getErrors()));
                continue;
            }

            if (validation.hasWarnings()) {
                System.out.println("  ⚠️  Warnings:");
                validation.getWarnings().forEach(w -> System.out.println("     - " + w));
            }

            // Interactive mode: show diff and ask for confirmation
            if (mode == RefactoringMode.INTERACTIVE) {
                if (!showDiffAndConfirm(cluster, recommendation)) {
                    session.addSkipped(cluster, "User rejected");
                    continue;
                }
            }

            // Batch mode: only process high-confidence refactorings
            if (mode == RefactoringMode.BATCH && !recommendation.isHighConfidence()) {
                session.addSkipped(cluster, "Low confidence for batch mode");
                continue;
            }

            // Apply refactoring based on strategy
            try {
                ExtractMethodRefactorer.RefactoringResult result = applyRefactoring(cluster, recommendation);

                if (mode == RefactoringMode.DRY_RUN) {
                    System.out.println("  ✓ Dry-run: Changes not applied");
                    session.addSkipped(cluster, "Dry-run mode");
                    continue;
                }

                // Create backup before writing
                verifier.createBackup(result.sourceFile());

                // Write refactored code
                result.apply();
                System.out.println("  ✓ Refactoring applied");

                // Verify compilation
                RefactoringVerifier.VerificationResult verify = verifier.verify();
                if (verify.isSuccess()) {
                    System.out.println("  ✓ Verification passed");
                    session.addSuccess(cluster, result.description());
                    verifier.clearBackups();
                } else {
                    System.out.println("  ❌ Verification failed:");
                    verify.errors().forEach(e -> System.out.println("     - " + e));
                    // Rollback
                    verifier.rollback();
                    session.addFailed(cluster, String.join("; ", verify.errors()));
                }
            } catch (Exception e) {
                System.out.println("  ❌ Refactoring failed: " + e.getMessage());
                session.addFailed(cluster, e.getMessage());
                try {
                    verifier.rollback();
                } catch (IOException rollbackEx) {
                    System.err.println("  ⚠️  Rollback failed: " + rollbackEx.getMessage());
                }
            }
        }

        System.out.println("\n=== Session Summary ===");
        System.out.println("Successful: " + session.successful.size());
        System.out.println("Skipped: " + session.skipped.size());
        System.out.println("Failed: " + session.failed.size());

        return session;
    }

    /**
     * Apply refactoring based on strategy.
     */
    private ExtractMethodRefactorer.RefactoringResult applyRefactoring(
            DuplicateCluster cluster,
            RefactoringRecommendation recommendation) throws IOException {
        return switch (recommendation.strategy()) {
            case EXTRACT_HELPER_METHOD -> {
                ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_TO_BEFORE_EACH ->
                throw new UnsupportedOperationException("BeforeEach refactoring not yet implemented");
            case EXTRACT_TO_PARAMETERIZED_TEST ->
                throw new UnsupportedOperationException("ParameterizedTest refactoring not yet implemented");
            case EXTRACT_TO_UTILITY_CLASS ->
                throw new UnsupportedOperationException("Utility class refactoring not yet implemented");
            case MANUAL_REVIEW_REQUIRED ->
                throw new UnsupportedOperationException("Manual review required");
        };
    }

    /**
     * Show diff and ask user for confirmation (interactive mode).
     */
    private boolean showDiffAndConfirm(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        System.out.println("\n  === PROPOSED REFACTORING ===");
        System.out.println("  Strategy: " + recommendation.strategy());
        System.out.println("  Method: " + recommendation.generateMethodSignature());
        System.out.println("  Confidence: " + recommendation.formatConfidence());
        System.out.println("  LOC Reduction: " + cluster.estimatedLOCReduction());
        System.out.println();

        // TODO: Generate and show actual diff once refactoring strategies are
        // implemented
        System.out.println("  [Diff preview will be shown here]");
        System.out.println();

        System.out.print("  Apply this refactoring? (y/n): ");
        try {
            int response = System.in.read();
            // Clear buffer
            while (System.in.available() > 0) {
                System.in.read();
            }
            return response == 'y' || response == 'Y';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Refactoring modes.
     */
    public enum RefactoringMode {
        INTERACTIVE, // Show diffs, ask for confirmation
        BATCH, // Auto-apply high-confidence refactorings
        DRY_RUN // Preview only, no changes
    }

    /**
     * Refactoring session tracking.
     */
    public static class RefactoringSession {
        private final List<RefactoringSuccess> successful = new ArrayList<>();
        private final List<RefactoringSkip> skipped = new ArrayList<>();
        private final List<RefactoringFailure> failed = new ArrayList<>();

        public void addSuccess(DuplicateCluster cluster, String details) {
            successful.add(new RefactoringSuccess(cluster, details));
        }

        public void addSkipped(DuplicateCluster cluster, String reason) {
            skipped.add(new RefactoringSkip(cluster, reason));
        }

        public void addFailed(DuplicateCluster cluster, String error) {
            failed.add(new RefactoringFailure(cluster, error));
        }

        public List<RefactoringSuccess> getSuccessful() {
            return successful;
        }

        public List<RefactoringSkip> getSkipped() {
            return skipped;
        }

        public List<RefactoringFailure> getFailed() {
            return failed;
        }

        public boolean hasFailures() {
            return !failed.isEmpty();
        }

        public int getTotalProcessed() {
            return successful.size() + skipped.size() + failed.size();
        }
    }

    public record RefactoringSuccess(DuplicateCluster cluster, String details) {
    }

    public record RefactoringSkip(DuplicateCluster cluster, String reason) {
    }

    public record RefactoringFailure(DuplicateCluster cluster, String error) {
    }
}
