package com.raditha.graph;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.IOException;

public class GraphStoreFactory {

    public static GraphStore createGraphStore(File configFile) throws IOException {
        // Load configuration
        Neo4jConfig.load(configFile);
        
        // Determine type from Settings or default to Neo4j
        // Assuming we add a new setting "graph.store.type" or similar
        // For now, let's use a simple heuristic or a new property in Neo4jConfig (rename to GraphConfig later?)
        
        String type = Settings.getProperty("graph.store.type", String.class).orElse("neo4j");

        if ("age".equalsIgnoreCase(type)) {
            return new ApacheAgeGraphStore(
                Settings.getProperty("graph.age.url", String.class).orElse("jdbc:postgresql://localhost:5432/postgres"),
                Settings.getProperty("graph.age.user", String.class).orElse("postgres"),
                Settings.getProperty("graph.age.password", String.class).orElse("postgres"),
                Settings.getProperty("graph.age.graphName", String.class).orElse("antikythera_graph"),
                Settings.getProperty("graph.batchSize", Integer.class).orElse(1000)
            );
        } else {
            return Neo4jGraphStore.fromSettings();
        }
    }
}
