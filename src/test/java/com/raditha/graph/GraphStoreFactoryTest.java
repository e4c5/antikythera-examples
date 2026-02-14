package com.raditha.graph;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStoreFactoryTest {

    @Test
    void testNeo4jConfigFromGraphSection() throws IOException {
        Path config = Files.createTempFile("graph-config", ".yml");
        Files.writeString(config, """
            graph:
              type: neo4j
              batch_size: 500
              neo4j:
                uri: bolt://myhost:7687
                username: admin
                password: secret
                database: mydb
            """);

        Settings.loadConfigMap(config.toFile());

        Map<String, Object> graphConfig = Settings.getProperty("graph", Map.class).orElse(Map.of());
        assertEquals("neo4j", graphConfig.get("type"));
        assertEquals(500, graphConfig.get("batch_size"));

        Map<String, Object> neo4jConfig = (Map<String, Object>) graphConfig.get("neo4j");
        assertEquals("bolt://myhost:7687", neo4jConfig.get("uri"));
        assertEquals("admin", neo4jConfig.get("username"));
        assertEquals("secret", neo4jConfig.get("password"));
        assertEquals("mydb", neo4jConfig.get("database"));
    }

    @Test
    void testAgeConfigFromGraphSection() throws IOException {
        Path config = Files.createTempFile("graph-config-age", ".yml");
        Files.writeString(config, """
            graph:
              type: age
              batch_size: 2000
              age:
                url: jdbc:postgresql://dbhost:5432/mydb
                user: pgadmin
                password: pgpass
                graph_name: code_graph
            """);

        Settings.loadConfigMap(config.toFile());

        Map<String, Object> graphConfig = Settings.getProperty("graph", Map.class).orElse(Map.of());
        assertEquals("age", graphConfig.get("type"));
        assertEquals(2000, graphConfig.get("batch_size"));

        Map<String, Object> ageConfig = (Map<String, Object>) graphConfig.get("age");
        assertEquals("jdbc:postgresql://dbhost:5432/mydb", ageConfig.get("url"));
        assertEquals("pgadmin", ageConfig.get("user"));
        assertEquals("pgpass", ageConfig.get("password"));
        assertEquals("code_graph", ageConfig.get("graph_name"));
    }

    @Test
    void testDefaultsWhenGraphSectionMissing() throws IOException {
        Path config = Files.createTempFile("graph-config-minimal", ".yml");
        Files.writeString(config, """
            base_path: /tmp/test
            """);

        Settings.loadConfigMap(config.toFile());

        Map<String, Object> graphConfig = Settings.getProperty("graph", Map.class).orElse(Map.of());
        assertTrue(graphConfig.isEmpty());
    }
}
