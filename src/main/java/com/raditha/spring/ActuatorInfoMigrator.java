package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrator for Actuator /info endpoint configuration and Spring Security
 * integration.
 * 
 * <p>
 * <b>CRITICAL CHANGE</b>: Spring Boot 2.5 no longer exposes /info endpoint by
 * default.
 * This migrator automatically:
 * <ul>
 * <li>Adds management.endpoints.web.exposure.include=info to configuration</li>
 * <li>Modifies Spring Security configuration using AST transformations to allow
 * public access</li>
 * </ul>
 * 
 * @see AbstractConfigMigrator
 */
public class ActuatorInfoMigrator extends AbstractConfigMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ActuatorInfoMigrator.class);

    public ActuatorInfoMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Migrating actuator /info endpoint configuration...");
        MigrationPhaseResult result = new MigrationPhaseResult();

        // TODO: Implement in Phase 3
        result.addWarning("ActuatorInfoMigrator: Implementation pending (Phase 3)");

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Actuator /info Endpoint Migration";
    }

    @Override
    public int getPriority() {
        return 30;
    }
}
