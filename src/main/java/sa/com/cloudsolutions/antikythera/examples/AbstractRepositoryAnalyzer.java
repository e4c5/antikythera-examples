package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Abstract base class for AI-powered type analyzers.
 *
 * <p>Provides the shared infrastructure for iterating over resolved types,
 * managing checkpoints, integrating with the AI service, and filtering.
 * Concrete subclasses implement {@link #shouldProcess(TypeWrapper)} to select
 * the types they care about (e.g. JPA repositories, {@code @Entity} classes)
 * and {@link #analyzeType(TypeWrapper)} to perform the actual analysis.</p>
 */
@SuppressWarnings("java:S106")
public abstract class AbstractRepositoryAnalyzer {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractRepositoryAnalyzer.class);

    // Shared CLI-level filters
    protected static boolean quietMode = false;
    protected static String targetClass = null;
    protected static String targetMethod = null;
    protected static String skipClass = null;

    // Core AI service state
    protected AbstractAIService aiService;
    protected final TokenUsage cumulativeTokenUsage = new TokenUsage();
    protected int totalRecommendations = 0;

    // Resumability infrastructure
    protected CheckpointManager checkpointManager;

    private int typesSkippedByFilter;
    private int typesResumed;

    /**
     * Template method: iterates all resolved types, applying filter + checkpoint
     * logic, then delegates to {@link #shouldProcess} and {@link #analyzeType}.
     *
     * @return the number of types that were actually analyzed (not skipped)
     */
    public int analyze() throws IOException, ReflectiveOperationException, InterruptedException {
        checkResumptionState();

        Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();
        int totalTypes = 0;
        int typesProcessed = 0;
        typesResumed = 0;
        typesSkippedByFilter = 0;

        logger.debug("targetClass filter value: {}", targetClass);
        logger.debug("skipClass filter value: {}", skipClass);

        for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
            String fullyQualifiedName = entry.getKey();
            TypeWrapper typeWrapper = entry.getValue();

            if (shouldProcess(typeWrapper)) {
                totalTypes++;

                if (shouldSkipType(fullyQualifiedName)) continue;

                System.out.println("\n" + "=".repeat(80));
                System.out.printf("Analyzing: %s%n", fullyQualifiedName);
                System.out.println("=".repeat(80));
                try {
                    analyzeType(typeWrapper);
                    typesProcessed++;

                    checkpointManager.markProcessed(fullyQualifiedName);
                    onBeforeCheckpointSave();
                    checkpointManager.save();
                } catch (AntikytheraException ae) {
                    logger.error("Error analyzing {}: {}", fullyQualifiedName, ae.getMessage());
                    onBeforeCheckpointSave();
                    checkpointManager.save();
                }
            }
        }

        afterAnalysisLoop();

        // Print summary
        if (typesSkippedByFilter > 0) {
            System.out.printf("\n✅ Analyzed %d types, skipped %d by target_class filter (total: %d)%n",
                    typesProcessed, typesSkippedByFilter, totalTypes);
        } else if (typesResumed > 0) {
            System.out.printf("\n✅ Analyzed %d types, skipped %d from checkpoint (total: %d)%n",
                    typesProcessed, typesResumed, totalTypes);
        } else {
            System.out.printf("\n✅ Successfully analyzed %d types%n", typesProcessed);
        }

        checkpointManager.clear();

        return typesProcessed;
    }

    /**
     * Hook invoked just before the checkpoint is saved after each type.
     * Subclasses can override to persist their own accumulated state (e.g. index suggestions).
     */
    protected void onBeforeCheckpointSave() {
        // Default: no-op
    }

    /**
     * Hook invoked once after the main analysis loop has finished.
     * Subclasses can override to perform final aggregation or stats flushing.
     *
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    protected void afterAnalysisLoop() throws IOException, InterruptedException {
        // Default: no-op
    }

    /**
     * Hook called when resuming from a checkpoint.
     * Subclasses override to restore their own accumulated state.
     */
    protected void restoreCheckpointState() {
        // Default: no-op
    }

    /**
     * Determines whether this analyzer should process the given type.
     *
     * @param typeWrapper the type to test
     * @return {@code true} if the type should be passed to {@link #analyzeType}
     */
    protected abstract boolean shouldProcess(TypeWrapper typeWrapper);

    /**
     * Performs the analysis for a single type.
     *
     * @param typeWrapper the type to analyze
     * @throws IOException if an I/O error occurs
     * @throws ReflectiveOperationException if reflection fails
     * @throws InterruptedException if the thread is interrupted
     */
    protected abstract void analyzeType(TypeWrapper typeWrapper)
            throws IOException, ReflectiveOperationException, InterruptedException;

    /**
     * Decides whether a fully-qualified type name should be skipped, taking into
     * account the {@code target_class} / {@code skip_class} CLI filters and the
     * checkpoint state.
     *
     * @param fullyQualifiedName the FQN of the type
     * @return {@code true} if the type should be skipped
     */
    protected boolean shouldSkipType(String fullyQualifiedName) {
        // Filter by target_class if specified
        if (targetClass != null && !targetClass.equals(fullyQualifiedName)) {
            typesSkippedByFilter++;
            logger.debug("Skipping type (target_class filter): {}", fullyQualifiedName);
            return true;
        }

        // Filter by skip_class if specified
        if (skipClass != null && skipClass.equals(fullyQualifiedName)) {
            typesSkippedByFilter++;
            logger.debug("Skipping type (skip_class filter): {}", fullyQualifiedName);
            return true;
        }

        // Check checkpoint for resume after crash/interruption
        if (checkpointManager.isProcessed(fullyQualifiedName)) {
            if (!quietMode) {
                System.out.printf("⏭️ Skipping (checkpoint): %s%n", fullyQualifiedName);
            }
            typesResumed++;
            return true;
        }
        return false;
    }

    /**
     * Loads the checkpoint (if present) and restores any accumulated state via
     * {@link #restoreCheckpointState()}.
     */
    protected void checkResumptionState() {
        boolean resumed = checkpointManager.load();
        if (resumed) {
            restoreCheckpointState();
            System.out.printf("🔄 Resuming from checkpoint: %d types already processed%n",
                    checkpointManager.getProcessedCount());
        }
    }

    /**
     * Resolves the Liquibase master changelog path from {@code generator.yml}.
     * Exits the process if the path is not configured or the file does not exist.
     *
     * @return the Liquibase XML file
     */
    protected static File getLiquibasePath() {
        String basePath = (String) Settings.getProperty("base_path");
        if (basePath == null) {
            System.err.println("base_path not found in generator.yml");
            System.exit(1);
        }

        String liquibaseXml = Paths.get(basePath, "src/main/resources/db/changelog/db.changelog-master.xml").toString();
        File liquibaseFile = new File(liquibaseXml);
        if (!liquibaseFile.exists()) {
            System.err.println("Liquibase file not found: " + liquibaseXml);
            System.exit(1);
        }
        return liquibaseFile;
    }

    /**
     * Parses a comma-separated list argument from command-line args.
     *
     * @param args   command-line arguments
     * @param prefix the prefix to look for (e.g. {@code "--low-cardinality="})
     * @return set of tokens found after the prefix, lowercased and trimmed
     */
    protected static Set<String> parseListArg(String[] args, String prefix) {
        HashSet<String> set = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length());
                for (String token : value.split(",")) {
                    String t = token.trim().toLowerCase();
                    if (!t.isEmpty())
                        set.add(t);
                }
            }
        }
        return set;
    }

    // -------------------------------------------------------------------------
    // Static setters / getters for CLI-level filters (shared across subclasses)
    // -------------------------------------------------------------------------

    /**
     * Enables or disables quiet mode.
     *
     * @param enabled {@code true} to enable quiet mode, {@code false} for normal output
     */
    public static void setQuietMode(boolean enabled) {
        quietMode = enabled;
    }

    /**
     * Returns whether quiet mode is enabled.
     */
    public static boolean isQuietMode() {
        return quietMode;
    }

    /**
     * Sets the target class to analyze. Supports an optional {@code #methodName} suffix
     * following the same convention used in Antikythera.
     *
     * @param className fully qualified class name (optionally with {@code #methodName}),
     *                  or {@code null} to analyze all types
     */
    public static void setTargetClass(String className) {
        if (className != null) {
            String[] parts = className.split("#");
            targetClass = parts[0];
            targetMethod = parts.length == 2 ? parts[1] : null;
        } else {
            targetClass = null;
            targetMethod = null;
        }
    }

    /**
     * Returns the current target class filter.
     */
    public static String getTargetClass() {
        return targetClass;
    }

    /**
     * Returns the current target method filter.
     */
    public static String getTargetMethod() {
        return targetMethod;
    }

    /**
     * Returns the cumulative token usage across all AI calls made by this analyzer.
     */
    public TokenUsage getCumulativeTokenUsage() {
        return cumulativeTokenUsage;
    }
}
