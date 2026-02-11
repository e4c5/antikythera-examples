package com.raditha.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;

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

    private static String getEnclosingClassName(GraphNode node) {
        if (node.getEnclosingType() == null) {
            return "Unknown";
        }
        return node.getEnclosingType().getFullyQualifiedName()
                .orElse(node.getEnclosingType().getNameAsString());
    }

    private static int getInitializerIndex(GraphNode node, InitializerDeclaration id) {
        if (node.getEnclosingType() == null) {
            return 0;
        }
        int targetLine = id.getBegin().map(p -> p.line).orElse(-1);
        int index = 0;
        for (var member : node.getEnclosingType().getMembers()) {
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
