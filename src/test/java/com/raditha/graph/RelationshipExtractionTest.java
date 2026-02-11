package com.raditha.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    @DisplayName("Structural traversal emits EXTENDS, IMPLEMENTS, CONTAINS")
    void testStructuralEdges() {
        CompilationUnit cu = StaticJavaParser.parse("""
            package com.example;
            interface Api {}
            class Base {}
            class Service extends Base implements Api {
                private int count;
                void work() {}
            }
            """);

        builder.build(List.of(cu));

        List<KnowledgeGraphEdge> edges = captureEdges();

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.EXTENDS
                        && e.sourceId().equals("com.example.Service")
                        && e.targetId().equals("com.example.Base")));

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.IMPLEMENTS
                        && e.sourceId().equals("com.example.Service")
                        && e.targetId().equals("com.example.Api")));

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CONTAINS
                        && e.sourceId().equals("com.example.Service")
                        && e.targetId().equals("com.example.Service#count")));

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CONTAINS
                        && e.sourceId().equals("com.example.Service")
                        && e.targetId().contains("work(")));
    }

    @Test
    @DisplayName("Enum constants are emitted as distinct CONTAINS targets")
    void testEnumConstantContainment() {
        CompilationUnit cu = StaticJavaParser.parse("""
            package com.example;
            enum Status { NEW, DONE }
            """);

        builder.build(List.of(cu));

        List<KnowledgeGraphEdge> edges = captureEdges();

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CONTAINS
                        && e.sourceId().equals("com.example.Status")
                        && e.targetId().equals("com.example.Status#NEW")));

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CONTAINS
                        && e.sourceId().equals("com.example.Status")
                        && e.targetId().equals("com.example.Status#DONE")));
    }

    @Test
    @DisplayName("Lambda body edges use lambda node as source")
    void testLambdaAttribution() {
        CompilationUnit cu = StaticJavaParser.parse("""
            package com.example;
            import java.util.List;
            class Service {
                void run(List<String> values) {
                    values.forEach(v -> helper(v));
                }
                void helper(String v) {}
            }
            """);

        builder.build(List.of(cu));

        List<KnowledgeGraphEdge> edges = captureEdges();

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CALLS
                        && e.targetId().equals("com.example.Service#helper()")
                        && e.sourceId().contains("$lambda_")));
    }

    @Test
    @DisplayName("Method references emit REFERENCES edges")
    void testMethodReferenceExtraction() {
        CompilationUnit cu = StaticJavaParser.parse("""
            package com.example;
            import java.util.List;
            class Service {
                void run(List<String> values) {
                    values.forEach(this::helper);
                }
                void helper(String v) {}
            }
            """);

        builder.build(List.of(cu));

        List<KnowledgeGraphEdge> edges = captureEdges();

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.REFERENCES
                        && e.sourceId().contains("run(")
                        && e.targetId().equals("com.example.Service#helper()")));
    }

    @Test
    @DisplayName("Resolution quality marks unresolved calls as partial")
    void testResolutionQuality() {
        CompilationUnit cu = StaticJavaParser.parse("""
            package com.example;
            class Repo { void save() {} }
            class Service {
                void resolved(Repo repo) {
                    repo.save();
                }
                void unresolved() {
                    mystery.save();
                }
            }
            """);

        builder.build(List.of(cu));

        List<KnowledgeGraphEdge> edges = captureEdges();

        assertTrue(edges.stream().anyMatch(e ->
                e.type() == EdgeType.CALLS
                        && e.targetId().equals("com.example.Repo#save()")));

        KnowledgeGraphEdge partial = edges.stream()
                .filter(e -> e.type() == EdgeType.CALLS && e.targetId().equals("mystery#save()"))
                .findFirst()
                .orElseThrow();

        assertEquals("partial", partial.attributes().get("resolution"));
    }

    private List<KnowledgeGraphEdge> captureEdges() {
        ArgumentCaptor<KnowledgeGraphEdge> captor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(mockStore, atLeastOnce()).persistEdge(captor.capture());
        return captor.getAllValues();
    }
}
