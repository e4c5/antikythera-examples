package com.raditha.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Knowledge Graph data structures.
 */
class KnowledgeGraphDataStructuresTest {

    @BeforeEach
    void setUp() {
        Graph.getNodes().clear();
    }

    @Test
    @DisplayName("Signature: method includes class, name, and parameter types")
    void testMethodSignature() {
        String code = """
            package com.example;
            class Service {
                public User findById(Long id, boolean active) { return null; }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(md);
        String signature = SignatureUtils.getSignature(node);

        assertEquals("com.example.Service#findById(Long,boolean)", signature);
    }

    @Test
    @DisplayName("Signature: overloaded methods produce distinct signatures")
    void testOverloadedMethods() {
        String code = """
            package com.example;
            class Service {
                void save(String s) {}
                void save(String s, int count) {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        var methods = cu.findAll(MethodDeclaration.class);

        String sig1 = SignatureUtils.getSignature(Graph.createGraphNode(methods.get(0)));
        String sig2 = SignatureUtils.getSignature(Graph.createGraphNode(methods.get(1)));

        assertNotEquals(sig1, sig2);
        assertEquals("com.example.Service#save(String)", sig1);
        assertEquals("com.example.Service#save(String,int)", sig2);
    }

    @Test
    @DisplayName("Signature: field uses class#fieldName format")
    void testFieldSignature() {
        String code = """
            package com.example;
            class Entity { private String email; }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        FieldDeclaration fd = cu.findFirst(FieldDeclaration.class).orElseThrow();

        String signature = SignatureUtils.getSignature(Graph.createGraphNode(fd));

        assertEquals("com.example.Entity#email", signature);
    }

    @Test
    @DisplayName("Signature: static blocks produce signatures with clinit")
    void testStaticBlockSignature() {
        String code = """
            package com.config;
            class Loader {
                static { System.out.println("init"); }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        InitializerDeclaration id = cu.findFirst(InitializerDeclaration.class).orElseThrow();

        String sig = SignatureUtils.getSignature(Graph.createGraphNode(id));

        assertNotNull(sig);
        assertTrue(sig.contains("Loader"));
        assertTrue(sig.contains("<clinit>"));
    }

    @Test
    @DisplayName("Signature: null node returns null")
    void testNullNode() {
        assertNull(SignatureUtils.getSignature(null));
    }

    @Test
    @DisplayName("Signature: enum constants are stable and unique")
    void testEnumConstantSignature() {
        String code = """
            package com.example;
            enum Status { NEW, DONE }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        var constants = cu.findAll(com.github.javaparser.ast.body.EnumConstantDeclaration.class);

        String sig1 = SignatureUtils.getEnumConstantSignature("com.example.Status", constants.get(0));
        String sig2 = SignatureUtils.getEnumConstantSignature("com.example.Status", constants.get(1));

        assertEquals("com.example.Status#NEW", sig1);
        assertEquals("com.example.Status#DONE", sig2);
        assertNotEquals(sig1, sig2);
    }

    @Test
    @DisplayName("Signature: lambda signatures are deterministic and unique by ordinal")
    void testLambdaSignature() {
        String code = """
            package com.example;
            class Service {
                void run() {
                    Runnable a = () -> {};
                    Runnable b = () -> {};
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        var lambdas = cu.findAll(LambdaExpr.class);

        String sig1 = SignatureUtils.getLambdaSignature("com.example.Service#run()", lambdas.get(0), 1);
        String sig2 = SignatureUtils.getLambdaSignature("com.example.Service#run()", lambdas.get(1), 2);

        assertTrue(sig1.startsWith("com.example.Service#run()$lambda_"));
        assertTrue(sig2.startsWith("com.example.Service#run()$lambda_"));
        assertNotEquals(sig1, sig2);
    }

    @Test
    @DisplayName("Edge: builder creates immutable edge with all attributes")
    void testEdgeBuilder() {
        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source("com.example.UserService#findById(Long)")
                .target("com.example.UserRepository#findById(Long)")
                .type(EdgeType.CALLS)
                .attribute("lineNumber", "42")
                .parameterValues("[123L]")
                .build();

        assertEquals("com.example.UserService#findById(Long)", edge.sourceId());
        assertEquals("com.example.UserRepository#findById(Long)", edge.targetId());
        assertEquals(EdgeType.CALLS, edge.type());
        assertEquals("42", edge.attributes().get("lineNumber"));
        assertEquals("[123L]", edge.attributes().get("parameterValues"));
    }

    @Test
    @DisplayName("Edge: ACCESSES edge includes accessType attribute")
    void testAccessesEdge() {
        KnowledgeGraphEdge edge = KnowledgeGraphEdge.builder()
                .source("com.example.Service#process()")
                .target("com.example.Entity#count")
                .type(EdgeType.ACCESSES)
                .accessType("WRITE")
                .build();

        assertEquals(EdgeType.ACCESSES, edge.type());
        assertEquals("WRITE", edge.attributes().get("accessType"));
    }

    @Test
    @DisplayName("Edge: rejects null required fields")
    void testEdgeValidation() {
        assertThrows(NullPointerException.class, () ->
            new KnowledgeGraphEdge(null, "target", EdgeType.CALLS, Map.of()));

        assertThrows(NullPointerException.class, () ->
            new KnowledgeGraphEdge("source", null, EdgeType.CALLS, Map.of()));

        assertThrows(NullPointerException.class, () ->
            new KnowledgeGraphEdge("source", "target", null, Map.of()));
    }
}
