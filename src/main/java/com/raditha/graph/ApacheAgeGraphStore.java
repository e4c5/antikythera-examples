package com.raditha.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Apache AGE implementation of GraphStore.
 * Uses PostgreSQL JDBC driver and Apache AGE extension.
 */
@SuppressWarnings("java:S2077")
public class ApacheAgeGraphStore implements GraphStore {

    private static final Logger logger = LoggerFactory.getLogger(ApacheAgeGraphStore.class);

    private final String url;
    private final String user;
    private final String password;
    private final String graphName;
    private final int batchSize;

    private Connection connection;
    private final List<KnowledgeGraphEdge> pendingEdges = new ArrayList<>();
    private int edgeCount = 0;

    private static final String BASE_LABEL = "CodeElement";

    public ApacheAgeGraphStore(String url, String user, String password, String graphName, int batchSize) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.graphName = graphName;
        this.batchSize = batchSize;
        initialize();
    }

    private void initialize() throws SQLException {
        ensureConnection();
        try (Statement stmt = connection.createStatement()) {
            // Ensure AGE extension is loaded
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");

            // Check if graph exists, if not create it
            // Note: creating graph strictly might fail if it already exists, need to handle gracefully or check first
            // simple check via select * from ag_graph where name = ?
            try (PreparedStatement checkStmt = connection.prepareStatement("SELECT 1 FROM ag_graph WHERE name = ?")) {
                checkStmt.setString(1, graphName);
                try (ResultSet rs = checkStmt.executeQuery()) {
                     if (!rs.next()) {
                         stmt.execute("SELECT create_graph('" + graphName + "')");
                         logger.info("Created Apache AGE graph '{}'", graphName);
                     } else {
                         logger.info("Using existing Apache AGE graph '{}'", graphName);
                     }
                }
            }
        }
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            connection = DriverManager.getConnection(url, props);
        }
    }

    @Override
    public void persistEdge(KnowledgeGraphEdge edge) {
        pendingEdges.add(edge);
        edgeCount++;
        if (pendingEdges.size() >= batchSize) {
            flushEdges();
        }
    }

    @Override
    public void persistNode(String signature, String nodeType, String name, String fqn) {
        try {
            ensureConnection();
            Map<String, Object> params = new HashMap<>();
            params.put("signature", signature);
            params.put("name", name);
            params.put("fqn", fqn != null ? fqn : name);
            params.put("nodeType", nodeType);

            // AGE does not support SET n:Label — store the type as a property instead
            String cypher = """
                SELECT * FROM cypher('%s', $$
                    MERGE (n:%s {signature: $signature})
                    SET n.name = $name, n.fqn = $fqn, n.nodeType = $nodeType
                $$, ?) as (v agtype)
                """.formatted(graphName, BASE_LABEL);

            String jsonParams = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);

            try (PreparedStatement stmt = connection.prepareStatement(cypher)) {
                stmt.setObject(1, jsonParams, java.sql.Types.OTHER);
                stmt.execute();
            }

        } catch (Exception e) {
            logger.error("Failed to persist node: {}", signature, e);
        }
    }

    @Override
    public void flushEdges() {
        if (pendingEdges.isEmpty()) {
            return;
        }
        try {
            ensureConnection();
            logger.debug("Flushing {} pending edges to Apache AGE", pendingEdges.size());

            for (KnowledgeGraphEdge edge : pendingEdges) {
                 Map<String, Object> params = new HashMap<>();
                 params.put("sourceId", edge.sourceId());
                 params.put("targetId", edge.targetId());

                 // Build individual SET clauses — AGE does not support SET r += $map
                 StringBuilder setClauses = new StringBuilder();
                 for (Map.Entry<String, String> attr : edge.attributes().entrySet()) {
                     params.put("attr_" + attr.getKey(), attr.getValue());
                     setClauses.append("SET r.").append(attr.getKey())
                               .append(" = $attr_").append(attr.getKey()).append(" ");
                 }

                 String cypher = """
                    SELECT * FROM cypher('%s', $$
                        MATCH (source:%s {signature: $sourceId})
                        MATCH (target:%s {signature: $targetId})
                        MERGE (source)-[r:%s]->(target)
                        %s
                    $$, ?) as (v agtype)
                    """.formatted(graphName, BASE_LABEL, BASE_LABEL,
                                  edge.type().name(), setClauses.toString());

                 String jsonParams = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);

                 try (PreparedStatement stmt = connection.prepareStatement(cypher)) {
                     stmt.setObject(1, jsonParams, java.sql.Types.OTHER);
                     stmt.execute();
                 }
            }
            pendingEdges.clear();

        } catch (Exception e) {
            logger.error("Failed to flush edges", e);
        }
    }

    @Override
    public int getEdgeCount() {
        return edgeCount;
    }

    @Override
    public void clearGraph() {
        try {
            ensureConnection();
             try (Statement stmt = connection.createStatement()) {
                 // dropping and recreating is often cleaner for "clear"
                 stmt.execute("SELECT drop_graph('" + graphName + "', true)");
                 stmt.execute("SELECT create_graph('" + graphName + "')");
                 logger.info("Graph cleared");
             }
        } catch (SQLException e) {
            logger.error("Failed to clear graph", e);
        }
    }

    @Override
    public List<String> findCallers(String signature) {
        return executeQuery("""
            MATCH (n)-[:CALLS]->(m:%s {signature: $sig}) RETURN n.signature
            """.formatted(BASE_LABEL), signature);
    }

    @Override
    public List<String> findCallees(String signature) {
        return executeQuery("""
            MATCH (n:%s {signature: $sig})-[:CALLS]->(m) RETURN m.signature
            """.formatted(BASE_LABEL), signature);
    }

    @Override
    public List<String> findUsages(String signature) {
        return executeQuery("""
            MATCH (n)-[:USES]->(m:%s {signature: $sig}) RETURN n.signature
            """.formatted(BASE_LABEL), signature);
    }
    
    private List<String> executeQuery(String cypherFragment, String signatureParam) {
        List<String> results = new ArrayList<>();
        try {
            ensureConnection();
            String query = """
                SELECT * FROM cypher('%s', $$
                    %s
                $$, ?) as (v agtype)
                """.formatted(graphName, cypherFragment);
            
            Map<String, Object> params = new HashMap<>();
            params.put("sig", signatureParam);
            String jsonParams = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);

            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setObject(1, jsonParams, java.sql.Types.OTHER);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // AGE returns results as PG objects, often need parsing if complex.
                        // Here we expect simple strings? AGE returns 'agtype' which is JSON-like.
                        // Ideally we parse the JSON result.
                         String val = rs.getString(1); 
                         // rudimentary parsing: remove quotes if present or parse JSON
                         if (val != null) {
                             // E.g. "signature_value" (quoted JSON string)
                             if (val.startsWith("\"") && val.endsWith("\"")) {
                                 results.add(val.substring(1, val.length() - 1));
                             } else {
                                 results.add(val);
                             }
                         }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Query failed", e);
        }
        return results;
    }

    @Override
    public void close() {
        flushEdges();
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warn("Error closing connection", e);
            }
        }
    }
}
