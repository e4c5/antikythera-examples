package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        // Step 1: Detect validation usage
        boolean usesValidation = detectValidationUsage(result);

        if (!usesValidation) {
            result.addChange("No validation usage detected - starter not needed");
            logger.info("No validation usage found in project");
            return result;
        }

        // Step 2: Check if validation starter already present
        if (hasValidationStarter()) {
            result.addChange("Validation starter already present in POM");
            logger.info("Validation starter already configured");
            return result;
        }

        // Step 3: Add validation starter
        if (addValidationStarter(result)) {
            logger.info("Added spring-boot-starter-validation dependency");
        }

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
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            boolean fileHasValidation = false;

            // Check imports for javax.validation
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                if (imp.getNameAsString().startsWith("javax.validation")) {
                    if (!fileHasValidation) {
                        filesWithValidation++;
                        fileHasValidation = true;
                    }
                    validationUsageCount++;
                    logger.debug("Found validation import in {}: {}", className, imp.getNameAsString());
                }
            }

            // Check for validation annotations
            for (AnnotationExpr annotation : cu.findAll(AnnotationExpr.class)) {
                String annotationName = annotation.getNameAsString();
                if (VALIDATION_ANNOTATIONS.contains(annotationName)) {
                    if (!fileHasValidation) {
                        filesWithValidation++;
                        fileHasValidation = true;
                    }
                    validationUsageCount++;
                    logger.debug("Found validation annotation in {}: @{}", className, annotationName);
                }
            }
        }

        if (validationUsageCount > 0) {
            result.addChange(String.format("Detected validation usage: %d occurrences across %d files",
                    validationUsageCount, filesWithValidation));
            logger.info("Validation detected: {} occurrences in {} files",
                    validationUsageCount, filesWithValidation);
            return true;
        }

        return false;
    }

    /**
     * Check if spring-boot-starter-validation is already in POM.
     */
    private boolean hasValidationStarter() {
        try {
            Path pomPath = resolvePomPath();
            if (pomPath == null) {
                logger.warn("Could not find pom.xml");
                return false;
            }

            Model model = readPomModel(pomPath);

            return model.getDependencies().stream()
                    .anyMatch(dep -> "org.springframework.boot".equals(dep.getGroupId()) &&
                            "spring-boot-starter-validation".equals(dep.getArtifactId()));

        } catch (Exception e) {
            logger.error("Error checking POM for validation starter", e);
            return false;
        }
    }

    /**
     * Add spring-boot-starter-validation to POM.
     */
    private boolean addValidationStarter(MigrationPhaseResult result) {
        try {
            Path pomPath = resolvePomPath();
            if (pomPath == null) {
                result.addError("Could not find pom.xml to add validation starter");
                return false;
            }

            Model model = readPomModel(pomPath);

            // Add validation starter dependency
            Dependency validationStarter = new Dependency();
            validationStarter.setGroupId("org.springframework.boot");
            validationStarter.setArtifactId("spring-boot-starter-validation");
            // No version needed - managed by Spring Boot BOM

            if (dryRun) {
                result.addChange("Would add spring-boot-starter-validation dependency");
            } else {
                model.addDependency(validationStarter);
                writePomModel(pomPath, model);
                result.addChange("Added spring-boot-starter-validation dependency");
                result.addWarning(
                        "CRITICAL: Validation starter added - required for @Valid, @Validated annotations to work");
            }

            return true;

        } catch (Exception e) {
            logger.error("Error adding validation starter to POM", e);
            result.addError("Failed to add validation starter: " + e.getMessage());
            return false;
        }
    }

    // Helper methods (copied from AbstractPomMigrator to avoid anonymous class
    // issues)

    private Path resolvePomPath() {
        try {
            // Check if Settings is initialized
            if (Settings.getBasePath() == null) {
                logger.warn("Settings not initialized, cannot resolve POM path");
                return null;
            }

            Path basePath = Paths.get(Settings.getBasePath());
            Path pomPath = basePath.resolve("pom.xml");

            if (!pomPath.toFile().exists()) {
                pomPath = basePath.getParent().resolve("pom.xml");
            }

            if (pomPath.toFile().exists()) {
                return pomPath;
            }
        } catch (Exception e) {
            logger.error("Error resolving POM path", e);
        }

        return null;
    }

    private Model readPomModel(Path pomPath) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomPath.toFile())) {
            return reader.read(fileReader);
        }
    }

    private void writePomModel(Path pomPath, Model model) throws Exception {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(pomPath.toFile())) {
            writer.write(fileWriter, model);
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
