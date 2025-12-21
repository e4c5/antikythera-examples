package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixer for code deprecated in Spring Boot 2.3 and removed in 2.5.
 * 
 * <p>
 * Uses AST transformations to automatically fix:
 * <ul>
 * <li>Deprecated imports (replaced with new equivalents)</li>
 * <li>Deprecated method calls (replaced with new APIs)</li>
 * <li>Deprecated actuator metrics exporters (migrated to Micrometer)</li>
 * </ul>
 * 
 * @see AbstractCodeMigrator
 */
public class DeprecatedCodeFixer extends AbstractCodeMigrator {
    private static final Logger logger = LoggerFactory.getLogger(DeprecatedCodeFixer.class);

    // Map of deprecated imports to their replacements
    private static final Map<String, String> IMPORT_REPLACEMENTS = new HashMap<>();

    static {
        // Actuator metrics exporters (moved to Micrometer in 2.3, removed in 2.5)
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.prometheus",
                "io.micrometer.prometheus");
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.graphite",
                "io.micrometer.graphite");
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.statsd",
                "io.micrometer.statsd");
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.simple",
                "io.micrometer.core.instrument.simple");
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.influx",
                "io.micrometer.influx");
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.metrics.export.jmx",
                "io.micrometer.jmx");

        // Security properties (moved in 2.3, old package removed in 2.5)
        IMPORT_REPLACEMENTS.put("org.springframework.boot.autoconfigure.security.SecurityProperties",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityProperties");
        
        // Actuator endpoint configuration (restructured in 2.3, old classes removed in 2.5)
        IMPORT_REPLACEMENTS.put("org.springframework.boot.actuate.endpoint.annotation.Endpoint",
                "org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint");
        
        // ConfigurationProperties deprecations
        IMPORT_REPLACEMENTS.put("org.springframework.boot.context.properties.ConfigurationPropertiesBean",
                "org.springframework.boot.context.properties.ConfigurationProperties");
    }

    // Map of deprecated method calls to their replacements
    private static final Map<String, String> METHOD_REPLACEMENTS = new HashMap<>();

    static {
        // Add method replacements here as needed
        // Example: METHOD_REPLACEMENTS.put("oldMethod", "newMethod");
    }

    public DeprecatedCodeFixer(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Fixing deprecated code...");
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            // Get all compilation units
            Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();

            for (Map.Entry<String, CompilationUnit> entry : allUnits.entrySet()) {
                String filePath = entry.getKey();
                CompilationUnit cu = entry.getValue();

                boolean modified = false;

                // Fix deprecated imports
                if (fixDeprecatedImports(cu, result, filePath)) {
                    modified = true;
                }

                // Fix deprecated method calls
                if (fixDeprecatedMethodCalls(cu, result, filePath)) {
                    modified = true;
                }

                // Modified files are tracked automatically by AbstractCodeMigrator
                // when compilation units are modified
            }

            if (result.getChangeCount() == 0) {
                result.addChange("No deprecated code found");
            }

        } catch (Exception e) {
            logger.error("Error during deprecated code fixing", e);
            result.addError("Deprecated code fixing failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Fix deprecated imports by replacing them with new equivalents.
     */
    private boolean fixDeprecatedImports(CompilationUnit cu, MigrationPhaseResult result, String filePath) {
        boolean modified = false;

        for (ImportDeclaration importDecl : cu.findAll(ImportDeclaration.class)) {
            String importName = importDecl.getNameAsString();

            // Check for exact match
            if (IMPORT_REPLACEMENTS.containsKey(importName)) {
                String replacement = IMPORT_REPLACEMENTS.get(importName);
                importDecl.setName(replacement);
                result.addChange(filePath + ": Fixed deprecated import: " + importName + " → " + replacement);
                logger.info("Fixed deprecated import in {}: {} → {}", filePath, importName, replacement);
                modified = true;
                continue;
            }

            // Check for prefix match (e.g.,
            // org.springframework.boot.actuate.metrics.export.prometheus.*)
            for (Map.Entry<String, String> entry : IMPORT_REPLACEMENTS.entrySet()) {
                String deprecatedPrefix = entry.getKey();
                String newPrefix = entry.getValue();

                if (importName.startsWith(deprecatedPrefix + ".")) {
                    // Replace prefix
                    String newImport = importName.replace(deprecatedPrefix, newPrefix);
                    importDecl.setName(newImport);
                    result.addChange(filePath + ": Fixed deprecated import: " + importName + " → " + newImport);
                    logger.info("Fixed deprecated import in {}: {} → {}", filePath, importName, newImport);
                    modified = true;
                    break;
                }
            }
        }

        return modified;
    }

    /**
     * Fix deprecated method calls by replacing them with new equivalents.
     */
    private boolean fixDeprecatedMethodCalls(CompilationUnit cu, MigrationPhaseResult result, String filePath) {
        boolean modified = false;

        for (MethodCallExpr methodCall : cu.findAll(MethodCallExpr.class)) {
            String methodName = methodCall.getNameAsString();

            if (METHOD_REPLACEMENTS.containsKey(methodName)) {
                String replacement = METHOD_REPLACEMENTS.get(methodName);
                methodCall.setName(replacement);
                result.addChange(filePath + ": Fixed deprecated method call: " + methodName + " → " + replacement);
                logger.info("Fixed deprecated method call in {}: {} → {}", filePath, methodName, replacement);
                modified = true;
            }
        }

        return modified;
    }

    @Override
    public String getPhaseName() {
        return "Deprecated Code Fixes";
    }

    @Override
    public int getPriority() {
        return 50;
    }
}
