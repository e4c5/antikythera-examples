package com.raditha.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for Knowledge Graph Query API.
 * Uses mocks to verify Cypher query generation.
 */
@ExtendWith(MockitoExtension.class)
class GraphQueryTest {

    @Mock
    private Driver driver;
    @Mock
    private Session session;
    @Mock
    private Result result;

    private GraphStore graphStore;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Mock driver/session flow
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        
        // Inject mock driver
        graphStore = new Neo4jGraphStore(driver, "neo4j", 1000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (graphStore != null) {
            graphStore.close();
        }
    }

    @Test
    void testFindCallers() {
        // Mock result
        Record mockRecord = mock(Record.class);
        Value mockValue = mock(Value.class);
        when(mockValue.asString()).thenReturn("A");
        when(mockRecord.get("n.signature")).thenReturn(mockValue);
        
        when(session.run(anyString(), any(org.neo4j.driver.Value.class)))
                .thenReturn(result);
        when(result.list(any())).thenAnswer(invocation -> {
            Function<Record, String> mapper = invocation.getArgument(0);
            return List.of(mapper.apply(mockRecord));
        });

        // Act
        List<String> callers = graphStore.findCallers("B");

        // Assert
        assertEquals(1, callers.size());
        assertEquals("A", callers.get(0));
        
        verify(session).run(
            org.mockito.ArgumentMatchers.contains("MATCH (n)-[:CALLS]->(m:CodeElement {signature: $sig}) RETURN n.signature"),
            any(org.neo4j.driver.Value.class)
        );
    }

    @Test
    void testFindCallees() {
        // Mock result
        Record mockRecord = mock(Record.class);
        Value mockValue = mock(Value.class);
        when(mockValue.asString()).thenReturn("B");
        when(mockRecord.get("m.signature")).thenReturn(mockValue);
        
        when(session.run(anyString(), any(org.neo4j.driver.Value.class)))
                .thenReturn(result);
        when(result.list(any())).thenAnswer(invocation -> {
            Function<Record, String> mapper = invocation.getArgument(0);
            return List.of(mapper.apply(mockRecord));
        });

        // Act
        List<String> callees = graphStore.findCallees("A");

        // Assert
        assertEquals(1, callees.size());
        assertEquals("B", callees.get(0));
        
        verify(session).run(
            org.mockito.ArgumentMatchers.contains("MATCH (n:CodeElement {signature: $sig})-[:CALLS]->(m) RETURN m.signature"),
            any(org.neo4j.driver.Value.class)
        );
    }

    @Test
    void testFindUsages() {
        // Mock result
        Record mockRecord = mock(Record.class);
        Value mockValue = mock(Value.class);
        when(mockValue.asString()).thenReturn("A");
        when(mockRecord.get("n.signature")).thenReturn(mockValue);
        
        when(session.run(anyString(), any(org.neo4j.driver.Value.class)))
                .thenReturn(result);
        when(result.list(any())).thenAnswer(invocation -> {
            Function<Record, String> mapper = invocation.getArgument(0);
            return List.of(mapper.apply(mockRecord));
        });

        // Act
        List<String> usages = graphStore.findUsages("String");

        // Assert
        assertEquals(1, usages.size());
        assertEquals("A", usages.get(0));
        
        verify(session).run(
            org.mockito.ArgumentMatchers.contains("MATCH (n)-[:USES]->(m:CodeElement {signature: $sig}) RETURN n.signature"),
            any(org.neo4j.driver.Value.class)
        );
    }
}
