package com.raditha.cleanunit;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles @Test annotation parameter conversions from JUnit 4 to JUnit 5.
 * 
 * Converts:
 * - @Test(expected = Exception.class) → assertThrows() wrapping
 * - @Test(timeout = millis) → @Timeout annotation
 */
public class TestAnnotationMigrator {
    private static final Logger logger = LoggerFactory.getLogger(TestAnnotationMigrator.class);

    private final List<String> conversions = new ArrayList<>();

    /**
     * Migrate @Test annotation parameters in test methods.
     * 
     * @param method the test method to migrate
     * @return true if any changes were made
     */
    public boolean migrateTestAnnotation(MethodDeclaration method) {
        Optional<AnnotationExpr> testAnnotation = method.getAnnotationByName("Test");
        if (testAnnotation.isEmpty()) {
            return false;
        }

        boolean modified = false;

        // Handle @Test with member values
        if (testAnnotation.get() instanceof SingleMemberAnnotationExpr) {
            // This is @Test(value) which is not valid JUnit 4 syntax, skip
            return false;
        } else if (testAnnotation.get() instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normalAnnotation = (NormalAnnotationExpr) testAnnotation.get();

            // Check for 'expected' parameter
            Optional<MemberValuePair> expectedParam = normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("expected"))
                    .findFirst();

            if (expectedParam.isPresent()) {
                if (convertExpectedException(method, normalAnnotation, expectedParam.get())) {
                    modified = true;
                }
            }

            // Check for 'timeout' parameter
            Optional<MemberValuePair> timeoutParam = normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("timeout"))
                    .findFirst();

            if (timeoutParam.isPresent()) {
                if (convertTimeout(method, normalAnnotation, timeoutParam.get())) {
                    modified = true;
                }
            }

            // If all parameters were removed, convert to simple @Test
            if (normalAnnotation.getPairs().isEmpty()) {
                MarkerAnnotationExpr simpleTest = new MarkerAnnotationExpr(new Name("Test"));
                normalAnnotation.replace(simpleTest);
            }
        }

        return modified;
    }

    /**
     * Convert @Test(expected = Exception.class) to assertThrows() pattern.
     */
    private boolean convertExpectedException(MethodDeclaration method, NormalAnnotationExpr testAnnotation,
            MemberValuePair expectedParam) {
        // Extract exception class
        Expression exceptionClassExpr = expectedParam.getValue();
        if (!(exceptionClassExpr instanceof ClassExpr)) {
            logger.warn("Unexpected expected parameter format in method: {}", method.getNameAsString());
            return false;
        }

        ClassExpr classExpr = (ClassExpr) exceptionClassExpr;
        ClassOrInterfaceType exceptionType = (ClassOrInterfaceType) classExpr.getType();

        // Get method body
        Optional<BlockStmt> bodyOpt = method.getBody();
        if (bodyOpt.isEmpty()) {
            logger.warn("Test method {} has no body", method.getNameAsString());
            return false;
        }

        BlockStmt body = bodyOpt.get();

        // Create assertThrows wrapper
        // assertThrows(ExceptionClass.class, () -> { original body });
        MethodCallExpr assertThrows = new MethodCallExpr("assertThrows");
        assertThrows.addArgument(new ClassExpr(exceptionType));

        // Create lambda with original body
        LambdaExpr lambda = new LambdaExpr();
        lambda.setEnclosingParameters(true);

        // Clone the body for the lambda
        BlockStmt lambdaBody = body.clone();
        lambda.setBody(lambdaBody);

        assertThrows.addArgument(lambda);

        // Replace method body with assertThrows call
        BlockStmt newBody = new BlockStmt();
        newBody.addStatement(new ExpressionStmt(assertThrows));
        method.setBody(newBody);

        // Remove 'expected' parameter from annotation
        testAnnotation.getPairs().remove(expectedParam);

        conversions.add("@Test(expected=" + exceptionType.getNameAsString() + ".class) → assertThrows() in " +
                method.getNameAsString());

        return true;
    }

    /**
     * Convert @Test(timeout = millis) to @Timeout annotation.
     */
    private boolean convertTimeout(MethodDeclaration method, NormalAnnotationExpr testAnnotation,
            MemberValuePair timeoutParam) {
        // Extract timeout value
        Expression timeoutValue = timeoutParam.getValue();

        // Create @Timeout annotation
        // @Timeout(value = X, unit = TimeUnit.MILLISECONDS)
        NormalAnnotationExpr timeoutAnnotation = new NormalAnnotationExpr();
        timeoutAnnotation.setName(new Name("Timeout"));

        // Add value parameter
        timeoutAnnotation.addPair("value", timeoutValue.clone());

        // Add unit parameter (default to MILLISECONDS for JUnit 4 compatibility)
        FieldAccessExpr timeUnit = new FieldAccessExpr(
                new NameExpr("TimeUnit"),
                "MILLISECONDS");
        timeoutAnnotation.addPair("unit", timeUnit);

        // Add @Timeout annotation to method
        method.addAnnotation(timeoutAnnotation);

        // Remove 'timeout' parameter from @Test
        testAnnotation.getPairs().remove(timeoutParam);

        conversions.add("@Test(timeout=" + timeoutValue + ") → @Timeout in " + method.getNameAsString());

        return true;
    }

    public List<String> getConversions() {
        return conversions;
    }
}
