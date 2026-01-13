package com.raditha.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public Neo4jGraphStore(String uri, String username, String password, String database, int batchSize) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        this.database = database;
        this.batchSize = batchSize;
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
        for (KnowledgeGraphEdge edge : pendingEdges) {
            persistEdgeInternal(edge);
        }
        pendingEdges.clear();
    }

    private void persistEdgeInternal(KnowledgeGraphEdge edge) {
        String cypher = """
            MERGE (source {signature: $sourceId})
            MERGE (target {signature: $targetId})
            MERGE (source)-[r:%s]->(target)
            SET r.attributes = $attributes
            """.formatted(edge.type().name());

        session.run(cypher, Values.parameters(
                "sourceId", edge.sourceId(),
                "targetId", edge.targetId(),
                "attributes", edge.attributes()
        ));
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
