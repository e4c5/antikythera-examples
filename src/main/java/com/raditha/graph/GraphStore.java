package com.raditha.graph;

import java.util.List;

/**
 * Interface defines the contract for a Graph Store implementation.
 */
public interface GraphStore extends AutoCloseable {

    /**
     * Persist an edge to the graph store.
     * @param edge the edge to persist
     */
    void persistEdge(KnowledgeGraphEdge edge);

    /**
     * Persist a node to the graph store.
     * @param signature unique signature of the node
     * @param nodeType type of the node (e.g. Class, Method, Field)
     * @param name name of the node
     * @param fqn fully qualified name of the node
     */
    void persistNode(String signature, String nodeType, String name, String fqn);

    /**
     * Flush any pending edges to the graph store.
     */
    void flushEdges();

    /**
     * Get the number of edges persisted so far.
     * @return number of edges
     */
    int getEdgeCount();

    /**
     * Clear the graph.
     */
    void clearGraph();

    /**
     * Find nodes that call the given target node.
     * @param signature signature of the target node
     * @return list of caller signatures
     */
    List<String> findCallers(String signature);

    /**
     * Find nodes called by the given source node.
     * @param signature signature of the source node
     * @return list of callee signatures
     */
    List<String> findCallees(String signature);

    /**
     * Find nodes that use the given target type/node.
     * @param signature signature of the usage target
     * @return list of user signatures
     */
    List<String> findUsages(String signature);
}
