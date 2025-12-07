package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for JUnit 4 to 5 migration.
 * 
 * Coordinates POM dependency migration, import conversions, and annotation
 * conversions.
 * Reuses ConversionOutcome for result tracking to avoid proliferation of
 * outcome classes.
 */
public class JUnit425Migrator {
    private static final Logger logger = LoggerFactory.getLogger(JUnit425Migrator.class);

    private final boolean dryRun;
    private final PomDependencyMigrator pomMigrator;
    private boolean pomMigrated = false;

    public JUnit425Migrator(boolean dryRun) {
        this.dryRun = dryRun;
        this.pomMigrator = new PomDependencyMigrator(dryRun);
    }

    /**
     * Migrate all test classes in a compilation unit from JUnit 4 to JUnit 5.
     * 
     * @param cu the compilation unit to migrate
     * @return list of conversion outcomes
     */
    public List<ConversionOutcome> migrateAll(CompilationUnit cu) {
        List<ConversionOutcome> outcomes = new ArrayList<>();

        // Migrate POM once (on first call)
        if (!pomMigrated) {
            migratePomDependencies();
            pomMigrated = true;
        }

        // Process each type declaration in the compilation unit
        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();

                // Only process test classes
                if (isTestClass(classDecl)) {
                    ConversionOutcome outcome = migrateTestClass(classDecl, cu);
                    if (outcome != null) {
                        outcomes.add(outcome);
                    }
                }
            }
        }

        return outcomes;
    }

    /**
     * Migrate a single test class from JUnit 4 to JUnit 5.
     */
    private ConversionOutcome migrateTestClass(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        ConversionOutcome outcome = new ConversionOutcome(
                testClass.getFullyQualifiedName().orElse(testClass.getNameAsString()));

        try {
            List<String> allConversions = new ArrayList<>();
            boolean modified = false;

            // 1. Migrate imports
            ImportMigrator importMigrator = new ImportMigrator();
            if (importMigrator.migrateImports(cu)) {
                allConversions.addAll(importMigrator.getConversions());
                modified = true;
            }

            // 2. Migrate annotations
            AnnotationMigrator annotationMigrator = new AnnotationMigrator();
            if (annotationMigrator.migrateAnnotations(testClass)) {
                allConversions.addAll(annotationMigrator.getConversions());
                modified = true;

                // Add extension imports based on detected patterns
                importMigrator.addExtensionImports(cu,
                        annotationMigrator.needsMockitoExtension(),
                        annotationMigrator.needsSpringExtension());
                allConversions.addAll(importMigrator.getConversions());
            }

            // Populate outcome
            if (modified) {
                outcome.modified = true;
                outcome.action = "MIGRATED";
                outcome.reason = buildReasonString(allConversions);
                outcome.embeddedAlternative = "JUnit 5";
            } else {
                outcome.action = "SKIPPED";
                outcome.reason = "No JUnit 4 patterns detected";
            }

        } catch (Exception e) {
            logger.error("Error migrating test class: " + testClass.getNameAsString(), e);
            outcome.action = "ERROR";
            outcome.reason = "Migration failed: " + e.getMessage();
        }

        return outcome;
    }

    /**
     * Check if a class is a test class.
     */
    private boolean isTestClass(ClassOrInterfaceDeclaration classDecl) {
        // Check for @Test annotation on any method
        return classDecl.getMethods().stream()
                .anyMatch(method -> method.getAnnotationByName("Test").isPresent());
    }

    /**
     * Migrate POM dependencies.
     */
    private void migratePomDependencies() {
        try {
            if (pomMigrator.migratePom()) {
                logger.info("POM migration completed successfully");
                if (dryRun) {
                    logger.info("POM changes (dry run):");
                    for (String change : pomMigrator.getChanges()) {
                        logger.info("  " + change);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error migrating POM dependencies", e);
        }
    }

    /**
     * Build reason string from conversions list.
     */
    private String buildReasonString(List<String> conversions) {
        if (conversions.isEmpty()) {
            return "No changes";
        }

        // Count different types of conversions
        long importCount = conversions.stream()
                .filter(c -> c.contains("→") || c.contains("Added:") || c.contains("Removed:")).count();
        long annotationCount = conversions.stream().filter(c -> c.contains("@")).count();

        StringBuilder sb = new StringBuilder("JUnit 4 → JUnit 5: ");
        if (importCount > 0) {
            sb.append(importCount).append(" imports");
        }
        if (annotationCount > 0) {
            if (importCount > 0)
                sb.append(", ");
            sb.append(annotationCount).append(" annotations");
        }

        return sb.toString();
    }

    /**
     * Get POM migration changes.
     */
    public List<String> getPomChanges() {
        return pomMigrator.getChanges();
    }
}
