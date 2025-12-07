package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Migrates JUnit 4 assertion patterns to JUnit 5.
 * 
 * Main responsibility: Reorder assertion message parameters from first to last
 * position.
 * JUnit 4: assertEquals("message", expected, actual)
 * JUnit 5: assertEquals(expected, actual, "message")
 */
public class AssertionMigrator {
    private static final Logger logger = LoggerFactory.getLogger(AssertionMigrator.class);

    // Assertions that take a message as the FIRST parameter in JUnit 4
    private static final Set<String> ASSERTIONS_WITH_MESSAGE_FIRST = Set.of(
            "assertEquals",
            "assertNotEquals",
            "assertSame",
            "assertNotSame",
            "assertTrue",
            "assertFalse",
            "assertNull",
            "assertNotNull",
            "assertArrayEquals");

    private final List<String> conversions = new ArrayList<>();

    /**
     * Migrate assertions in all methods of the compilation unit.
     */
    public boolean migrate(CompilationUnit cu) {
        conversions.clear();
        boolean modified = false;

        // Find all method declarations
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

        for (MethodDeclaration method : methods) {
            if (reorderAssertionMessages(method)) {
                modified = true;
            }
        }

        if (modified) {
            logger.info("Migrated {} assertion message parameters", conversions.size());
        }

        return modified;
    }

    /**
     * Reorder assertion message parameters in a method from first to last position.
     */
    private boolean reorderAssertionMessages(MethodDeclaration method) {
        boolean modified = false;

        // Find all method call expressions
        List<MethodCallExpr> methodCalls = method.findAll(MethodCallExpr.class);

        for (MethodCallExpr call : methodCalls) {
            if (isAssertionWithMessageFirst(call)) {
                if (moveMessageToEnd(call)) {
                    String methodName = call.getNameAsString();
                    conversions.add("Reordered message parameter in " + methodName +
                            " in method " + method.getNameAsString());
                    modified = true;
                }
            }
        }

        return modified;
    }

    /**
     * Check if this is an assertion with message as first parameter.
     * 
     * Pattern: assertXxx("message", otherArgs...)
     * Must have at least 2 arguments, with first being a String literal or
     * expression.
     */
    private boolean isAssertionWithMessageFirst(MethodCallExpr call) {
        String methodName = call.getNameAsString();

        // Must be a known assertion method
        if (!ASSERTIONS_WITH_MESSAGE_FIRST.contains(methodName)) {
            return false;
        }

        // Must have at least 2 arguments (message + at least one other)
        if (call.getArguments().size() < 2) {
            return false;
        }

        // First argument should look like a message (String literal or String
        // expression)
        Expression firstArg = call.getArgument(0);
        return firstArg instanceof StringLiteralExpr ||
                isLikelyStringExpression(firstArg);
    }

    /**
     * Check if an expression is likely a String (basic heuristic).
     */
    private boolean isLikelyStringExpression(Expression expr) {
        String exprStr = expr.toString();

        // String concatenation
        if (exprStr.contains("+") && exprStr.contains("\"")) {
            return true;
        }

        // Variable/method that looks like a message
        if (exprStr.toLowerCase().contains("message") ||
                exprStr.toLowerCase().contains("msg")) {
            return true;
        }

        return false;
    }

    /**
     * Move the message parameter from first to last position.
     * 
     * Before: assertEquals("message", expected, actual)
     * After: assertEquals(expected, actual, "message")
     */
    private boolean moveMessageToEnd(MethodCallExpr call) {
        try {
            List<Expression> args = call.getArguments();

            if (args.size() < 2) {
                return false;
            }

            // Extract the message (first argument)
            Expression message = args.get(0).clone();

            // Create new argument list without the first element
            List<Expression> newArgs = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                newArgs.add(args.get(i).clone());
            }

            // Add message at the end
            newArgs.add(message);

            // Replace arguments
            call.getArguments().clear();
            call.getArguments().addAll(newArgs);

            return true;
        } catch (Exception e) {
            logger.warn("Failed to reorder message parameter in {}: {}",
                    call.getNameAsString(), e.getMessage());
            return false;
        }
    }

    public List<String> getConversions() {
        return conversions;
    }
}
