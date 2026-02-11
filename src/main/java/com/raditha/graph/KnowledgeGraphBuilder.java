package com.raditha.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.depsolver.DependencyAnalyzer;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Knowledge Graph Builder that streams structural and behavioral relationships to Neo4j.
 */
public class KnowledgeGraphBuilder extends DependencyAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphBuilder.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Neo4jGraphStore graphStore;
    private boolean autoClose = true;

    public KnowledgeGraphBuilder(Neo4jGraphStore graphStore) {
        this.graphStore = graphStore;
    }

    public static KnowledgeGraphBuilder fromSettings(File configFile) throws IOException {
        Neo4jGraphStore store = Neo4jGraphStore.fromSettings(configFile);
        return new KnowledgeGraphBuilder(store);
    }

    /**
     * Primary entry point: build graph from compilation units.
     */
    public void build(List<CompilationUnit> units) {
        logger.info("Starting knowledge graph build for {} compilation units", units.size());
        try {
            for (CompilationUnit cu : units) {
                for (TypeDeclaration<?> type : cu.getTypes()) {
                    traverseType(type, null);
                }
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
     * Compatibility wrapper for existing method-based callers.
     */
    public void build(Collection<MethodDeclaration> methods) {
        Set<CompilationUnit> units = methods.stream()
                .map(MethodDeclaration::findCompilationUnit)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!units.isEmpty()) {
            build(new ArrayList<>(units));
            return;
        }

        logger.info("No compilation units found for compatibility mode. Falling back to method traversal.");
        try {
            for (MethodDeclaration method : methods) {
                GraphNode node = Graph.createGraphNode(method);
                String sourceId = SignatureUtils.getSignature(node);
                if (sourceId == null) {
                    continue;
                }
                persistNode(sourceId, "Method", method.getNameAsString(), sourceId);

                ScopeContext context = fromGraphNode(node, sourceId);
                method.accept(new GraphVisitor(), context);
            }
            graphStore.flushEdges();
        } finally {
            if (autoClose) {
                close();
            }
        }
    }

    public void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    public void close() {
        if (graphStore != null) {
            graphStore.close();
        }
    }

    public Neo4jGraphStore getGraphStore() {
        return graphStore;
    }

    @Override
    protected void onFieldAccessed(GraphNode node, FieldAccessExpr fae) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) {
            return;
        }

        ScopeContext context = fromGraphNode(node, sourceId);
        emitFieldAccess(context, fae);
    }

    @Override
    protected void onTypeUsed(GraphNode node, Type type) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) {
            return;
        }

        String targetId = resolveTypeSignature(node.getCompilationUnit(), type);
        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.USES)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --USES--> {}", sourceId, targetId);
    }

    @Override
    protected void onLambdaDiscovered(GraphNode node, LambdaExpr lambda) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) {
            return;
        }

        String lambdaId = SignatureUtils.getLambdaSignature(sourceId, lambda, 0);
        persistNode(lambdaId, "Lambda", "lambda", node.getEnclosingType() == null ? sourceId : SignatureUtils.getTypeSignature(node.getEnclosingType()));

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(lambdaId)
                .type(EdgeType.ENCLOSES)
                .attribute("kind", "lambda")
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --ENCLOSES--> {} (lambda)", sourceId, lambdaId);
    }

    @Override
    protected void onNestedTypeDiscovered(GraphNode node, ClassOrInterfaceDeclaration nestedType) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) {
            return;
        }

        String targetId = SignatureUtils.getTypeSignature(nestedType);
        persistNode(targetId, nestedType.isInterface() ? "Interface" : "Class", nestedType.getNameAsString(), targetId);

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.ENCLOSES)
                .attribute("kind", "inner_class")
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --ENCLOSES--> {} (nested type)", sourceId, targetId);
    }

    public void onMethodCalled(GraphNode node, MethodCallExpr mce) {
        String sourceId = SignatureUtils.getSignature(node);
        if (sourceId == null) {
            return;
        }

        ScopeContext context = fromGraphNode(node, sourceId);
        emitMethodCall(context, mce);
    }

    public void onMemberDiscovered(ClassOrInterfaceDeclaration clazz, BodyDeclaration<?> member) {
        String sourceId = SignatureUtils.getTypeSignature(clazz);
        String targetId = SignatureUtils.getMemberSignature(sourceId, member);

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.CONTAINS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --CONTAINS--> {}", sourceId, targetId);
    }

    public void onExtendsDiscovered(ClassOrInterfaceDeclaration clazz, ClassOrInterfaceType extendedType) {
        CompilationUnit cu = clazz.findCompilationUnit().orElse(null);
        String sourceId = SignatureUtils.getTypeSignature(clazz);
        String targetId = resolveTypeSignature(cu, extendedType);

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.EXTENDS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --EXTENDS--> {}", sourceId, targetId);
    }

    public void onImplementsDiscovered(ClassOrInterfaceDeclaration clazz, ClassOrInterfaceType implementedType) {
        CompilationUnit cu = clazz.findCompilationUnit().orElse(null);
        String sourceId = SignatureUtils.getTypeSignature(clazz);
        String targetId = resolveTypeSignature(cu, implementedType);

        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source(sourceId)
                .target(targetId)
                .type(EdgeType.IMPLEMENTS)
                .build();

        graphStore.persistEdge(edge);
        logger.trace("Edge: {} --IMPLEMENTS--> {}", sourceId, targetId);
    }

    private void traverseType(TypeDeclaration<?> type, String enclosingSignature) {
        String typeSignature = SignatureUtils.getTypeSignature(type);
        persistNode(typeSignature, nodeTypeLabel(type), type.getNameAsString(), typeSignature);

        if (enclosingSignature != null) {
            graphStore.persistEdge(KnowledgeGraphEdge.builder()
                    .source(enclosingSignature)
                    .target(typeSignature)
                    .type(EdgeType.ENCLOSES)
                    .attribute("kind", "inner_class")
                    .build());
        }

        if (type instanceof ClassOrInterfaceDeclaration coid) {
            for (ClassOrInterfaceType extendedType : coid.getExtendedTypes()) {
                onExtendsDiscovered(coid, extendedType);
            }
            for (ClassOrInterfaceType implementedType : coid.getImplementedTypes()) {
                onImplementsDiscovered(coid, implementedType);
            }
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            emitContains(typeSignature, member);
            processMember(type, typeSignature, member);
            if (member instanceof TypeDeclaration<?> nestedType) {
                traverseType(nestedType, typeSignature);
            }
        }

        if (type instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                String constantSignature = SignatureUtils.getEnumConstantSignature(typeSignature, constant);
                persistNode(constantSignature, "EnumConstant", constant.getNameAsString(), typeSignature);

                graphStore.persistEdge(KnowledgeGraphEdge.builder()
                        .source(typeSignature)
                        .target(constantSignature)
                        .type(EdgeType.CONTAINS)
                        .build());
            }
        }
    }

    private void processMember(TypeDeclaration<?> ownerType, String ownerSignature, BodyDeclaration<?> member) {
        CompilationUnit cu = ownerType.findCompilationUnit().orElse(null);

        if (member instanceof MethodDeclaration md) {
            String methodSignature = SignatureUtils.getMethodSignature(ownerSignature, md);
            persistNode(methodSignature, "Method", md.getNameAsString(), ownerSignature);
            processCallableBody(cu, ownerSignature, methodSignature, md, md.getParameters(), md.getBody().orElse(null));
            return;
        }

        if (member instanceof ConstructorDeclaration cd) {
            String constructorSignature = SignatureUtils.getMethodSignature(ownerSignature, cd);
            persistNode(constructorSignature, "Constructor", cd.getNameAsString(), ownerSignature);
            processCallableBody(cu, ownerSignature, constructorSignature, cd, cd.getParameters(), cd.getBody());
            return;
        }

        if (member instanceof FieldDeclaration fd) {
            for (VariableDeclarator var : fd.getVariables()) {
                String fieldSignature = SignatureUtils.getFieldSignature(ownerSignature, var.getNameAsString());
                persistNode(fieldSignature, "Field", var.getNameAsString(), ownerSignature);

                var.getInitializer().ifPresent(init -> {
                    ScopeContext context = new ScopeContext(ownerSignature, ownerSignature, cu, new HashMap<>(), new AtomicInteger());
                    context.symbolTypes().put("this", ownerSignature);
                    init.accept(new GraphVisitor(), context);
                });
            }
            return;
        }

        if (member instanceof InitializerDeclaration initializer) {
            String initSignature = SignatureUtils.getInitializerSignature(ownerSignature, initializer, ownerType);
            persistNode(initSignature, "StaticBlock", "<clinit>", ownerSignature);
            ScopeContext context = new ScopeContext(initSignature, ownerSignature, cu, new HashMap<>(), new AtomicInteger());
            context.symbolTypes().put("this", ownerSignature);
            initializer.accept(new GraphVisitor(), context);
        }
    }

    private void processCallableBody(
            CompilationUnit cu,
            String ownerTypeSignature,
            String callableSignature,
            CallableDeclaration<?> callable,
            List<Parameter> parameters,
            Node bodyNode
    ) {
        Map<String, String> symbols = new HashMap<>();
        symbols.put("this", ownerTypeSignature);

        for (Parameter parameter : parameters) {
            symbols.put(parameter.getNameAsString(), resolveTypeSignature(cu, parameter.getType()));
        }

        ScopeContext context = new ScopeContext(callableSignature, ownerTypeSignature, cu, symbols, new AtomicInteger());

        if (bodyNode != null) {
            bodyNode.accept(new GraphVisitor(), context);
        }

        if (callable instanceof MethodDeclaration methodDeclaration) {
            String returnType = methodDeclaration.getType().asString();
            if (!"void".equals(returnType)) {
                graphStore.persistEdge(KnowledgeGraphEdge.builder()
                        .source(callableSignature)
                        .target(resolveTypeSignature(cu, methodDeclaration.getType()))
                        .type(EdgeType.USES)
                        .build());
            }
        }
    }

    private void emitContains(String ownerSignature, BodyDeclaration<?> member) {
        if (member instanceof FieldDeclaration fd) {
            for (String fieldSignature : SignatureUtils.getFieldSignatures(ownerSignature, fd)) {
                graphStore.persistEdge(KnowledgeGraphEdge.builder()
                        .source(ownerSignature)
                        .target(fieldSignature)
                        .type(EdgeType.CONTAINS)
                        .build());
            }
            return;
        }

        String memberSignature = SignatureUtils.getMemberSignature(ownerSignature, member);
        graphStore.persistEdge(KnowledgeGraphEdge.builder()
                .source(ownerSignature)
                .target(memberSignature)
                .type(EdgeType.CONTAINS)
                .build());
    }

    private void emitMethodCall(ScopeContext context, MethodCallExpr mce) {
        String targetId;
        String resolution = "exact";

        if (mce.getScope().isPresent()) {
            Expression scope = mce.getScope().orElseThrow();
            String resolvedScopeType = resolveExpressionType(context, scope);
            if (resolvedScopeType != null) {
                targetId = resolvedScopeType + "#" + mce.getNameAsString() + "()";
            } else {
                targetId = scope + "#" + mce.getNameAsString() + "()";
                resolution = "partial";
            }
        } else {
            targetId = context.enclosingTypeSignature() + "#" + mce.getNameAsString() + "()";
        }

        List<String> args = mce.getArguments().stream()
                .map(Expression::toString)
                .collect(Collectors.toList());

        KnowledgeGraphEdge.Builder edgeBuilder = KnowledgeGraphEdge.builder()
                .source(context.sourceId())
                .target(targetId)
                .type(EdgeType.CALLS);

        if (!"exact".equals(resolution)) {
            edgeBuilder.attribute("resolution", resolution);
        }

        if (!args.isEmpty()) {
            try {
                edgeBuilder.attribute("parameterValues", objectMapper.writeValueAsString(args));
            } catch (Exception e) {
                logger.warn("Failed to serialize arguments for call: {}", targetId, e);
            }
        }

        graphStore.persistEdge(edgeBuilder.build());
        logger.trace("Edge: {} --CALLS--> {}", context.sourceId(), targetId);
    }

    private void emitMethodReference(ScopeContext context, MethodReferenceExpr mre) {
        String scopeType = resolveExpressionType(context, mre.getScope());
        String targetId;
        String resolution = "exact";

        if (scopeType != null) {
            targetId = scopeType + "#" + mre.getIdentifier() + "()";
        } else {
            targetId = mre.getScope() + "#" + mre.getIdentifier() + "()";
            resolution = "partial";
        }

        KnowledgeGraphEdge.Builder edgeBuilder = KnowledgeGraphEdge.builder()
                .source(context.sourceId())
                .target(targetId)
                .type(EdgeType.REFERENCES);

        if (!"exact".equals(resolution)) {
            edgeBuilder.attribute("resolution", resolution);
        }

        graphStore.persistEdge(edgeBuilder.build());
        logger.trace("Edge: {} --REFERENCES--> {}", context.sourceId(), targetId);
    }

    private void emitFieldAccess(ScopeContext context, FieldAccessExpr fae) {
        String targetId;
        String resolution = "exact";

        String scopeType = resolveExpressionType(context, fae.getScope());
        if (scopeType != null) {
            targetId = scopeType + "#" + fae.getNameAsString();
        } else {
            targetId = fae.getScope() + "#" + fae.getNameAsString();
            resolution = "partial";
        }

        KnowledgeGraphEdge.Builder edgeBuilder = KnowledgeGraphEdge.builder()
                .source(context.sourceId())
                .target(targetId)
                .type(EdgeType.ACCESSES)
                .accessType("READ");

        if (!"exact".equals(resolution)) {
            edgeBuilder.attribute("resolution", resolution);
        }

        graphStore.persistEdge(edgeBuilder.build());
        logger.trace("Edge: {} --ACCESSES--> {}", context.sourceId(), targetId);
    }

    private String resolveExpressionType(ScopeContext context, Expression expression) {
        if (expression instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            String fromSymbols = context.symbolTypes().get(name);
            if (fromSymbols != null) {
                return fromSymbols;
            }

            String fqn = AbstractCompiler.findFullyQualifiedName(context.compilationUnit(), name);
            if (fqn != null) {
                return fqn;
            }
            return null;
        }

        if (expression instanceof ThisExpr) {
            return context.enclosingTypeSignature();
        }

        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String scopeType = resolveExpressionType(context, fieldAccessExpr.getScope());
            if (scopeType != null) {
                return scopeType;
            }
            return null;
        }

        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return resolveTypeSignature(context.compilationUnit(), objectCreationExpr.getType());
        }

        if (expression instanceof CastExpr castExpr) {
            return resolveTypeSignature(context.compilationUnit(), castExpr.getType());
        }

        return null;
    }

    private String resolveTypeSignature(CompilationUnit compilationUnit, Type type) {
        TypeWrapper wrapper = AbstractCompiler.findType(compilationUnit, type);
        if (wrapper != null) {
            String fqn = wrapper.getFullyQualifiedName();
            if (fqn != null) {
                return fqn;
            }
        }
        return type.asString();
    }

    private ScopeContext fromGraphNode(GraphNode node, String sourceId) {
        CompilationUnit cu = node.getCompilationUnit();
        String ownerType = node.getEnclosingType() == null ? sourceId : SignatureUtils.getTypeSignature(node.getEnclosingType());

        Map<String, String> symbols = new HashMap<>();
        symbols.put("this", ownerType);

        Node astNode = node.getNode();
        if (astNode instanceof CallableDeclaration<?> callable) {
            for (Parameter parameter : callable.getParameters()) {
                symbols.put(parameter.getNameAsString(), resolveTypeSignature(cu, parameter.getType()));
            }
        }

        return new ScopeContext(sourceId, ownerType, cu, symbols, new AtomicInteger());
    }

    private String nodeTypeLabel(TypeDeclaration<?> typeDeclaration) {
        if (typeDeclaration instanceof EnumDeclaration) {
            return "Enum";
        }
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration coid && coid.isInterface()) {
            return "Interface";
        }
        return "Class";
    }

    private void persistNode(String signature, String nodeType, String name, String fqn) {
        try {
            graphStore.persistNode(signature, nodeType, name, fqn);
        } catch (Exception e) {
            logger.debug("Node upsert skipped for {} due to store implementation: {}", signature, e.getMessage());
        }
    }

    private record ScopeContext(
            String sourceId,
            String enclosingTypeSignature,
            CompilationUnit compilationUnit,
            Map<String, String> symbolTypes,
            AtomicInteger lambdaCounter
    ) {
        ScopeContext child(String childSource, Map<String, String> childSymbols) {
            return new ScopeContext(childSource, enclosingTypeSignature, compilationUnit, childSymbols, new AtomicInteger());
        }

        int nextLambdaIndex() {
            return lambdaCounter.incrementAndGet();
        }
    }

    private class GraphVisitor extends VoidVisitorAdapter<ScopeContext> {
        @Override
        public void visit(MethodCallExpr n, ScopeContext context) {
            emitMethodCall(context, n);
            super.visit(n, context);
        }

        @Override
        public void visit(FieldAccessExpr n, ScopeContext context) {
            emitFieldAccess(context, n);
            super.visit(n, context);
        }

        @Override
        public void visit(MethodReferenceExpr n, ScopeContext context) {
            emitMethodReference(context, n);
            super.visit(n, context);
        }

        @Override
        public void visit(ClassOrInterfaceType n, ScopeContext context) {
            graphStore.persistEdge(KnowledgeGraphEdge.builder()
                    .source(context.sourceId())
                    .target(resolveTypeSignature(context.compilationUnit(), n))
                    .type(EdgeType.USES)
                    .build());
            super.visit(n, context);
        }

        @Override
        public void visit(VariableDeclarationExpr n, ScopeContext context) {
            for (VariableDeclarator variable : n.getVariables()) {
                context.symbolTypes().put(variable.getNameAsString(), resolveTypeSignature(context.compilationUnit(), variable.getType()));
            }
            super.visit(n, context);
        }

        @Override
        public void visit(LambdaExpr n, ScopeContext context) {
            int index = context.nextLambdaIndex();
            String lambdaSignature = SignatureUtils.getLambdaSignature(context.sourceId(), n, index);
            persistNode(lambdaSignature, "Lambda", "lambda", context.enclosingTypeSignature());

            graphStore.persistEdge(KnowledgeGraphEdge.builder()
                    .source(context.sourceId())
                    .target(lambdaSignature)
                    .type(EdgeType.ENCLOSES)
                    .attribute("kind", "lambda")
                    .build());

            Map<String, String> lambdaSymbols = new HashMap<>(context.symbolTypes());
            for (Parameter parameter : n.getParameters()) {
                if (!parameter.getType().isUnknownType()) {
                    lambdaSymbols.put(parameter.getNameAsString(), resolveTypeSignature(context.compilationUnit(), parameter.getType()));
                }
            }

            ScopeContext lambdaContext = context.child(lambdaSignature, lambdaSymbols);
            n.getBody().accept(this, lambdaContext);
        }
    }
}
