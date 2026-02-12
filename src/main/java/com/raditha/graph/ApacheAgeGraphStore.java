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

    public ApacheAgeGraphStore(String url, String user, String password, String graphName, int batchSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.graphName = graphName;
        this.batchSize = batchSize;
        initialize();
    }

    private void initialize() {
        try {
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
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize Apache AGE connection", e);
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
            // Using cypher function call within SQL
            // SELECT * FROM cypher('graph_name', $$ ... $$) as (v agtype)
            String cypher = """
                SELECT * FROM cypher('%s', $$
                    MERGE (n:%s {signature: $signature})
                    SET n:%s, n.name = $name, n.fqn = $fqn
                $$, $params$) as (v agtype)
                """.formatted(graphName, BASE_LABEL, nodeType);

            // Access parameters need to be constructed as a JSON string for AGE, 
            // or we use string formatting if we want to be simple (but careful with injection).
            // Apache AGE JDBC support for parameters logic: normally passed as a separate argument to the cypher function if supported,
            // or we construct the string. The standard `cypher` function signature in PostgreSQL is cypher(graph_name, query_string, params_agtype)
            
            // For safety and correctness with AGE, we should use a prepared statement but handling agtype params via JDBC is tricky.
            // A common pattern is to pass parameters as a JSON string cast to agtype.
            
            // Building JSON param string manually or via Jackson
            Map<String, Object> params = new HashMap<>();
            params.put("signature", signature);
            params.put("name", name);
            params.put("fqn", fqn != null ? fqn : name);
            
            String jsonParams = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);

            String sql = cypher.replace("$params$", "?");

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setObject(1, jsonParams, java.sql.Types.OTHER); // Pass as JSON/AGTYPE
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
            
            // Simplify: processing one by one or in small batches via string concatenation for now
            // Bulk insert in AGE is often done via creating nodes and edges.
            // For now, let's iterate and execute. Optimization can come later.
            
            for (KnowledgeGraphEdge edge : pendingEdges) {
                 Map<String, Object> params = new HashMap<>();
                 params.put("sourceId", edge.sourceId());
                 params.put("targetId", edge.targetId());
                 params.put("props", edge.attributes());

                 String cypher = """
                    SELECT * FROM cypher('%s', $$
                        MATCH (source:%s {signature: $sourceId})
                        MATCH (target:%s {signature: $targetId})
                        MERGE (source)-[r:%s]->(target)
                        SET r += $props
                    $$, ?) as (v agtype)
                    """.formatted(graphName, BASE_LABEL, BASE_LABEL, edge.type().name());
                 
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
            """.formatted(BASE_LABEL), signature, "n.signature");
    }

    @Override
    public List<String> findCallees(String signature) {
        return executeQuery("""
            MATCH (n:%s {signature: $sig})-[:CALLS]->(m) RETURN m.signature
            """.formatted(BASE_LABEL), signature, "m.signature");
    }

    @Override
    public List<String> findUsages(String signature) {
        return executeQuery("""
            MATCH (n)-[:USES]->(m:%s {signature: $sig}) RETURN n.signature
            """.formatted(BASE_LABEL), signature, "n.signature");
    }
    
    private List<String> executeQuery(String cypherFragment, String signatureParam, String returnField) {
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
