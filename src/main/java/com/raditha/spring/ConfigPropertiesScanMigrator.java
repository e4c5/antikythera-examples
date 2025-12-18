package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.*;

/**
 * Migrates @EnableConfigurationProperties to @ConfigurationPropertiesScan where
 * applicable.
 * 
 * Strategy:
 * - Find @SpringBootApplication class
 * - Check for @EnableConfigurationProperties
 * - Validate classes are within base package scan path
 * - Replace with @ConfigurationPropertiesScan for internal classes
 */
public class ConfigPropertiesScanMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ConfigPropertiesScanMigrator.class);

    private final boolean dryRun;

    public ConfigPropertiesScanMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate configuration properties annotations.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            // Find @SpringBootApplication class
            CompilationUnit appClass = findSpringBootApplication();

            if (appClass == null) {
                result.addWarning("Could not find @SpringBootApplication class");
                return result;
            }

            String basePackage = appClass.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Find @EnableConfigurationProperties
            Optional<AnnotationExpr> enableConfigProps = appClass.findFirst(AnnotationExpr.class,
                    a -> a.getNameAsString().equals("EnableConfigurationProperties") ||
                            a.getNameAsString().equals(
                                    "org.springframework.boot.context.properties.EnableConfigurationProperties"));

            if (!enableConfigProps.isPresent()) {
                result.addChange("No @EnableConfigurationProperties found");
                return result;
            }

            // Extract classes from annotation
            List<String> propClasses = extractConfigurationPropertiesClasses(enableConfigProps.get());

            if (propClasses.isEmpty()) {
                result.addChange("@EnableConfigurationProperties has no classes");
                return result;
            }

            // Check if all classes are within base package
            List<String> internalClasses = new ArrayList<>();
            List<String> externalClasses = new ArrayList<>();

            for (String className : propClasses) {
                if (className.startsWith(basePackage)) {
                    internalClasses.add(className);
                } else {
                    externalClasses.add(className);
                }
            }

            if (internalClasses.isEmpty()) {
                result.addChange("All configuration properties are external - keeping @EnableConfigurationProperties");
                return result;
            }

            // Perform migration
            if (!dryRun) {
                migrateAnnotations(appClass, enableConfigProps.get(), externalClasses, result);
            } else {
                if (externalClasses.isEmpty()) {
                    result.addChange("Would replace @EnableConfigurationProperties with @ConfigurationPropertiesScan");
                } else {
                    result.addChange(
                            "Would add @ConfigurationPropertiesScan and keep @EnableConfigurationProperties for external classes");
                }
            }

        } catch (Exception e) {
            logger.error("Error during ConfigPropertiesScan migration", e);
            result.addError("ConfigPropertiesScan migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Find the Spring Boot application class.
     */
    private CompilationUnit findSpringBootApplication() {
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for @SpringBootApplication
            Optional<AnnotationExpr> annotation = cu.findFirst(AnnotationExpr.class,
                    a -> a.getNameAsString().equals("SpringBootApplication") ||
                            a.getNameAsString().equals("org.springframework.boot.autoconfigure.SpringBootApplication"));

            if (annotation.isPresent()) {
                logger.info("Found @SpringBootApplication in {}", className);
                return cu;
            }
        }

        return null;
    }

    /**
     * Extract class names from @EnableConfigurationProperties annotation.
     */
    private List<String> extractConfigurationPropertiesClasses(AnnotationExpr annotation) {
        List<String> classes = new ArrayList<>();

        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            extractClassesFromExpression(singleMember.getMemberValue(), classes);
        } else if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    extractClassesFromExpression(pair.getValue(), classes);
                }
            }
        }

        return classes;
    }

    /**
     * Extract class names from an annotation expression.
     */
    private void extractClassesFromExpression(Expression expr, List<String> classes) {
        if (expr instanceof ClassExpr classExpr) {
            classes.add(classExpr.getTypeAsString());
        } else if (expr instanceof ArrayInitializerExpr arrayExpr) {
            for (Expression value : arrayExpr.getValues()) {
                extractClassesFromExpression(value, classes);
            }
        }
    }

    /**
     * Migrate annotations in the application class.
     */
    private void migrateAnnotations(CompilationUnit appClass, AnnotationExpr enableConfigProps,
            List<String> externalClasses, MigrationPhaseResult result) {

        if (externalClasses.isEmpty()) {
            // Remove @EnableConfigurationProperties and add @ConfigurationPropertiesScan
            enableConfigProps.remove();

            // Add @ConfigurationPropertiesScan to the class
            appClass.getType(0).addAnnotation("ConfigurationPropertiesScan");

            result.addChange("Replaced @EnableConfigurationProperties with @ConfigurationPropertiesScan");
            logger.info("Migrated to @ConfigurationPropertiesScan");
        } else {
            // Keep @EnableConfigurationProperties with only external classes
            // Add @ConfigurationPropertiesScan for internal ones

            // Update annotation to only have external classes
            updateEnableConfigurationProperties(enableConfigProps, externalClasses);

            // Add @ConfigurationPropertiesScan
            appClass.getType(0).addAnnotation("ConfigurationPropertiesScan");

            result.addChange(
                    "Added @ConfigurationPropertiesScan and kept @EnableConfigurationProperties for external classes");
            logger.info("Added @ConfigurationPropertiesScan alongside @EnableConfigurationProperties");
        }
    }

    /**
     * Update @EnableConfigurationProperties to only include specified classes.
     */
    private void updateEnableConfigurationProperties(AnnotationExpr annotation, List<String> classes) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            singleMember.setMemberValue(createClassArrayExpression(classes));
        } else if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    pair.setValue(createClassArrayExpression(classes));
                }
            }
        }
    }

    /**
     * Create an array expression of class literals.
     */
    private Expression createClassArrayExpression(List<String> classes) {
        if (classes.size() == 1) {
            return new ClassExpr(new ClassOrInterfaceType(null, classes.get(0)));
        }

        NodeList<Expression> values = new NodeList<>();
        for (String className : classes) {
            values.add(new ClassExpr(new ClassOrInterfaceType(null, className)));
        }

        return new ArrayInitializerExpr(values);
    }
}
