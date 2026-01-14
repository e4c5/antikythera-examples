package com.raditha.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD Tests for Phase 4: Relationship Extraction.
 * These tests are written FIRST (Red phase), then implementation follows.
 */
class RelationshipExtractionTest {

    private Neo4jGraphStore mockStore;
    private KnowledgeGraphBuilder builder;

    @BeforeEach
    void setUp() {
        Graph.getNodes().clear();
        mockStore = mock(Neo4jGraphStore.class);
        builder = new KnowledgeGraphBuilder(mockStore);
        builder.setAutoClose(false);
    }

    // ========================
    // Task 4.1: CALLS Edge Tests
    // ========================

    @Test
    @DisplayName("CALLS: method calling another method creates CALLS edge")
    void testCallsEdge_MethodToMethod() {
        String code = """
            package com.example;
            class Service {
                void caller() {
                    helper();
                }
                void helper() {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration caller = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("caller"))
                .findFirst().orElseThrow();
        MethodCallExpr mce = cu.findFirst(MethodCallExpr.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(caller);
        
        // Act: Simulate what happens when a method call is visited
        builder.onMethodCalled(node, mce);

        // Assert: Verify CALLS edge was persisted
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.CALLS, edge.type());
        assertTrue(edge.sourceId().contains("caller"));
        assertTrue(edge.targetId().contains("helper"));
    }

    // ========================
    // Task 4.2: ACCESSES Edge Tests
    // ========================

    @Test
    @DisplayName("ACCESSES: reading a field creates ACCESSES edge with READ type")
    void testAccessesEdge_FieldRead() {
        String code = """
            package com.example;
            class Entity {
                private String name;
                String getName() { return this.name; }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration getter = cu.findFirst(MethodDeclaration.class).orElseThrow();
        FieldAccessExpr fae = cu.findFirst(FieldAccessExpr.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(getter);
        
        // Act
        builder.onFieldAccessed(node, fae);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.ACCESSES, edge.type());
        assertEquals("READ", edge.attributes().get("accessType"));
        assertTrue(edge.targetId().contains("name"));
    }

    // ========================
    // Task 4.3: CONTAINS Edge Tests
    // ========================

    @Test
    @DisplayName("CONTAINS: class containing method creates CONTAINS edge")
    void testContainsEdge_ClassToMethod() {
        String code = """
            package com.example;
            class UserService {
                void findUser() {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();

        // Act: When processing a class, CONTAINS edges should be created for members
        builder.onMemberDiscovered(clazz, method);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.CONTAINS, edge.type());
        assertTrue(edge.sourceId().contains("UserService"));
        assertTrue(edge.targetId().contains("findUser"));
    }

    // ========================
    // Task 4.4: EXTENDS/IMPLEMENTS Edge Tests
    // ========================

    @Test
    @DisplayName("EXTENDS: class extending another creates EXTENDS edge")
    void testExtendsEdge() {
        String code = """
            package com.example;
            class Child extends Parent {}
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        ClassOrInterfaceType extendedType = clazz.getExtendedTypes().get(0);

        // Act
        builder.onExtendsDiscovered(clazz, extendedType);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.EXTENDS, edge.type());
        assertTrue(edge.sourceId().contains("Child"));
        assertTrue(edge.targetId().contains("Parent"));
    }

    @Test
    @DisplayName("IMPLEMENTS: class implementing interface creates IMPLEMENTS edge")
    void testImplementsEdge() {
        String code = """
            package com.example;
            class ServiceImpl implements Service {}
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        ClassOrInterfaceType implementedType = clazz.getImplementedTypes().get(0);

        // Act
        builder.onImplementsDiscovered(clazz, implementedType);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.IMPLEMENTS, edge.type());
        assertTrue(edge.sourceId().contains("ServiceImpl"));
        assertTrue(edge.targetId().contains("Service"));
    }

    // ========================
    // Task 4.5: USES Edge Test
    // ========================

    @Test
    @DisplayName("USES: casting to a type creates USES edge")
    void testUsesEdge_Cast() {
        String code = """
            package com.example;
            class Processor {
                void process(Object obj) {
                    String s = (String) obj;
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        var castType = StaticJavaParser.parseType("String");

        GraphNode node = Graph.createGraphNode(method);

        // Act
        builder.onTypeUsed(node, castType);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());
        
        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.USES, edge.type());
        assertTrue(edge.targetId().contains("String"));
    }

    @Test
    @DisplayName("CALLS: method call captures local variable arguments in JSON format")
    void testCallsEdge_CapturesArguments() {
        String code = """
            package com.example;
            class Service {
                void process() {
                    String localVar = "value";
                    int num = 10;
                    helper(localVar, num);
                }
                void helper(String s, int i) {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration caller = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("process"))
                .findFirst().orElseThrow();
        MethodCallExpr mce = cu.findFirst(MethodCallExpr.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(caller);

        // Act
        builder.onMethodCalled(node, mce);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());

        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.CALLS, edge.type());

        // Verify attributes
        assertNotNull(edge.attributes(), "Attributes should not be null");
        assertTrue(edge.attributes().containsKey("parameterValues"), "Should contain parameterValues attribute");

        String paramValues = edge.attributes().get("parameterValues");
        // Check for JSON array structure
        assertTrue(paramValues.startsWith("[") && paramValues.endsWith("]"), "Should be a JSON array");
        assertTrue(paramValues.contains("\"localVar\""), "Should contain 'localVar'");
        assertTrue(paramValues.contains("\"num\""), "Should contain 'num'");
    }

    @Test
    @DisplayName("CALLS: no arguments results in no parameterValues attribute")
    void testCallsEdge_NoArguments() {
        String code = """
            package com.example;
            class Service {
                void process() {
                    noArgs();
                }
                void noArgs() {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration caller = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("process"))
                .findFirst().orElseThrow();
        MethodCallExpr mce = cu.findFirst(MethodCallExpr.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(caller);

        // Act
        builder.onMethodCalled(node, mce);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());

        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.CALLS, edge.type());

        // Verify attributes
        assertFalse(edge.attributes().containsKey("parameterValues"), "Should NOT contain parameterValues attribute for no-arg call");
    }

    @Test
    @DisplayName("CALLS: handles complex arguments correctly")
    void testCallsEdge_ComplexArguments() {
        String code = """
            package com.example;
            class Service {
                void process(int x, int y) {
                    helper("a,b", x + y);
                }
                void helper(String s, int i) {}
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration caller = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("process"))
                .findFirst().orElseThrow();
        MethodCallExpr mce = cu.findFirst(MethodCallExpr.class).orElseThrow();

        GraphNode node = Graph.createGraphNode(caller);

        // Act
        builder.onMethodCalled(node, mce);

        // Assert
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore).persistEdge(captor.capture());

        KnowledgeGraphEdge edge = captor.getValue();
        assertEquals(EdgeType.CALLS, edge.type());

        String paramValues = edge.attributes().get("parameterValues");
        // Verify proper JSON escaping and separation
        // Note: JavaParser might include quotes in the string representation of a StringLiteral
        assertTrue(paramValues.contains("a,b"), "Should contain string literal with comma");
        assertTrue(paramValues.contains("x + y"), "Should contain binary expression");
    }
}
