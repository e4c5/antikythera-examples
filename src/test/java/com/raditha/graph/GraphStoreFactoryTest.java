package com.raditha.graph;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphStoreFactoryTest {

    @Test
    void testNeo4jStoreCreatedFromConfig() throws Exception {
        Path config = Files.createTempFile("graph-config", ".yml");
        Files.writeString(config, """
            graph:
              type: neo4j
              batch_size: 500
              neo4j:
                uri: bolt://localhost:7687
                username: admin
                password: secret
                database: mydb
            """);

        // Neo4j driver creation succeeds without a running server (lazy connect)
        try (GraphStore store = GraphStoreFactory.createGraphStore(config.toFile())) {
            assertInstanceOf(Neo4jGraphStore.class, store);
        }
    }

    @Test
    void testDefaultsToNeo4jWhenNoType() throws Exception {
        Path config = Files.createTempFile("graph-config-notype", ".yml");
        Files.writeString(config, """
            graph:
              neo4j:
                uri: bolt://localhost:7687
                password: secret
            """);

        try (GraphStore store = GraphStoreFactory.createGraphStore(config.toFile())) {
            assertInstanceOf(Neo4jGraphStore.class, store);
        }
    }

    @Test
    void testDefaultsToNeo4jWhenNoGraphSection() throws Exception {
        Path config = Files.createTempFile("graph-config-empty", ".yml");
        Files.writeString(config, """
            base_path: /tmp/test
            """);

        // No graph section at all — should still create Neo4j with all defaults
        try (GraphStore store = GraphStoreFactory.createGraphStore(config.toFile())) {
            assertInstanceOf(Neo4jGraphStore.class, store);
        }
    }

    @Test
    void testAgeStoreCreationAttempted() throws IOException {
        Path config = Files.createTempFile("graph-config-age", ".yml");
        Files.writeString(config, """
            graph:
              type: age
              batch_size: 2000
              age:
                url: jdbc:postgresql://localhost:5432/postgres
                user: postgres
                password: pgpass
                graph_name: code_graph
            """);

        Settings.loadConfigMap(config.toFile());

        // AGE connects eagerly in the constructor — no database means SQLException
        assertThrows(SQLException.class, GraphStoreFactory::createGraphStore);
    }

    @Test
    void testCreateGraphStoreFromFile() throws Exception {
        Path config = Files.createTempFile("graph-config-file", ".yml");
        Files.writeString(config, """
            graph:
              type: neo4j
              batch_size: 750
              neo4j:
                uri: bolt://localhost:7687
                password: p
            """);

        // File overload loads Settings and delegates
        try (GraphStore store = GraphStoreFactory.createGraphStore(config.toFile())) {
            assertInstanceOf(Neo4jGraphStore.class, store);
        }
    }
}
