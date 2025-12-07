package com.raditha.cleanunit;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.ClassExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles annotation conversions from JUnit 4 to JUnit 5.
 * 
 * Converts:
 * - Lifecycle annotations (@Before → @BeforeEach, etc.)
 * - @RunWith to @ExtendWith
 * - @Ignore to @Disabled
 */
public class AnnotationMigrator {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationMigrator.class);

    // Mapping of JUnit 4 annotations to JUnit 5 equivalents
    private static final Map<String, String> ANNOTATION_MAPPINGS = new HashMap<>();

    static {
        ANNOTATION_MAPPINGS.put("Before", "BeforeEach");
        ANNOTATION_MAPPINGS.put("After", "AfterEach");
        ANNOTATION_MAPPINGS.put("BeforeClass", "BeforeAll");
        ANNOTATION_MAPPINGS.put("AfterClass", "AfterAll");
        ANNOTATION_MAPPINGS.put("Ignore", "Disabled");
    }

    private final List<String> conversions = new ArrayList<>();
    private boolean needsMockitoExtension = false;
    private boolean needsSpringExtension = false;

    /**
     * Migrate annotations in a test class from JUnit 4 to JUnit 5.
     * 
     * @param testClass the test class to migrate
     * @return true if any annotations were changed
     */
    public boolean migrateAnnotations(ClassOrInterfaceDeclaration testClass) {
        boolean modified = false;

        // Migrate class-level annotations
        modified |= migrateClassAnnotations(testClass);

        // Migrate method-level annotations
        for (MethodDeclaration method : testClass.getMethods()) {
            modified |= migrateMethodAnnotations(method);
        }

        return modified;
    }

    /**
     * Migrate class-level annotations.
     */
    private boolean migrateClassAnnotations(ClassOrInterfaceDeclaration testClass) {
        boolean modified = false;

        // Handle @RunWith annotation
        Optional<AnnotationExpr> runWith = testClass.getAnnotationByName("RunWith");
        if (runWith.isPresent()) {
            modified = convertRunWithToExtendWith(testClass, runWith.get());
        }

        return modified;
    }

    /**
     * Convert @RunWith to @ExtendWith.
     */
    private boolean convertRunWithToExtendWith(ClassOrInterfaceDeclaration testClass, AnnotationExpr runWith) {
        if (!(runWith instanceof SingleMemberAnnotationExpr)) {
            logger.warn("Unexpected @RunWith format in class: " + testClass.getNameAsString());
            return false;
        }

        SingleMemberAnnotationExpr runWithExpr = (SingleMemberAnnotationExpr) runWith;
        String runnerClass = extractRunnerClass(runWithExpr);

        if (runnerClass == null) {
            logger.warn("Could not extract runner class from @RunWith");
            return false;
        }

        // Determine extension class based on runner
        String extensionClass = mapRunnerToExtension(runnerClass);
        if (extensionClass == null) {
            conversions.add("⚠ Manual review needed: Custom runner " + runnerClass);
            return false;
        }

        // Create @ExtendWith annotation
        SingleMemberAnnotationExpr extendWith = new SingleMemberAnnotationExpr(
                new Name("ExtendWith"),
                new ClassExpr(new ClassOrInterfaceType(null, extensionClass)));

        // Replace annotation
        runWith.replace(extendWith);
        conversions.add("@RunWith(" + runnerClass + ") → @ExtendWith(" + extensionClass + ")");

        // Track which extension imports are needed
        if (extensionClass.contains("MockitoExtension")) {
            needsMockitoExtension = true;
        } else if (extensionClass.contains("SpringExtension")) {
            needsSpringExtension = true;
        }

        return true;
    }

    /**
     * Extract runner class name from @RunWith annotation.
     */
    private String extractRunnerClass(SingleMemberAnnotationExpr runWith) {
        String memberValue = runWith.getMemberValue().toString();

        // Remove .class suffix
        if (memberValue.endsWith(".class")) {
            memberValue = memberValue.substring(0, memberValue.length() - 6);
        }

        return memberValue;
    }

    /**
     * Map JUnit 4 runner to JUnit 5 extension.
     */
    private String mapRunnerToExtension(String runnerClass) {
        // Mockito runners
        if (runnerClass.contains("MockitoJUnitRunner") || runnerClass.contains("MockitoJUnit44Runner")) {
            return "MockitoExtension";
        }

        // Spring runners
        if (runnerClass.contains("SpringRunner") || runnerClass.contains("SpringJUnit4ClassRunner")) {
            return "SpringExtension";
        }

        // Parameterized runner - requires manual intervention
        if (runnerClass.contains("Parameterized")) {
            return null; // Flag for manual review
        }

        // Suite runner - requires manual intervention
        if (runnerClass.contains("Suite")) {
            return null; // Flag for manual review
        }

        // Unknown runner
        return null;
    }

    /**
     * Migrate method-level annotations.
     */
    private boolean migrateMethodAnnotations(MethodDeclaration method) {
        boolean modified = false;

        // Migrate lifecycle annotations
        for (Map.Entry<String, String> entry : ANNOTATION_MAPPINGS.entrySet()) {
            String oldAnnotation = entry.getKey();
            String newAnnotation = entry.getValue();

            Optional<AnnotationExpr> annotation = method.getAnnotationByName(oldAnnotation);
            if (annotation.isPresent()) {
                // Create new annotation
                AnnotationExpr newAnnotationExpr;
                if (annotation.get() instanceof SingleMemberAnnotationExpr) {
                    // Preserve annotation value (e.g., @Ignore("reason") → @Disabled("reason"))
                    SingleMemberAnnotationExpr oldExpr = (SingleMemberAnnotationExpr) annotation.get();
                    newAnnotationExpr = new SingleMemberAnnotationExpr(
                            new Name(newAnnotation),
                            oldExpr.getMemberValue());
                } else {
                    // Simple annotation without value
                    newAnnotationExpr = new com.github.javaparser.ast.expr.MarkerAnnotationExpr(
                            new Name(newAnnotation));
                }

                // Replace annotation
                annotation.get().replace(newAnnotationExpr);
                conversions.add(
                        "@" + oldAnnotation + " → @" + newAnnotation + " (method: " + method.getNameAsString() + ")");
                modified = true;
            }
        }

        return modified;
    }

    public List<String> getConversions() {
        return conversions;
    }

    public boolean needsMockitoExtension() {
        return needsMockitoExtension;
    }

    public boolean needsSpringExtension() {
        return needsSpringExtension;
    }
}
