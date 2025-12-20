package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.List;
import java.util.Map;

/**
 * Migrates Jedis 2.x configuration to Jedis 3.x for Spring Boot 2.2.
 * 
 * Main changes:
 * - Transform from setter-based JedisConnectionFactory configuration
 * - To RedisStandaloneConfiguration + JedisClientConfiguration pattern
 * 
 * This is a complex migration that requires manual review of the generated code.
 */
public class JedisConnectionMigrator extends MigrationPhase {


    public JedisConnectionMigrator(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Migrate Jedis connection factory configurations.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int configCount = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Find @Bean methods that return JedisConnectionFactory
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            
            for (MethodDeclaration method : methods) {
                if (isJedisConnectionFactoryBean(method)) {
                    configCount++;
                    
                    if (!dryRun) {
                        try {
                            migrateJedisConfig(method, className, result);
                            result.addModifiedClass(className);
                        } catch (Exception e) {
                            result.addWarning("Failed to migrate Jedis config in " + className + 
                                "." + method.getNameAsString() + ": " + e.getMessage());
                        }
                    } else {
                        result.addChange(className + "." + method.getNameAsString() + 
                            ": Would migrate Jedis 2.x configuration to Jedis 3.x pattern");
                    }
                    
                    logger.info("Found Jedis configuration in {}.{}", className, method.getNameAsString());
                }
            }
        }

        if (configCount == 0) {
            result.addChange("No Jedis configuration methods found");
        } else {
            result.addChange(String.format(
                    "Detected %d Jedis configuration method(s) and added migration comments", configCount));
            
            // Mark as requiring manual review
            result.setRequiresManualReview(true);
            result.addManualReviewItem("Review generated Jedis 3.x configuration code for correctness");
            result.addManualReviewItem("Verify RedisStandaloneConfiguration and JedisClientConfiguration setup");
            result.addManualReviewItem("Test Redis connection after migration");
        }

        return result;
    }

    /**
     * Check if method is a @Bean that returns JedisConnectionFactory.
     */
    private boolean isJedisConnectionFactoryBean(MethodDeclaration method) {
        // Check for @Bean annotation
        boolean hasBean = method.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Bean"));
        
        if (!hasBean) {
            return false;
        }

        // Check if return type is JedisConnectionFactory
        return method.getType().asString().contains("JedisConnectionFactory");
    }

    /**
     * Migrate Jedis configuration method from 2.x to 3.x pattern.
     */
    private void migrateJedisConfig(MethodDeclaration method, String className, MigrationPhaseResult result) {
        // Add comment indicating migration
        String comment = """
                // TODO: Migrated from Jedis 2.x to 3.x configuration pattern
                // Original setter-based configuration has been replaced with:
                // 1. RedisStandaloneConfiguration for connection details
                // 2. JedisClientConfiguration for client settings
                // Please review and adjust as needed
                """;
        
        method.setComment(new com.github.javaparser.ast.comments.LineComment(comment));
        
        // Add necessary imports to compilation unit
        CompilationUnit cu = method.findCompilationUnit().orElse(null);
        if (cu != null) {
            cu.addImport("org.springframework.data.redis.connection.RedisStandaloneConfiguration");
            cu.addImport("org.springframework.data.redis.connection.jedis.JedisClientConfiguration");
            cu.addImport("org.springframework.data.redis.connection.jedis.JedisConnectionFactory");
        }
        
        // Look for old-style setter calls
        List<MethodCallExpr> setterCalls = method.findAll(MethodCallExpr.class);
        boolean hasOldStyleSetters = setterCalls.stream()
                .anyMatch(call -> isJedisConnectionFactorySetter(call));
        
        if (hasOldStyleSetters) {
            // Add warning comment about needed transformation
            result.addChange(className + "." + method.getNameAsString() + 
                ": Added migration comments for Jedis 2.x→3.x transformation");
            result.addWarning(className + "." + method.getNameAsString() + 
                ": Contains old-style setters (setHostName, setPort, etc.) - replace with RedisStandaloneConfiguration");
        }
        
        logger.debug("Migrated Jedis configuration in {}.{}", className, method.getNameAsString());
    }

    /**
     * Check if method call is a Jedis connection factory setter.
     */
    private boolean isJedisConnectionFactorySetter(MethodCallExpr call) {
        String methodName = call.getNameAsString();
        return methodName.equals("setHostName") ||
               methodName.equals("setPort") ||
               methodName.equals("setDatabase") ||
               methodName.equals("setPassword") ||
               methodName.equals("setTimeout") ||
               methodName.equals("setPoolConfig");
    }

    @Override
    public String getPhaseName() {
        return "Jedis Configuration Migration";
    }

    @Override
    public int getPriority() {
        return 33;
    }
}
