package com.raditha.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Neo4jConfigTest {

    @Test
    void testBatchSizeSnakeCase() throws IOException {
        Path config = Files.createTempFile("graph-config", ".yml");
        Files.writeString(config, """
            neo4j:
              uri: bolt://localhost:7687
              username: neo4j
              password: pass
              database: neo4j
              batch_size: 321
            """);

        Neo4jConfig.load(config.toFile());
        assertEquals(321, Neo4jConfig.getBatchSize());
    }

    @Test
    void testBatchSizeCamelCaseInNestedConfig() throws IOException {
        Path config = Files.createTempFile("graph-config-nested", ".yml");
        Files.writeString(config, """
            antikythera:
              graph:
                neo4j:
                  uri: bolt://localhost:7687
                  username: neo4j
                  password: pass
                  database: neo4j
                  batchSize: 654
            """);

        Neo4jConfig.load(config.toFile());
        assertEquals(654, Neo4jConfig.getBatchSize());
    }
}
