package com.raditha.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphStoreFactoryTest {

    /**
     * Provides test cases for Neo4j graph store creation scenarios.
     * Each argument contains: test name, YAML configuration content, expected store class.
     */
    static Stream<Arguments> neo4jStoreTestCases() {
        return Stream.of(
            Arguments.of(
                "Neo4j store created from full config",
                """
                graph:
                  type: neo4j
                  batch_size: 500
                  neo4j:
                    uri: bolt://localhost:7687
                    username: admin
                    password: secret
                    database: mydb
                """,
                Neo4jGraphStore.class
            ),
            Arguments.of(
                "Defaults to Neo4j when no type specified",
                """
                graph:
                  neo4j:
                    uri: bolt://localhost:7687
                    password: secret
                """,
                Neo4jGraphStore.class
            ),
            Arguments.of(
                "Defaults to Neo4j when no graph section",
                """
                base_path: /tmp/test
                """,
                Neo4jGraphStore.class
            ),
            Arguments.of(
                "Neo4j store from file overload",
                """
                graph:
                  type: neo4j
                  batch_size: 750
                  neo4j:
                    uri: bolt://localhost:7687
                    password: p
                """,
                Neo4jGraphStore.class
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("neo4jStoreTestCases")
    void testNeo4jStoreCreation(String testName, String yamlConfig, Class<? extends GraphStore> expectedClass) throws Exception {
        Path config = Files.createTempFile("graph-config", ".yml");
        try {
            Files.writeString(config, yamlConfig);

            // Neo4j driver creation succeeds without a running server (lazy connect)
            try (GraphStore store = GraphStoreFactory.createGraphStore(config.toFile())) {
                assertInstanceOf(expectedClass, store);
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void testAgeStoreCreationAttempted() throws IOException {
        Path config = Files.createTempFile("graph-config-age", ".yml");
        try {
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
        } finally {
            Files.deleteIfExists(config);
        }
    }
}
