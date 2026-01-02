package com.raditha.spring;

import java.util.Map;

/**
 * Property file migrator for Spring Boot 2.3 to 2.4 upgrade.
 * 
 * <p>
 * Handles basic property migrations that don't require complex transformation.
 * More complex migrations are handled by specialized migrators:
 * <ul>
 * <li>Configuration file processing →
 * {@link ConfigurationProcessingMigrator}</li>
 * <li>Neo4j properties → {@link Neo4jPropertyMigrator}</li>
 * <li>Logback properties → {@link LogbackPropertyMigrator}</li>
 * <li>Data.sql configuration → {@link DataSqlMigrator}</li>
 * </ul>
 * 
 * <p>
 * This migrator handles straightforward renames and deprecations that can be
 * expressed as simple key-value mappings.
 * 
 * @see AbstractPropertyFileMigrator
 */
public class PropertyMigrator23to24 extends AbstractPropertyFileMigrator {

    // Note: Most property migrations in 2.3→2.4 are handled by specialized
    // migrators:
    // - ConfigurationProcessingMigrator: profile syntax changes
    // - Neo4jPropertyMigrator: Neo4j property namespace changes
    // - LogbackPropertyMigrator: Logback rolling policy properties
    // - DataSqlMigrator: defer-datasource-initialization
    // This map is empty since all property changes require complex logic
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS_23_TO_24 = Map.of();

    public PropertyMigrator23to24(boolean dryRun) {
        super(dryRun, PROPERTY_MAPPINGS_23_TO_24);
    }

    @Override
    protected String getTargetVersion() {
        return "2.4";
    }

    @Override
    public String getPhaseName() {
        return "Property Migration (2.3→2.4)";
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
