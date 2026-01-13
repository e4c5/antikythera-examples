package com.raditha.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an edge (relationship) in the Knowledge Graph.
 * Edges connect source and target nodes with a specific relationship type.
 */
public record KnowledgeGraphEdge(
        String sourceId,
        String targetId,
        EdgeType type,
        Map<String, String> attributes
) {
    public KnowledgeGraphEdge {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (attributes == null) {
            attributes = Map.of();
        }
    }

    public static class Builder {
        private String sourceId;
        private String targetId;
        private EdgeType type;
        private final Map<String, String> attributes = new HashMap<>();

        public Builder source(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder target(String targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder type(EdgeType type) {
            this.type = type;
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder accessType(String accessType) {
            return attribute("accessType", accessType);
        }

        public Builder parameterValues(String values) {
            return attribute("parameterValues", values);
        }

        public KnowledgeGraphEdge build() {
            return new KnowledgeGraphEdge(sourceId, targetId, type, Map.copyOf(attributes));
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
