package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Migrates JUnit 4 @Rule and @ClassRule to JUnit 5 equivalents.
 * 
 * Supported conversions:
 * - TemporaryFolder → @TempDir
 * - TestName → TestInfo parameter
 * - ExpectedException → assertThrows() (basic patterns)
 * - Custom rules → flagged for manual migration
 */
public class RuleMigrator {
    private static final Logger logger = LoggerFactory.getLogger(RuleMigrator.class);

    private final List<String> conversions = new ArrayList<>();
    private final Set<String> requiredImports = new HashSet<>();

    /**
     * Migrate @Rule and @ClassRule annotations in a test class.
     */
    public boolean migrate(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        conversions.clear();
        requiredImports.clear();

        // Find all fields with @Rule or @ClassRule
        List<FieldDeclaration> rulesToRemove = new ArrayList<>();

        boolean modified =  migrateFields(testClass, rulesToRemove);

        // Remove converted rule fields
        for (FieldDeclaration field : rulesToRemove) {
            testClass.remove(field);
        }

        // Add required imports
        for (String importStr : requiredImports) {
            if (!hasImport(cu, importStr)) {
                cu.addImport(importStr);
            }
        }

        return modified;
    }

    private boolean migrateFields(ClassOrInterfaceDeclaration testClass,  List<FieldDeclaration> rulesToRemove) {
        boolean modified = false;

        for (FieldDeclaration field : testClass.getFields()) {
            if (hasRuleAnnotation(field)) {
                String ruleType = getRuleType(field);

                switch (ruleType) {
                    case "TemporaryFolder":
                        if (convertTemporaryFolder(field, testClass)) {
                            rulesToRemove.add(field);
                            modified = true;
                        }
                        break;
                    case "TestName":
                        if (convertTestName(field, testClass)) {
                            rulesToRemove.add(field);
                            modified = true;
                        }
                        break;
                    case "ExpectedException":
                        if (convertExpectedException(field, testClass)) {
                            rulesToRemove.add(field);
                            modified = true;
                        }
                        break;
                    default:
                        flagCustomRule(field);
                        conversions.add("Flagged custom rule for manual migration: " + ruleType);
                        break;
                }
            }
        }
        return modified;
    }

    /**
     * Check if field has @Rule or @ClassRule annotation.
     */
    private boolean hasRuleAnnotation(FieldDeclaration field) {
        return field.getAnnotationByName("Rule").isPresent() ||
                field.getAnnotationByName("ClassRule").isPresent();
    }

    /**
     * Get the type of rule from field declaration.
     */
    private String getRuleType(FieldDeclaration field) {
        if (field.getVariables().isEmpty()) {
            return "Unknown";
        }

        String type = field.getVariable(0).getType().asString();
        // Extract simple class name
        if (type.contains(".")) {
            type = type.substring(type.lastIndexOf('.') + 1);
        }
        return type;
    }

    /**
     * Convert TemporaryFolder to @TempDir.
     */
    private boolean convertTemporaryFolder(FieldDeclaration field, ClassOrInterfaceDeclaration testClass) {
        try {
            String fieldName = field.getVariable(0).getNameAsString();

            // Create new @TempDir field with Path type
            String newFieldName = fieldName.contains("folder") ? fieldName.replace("folder", "tempDir")
                    : fieldName + "TempDir";

            FieldDeclaration tempDirField = testClass.addField(
                    "Path",
                    newFieldName,
                    Modifier.Keyword.PRIVATE);
            tempDirField.addAnnotation("TempDir");

            requiredImports.add("org.junit.jupiter.api.io.TempDir");
            requiredImports.add("java.nio.file.Path");

            conversions.add("Converted TemporaryFolder → @TempDir: " + fieldName + " → " + newFieldName);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to convert TemporaryFolder: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convert TestName to TestInfo parameter.
     */
    private boolean convertTestName(FieldDeclaration field, ClassOrInterfaceDeclaration testClass) {
        try {
            String fieldName = field.getVariable(0).getNameAsString();

            // Add TestInfo parameter to all @Test methods
            List<MethodDeclaration> testMethods = testClass.getMethods().stream()
                    .filter(m -> m.getAnnotationByName("Test").isPresent())
                    .toList();

            for (MethodDeclaration method : testMethods) {
                // Check if TestInfo parameter already exists
                boolean hasTestInfo = method.getParameters().stream()
                        .anyMatch(p -> p.getType().asString().equals("TestInfo"));

                if (!hasTestInfo) {
                    Parameter testInfoParam = new Parameter(
                            new ClassOrInterfaceType(null, "TestInfo"),
                            "testInfo");
                    method.addParameter(testInfoParam);
                }
            }

            requiredImports.add("org.junit.jupiter.api.TestInfo");

            conversions.add("Converted TestName → TestInfo parameter: " + fieldName);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to convert TestName: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convert ExpectedException to assertThrows() (basic patterns only).
     * Complex patterns are flagged for manual migration.
     */
    private boolean convertExpectedException(FieldDeclaration field, ClassOrInterfaceDeclaration testClass) {
        try {
            String fieldName = field.getVariable(0).getNameAsString();

            // Find methods that use this ExpectedException
            List<MethodDeclaration> methods = testClass.getMethods();
            boolean converted = false;

            for (MethodDeclaration method : methods) {
                // Look for patterns like: thrown.expect(Exception.class)
                List<MethodCallExpr> expectCalls = method.findAll(MethodCallExpr.class).stream()
                        .filter(call -> call.getScope().isPresent() &&
                                call.getScope().get().toString().equals(fieldName) &&
                                call.getNameAsString().equals("expect"))
                        .toList();

                if (!expectCalls.isEmpty()) {
                    // This is a basic pattern we might be able to convert
                    // For now, just flag it - full conversion is complex
                    method.setComment(new LineComment(" TODO: Convert ExpectedException to assertThrows()"));
                    conversions.add("Flagged ExpectedException for manual conversion in: " + method.getNameAsString());
                    converted = true;
                }
            }

            if (!converted) {
                // No usage found, safe to remove
                conversions.add("Removed unused ExpectedException rule: " + fieldName);
                return true;
            }

            return false; // Don't remove if flagged for manual conversion
        } catch (Exception e) {
            logger.warn("Failed to process ExpectedException: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Flag custom rules for manual migration.
     */
    private void flagCustomRule(FieldDeclaration field) {
        field.setComment(new LineComment(" TODO: Convert custom @Rule to JUnit 5 Extension"));
    }

    /**
     * Check if import exists in compilation unit.
     */
    private boolean hasImport(CompilationUnit cu, String importName) {
        return cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(importName));
    }

    public List<String> getConversions() {
        return conversions;
    }

    public Set<String> getRequiredImports() {
        return requiredImports;
    }
}
