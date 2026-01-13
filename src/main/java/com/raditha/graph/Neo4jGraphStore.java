package com.raditha.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j graph store for persisting Knowledge Graph nodes and edges.
 * Supports streaming persistence with configurable batch sizes.
 */
public class Neo4jGraphStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private final Driver driver;
    private final String database;
    private final int batchSize;

    private Session session;
    private final List<KnowledgeGraphEdge> pendingEdges = new ArrayList<>();
    private int edgeCount = 0;

    /**
     * Create a Neo4jGraphStore from graph.yml configuration.
     *
     * @param configFile path to graph.yml
     * @return configured Neo4jGraphStore instance
     * @throws IOException if config file cannot be read
     */
    public static Neo4jGraphStore fromSettings(File configFile) throws IOException {
        Neo4jConfig.load(configFile);
        return fromSettings();
    }

    /**
     * Create a Neo4jGraphStore from already-loaded Settings.
     *
     * @return configured Neo4jGraphStore instance
     */
    public static Neo4jGraphStore fromSettings() {
        return new Neo4jGraphStore(
                Neo4jConfig.getUri(),
                Neo4jConfig.getUsername(),
                Neo4jConfig.getPassword(),
                Neo4jConfig.getDatabase(),
                Neo4jConfig.getBatchSize()
        );
    }

    /**
     * Create a Neo4jGraphStore with an existing driver.
     * Useful for testing with mock drivers.
     */
    public Neo4jGraphStore(Driver driver, String database, int batchSize) {
        this.driver = driver;
        this.database = database;
        this.batchSize = batchSize;
        // Don't log connection info for existing driver as it might be a mock
    }

    public Neo4jGraphStore(String uri, String username, String password, String database, int batchSize) {
        this(GraphDatabase.driver(uri, AuthTokens.basic(username, password)), database, batchSize);
        logger.info("Connected to Neo4j at {} with batch size {}", uri, batchSize);
    }

    public void persistEdge(KnowledgeGraphEdge edge) {
        pendingEdges.add(edge);
        edgeCount++;
        if (pendingEdges.size() >= batchSize) {
            flushEdges();
        }
    }

    public void persistNode(String signature, String nodeType, String name, String fqn) {
        ensureSession();
        String cypher = """
            MERGE (n:%s {signature: $signature})
            SET n.name = $name, n.fqn = $fqn
            """.formatted(nodeType);

        session.run(cypher, Values.parameters(
                "signature", signature,
                "name", name,
                "fqn", fqn != null ? fqn : name
        ));
    }

    public void flushEdges() {
        if (pendingEdges.isEmpty()) {
            return;
        }
        ensureSession();
        logger.debug("Flushing {} pending edges to Neo4j", pendingEdges.size());

        // Group by Edge Type for batched execution
        java.util.Map<String, List<KnowledgeGraphEdge>> byType = new java.util.HashMap<>();
        for (KnowledgeGraphEdge edge : pendingEdges) {
            byType.computeIfAbsent(edge.type().name(), k -> new ArrayList<>()).add(edge);
        }

        for (java.util.Map.Entry<String, List<KnowledgeGraphEdge>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<KnowledgeGraphEdge> group = entry.getValue();

            List<java.util.Map<String, Object>> batch = new ArrayList<>();
            for (KnowledgeGraphEdge edge : group) {
                 java.util.Map<String, Object> row = new java.util.HashMap<>();
                 row.put("sourceId", edge.sourceId());
                 row.put("targetId", edge.targetId());
                 row.put("attributes", edge.attributes());
                 batch.add(row);
            }

            String cypher = """
                UNWIND $batch AS row
                MERGE (source {signature: row.sourceId})
                MERGE (target {signature: row.targetId})
                MERGE (source)-[r:%s]->(target)
                SET r += row.attributes
                """.formatted(type);

            session.run(cypher, Values.parameters("batch", batch));
        }
        pendingEdges.clear();
    }

    private void ensureSession() {
        if (session == null || !session.isOpen()) {
            session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(database));
        }
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public void clearGraph() {
        ensureSession();
        session.run("MATCH (n) DETACH DELETE n");
        logger.info("Graph cleared");
    }

    // ========================
    // Query API
    // ========================

    /**
     * Find nodes that call the given target node.
     * @param signature signature of the target node
     * @return list of caller signatures
     */
    public List<String> findCallers(String signature) {
        ensureSession();
        String cypher = "MATCH (n)-[:CALLS]->(m {signature: $sig}) RETURN n.signature";
        return session.run(cypher, Values.parameters("sig", signature))
                .list(r -> r.get("n.signature").asString());
    }

    /**
     * Find nodes called by the given source node.
     * @param signature signature of the source node
     * @return list of callee signatures
     */
    public List<String> findCallees(String signature) {
        ensureSession();
        String cypher = "MATCH (n {signature: $sig})-[:CALLS]->(m) RETURN m.signature";
        return session.run(cypher, Values.parameters("sig", signature))
                .list(r -> r.get("m.signature").asString());
    }

    /**
     * Find nodes that use the given target type/node.
     * @param signature signature of the usage target
     * @return list of user signatures
     */
    public List<String> findUsages(String signature) {
        ensureSession();
        String cypher = "MATCH (n)-[:USES]->(m {signature: $sig}) RETURN n.signature";
        return session.run(cypher, Values.parameters("sig", signature))
                .list(r -> r.get("n.signature").asString());
    }

    @Override
    public void close() {
        flushEdges();
        if (session != null) {
            session.close();
        }
        driver.close();
        logger.info("Neo4j connection closed. Total edges persisted: {}", edgeCount);
    }

    public static class Builder {
        private String uri = "bolt://localhost:7687";
        private String username = "neo4j";
        private String password = "password";
        private String database = "neo4j";
        private int batchSize = 1000;

        public Builder uri(String uri) { this.uri = uri; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder database(String database) { this.database = database; return this; }
        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }

        public Neo4jGraphStore build() {
            return new Neo4jGraphStore(uri, username, password, database, batchSize);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
