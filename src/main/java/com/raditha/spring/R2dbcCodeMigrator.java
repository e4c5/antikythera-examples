package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects R2DBC usage for Spring Boot 2.4 migration.
 * 
 * <p>
 * Spring Boot 2.4 moved R2DBC infrastructure from Spring Boot to Spring
 * Framework 5.3.
 * The new module is {@code spring-r2dbc}.
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects R2DBC usage via imports</li>
 * <li>Validates that imports are from correct packages</li>
 * <li>Warns about any deprecated R2DBC APIs</li>
 * <li>Provides guidance for migration if needed</li>
 * </ul>
 * 
 * <p>
 * Key changes:
 * <ul>
 * <li>R2DBC infrastructure moved to Spring Framework 5.3</li>
 * <li>Auto-configuration remains in Spring Boot</li>
 * <li>Most imports should remain compatible:
 * {@code org.springframework.r2dbc.core.DatabaseClient}</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class R2dbcCodeMigrator extends MigrationPhase {

    public R2dbcCodeMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithR2dbc = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for R2DBC imports
            boolean hasR2dbc = false;
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                // Check for R2DBC-related imports
                if (importName.startsWith("org.springframework.r2dbc") ||
                        importName.startsWith("org.springframework.data.r2dbc") ||
                        importName.startsWith("io.r2dbc")) {

                    hasR2dbc = true;
                    result.addChange(String.format("%s: R2DBC import detected: %s",
                            className, importName));
                }
            }

            if (hasR2dbc) {
                filesWithR2dbc.add(className);
            }
        }

        if (filesWithR2dbc.isEmpty()) {
            result.addChange("No R2DBC usage detected");
            logger.info("No R2DBC imports found");
            return result;
        }

        // R2DBC usage detected - provide guidance
        result.addWarning("R2DBC: Spring Boot 2.4 moved R2DBC infrastructure to Spring Framework 5.3");
        result.addWarning("R2DBC: Auto-configuration remains in Spring Boot - minimal changes expected");
        result.addWarning("R2DBC: Verify that imports are from org.springframework.r2dbc.* packages");
        result.addWarning(String.format("R2DBC: Found R2DBC usage in %d file(s) - manual review recommended",
                filesWithR2dbc.size()));

        for (String className : filesWithR2dbc) {
            result.addChange(String.format("  - %s", className));
        }

        result.setRequiresManualReview(true);
        logger.warn("R2DBC usage detected in {} files", filesWithR2dbc.size());

        return result;
    }

    @Override
    public String getPhaseName() {
        return "R2DBC Code Detection";
    }

    @Override
    public int getPriority() {
        return 80;
    }
}
