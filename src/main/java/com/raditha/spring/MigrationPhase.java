package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Abstract base class for Spring Boot migration phases.
 * 
 * <p>
 * Each phase handles a specific aspect of the migration (POM updates, property
 * transformations, code modifications, etc.).
 * 
 * <p>
 * All migrators share common functionality:
 * <ul>
 * <li>Dry-run mode support</li>
 * <li>Consistent logging</li>
 * <li>Priority-based execution</li>
 * </ul>
 * 
 * <p>
 * Subclasses should extend this to create version-specific or feature-specific
 * migrators.
 */
public abstract class MigrationPhase {

    /**
     * Logger for migration operations.
     * Subclasses should use this instead of creating their own.
     */
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Whether this migration should run in dry-run mode (no file modifications).
     */
    protected final boolean dryRun;

    /**
     * Constructor for migration phase.
     * 
     * @param dryRun if true, no files will be modified (preview mode)
     */
    protected MigrationPhase(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Execute this migration phase.
     * 
     * @return the result of this migration phase
     * @throws Exception if the migration fails
     */
    public abstract MigrationPhaseResult migrate() throws Exception;

    /**
     * Get the name of this migration phase.
     * 
     * @return the phase name (e.g., "POM Migration (2.3→2.4)")
     */
    public abstract String getPhaseName();

    /**
     * Get the priority of this phase. Lower values run first.
     * 
     * <p>
     * Default priority is 100. Override to customize execution order.
     * 
     * <p>
     * Typical priorities:
     * <ul>
     * <li>1-10: Critical pre-migration validation</li>
     * <li>10-30: POM/dependency updates</li>
     * <li>30-50: Property file migrations</li>
     * <li>50-70: Code transformations</li>
     * <li>70-100: Post-migration validation</li>
     * </ul>
     * 
     * @return the priority (default 100)
     */
    public int getPriority() {
        return 100;
    }


    protected static boolean migrateJavaXMail(Model model, MigrationPhaseResult result, Dependency javaxMail) {
        if (javaxMail != null) {
            model.getDependencies().remove(javaxMail);

            // Add Jakarta Mail
            Dependency jakartaMail = new Dependency();
            jakartaMail.setGroupId("com.sun.mail");
            jakartaMail.setArtifactId("jakarta.mail");
            jakartaMail.setVersion(javaxMail.getVersion()); // Keep same version
            if (javaxMail.getScope() != null) {
                jakartaMail.setScope(javaxMail.getScope());
            }
            model.addDependency(jakartaMail);

            result.addChange("Migrated: javax.mail:javax.mail-api → com.sun.mail:jakarta.mail");
            return true;
        }
        return false;
    }

    protected Path resolvePomPath() {
        Path basePath = Paths.get(Settings.getBasePath());
        Path pomPath = basePath.resolve("pom.xml");

        if (!pomPath.toFile().exists()) {
            pomPath = basePath.getParent().resolve("pom.xml");
        }

        if (pomPath.toFile().exists()) {
            return pomPath;
        }
        return null;
    }

}
