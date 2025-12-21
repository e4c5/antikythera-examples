package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Migrates Redis code from Spring Boot 2.1 to 2.2.
 * 
 * Main changes:
 * - union(key, otherKeys) → union(allKeys)
 * - intersect(key, otherKeys) → intersect(allKeys)
 * - difference(key, otherKeys) → difference(allKeys)
 * 
 * The new API accepts a single Collection of keys instead of (key, Collection).
 */
public class RedisCodeMigrator extends MigrationPhase {

    private static final List<String> REDIS_SET_OPERATIONS = List.of("union", "intersect", "difference");

    public RedisCodeMigrator(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Migrate Redis code.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();

        int changeCount = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Find all method calls
            List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
            boolean classModified = false;

            for (MethodCallExpr call : calls) {
                if (isRedisSetOperation(call) && call.getArguments().size() == 2) {
                    if (!dryRun) {
                        transformSetOperation(call, className, result);
                    } else {
                        result.addChange(String.format("%s: Would update %s() method signature",
                                className, call.getNameAsString()));
                    }
                    classModified = true;
                    changeCount++;
                }
            }

            if (classModified) {
                result.addModifiedClass(className);
            }
        }

        if (changeCount == 0) {
            result.addChange("No Redis migrations needed");
        } else {
            result.setRequiresManualReview(true);
            result.addManualReviewItem(String.format(
                "Verify Redis set operations (%d method calls transformed) compile and function correctly", 
                changeCount));
        }

        return result;
    }

    /**
     * Check if method call is a Redis set operation.
     */
    private boolean isRedisSetOperation(MethodCallExpr call) {
        return REDIS_SET_OPERATIONS.contains(call.getNameAsString());
    }

    /**
     * Transform Redis set operation from union(key, otherKeys) to union(allKeys).
     * 
     * Before: redisTemplate.opsForSet().union("key1", Arrays.asList("key2", "key3"))
     * After:  redisTemplate.opsForSet().union(mergeKeys("key1", Arrays.asList("key2", "key3")))
     * 
     * Since we can't easily merge at compile time, we wrap with a helper method call
     * or use Stream.concat pattern.
     */
    private void transformSetOperation(MethodCallExpr call, String className, MigrationPhaseResult result) {
        Expression firstKey = call.getArgument(0);
        Expression otherKeys = call.getArgument(1);

        // Get the compilation unit to add imports
        CompilationUnit cu = call.findCompilationUnit().orElse(null);
        if (cu != null) {
            // Add necessary imports if not already present
            if (!hasImport(cu, "java.util.stream.Stream")) {
                cu.addImport("java.util.stream.Stream");
            }
            if (!hasImport(cu, "java.util.stream.Collectors")) {
                cu.addImport("java.util.stream.Collectors");
            }
        }

        // Create: Stream.concat(Stream.of(firstKey), otherKeys.stream()).collect(Collectors.toList())
        // Simplified: We'll create Arrays.asList(firstKey, otherKeys...) pattern
        // For now, create a method call expression that wraps the merge
        
        // Build: java.util.stream.Stream.concat(java.util.stream.Stream.of(key), collection.stream()).collect(java.util.stream.Collectors.toList())
        // Simplified approach: Create a method call to merge the keys
        
        // Create Stream.of(firstKey)
        MethodCallExpr streamOf = new MethodCallExpr(new NameExpr("Stream"), "of", new NodeList<>(firstKey.clone()));
        
        // Create otherKeys.stream()
        MethodCallExpr otherStream = new MethodCallExpr(otherKeys.clone(), "stream");
        
        // Create Stream.concat(streamOf, otherStream)
        MethodCallExpr concat = new MethodCallExpr(new NameExpr("Stream"), "concat", 
                new NodeList<>(streamOf, otherStream));
        
        // Create .collect(Collectors.toList())
        MethodCallExpr collectorsToList = new MethodCallExpr(new NameExpr("Collectors"), "toList");
        MethodCallExpr collect = new MethodCallExpr(concat, "collect", new NodeList<>(collectorsToList));

        // Replace both arguments with single merged collection
        call.getArguments().clear();
        call.addArgument(collect);

        result.addChange(String.format("%s: Transformed %s(key, otherKeys) → %s(mergedKeys)",
                className, call.getNameAsString(), call.getNameAsString()));

    }

    /**
     * Check if compilation unit already has the specified import.
     */
    private boolean hasImport(CompilationUnit cu, String importName) {
        return cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(importName));
    }

    @Override
    public String getPhaseName() {
        return "Redis Migration";
    }

    @Override
    public int getPriority() {
        return 31;
    }
}
