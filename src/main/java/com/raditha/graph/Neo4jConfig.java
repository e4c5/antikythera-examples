package com.raditha.graph;

import sa.com.cloudsolutions.antikythera.configuration.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration helper for Neo4j connection settings.
 * Reads from graph.yml using the Settings infrastructure.
 */
public class Neo4jConfig {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jConfig.class);

    private static final String DEFAULT_URI = "bolt://localhost:7687";
    private static final String DEFAULT_USERNAME = "neo4j";
    private static final String DEFAULT_DATABASE = "neo4j";
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final String KEY_NEO4J = "neo4j";

    private static Map<String, Object> neo4jSettings;

    private Neo4jConfig() {
        // Utility class
    }

    /**
     * Load the graph.yml configuration file.
     *
     * @param configFile path to graph.yml
     * @throws IOException if file cannot be read
     */
    public static void load(File configFile) throws IOException {
        Settings.loadConfigMap(configFile);
        loadNeo4jSettings();
    }

    /**
     * Load the graph.yml from the default location in current directory.
     *
     * @throws IOException if file cannot be read
     */
    public static void load() throws IOException {
        File defaultConfig = new File("graph.yml");
        if (!defaultConfig.exists()) {
            defaultConfig = new File("src/main/resources/graph.yml");
        }
        if (!defaultConfig.exists()) {
            throw new IOException("graph.yml not found in current directory or src/main/resources/");
        }
        load(defaultConfig);
    }

    @SuppressWarnings("unchecked")
    private static void loadNeo4jSettings() {
        Map<String, Object> merged = new HashMap<>();

        Optional<Map> directNeo4jOpt = Settings.getProperty(KEY_NEO4J, Map.class);
        directNeo4jOpt.ifPresent(map -> merged.putAll((Map<String, Object>) map));

        Optional<Map> graphOpt = Settings.getProperty("graph", Map.class);
        if (graphOpt.isPresent()) {
            Object neo4jObj = graphOpt.get().get(KEY_NEO4J);
            if (neo4jObj instanceof Map<?, ?> neo4jMap) {
                neo4jMap.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
        }

        Optional<Map> antikytheraOpt = Settings.getProperty("antikythera", Map.class);
        if (antikytheraOpt.isPresent()) {
            Object graphObj = antikytheraOpt.get().get("graph");
            if (graphObj instanceof Map<?, ?> graphMap) {
                Object nestedNeo4jObj = graphMap.get(KEY_NEO4J);
                if (nestedNeo4jObj instanceof Map<?, ?> nestedNeo4jMap) {
                    nestedNeo4jMap.forEach((k, v) -> merged.put(String.valueOf(k), v));
                }
            }
        }

        neo4jSettings = merged;
        logger.info("Neo4j config loaded: uri={}", getUri());
    }

    public static String getUri() {
        return getStringProperty("uri", DEFAULT_URI);
    }

    public static String getUsername() {
        return getStringProperty("username", DEFAULT_USERNAME);
    }

    public static String getPassword() {
        return getStringProperty("password", "");
    }

    public static String getDatabase() {
        return getStringProperty("database", DEFAULT_DATABASE);
    }

    public static int getBatchSize() {
        Object value = neo4jSettings.get("batch_size");
        if (!(value instanceof Number)) {
            value = neo4jSettings.get("batchSize");
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                logger.warn("Invalid Neo4j batch size '{}', using default {}", s, DEFAULT_BATCH_SIZE);
            }
        }
        return DEFAULT_BATCH_SIZE;
    }

    private static String getStringProperty(String key, String defaultValue) {
        Object value = neo4jSettings.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Check if Neo4j configuration is present.
     */
    public static boolean isConfigured() {
        return neo4jSettings != null && !neo4jSettings.isEmpty();
    }
}
