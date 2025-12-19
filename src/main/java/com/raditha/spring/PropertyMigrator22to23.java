package com.raditha.spring;

import java.util.Map;

/**
 * Property file migrator for Spring Boot 2.2 to 2.3 upgrade.
 * 
 * <p>
 * Handles property migrations:
 * <ul>
 * <li>{@code spring.http.encoding.*} → {@code server.servlet.encoding.*}</li>
 * <li>{@code spring.http.converters.preferred-json-mapper} →
 * {@code spring.mvc.converters.preferred-json-mapper}</li>
 * </ul>
 * 
 * @see AbstractPropertyFileMigrator
 */
public class PropertyMigrator22to23 extends AbstractPropertyFileMigrator {

    // Property mappings for Spring Boot 2.2 to 2.3
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS_22_TO_23 = Map.of(
            "spring.http.encoding.charset",
            new PropertyMapping("server.servlet.encoding.charset", TransformationType.NEST),
            "spring.http.encoding.enabled",
            new PropertyMapping("server.servlet.encoding.enabled", TransformationType.NEST),
            "spring.http.encoding.force",
            new PropertyMapping("server.servlet.encoding.force", TransformationType.NEST),
            "spring.http.encoding.force-request",
            new PropertyMapping("server.servlet.encoding.force-request", TransformationType.NEST),
            "spring.http.encoding.force-response",
            new PropertyMapping("server.servlet.encoding.force-response", TransformationType.NEST),
            "spring.http.converters.preferred-json-mapper",
            new PropertyMapping("spring.mvc.converters.preferred-json-mapper", TransformationType.NEST));

    public PropertyMigrator22to23(boolean dryRun) {
        super(dryRun, PROPERTY_MAPPINGS_22_TO_23);
    }

    @Override
    protected String getTargetVersion() {
        return "2.3";
    }

    @Override
    public String getPhaseName() {
        return "Property Migration (2.2→2.3)";
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
