package com.raditha.spring;

import java.util.Map;

/**
 * Migrates Spring Boot property files from 2.1 to 2.2 format.
 * 
 * <p>
 * Handles:
 * <ul>
 * <li>YAML file transformations (nesting properties)</li>
 * <li>.properties file transformations</li>
 * <li>Smart forward headers strategy selection</li>
 * </ul>
 * 
 * <p>
 * Property transformations:
 * <ul>
 * <li>{@code logging.file} → {@code logging.file.name}</li>
 * <li>{@code logging.path} → {@code logging.file.path}</li>
 * <li>{@code server.connection-timeout} →
 * {@code server.tomcat.connection-timeout}</li>
 * <li>{@code server.use-forward-headers} →
 * {@code server.forward-headers-strategy}</li>
 * </ul>
 */
public class PropertyFileMigrator extends AbstractPropertyFileMigrator {

    // Property mappings: old key -> new key + transformation strategy
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS_21_TO_22 = Map.of(
            "logging.file", new PropertyMapping("logging.file.name", TransformationType.NEST),
            "logging.path", new PropertyMapping("logging.file.path", TransformationType.NEST),
            "server.connection-timeout",
            new PropertyMapping("server.tomcat.connection-timeout", TransformationType.NEST),
            "server.use-forward-headers",
            new PropertyMapping("server.forward-headers-strategy", TransformationType.VALUE_TRANSFORM));

    public PropertyFileMigrator(boolean dryRun) {
        super(dryRun, PROPERTY_MAPPINGS_21_TO_22);
    }

    /**
     * Transform property value - handles server.use-forward-headers conversion.
     * 
     * @param oldKey original property key
     * @param value  original property value
     * @return transformed value
     */
    @Override
    protected Object transformValue(String oldKey, Object value) {
        if ("server.use-forward-headers".equals(oldKey)) {
            // Auto-select strategy based on boolean value
            // true -> "native" (most common case for servlet containers)
            // false -> "none"
            return Boolean.TRUE.equals(value) || "true".equals(value.toString()) ? "native" : "none";
        }
        return super.transformValue(oldKey, value);
    }

    @Override
    protected String getTargetVersion() {
        return "2.2";
    }

    @Override
    public String getPhaseName() {
        return "Property Migration (2.1→2.2)";
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
