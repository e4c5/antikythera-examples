package com.raditha.graph;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.IOException;

public class GraphStoreFactory {

    public static GraphStore createGraphStore(File configFile) throws IOException {
        // Load configuration
        Neo4jConfig.load(configFile);
        
        // Determine type from Settings
        // We expect a structure like:
        // graph:
        //   store:
        //     type: age
        //   age:
        //     ...
        
        String type = "neo4j";
        java.util.Map<String, Object> ageConfig = java.util.Collections.emptyMap();
        int batchSize = 1000;

        java.util.Optional<java.util.Map> graphConfigOpt = Settings.getProperty("graph", java.util.Map.class);
        if (graphConfigOpt.isPresent()) {
            java.util.Map<?, ?> graphConfig = graphConfigOpt.get();
            
            // Check store.type
            Object storeObj = graphConfig.get("store");
            if (storeObj instanceof java.util.Map<?, ?> storeConfig) {
                Object typeObj = storeConfig.get("type");
                if (typeObj != null) {
                    type = typeObj.toString();
                }
            }
            
            // Get age config
            Object ageObj = graphConfig.get("age");
            if (ageObj instanceof java.util.Map<?, ?>) {
                ageConfig = (java.util.Map<String, Object>) ageObj;
            }

            // Get batch size
             Object batchObj = graphConfig.get("batchSize");
             if (batchObj instanceof Number n) {
                 batchSize = n.intValue();
             }
        }

        if ("age".equalsIgnoreCase(type)) {
            String url = (String) ageConfig.getOrDefault("url", "jdbc:postgresql://localhost:5432/postgres");
            String user = (String) ageConfig.getOrDefault("user", "postgres");
            String password = (String) ageConfig.getOrDefault("password", "postgres");
            String graphName = (String) ageConfig.getOrDefault("graphName", "antikythera_graph");
            
            return new ApacheAgeGraphStore(url, user, password, graphName, batchSize);
        } else {
            return Neo4jGraphStore.fromSettings();
        }
    }
}
