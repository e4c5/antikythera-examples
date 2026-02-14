package com.raditha.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Factory for creating GraphStore instances based on configuration.
 * <p>
 * Reads all graph settings from the {@code graph:} section of the YAML config
 * loaded via {@link Settings}. The expected structure is:
 * <pre>
 * graph:
 *   type: neo4j          # or "age"
 *   batch_size: 1000
 *   neo4j:
 *     uri: bolt://localhost:7687
 *     username: neo4j
 *     password: secret
 *     database: neo4j
 *   age:
 *     url: jdbc:postgresql://localhost:5432/postgres
 *     user: postgres
 *     password: secret
 *     graph_name: antikythera_graph
 * </pre>
 */
public class GraphStoreFactory {

    private static final Logger logger = LoggerFactory.getLogger(GraphStoreFactory.class);

    private static final String DEFAULT_TYPE = "neo4j";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    // Neo4j defaults
    private static final String DEFAULT_NEO4J_URI = "bolt://localhost:7687";
    private static final String DEFAULT_NEO4J_USERNAME = "neo4j";
    private static final String DEFAULT_NEO4J_DATABASE = "neo4j";

    // Apache AGE defaults
    private static final String DEFAULT_AGE_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DEFAULT_AGE_USER = "postgres";
    private static final String DEFAULT_AGE_GRAPH_NAME = "antikythera_graph";

    private GraphStoreFactory() {
    }

    /**
     * Create a GraphStore from a configuration file.
     * Loads the file into Settings, then reads the {@code graph:} section.
     */
    public static GraphStore createGraphStore(File configFile) throws IOException, SQLException {
        Settings.loadConfigMap(configFile);
        return createGraphStore();
    }

    /**
     * Create a GraphStore from already-loaded Settings.
     */
    @SuppressWarnings("unchecked")
    public static GraphStore createGraphStore() throws SQLException {
        Map<String, Object> graphConfig = Settings.getProperty("graph", Map.class)
                .orElse(Map.of());

        String type = getString(graphConfig, "type", DEFAULT_TYPE);
        int batchSize = getInt(graphConfig, "batch_size", DEFAULT_BATCH_SIZE);

        logger.info("Creating {} graph store with batch size {}", type, batchSize);

        if ("age".equalsIgnoreCase(type)) {
            return createAgeStore(graphConfig, batchSize);
        }
        return createNeo4jStore(graphConfig, batchSize);
    }

    @SuppressWarnings("unchecked")
    private static GraphStore createNeo4jStore(Map<String, Object> graphConfig, int batchSize) {
        Map<String, Object> neo4jConfig = (Map<String, Object>) graphConfig.getOrDefault("neo4j", Map.of());

        String uri = getString(neo4jConfig, "uri", DEFAULT_NEO4J_URI);
        String username = getString(neo4jConfig, "username", DEFAULT_NEO4J_USERNAME);
        String password = getString(neo4jConfig, "password", "");
        String database = getString(neo4jConfig, "database", DEFAULT_NEO4J_DATABASE);

        return new Neo4jGraphStore(uri, username, password, database, batchSize);
    }

    @SuppressWarnings("unchecked")
    private static GraphStore createAgeStore(Map<String, Object> graphConfig, int batchSize) throws SQLException {
        Map<String, Object> ageConfig = (Map<String, Object>) graphConfig.getOrDefault("age", Map.of());

        String url = getString(ageConfig, "url", DEFAULT_AGE_URL);
        String user = getString(ageConfig, "user", DEFAULT_AGE_USER);
        String password = getString(ageConfig, "password", "");
        String graphName = getString(ageConfig, "graph_name", DEFAULT_AGE_GRAPH_NAME);

        return new ApacheAgeGraphStore(url, user, password, graphName, batchSize);
    }

    private static String getString(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer value '{}' for key '{}', using default {}", s, key, defaultValue);
            }
        }
        return defaultValue;
    }
}
