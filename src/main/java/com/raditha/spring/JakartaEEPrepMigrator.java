package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.*;

/**
 * Adds preparatory TODO comments to javax.* imports for future Jakarta EE migration.
 * 
 * Spring Boot 3.x will require migration from javax.* to jakarta.* packages.
 * This migrator adds helpful comments to prepare for that future migration.
 * 
 * Note: This is disabled by default and should be enabled via configuration flag.
 */
public class JakartaEEPrepMigrator extends MigrationPhase {

    private final boolean enableJakartaPrep;

    // javax packages that will move to jakarta in Jakarta EE 9+
    private static final Set<String> JAVAX_PACKAGES_TO_MIGRATE = Set.of(
            "javax.persistence",
            "javax.validation",
            "javax.servlet",
            "javax.annotation",
            "javax.transaction",
            "javax.ejb",
            "javax.jms",
            "javax.inject",
            "javax.xml.bind",
            "javax.xml.ws",
            "javax.xml.soap"
    );

    public JakartaEEPrepMigrator(boolean dryRun, boolean enableJakartaPrep) {
        super(dryRun);
        this.enableJakartaPrep = enableJakartaPrep;
    }

    /**
     * Add Jakarta EE preparatory comments if enabled.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        if (!enableJakartaPrep) {
            result.addChange("Jakarta EE prep comments not enabled - skipping");
            result.addChange("💡 Tip: Enable Jakarta EE prep comments to prepare for Spring Boot 3.x migration");
            return result;
        }

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int commentCount = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            List<ImportDeclaration> imports = cu.findAll(ImportDeclaration.class);
            boolean modified = false;

            for (ImportDeclaration imp : imports) {
                String importName = imp.getNameAsString();
                
                // Check if this is a javax import that needs migration
                if (needsJakartaMigration(importName)) {
                    if (!dryRun) {
                        addJakartaComment(imp, importName);
                        modified = true;
                    }
                    commentCount++;
                }
            }

            if (modified) {
                result.addModifiedClass(className);
                result.addChange(className + ": Added Jakarta EE migration TODO comments");
            }
        }

        if (commentCount == 0) {
            result.addChange("No javax.* imports found requiring Jakarta migration prep");
        } else {
            result.addChange(String.format(
                    "Added Jakarta EE migration prep comments to %d javax.* imports", commentCount));
            result.addWarning("These comments prepare for future Spring Boot 3.x migration");
            result.addWarning("Spring Boot 3.x will require javax.* → jakarta.* package migration");
        }

        return result;
    }

    /**
     * Check if an import needs Jakarta migration.
     */
    private boolean needsJakartaMigration(String importName) {
        return JAVAX_PACKAGES_TO_MIGRATE.stream()
                .anyMatch(importName::startsWith);
    }

    private void addJakartaComment(ImportDeclaration imp, String importName) {
        // Determine the jakarta equivalent
        String jakartaPackage = importName.replace("javax.", "jakarta.");
        
        // Create comment
        String commentText = String.format(
                " TODO [Spring Boot 3.x]: Migrate to %s", jakartaPackage);
        
        LineComment comment = new LineComment(commentText);
        
        // Add comment to the import
        imp.setComment(comment);
    }

    @Override
    public String getPhaseName() {
        return "Jakarta EE Preparatory Comments";
    }

    @Override
    public int getPriority() {
        return 70;
    }
}
