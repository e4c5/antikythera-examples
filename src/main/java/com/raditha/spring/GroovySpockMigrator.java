package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrator for Groovy 3.x and Spock 2.0 version upgrades.
 * 
 * <p>
 * Spring Boot 2.5 uses Groovy 3.0.8, which requires Spock 2.0+.
 * This migrator automatically upgrades versions and fixes common Spock 2.0
 * compatibility issues.
 * 
 * @see AbstractPomMigrator
 */
public class GroovySpockMigrator extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(GroovySpockMigrator.class);

    public GroovySpockMigrator(boolean dryRun) {
        super("2.5.15", dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(org.apache.maven.model.Model model,
            MigrationPhaseResult result) {
        // TODO: Implement in Phase 4
        result.addWarning("GroovySpockMigrator: Implementation pending (Phase 4)");
    }

    @Override
    protected void validateVersionSpecificRequirements(org.apache.maven.model.Model model,
            MigrationPhaseResult result) {
        // No additional validation needed
    }

    @Override
    public String getPhaseName() {
        return "Groovy/Spock Version Upgrade";
    }

    @Override
    public int getPriority() {
        return 45;
    }
}
