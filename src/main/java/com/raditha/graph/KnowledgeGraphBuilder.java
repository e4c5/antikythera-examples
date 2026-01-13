package com.raditha.graph;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import sa.com.cloudsolutions.antikythera.depsolver.DependencyAnalyzer;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * Knowledge Graph Builder that extends DependencyAnalyzer.
 * Overrides hook methods to stream edges directly to Neo4j during AST traversal.
 * 
 * Note: The hook methods (onFieldAccessed, onTypeUsed, etc.) were added in Phase 1.
 * Until that version is published, these methods will not be called automatically
 * during DFS traversal but can be called manually for testing.
 */
public class KnowledgeGraphBuilder extends DependencyAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphBuilder.class);

    private final Neo4jGraphStore graphStore;
    private boolean autoClose = true;

    /**
     * Create a builder with an existing Neo4jGraphStore.
     */
    public KnowledgeGraphBuilder(Neo4jGraphStore graphStore) {
        this.graphStore = graphStore;
    }

    /**
     * Create a builder from graph.yml configuration file.
     *
     * @param configFile path to graph.yml
     * @throws IOException if config cannot be read
     */
    public static KnowledgeGraphBuilder fromSettings(File configFile) throws IOException {
        Neo4jGraphStore store = Neo4jGraphStore.fromSettings(configFile);
        return new KnowledgeGraphBuilder(store);
    }

    /**
     * Build the knowledge graph from a collection of methods.
     * This is the main entry point for graph construction.
     *
     * @param methods collection of methods to analyze
     */
    public void build(Collection<MethodDeclaration> methods) {
        logger.info("Starting knowledge graph build for {} methods", methods.size());
        try {
            // Manually traverse AST to ensure hooks are called (bypass DependencyAnalyzer mismatch)
            GraphVisitor visitor = new GraphVisitor();
            for (MethodDeclaration method : methods) {
                // Ensure GraphNode is created and tracked
                GraphNode node = Graph.createGraphNode(method);
                method.accept(visitor, node);
            }
            graphStore.flushEdges();
            logger.info("Knowledge graph build complete. Total edges: {}", graphStore.getEdgeCount());
        } finally {
            if (autoClose) {
                close();
            }
        }
    }

    /**
     * Set whether to automatically close the graph store after build.
     */
    public void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    /**
     * Close the graph store connection.
     */
    public void close() {
        if (graphStore != null) {
            graphStore.close();
        }
    }

    /**
     * Get the underlying graph store for direct access.
     */
    public Neo4jGraphStore getGraphStore() {
        return graphStore;
    }

    // ========================
    // Hook Methods (called by DependencyAnalyzer during DFS)
    // These override protected methods in DependencyAnalyzer
    // ========================

    /**
     * Called when a field is accessed during traversal.
     * Creates an ACCESSES edge in the knowledge graph.
     */
    protected void onFieldAccessed(GraphNode node, FieldAccessExpr fae) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) return;

        String targetId = resolveFieldSignature(node, fae);

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.ACCESSES)
                .accessType("READ")
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --ACCESSES--> {}", sourceId, targetId);
    }

    /**
     * Called when a type is explicitly used (e.g., in casts, catch clauses).
     * Creates a USES edge in the knowledge graph.
     */
    protected void onTypeUsed(GraphNode node, Type type) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) return;

        String targetId = type.asString();

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.USES)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --USES--> {}", sourceId, targetId);
    }

    /**
     * Called when a lambda expression is discovered.
     * Creates an ENCLOSES edge in the knowledge graph.
     */
    protected void onLambdaDiscovered(GraphNode node, LambdaExpr lambda) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) return;

        int line = lambda.getBegin().map(p -> p.line).orElse(0);
        String lambdaId = sourceId + "$lambda_L" + line;

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(lambdaId)
                .type(EdgeType.ENCLOSES)
                .attribute("kind", "lambda")
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --ENCLOSES--> {} (lambda)", sourceId, lambdaId);
    }

    /**
     * Called when a nested type (inner class) is discovered.
     * Creates an ENCLOSES edge in the knowledge graph.
     */
    protected void onNestedTypeDiscovered(GraphNode node, ClassOrInterfaceDeclaration nestedType) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) return;

        String targetId = nestedType.getFullyQualifiedName()
                .orElse(nestedType.getNameAsString());

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.ENCLOSES)
                .attribute("kind", "inner_class")
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --ENCLOSES--> {} (nested type)", sourceId, targetId);
    }

    // ========================
    // Helper Methods
    // ========================

    private String resolveFieldSignature(GraphNode node, FieldAccessExpr fae) {
        String scopeType = "Unknown";
        if (node.getEnclosingType() != null) {
            scopeType = node.getEnclosingType().getFullyQualifiedName()
                    .orElse(node.getEnclosingType().getNameAsString());
        }
        return scopeType + "#" + fae.getNameAsString();
    }

    // ========================
    // Phase 4: Additional Hook Methods
    // ========================

    /**
     * Called when a method call is discovered.
     * Creates a CALLS edge in the knowledge graph.
     */
    public void onMethodCalled(GraphNode node, MethodCallExpr mce) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) return;

        String targetId = mce.getNameAsString();
        // Try to resolve fully qualified target - for now use simple name
        if (node.getEnclosingType() != null) {
            targetId = node.getEnclosingType().getFullyQualifiedName()
                    .orElse(node.getEnclosingType().getNameAsString()) + "#" + mce.getNameAsString() + "()";
        }

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.CALLS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --CALLS--> {}", sourceId, targetId);
    }

    /**
     * Called when a class member (method, field, etc.) is discovered.
     * Creates a CONTAINS edge in the knowledge graph.
     */
    public void onMemberDiscovered(ClassOrInterfaceDeclaration clazz, BodyDeclaration<?> member) {
        String sourceId = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());

        String targetId;
        if (member instanceof MethodDeclaration md) {
            targetId = sourceId + "#" + md.getNameAsString() + "()";
        } else {
            targetId = sourceId + "#member";
        }

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.CONTAINS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --CONTAINS--> {}", sourceId, targetId);
    }

    /**
     * Called when a class extends another class.
     * Creates an EXTENDS edge in the knowledge graph.
     */
    public void onExtendsDiscovered(ClassOrInterfaceDeclaration clazz, ClassOrInterfaceType extendedType) {
        String sourceId = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
        String targetId = extendedType.getNameAsString();

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.EXTENDS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --EXTENDS--> {}", sourceId, targetId);
    }

    /**
     * Called when a class implements an interface.
     * Creates an IMPLEMENTS edge in the knowledge graph.
     */
    public void onImplementsDiscovered(ClassOrInterfaceDeclaration clazz, ClassOrInterfaceType implementedType) {
        String sourceId = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
        String targetId = implementedType.getNameAsString();

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.IMPLEMENTS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --IMPLEMENTS--> {}", sourceId, targetId);
    }
    /**
     * Internal Visitor to traverse AST and trigger hooks.
     * This ensures our hooks are called regardless of the parent DependencyAnalyzer version.
     */
    private class GraphVisitor extends VoidVisitorAdapter<GraphNode> {
        @Override
        public void visit(MethodCallExpr n, GraphNode scope) {
            onMethodCalled(scope, n);
            super.visit(n, scope);
        }

        @Override
        public void visit(FieldAccessExpr n, GraphNode scope) {
            onFieldAccessed(scope, n);
            super.visit(n, scope);
        }

        @Override
        public void visit(LambdaExpr n, GraphNode scope) {
            onLambdaDiscovered(scope, n);
            // Continue traversal with same scope for now
            super.visit(n, scope);
        }
        
        @Override
        public void visit(ClassOrInterfaceType n, GraphNode scope) {
            // USES edges for types
            onTypeUsed(scope, n);
            super.visit(n, scope);
        }
    }
}
