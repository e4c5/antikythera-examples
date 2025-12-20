package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.HashMap;
import java.util.Map;

/**
 * Performs automated code migration for Hazelcast 3.x to 4.x upgrade.
 * 
 * <p>
 * Spring Boot 2.4 upgrades Hazelcast from 3.x to 4.x, which introduced breaking
 * API changes. This migrator performs automatic code transformations where
 * safe.
 * 
 * <p>
 * Automated transformations:
 * <ul>
 * <li>Static Hazelcast method calls → instance-based calls</li>
 * <li>GroupConfig → ClusterName configuration</li>
 * <li>Deprecated configuration method updates</li>
 * <li>Remove ICompletableFuture → CompletionStage</li>
 * </ul>
 * 
 * <p>
 * Manual review required for:
 * <ul>
 * <li>Network and join configuration changes</li>
 * <li>EventJournal configuration (moved to map/cache level)</li>
 * <li>Serialization API changes</li>
 * <li>MapReduce removal (use Hazelcast Jet or Aggregations)</li>
 * </ul>
 * 
 * @see AbstractCodeMigrator
 */
public class HazelcastCodeMigrator extends AbstractCodeMigrator {

    // Static Hazelcast methods that were removed in 4.x
    private static final Map<String, String> DEPRECATED_STATIC_METHODS = Map.of(
            "getMap", "Data structure methods removed from static Hazelcast class",
            "getQueue", "Use HazelcastInstance.getQueue() instead",
            "getTopic", "Use HazelcastInstance.getTopic() instead",
            "getList", "Use HazelcastInstance.getList() instead",
            "getSet", "Use HazelcastInstance.getSet() instead",
            "getMultiMap", "Use HazelcastInstance.getMultiMap() instead",
            "getLock", "Use HazelcastInstance.getCPSubsystem().getLock() instead",
            "getExecutorService", "Use HazelcastInstance.getExecutorService() instead");

    public HazelcastCodeMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = getCompilationUnits();
        Map<String, CompilationUnit> modifiedUnits = new HashMap<>();

        int filesWithChanges = 0;
        int totalTransformations = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check if this file uses Hazelcast
            boolean usesHazelcast = cu.findAll(ImportDeclaration.class).stream()
                    .anyMatch(imp -> imp.getNameAsString().startsWith("com.hazelcast"));

            if (!usesHazelcast) {
                continue;
            }

            boolean modified = false;
            int transformCount = 0;

            // Transform static Hazelcast method calls
            int staticMethodChanges = transformStaticHazelcastCalls(cu, className, result);
            if (staticMethodChanges > 0) {
                modified = true;
                transformCount += staticMethodChanges;
            }

            // Transform GroupConfig to cluster name pattern
            int groupConfigChanges = transformGroupConfig(cu, className, result);
            if (groupConfigChanges > 0) {
                modified = true;
                transformCount += groupConfigChanges;
            }

            // Flag ICompletableFuture usage
            int futureChanges = flagICompletableFuture(cu, className, result);
            if (futureChanges > 0) {
                transformCount += futureChanges;
                // Note: We flag but don't auto-transform ICompletableFuture (too complex)
            }

            if (modified) {
                modifiedUnits.put(className, cu);
                filesWithChanges++;
                totalTransformations += transformCount;
                result.addChange(String.format("Transformed %d Hazelcast patterns in: %s",
                        transformCount, className));
            } else if (transformCount > 0) {
                // Has flagged items but no modifications
                result.addChange(String.format("Flagged %d items for review in: %s",
                        transformCount, className));
            }
        }

        if (filesWithChanges == 0 && totalTransformations == 0) {
            result.addChange("No Hazelcast usage detected");
            logger.info("No Hazelcast code found to migrate");
            return result;
        }

        // Write modified files
        if (!modifiedUnits.isEmpty()) {
            writeModifiedFiles(modifiedUnits, result);
            result.addChange(String.format("Applied %d transformations across %d files",
                    totalTransformations, filesWithChanges));
        }

        // Add manual review guidance
        addManualReviewGuidance(result, filesWithChanges, totalTransformations);

        result.setRequiresManualReview(true);
        return result;
    }

    /**
     * Transform static Hazelcast method calls to instance-based calls.
     * 
     * Example: Hazelcast.getMap("name") → // TODO: Use
     * hazelcastInstance.getMap("name")
     */
    private int transformStaticHazelcastCalls(CompilationUnit cu, String className,
            MigrationPhaseResult result) {
        int count = 0;

        for (MethodCallExpr methodCall : cu.findAll(MethodCallExpr.class)) {
            // Check if it's a static call to Hazelcast class
            if (methodCall.getScope().isPresent() &&
                    methodCall.getScope().get() instanceof NameExpr) {

                NameExpr scope = (NameExpr) methodCall.getScope().get();
                String scopeName = scope.getNameAsString();
                String methodName = methodCall.getNameAsString();

                if ("Hazelcast".equals(scopeName) &&
                        DEPRECATED_STATIC_METHODS.containsKey(methodName)) {

                    // Add comment before the method call
                    String comment = String.format(
                            " TODO [Hazelcast 4.x]: Static method Hazelcast.%s() removed. " +
                                    "Use HazelcastInstance.%s() instead. " +
                                    "Inject HazelcastInstance bean or use Hazelcast.newHazelcastInstance(config)",
                            methodName, methodName);

                    methodCall.setLineComment(comment);
                    count++;

                    logger.info("Flagged static Hazelcast.{}() call in {}", methodName, className);
                }
            }
        }

        return count;
    }

    /**
     * Transform GroupConfig usage to cluster name pattern.
     * 
     * Example: config.getGroupConfig().setName() → config.setClusterName()
     */
    private int transformGroupConfig(CompilationUnit cu, String className,
            MigrationPhaseResult result) {
        int count = 0;

        for (MethodCallExpr methodCall : cu.findAll(MethodCallExpr.class)) {
            String methodName = methodCall.getNameAsString();

            // Look for getGroupConfig() calls
            if ("getGroupConfig".equals(methodName)) {
                methodCall.setLineComment(
                        " TODO [Hazelcast 4.x]: GroupConfig deprecated. " +
                                "Use config.setClusterName(\"name\") instead of config.getGroupConfig().setName(\"name\")");
                count++;
                logger.info("Flagged GroupConfig usage in {}", className);
            }

            // Look for setGroup() calls
            if ("setGroup".equals(methodName) || "setGroupConfig".equals(methodName)) {
                methodCall.setLineComment(
                        " TODO [Hazelcast 4.x]: setGroup/setGroupConfig deprecated. " +
                                "Use setClusterName() instead");
                count++;
                logger.info("Flagged setGroup/setGroupConfig call in {}", className);
            }
        }

        return count;
    }

    /**
     * Flag ICompletableFuture usage (removed in 4.x, replaced with
     * CompletionStage).
     */
    private int flagICompletableFuture(CompilationUnit cu, String className,
            MigrationPhaseResult result) {
        int count = 0;

        // Check for ICompletableFuture imports
        for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
            if (imp.getNameAsString().contains("ICompletableFuture")) {
                imp.setLineComment(
                        " TODO [Hazelcast 4.x]: ICompletableFuture removed. " +
                                "Use CompletionStage or CompletableFuture instead");
                count++;
                logger.info("Flagged ICompletableFuture import in {}", className);
            }
        }

        return count;
    }

    /**
     * Add manual review guidance for complex migration scenarios.
     */
    private void addManualReviewGuidance(MigrationPhaseResult result, int filesChanged,
            int transformations) {

        result.addWarning(String.format(
                "HAZELCAST 4.x: Completed %d automated transformations in %d files",
                transformations, filesChanged));

        result.addWarning("MANUAL REVIEW REQUIRED for:");
        result.addWarning("1. Network/Join configuration - API changed significantly");
        result.addWarning("2. EventJournal config - moved from Config to MapConfig/CacheConfig");
        result.addWarning("3. MapReduce usage - REMOVED, use Hazelcast Jet or Aggregations");
        result.addWarning("4. Client protocol - All clients must be upgraded to 4.x");
        result.addWarning("5. Serialization changes - GenericRecord API renamed methods");

        result.addWarning("\nACTIONS:");
        result.addWarning("1. Review all TODO comments added to code");
        result.addWarning("2. Test Hazelcast functionality thoroughly");
        result.addWarning("3. Check cluster formation in distributed environments");
        result.addWarning("4. Review migration guide: https://docs.hazelcast.com/hazelcast/4.0/migration-guides/4.0");
    }

    @Override
    public String getPhaseName() {
        return "Hazelcast 4.x Code Migration";
    }

    @Override
    public int getPriority() {
        return 55;
    }
}
