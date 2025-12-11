package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Migrates JUnit 4 Parameterized tests to JUnit 5 @ParameterizedTest.
 * 
 * Current implementation flags parameterized tests for manual migration
 * due to complexity of AST transformations required.
 * 
 * Future enhancement: Full automatic conversion of @Parameters
 * to @MethodSource.
 */
public class ParameterizedTestMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ParameterizedTestMigrator.class);

    private final List<String> conversions = new ArrayList<>();
    private boolean needsJupiterParams = false;

    /**
     * Detect and handle parameterized tests.
     * Currently flags for manual migration.
     */
    public boolean migrate(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        conversions.clear();
        needsJupiterParams = false;

        if (isParameterizedTest(testClass)) {
            flagForManualMigration(testClass);
            conversions.add("Flagged parameterized test for manual migration: " + testClass.getNameAsString());
            conversions.add("  → Remove @RunWith(Parameterized.class)");
            conversions.add("  → Convert @Parameters method to @MethodSource");
            conversions.add("  → Add @ParameterizedTest to test methods");
            conversions.add("  → Move constructor parameters to test method parameters");
            conversions.add("  → Add dependency: junit-jupiter-params:5.11.3");

            needsJupiterParams = true;
            return true; // Modified by adding comment
        }

        return false;
    }

    /**
     * Check if class is a parameterized test.
     */
    private boolean isParameterizedTest(ClassOrInterfaceDeclaration testClass) {
        // Check for @RunWith(Parameterized.class)
        Optional<AnnotationExpr> runWith = testClass.getAnnotationByName("RunWith");
        if (runWith.isPresent()) {
            String annotation = runWith.get().toString();
            return annotation.contains("Parameterized");
        }

        // Alternative: check for @Parameters method
        return testClass.getMethods().stream()
                .anyMatch(m -> m.getAnnotationByName("Parameters").isPresent());
    }

    /**
     * Add TODO comment for manual migration.
     */
    private void flagForManualMigration(ClassOrInterfaceDeclaration testClass) {
        String comment = " TODO: Migrate Parameterized test to JUnit 5\n" +
                " 1. Remove @RunWith(Parameterized.class)\n" +
                " 2. Convert @Parameters method to static Stream<Arguments> and annotate with @MethodSource\n" +
                " 3. Add @ParameterizedTest to test methods\n" +
                " 4. Move constructor parameters to test method parameters\n" +
                " 5. Remove constructor and instance fields\n" +
                " 6. Import: org.junit.jupiter.params.ParameterizedTest\n" +
                " 7. Import: org.junit.jupiter.params.provider.MethodSource\n" +
                " 8. Import: org.junit.jupiter.params.provider.Arguments";

        testClass.setComment(new LineComment(comment));
    }

    public List<String> getConversions() {
        return conversions;
    }

    public boolean needsJupiterParams() {
        return needsJupiterParams;
    }
}
