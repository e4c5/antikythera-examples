package com.raditha.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestOnlyCallersQueryTest {

    @Test
    void testQueryClassifiesNestedCallersFromTestMethodAnnotations() throws IOException {
        String query = Files.readString(Path.of("src/main/resources/queries/test-only-callers.cypher"));

        assertTrue(query.contains("MATCH (testMethod:Method)-[:ENCLOSES*0..]->(caller)"));
        assertTrue(query.contains("MATCH (testMethod)-[:ANNOTATED_BY]->(a:Annotation)"));
    }

    @Test
    void testQueryMatchesAnnotationFqnInsteadOfPrefixedSignature() throws IOException {
        String query = Files.readString(Path.of("src/main/resources/queries/test-only-callers.cypher"));

        assertTrue(query.contains("WHERE a.fqn IN"));
        assertFalse(query.contains("WHERE a.signature IN"));
    }
}
