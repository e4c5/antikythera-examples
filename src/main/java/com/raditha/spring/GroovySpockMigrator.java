package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.util.List;

/**
 * Migrator for Groovy 3.x and Spock 2.0 version upgrades.
 * 
 * <p>
 * Spring Boot 2.5 uses Groovy 3.0.8, which requires Spock 2.0+.
 * This migrator automatically upgrades versions in POM and can fix common Spock
 * 2.0 compatibility issues.
 * 
 * @see AbstractPomMigrator
 */
public class GroovySpockMigrator extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(GroovySpockMigrator.class);

    private static final String TARGET_GROOVY_VERSION = "3.0.8";
    private static final String TARGET_SPOCK_VERSION = "2.0-groovy-3.0";

    public GroovySpockMigrator(boolean dryRun) {
        super("2.5.15", dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        logger.info("Upgrading Groovy and Spock versions...");

        // Upgrade Groovy version
        upgradeGroovyVersion(model, result);

        // Upgrade Spock dependencies
        upgradeSpockDependencies(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Add warnings about potential test compatibility issues
        List<Dependency> spockDeps = getDependenciesByGroupId(model, "org.spockframework");

        if (!spockDeps.isEmpty()) {
            result.addWarning("Spock upgraded to 2.0 - some breaking changes may affect tests");
            result.addWarning("Common issues:");
            result.addWarning("  - @Unroll annotation now requires explicit test names");
            result.addWarning("  - Some power assertion syntax changes");
            result.addWarning("  - Spock Spring integration updates");
            result.addWarning("See: https://spockframework.org/spock/docs/2.0/migration_guide.html");
        }
    }

    /**
     * Upgrade Groovy version property.
     */
    private void upgradeGroovyVersion(Model model, MigrationPhaseResult result) {
        // Check if groovy.version property exists
        if (model.getProperties() != null && model.getProperties().containsKey("groovy.version")) {
            String oldVersion = model.getProperties().getProperty("groovy.version");
            model.getProperties().setProperty("groovy.version", TARGET_GROOVY_VERSION);
            result.addChange("Upgraded groovy.version: " + oldVersion + " → " + TARGET_GROOVY_VERSION);
            logger.info("Upgraded groovy.version to {}", TARGET_GROOVY_VERSION);
        }

        // Also check for explicit Groovy dependencies
        List<Dependency> groovyDeps = getDependenciesByGroupId(model, "org.codehaus.groovy");
        for (Dependency dep : groovyDeps) {
            if (dep.getVersion() != null && !dep.getVersion().startsWith("${")) {
                String oldVersion = dep.getVersion();
                dep.setVersion(TARGET_GROOVY_VERSION);
                result.addChange(String.format("Upgraded %s version: %s → %s",
                        dep.getArtifactId(), oldVersion, TARGET_GROOVY_VERSION));
            }
        }
    }

    /**
     * Upgrade Spock dependencies to version 2.0.
     */
    private void upgradeSpockDependencies(Model model, MigrationPhaseResult result) {
        List<Dependency> spockDeps = getDependenciesByGroupId(model, "org.spockframework");

        for (Dependency dep : spockDeps) {
            String artifactId = dep.getArtifactId();
            String oldVersion = dep.getVersion();

            // Determine target version based on artifact
            String targetVersion = TARGET_SPOCK_VERSION;

            // Update version
            if (oldVersion != null && !oldVersion.equals(targetVersion)) {
                dep.setVersion(targetVersion);
                result.addChange(String.format("Upgraded %s: %s → %s",
                        artifactId, oldVersion, targetVersion));
                logger.info("Upgraded {} to {}", artifactId, targetVersion);
            }

            // Update artifact ID if needed (some Spock 2.0 artifacts have different IDs)
            if ("spock-core".equals(artifactId) && oldVersion != null && oldVersion.startsWith("1.")) {
                result.addWarning("Spock core upgraded from 1.x to 2.0 - check test compatibility");
            }
        }

        if (!spockDeps.isEmpty()) {
            result.addWarning("⚠️  TEST REVIEW REQUIRED: Spock upgraded to 2.0");
            result.addWarning("Run tests and fix any compatibility issues");
            result.addWarning("Common fixes may be available via GroovySpockMigrator");
        }
    }

    /**
     * Fix common Spock 2.0 compatibility issues in test files.
     * 
     * TODO: Implement AST-based test fixing:
     * - Fix @Unroll annotations
     * - Update power assertion syntax
     * - Fix Spock Spring integration
     */
    public MigrationPhaseResult fixSpockTests() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        logger.info("Scanning for Spock test compatibility issues...");

        // TODO: Use JavaParser to scan and fix Groovy/Spock test files
        // This would require:
        // 1. Find all *Spec.groovy files
        // 2. Parse with JavaParser (if Groovy support available)
        // 3. Fix @Unroll annotations
        // 4. Fix power assertions
        // 5. Update Spock Spring annotations

        result.addChange("Spock test fixing not yet implemented");
        result.addWarning("TODO: Implement Spock 2.0 test compatibility fixes");
        result.addWarning("For now, run tests manually and fix issues as they arise");

        return result;
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
