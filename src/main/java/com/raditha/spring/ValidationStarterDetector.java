package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Detects validation usage and adds spring-boot-starter-validation if needed.
 * 
 * <p>
 * <b>CRITICAL for Spring Boot 2.3</b>: The validation starter is no longer
 * included
 * automatically with spring-boot-starter-web. Applications using Bean
 * Validation
 * will fail at runtime if this starter is not added.
 * 
 * <p>
 * Detection strategy:
 * <ul>
 * <li>Scans Java files for validation annotations (@Valid, @Validated)</li>
 * <li>Scans Java files for constraint annotations (@NotNull, @Size, etc.)</li>
 * <li>Scans Java files for javax.validation imports</li>
 * <li>Checks POM to see if starter is already present</li>
 * <li>Automatically adds dependency if validation detected but starter
 * missing</li>
 * </ul>
 * 
 * <p>
 * Automation confidence: 100% (fully automated, safe, no code changes)
 * 
 * @see MigrationPhase
 */
public class ValidationStarterDetector extends MigrationPhase {

    // Validation annotations to detect
    private static final List<String> VALIDATION_ANNOTATIONS = Arrays.asList(
            "Valid", "Validated", "NotNull", "NotEmpty", "NotBlank",
            "Size", "Min", "Max", "Email", "Pattern", "Positive", "Negative",
            "Future", "Past", "AssertTrue", "AssertFalse", "Digits");

    public ValidationStarterDetector(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        // Step 1: Detect validation usage
        boolean usesValidation = detectValidationUsage(result);

        if (!usesValidation) {
            result.addChange("No validation usage detected - starter not needed");
            return result;
        }

        // Step 2: Check if validation starter already present
        if (hasValidationStarter()) {
            result.addChange("Validation starter already present in POM");
            return result;
        }

        // Step 3: Add validation starter
        addValidationStarter(result);
        return result;
    }

    /**
     * Detect validation usage by scanning all compilation units.
     */
    private boolean detectValidationUsage(MigrationPhaseResult result) {
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int validationUsageCount = 0;
        int filesWithValidation = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            CompilationUnit cu = entry.getValue();

            boolean fileHasValidation = false;

            // Check for validation annotations using AbstractCompiler's import resolution
            for (AnnotationExpr annotation : cu.findAll(AnnotationExpr.class)) {
                String annotationName = annotation.getNameAsString();
                if (VALIDATION_ANNOTATIONS.contains(annotationName)) {
                    // Use AbstractCompiler.findImport to verify the annotation is from javax.validation
                    ImportWrapper importWrapper = AbstractCompiler.findImport(cu, annotationName);
                    if (isValidationImport(importWrapper)) {
                        if (!fileHasValidation) {
                            filesWithValidation++;
                            fileHasValidation = true;
                        }
                        validationUsageCount++;
                    }
                }
            }
        }

        if (validationUsageCount > 0) {
            result.addChange(String.format("Detected validation usage: %d occurrences across %d files",
                    validationUsageCount, filesWithValidation));
            return true;
        }

        return false;
    }

    /**
     * Check if an import is from javax.validation package.
     * Handles both direct imports and wildcard imports.
     */
    private boolean isValidationImport(ImportWrapper wrapper) {
        if (wrapper == null) {
            return false;
        }
        ImportDeclaration imp = wrapper.getImport();
        return imp.getNameAsString().startsWith("javax.validation");
    }

    /**
     * Check if spring-boot-starter-validation is already in POM.
     */
    private boolean hasValidationStarter() throws Exception {
        Path pomPath = PomUtils.resolvePomPath();
        Model model = PomUtils.readPomModel(pomPath);

        return model.getDependencies().stream()
                .anyMatch(dep -> "org.springframework.boot".equals(dep.getGroupId()) &&
                        "spring-boot-starter-validation".equals(dep.getArtifactId()));
    }

    /**
     * Add spring-boot-starter-validation to POM.
     */
    private void addValidationStarter(MigrationPhaseResult result) throws Exception {
        Path pomPath = PomUtils.resolvePomPath();
        Model model = PomUtils.readPomModel(pomPath);

        // Add validation starter dependency
        Dependency validationStarter = new Dependency();
        validationStarter.setGroupId("org.springframework.boot");
        validationStarter.setArtifactId("spring-boot-starter-validation");
        // No version needed - managed by Spring Boot BOM

        if (dryRun) {
            result.addChange("Would add spring-boot-starter-validation dependency");
        } else {
            model.addDependency(validationStarter);
            PomUtils.writePomModel(pomPath, model);
            result.addChange("Added spring-boot-starter-validation dependency");
            result.addWarning(
                    "CRITICAL: Validation starter added - required for @Valid, @Validated annotations to work");
        }
    }

    @Override
    public String getPhaseName() {
        return "Validation Starter Detection";
    }

    @Override
    public int getPriority() {
        return 5; // Highest priority for Spring Boot 2.3
    }
}
