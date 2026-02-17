package com.raditha.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for generating stable, deterministic signatures for code elements.
 * These signatures serve as unique identifiers for nodes in the knowledge graph.
 */
public final class SignatureUtils {

    private SignatureUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Generate a stable signature for a GraphNode.
     *
     * @param node the graph node
     * @return unique signature string, or null if unsupported node type
     */
    public static String getSignature(GraphNode node) {
        if (node == null || node.getNode() == null) {
            return null;
        }

        String className = getEnclosingClassName(node);
        Node astNode = node.getNode();

        if (astNode instanceof CallableDeclaration<?> cd) {
            return getMethodSignature(className, cd);
        }

        if (astNode instanceof FieldDeclaration fd) {
            return getFieldSignature(className, fd);
        }

        if (astNode instanceof TypeDeclaration<?> td) {
            return getTypeSignature(td);
        }

        if (astNode instanceof InitializerDeclaration id) {
            return getInitializerSignature(className, node, id);
        }

        if (astNode instanceof EnumConstantDeclaration constant) {
            return getEnumConstantSignature(className, constant);
        }

        if (astNode instanceof LambdaExpr lambda) {
            int line = lambda.getBegin().map(p -> p.line).orElse(0);
            int column = lambda.getBegin().map(p -> p.column).orElse(0);
            return className + "#<lambda_L" + line + "C" + column + ">";
        }

        return null;
    }

    /**
     * Get signature for a method or constructor.
     * Format: ClassName#methodName(ParamType1,ParamType2)
     */
    public static String getMethodSignature(String className, CallableDeclaration<?> cd) {
        String params = cd.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        return className + "#" + cd.getNameAsString() + "(" + params + ")";
    }

    /**
     * Get signature for a field.
     * Format: ClassName#fieldName
     */
    public static String getFieldSignature(String className, FieldDeclaration fd) {
        String fieldName = fd.getVariables().isEmpty()
                ? "unknown"
                : fd.getVariable(0).getNameAsString();
        return className + "#" + fieldName;
    }

    /**
     * Get signatures for all variables in a field declaration.
     * Format: ClassName#fieldName
     */
    public static List<String> getFieldSignatures(String className, FieldDeclaration fd) {
        List<String> signatures = new ArrayList<>();
        fd.getVariables().forEach(v -> signatures.add(className + "#" + v.getNameAsString()));
        return signatures;
    }

    public static String getFieldSignature(String className, String fieldName) {
        return className + "#" + fieldName;
    }

    /**
     * Get signature for a type (class, interface, enum).
     * Format: fully.qualified.ClassName
     */
    public static String getTypeSignature(TypeDeclaration<?> td) {
        return td.getFullyQualifiedName().orElse(td.getNameAsString());
    }

    /**
     * Get signature for a static initializer block.
     * Format: ClassName#<clinit> or ClassName#<clinit>_1 for multiple blocks
     */
    public static String getInitializerSignature(String className, GraphNode node, InitializerDeclaration id) {
        int index = getInitializerIndex(node, id);
        return className + "#<clinit>" + (index > 0 ? "_" + index : "");
    }

    public static String getInitializerSignature(String className, InitializerDeclaration id, TypeDeclaration<?> enclosingType) {
        int index = getInitializerIndex(enclosingType, id);
        return className + "#<clinit>" + (index > 0 ? "_" + index : "");
    }

    public static String getEnumConstantSignature(String enumClassName, EnumConstantDeclaration constant) {
        return enumClassName + "#" + constant.getNameAsString();
    }

    public static String getLambdaSignature(String enclosingSignature, LambdaExpr lambda, int ordinal) {
        int line = lambda.getBegin().map(p -> p.line).orElse(0);
        int column = lambda.getBegin().map(p -> p.column).orElse(0);
        return enclosingSignature + "$lambda_L" + line + "C" + column + "_" + ordinal;
    }

    public static String getMemberSignature(String ownerTypeSignature, BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration md) {
            return getMethodSignature(ownerTypeSignature, md);
        }
        if (member instanceof ConstructorDeclaration cd) {
            return getMethodSignature(ownerTypeSignature, cd);
        }
        if (member instanceof FieldDeclaration fd) {
            return getFieldSignature(ownerTypeSignature, fd);
        }
        if (member instanceof InitializerDeclaration id) {
            TypeDeclaration<?> enclosing = member.findAncestor(TypeDeclaration.class).orElse(null);
            return getInitializerSignature(ownerTypeSignature, id, enclosing);
        }
        if (member instanceof TypeDeclaration<?> td) {
            return getTypeSignature(td);
        }
        return ownerTypeSignature + "#member@" + member.getBegin().map(p -> p.line + ":" + p.column).orElse("unknown");
    }

    private static String getEnclosingClassName(GraphNode node) {
        if (node.getEnclosingType() == null) {
            return "Unknown";
        }
        return node.getEnclosingType().getFullyQualifiedName()
                .orElse(node.getEnclosingType().getNameAsString());
    }

    private static int getInitializerIndex(GraphNode node, InitializerDeclaration id) {
        return getInitializerIndex(node.getEnclosingType(), id);
    }

    private static int getInitializerIndex(TypeDeclaration<?> enclosingType, InitializerDeclaration id) {
        if (enclosingType == null) {
            return 0;
        }
        int targetLine = id.getBegin().map(p -> p.line).orElse(-1);
        int index = 0;
        for (var member : enclosingType.getMembers()) {
            if (member instanceof InitializerDeclaration init) {
                int memberLine = init.getBegin().map(p -> p.line).orElse(-2);
                if (memberLine == targetLine) {
                    return index;
                }
                index++;
            }
        }
        return 0;
    }
}
