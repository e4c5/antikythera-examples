package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.List;
import java.util.Map;

/**
 * Migrates Hibernate code from Spring Boot 2.1 to 2.2.
 * 
 * Main changes:
 * - Detects @TypeDef annotations
 * - Flags entities requiring AttributeConverter migration
 */
public class HibernateCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(HibernateCodeMigrator.class);

    private final boolean dryRun;

    public HibernateCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate Hibernate code.
     * Detects @TypeDef annotations and flags them for manual AttributeConverter migration.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int typeDefCount = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Find @TypeDef annotations
            List<AnnotationExpr> typeDefs = cu.findAll(AnnotationExpr.class);
            boolean classHasTypeDef = false;

            for (AnnotationExpr annotation : typeDefs) {
                if (annotation.getNameAsString().equals("TypeDef") ||
                        annotation.getNameAsString().equals("org.hibernate.annotations.TypeDef")) {

                    typeDefCount++;
                    classHasTypeDef = true;
                    result.addChange(className + ": Found @TypeDef annotation requiring migration");
                    logger.info("Found @TypeDef in {} - AttributeConverter migration required", className);
                }
            }

            if (classHasTypeDef) {
                result.addModifiedClass(className);
            }
        }

        if (typeDefCount == 0) {
            result.addChange("No Hibernate @TypeDef annotations found");
        } else {
            result.addChange(String.format(
                    "Detected %d @TypeDef annotation(s) requiring AttributeConverter migration", typeDefCount));
        }

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Hibernate Migration";
    }

    @Override
    public int getPriority() {
        return 32;
    }
}
