package com.raditha.spring;

import java.util.Map;

/**
 * Property file migrator for Spring Boot 2.1 to 2.2 upgrade.
 * 
 * <p>
 * Handles property migrations:
 * <ul>
 * <li>{@code logging.file} → {@code logging.file.name}</li>
 * <li>{@code logging.path} → {@code logging.file.path}</li>
 * <li>{@code server.connection-timeout} →
 * {@code server.tomcat.connection-timeout}</li>
 * <li>{@code server.use-forward-headers} →
 * {@code server.forward-headers-strategy} (with value transformation)</li>
 * </ul>
 * 
 * @see AbstractPropertyFileMigrator
 */
public class PropertyMigrator21to22 extends AbstractPropertyFileMigrator {

    // Property mappings for Spring Boot 2.1 to 2.2
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS_21_TO_22 = Map.of(
            "logging.file", new PropertyMapping("logging.file.name", TransformationType.NEST),
            "logging.path", new PropertyMapping("logging.file.path", TransformationType.NEST),
            "server.connection-timeout",
            new PropertyMapping("server.tomcat.connection-timeout", TransformationType.NEST),
            "server.use-forward-headers",
            new PropertyMapping("server.forward-headers-strategy", TransformationType.VALUE_TRANSFORM));

    /**
     * Constructor.
     * 
     * @param dryRun if true, no files will be modified
     */
    public PropertyMigrator21to22(boolean dryRun) {
        super(dryRun, PROPERTY_MAPPINGS_21_TO_22);
    }

    @Override
    protected Object transformValue(String oldKey, Object value) {
        // server.use-forward-headers requires value transformation
        if ("server.use-forward-headers".equals(oldKey)) {
            // true → "native", false → "none"
            return "true".equals(value.toString()) ? "native" : "none";
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
