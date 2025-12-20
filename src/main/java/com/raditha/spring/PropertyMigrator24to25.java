package com.raditha.spring;

import java.util.Map;

/**
 * Property file migrator for Spring Boot 2.4 to 2.5 upgrade.
 * 
 * <p>
 * Handles basic property migrations that don't require complex transformation.
 * More complex migrations are handled by specialized migrators:
 * <ul>
 * <li>SQL script initialization → {@link SqlScriptPropertiesMigrator}</li>
 * <li>Actuator /info endpoint → {@link ActuatorInfoMigrator}</li>
 * <li>Cassandra throttling → {@link CassandraThrottlingMigrator}</li>
 * </ul>
 * 
 * <p>
 * This migrator handles straightforward renames and deprecations that can be
 * expressed as simple key-value mappings.
 * 
 * @see AbstractPropertyFileMigrator
 */
public class PropertyMigrator24to25 extends AbstractPropertyFileMigrator {

    // Note: Most property migrations in 2.4→2.5 are handled by specialized migrators:
    // - SqlScriptPropertiesMigrator: SQL script initialization properties
    // - ActuatorInfoMigrator: Actuator endpoint exposure
    // - CassandraThrottlingMigrator: Cassandra throttling configuration
    // This map contains only simple renames not requiring complex logic
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS_24_TO_25 = Map.of();

    public PropertyMigrator24to25(boolean dryRun) {
        super(dryRun, PROPERTY_MAPPINGS_24_TO_25);
    }

    @Override
    protected String getTargetVersion() {
        return "2.5";
    }

    @Override
    public String getPhaseName() {
        return "Property Migration (2.4→2.5)";
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
