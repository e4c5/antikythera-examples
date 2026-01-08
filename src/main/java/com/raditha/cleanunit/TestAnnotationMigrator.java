package com.raditha.cleanunit;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
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
        } else if (testAnnotation.get() instanceof NormalAnnotationExpr normalAnnotation) {

            // Check for 'expected' parameter
            Optional<MemberValuePair> expectedParam = normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("expected"))
                    .findFirst();

            if (expectedParam.isPresent()) {
                modified = convertExpectedException(method, normalAnnotation, expectedParam.get());
            }

            // Check for 'timeout' parameter
            Optional<MemberValuePair> timeoutParam = normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("timeout"))
                    .findFirst();

            if (timeoutParam.isPresent()) {
                modified = convertTimeout(method, normalAnnotation, timeoutParam.get());
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
        if (exceptionClassExpr instanceof ClassExpr classExpr) {
            return convertExpectedException(method, testAnnotation, expectedParam, classExpr);
        }
        throw new IllegalArgumentException(
                "Expected parameter is not a class expression in method " + method.getNameAsString());
    }

    private boolean convertExpectedException(MethodDeclaration method, NormalAnnotationExpr testAnnotation, MemberValuePair expectedParam, ClassExpr classExpr) {
        ClassOrInterfaceType exceptionType = (ClassOrInterfaceType) classExpr.getType();

        // Get method body
        Optional<BlockStmt> bodyOpt = method.getBody();
        if (bodyOpt.isEmpty()) {
            logger.warn("Test method {} has no body", method.getNameAsString());
            return true;
        }

        BlockStmt body = bodyOpt.get();

        // Create assertThrows wrapper
        MethodCallExpr assertThrows = new MethodCallExpr("assertThrows");
        assertThrows.addArgument(new ClassExpr(exceptionType));

        // Create lambda with original body
        LambdaExpr lambda = new LambdaExpr();
        lambda.setEnclosingParameters(true);

        // Clone the body for the lambda (removing any try-catch that just rethrows)
        BlockStmt lambdaBody = cleanBodyForAssertThrows(body.clone(), exceptionType.getNameAsString());
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
     * Clean body for assertThrows - remove try-catch blocks that just rethrow the
     * expected exception.
     * This handles patterns like:
     * try {
     * // code
     * } catch (ExpectedException ex) {
     * // optional assertions
     * throw ex;
     * }
     */
    private BlockStmt cleanBodyForAssertThrows(BlockStmt body, String expectedExceptionName) {
        // Iteratively unwrap top-level try-catch that only rethrows the expected exception
        boolean unwrapped = true;
        while (unwrapped) {
            unwrapped = false;
            if (body.getStatements().size() == 1) {
                Statement only = body.getStatement(0);
                if (only.isTryStmt()) {
                    TryStmt tryStmt = only.asTryStmt();
                    // Do not modify if there is a finally block
                    if (tryStmt.getFinallyBlock().isPresent()) {
                        break;
                    }

                    // Find a catch that matches expected exception and simply rethrows the caught var
                    Optional<CatchClause> matching = tryStmt.getCatchClauses().stream()
                            .filter(c -> catchMatchesExpected(c, expectedExceptionName) && isSimpleRethrow(c))
                            .findFirst();

                    if (matching.isPresent()) {
                        // Unwrap: keep the try block content
                        body = tryStmt.getTryBlock().clone();
                        unwrapped = true; // continue in case of nested wrapping
                    }
                }
            }
        }
        return body;
    }

    private boolean catchMatchesExpected(CatchClause catchClause, String expectedSimpleName) {
        Type t = catchClause.getParameter().getType();
        if (t.isUnionType()) {
            UnionType ut = t.asUnionType();
            return ut.getElements().stream().anyMatch(elem -> typeSimpleNameEquals(elem, expectedSimpleName));
        }
        return typeSimpleNameEquals(t, expectedSimpleName);
    }

    private boolean typeSimpleNameEquals(Type type, String expectedSimpleName) {
        if (type.isClassOrInterfaceType()) {
            String simple = type.asClassOrInterfaceType().getName().asString();
            return simple.equals(expectedSimpleName);
        }
        // Fallback: compare toString simple form
        return type.asString().endsWith("." + expectedSimpleName) || type.asString().equals(expectedSimpleName);
    }

    private boolean isSimpleRethrow(CatchClause catchClause) {
        String paramName = catchClause.getParameter().getNameAsString();
        List<Statement> stmts = catchClause.getBody().getStatements();
        if (stmts.isEmpty()) return false;
        // Allow assertions or other expressions before the final throw
        // Require that the last statement is `throw <paramName>;`
        Statement last = stmts.getLast();
        if (!(last.isThrowStmt())) return false;
        ThrowStmt throwStmt = last.asThrowStmt();
        Expression thrown = throwStmt.getExpression();
        if (!(thrown instanceof NameExpr nameExpr)) return false;
        if (!nameExpr.getNameAsString().equals(paramName)) return false;
        // If there are preceding throw statements or returns, reject (we only allow expression statements)
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s = stmts.get(i);
            if (!(s.isExpressionStmt())) {
                return false;
            }
        }
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
