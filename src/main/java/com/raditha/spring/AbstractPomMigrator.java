package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstract base class for Spring Boot POM migrations.
 * 
 * <p>
 * Provides common functionality for updating Maven POMs during Spring Boot
 * version migrations:
 * <ul>
 * <li>Reading and writing POM files</li>
 * <li>Updating Spring Boot parent version</li>
 * <li>Adding dependencies</li>
 * <li>Version comparison utilities</li>
 * </ul>
 * 
 * <p>
 * Subclasses implement version-specific dependency rules via hook methods:
 * <ul>
 * <li>{@link #applyVersionSpecificDependencyRules(Model, MigrationPhaseResult)}
 * - Apply version-specific dependency updates</li>
 * <li>{@link #validateVersionSpecificRequirements(Model, MigrationPhaseResult)}
 * - Validate version-specific requirements</li>
 * </ul>
 * 
 * <p>
 * Example subclass for 2.2→2.3 migration:
 * 
 * <pre>
 * {
 *     &#64;code
 *     public class PomMigrator22to23 extends AbstractPomMigrator {
 *         public PomMigrator22to23(boolean dryRun) {
 *             super("2.3.12.RELEASE", dryRun);
 *         }
 * 
 *         &#64;Override
 *         protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
 *             // Check for Spring Cloud compatibility
 *             validateSpringCloudVersion(model, result);
 *             // Check for removed dependencies
 *             checkRemovedDependencies(model, result);
 *         }
 * 
 *         @Override
 *         protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
 *             // Validate Gradle version if build.gradle exists
 *             validateGradleVersion(result);
 *         }
 *     }
 * }
 * </pre>
 * 
 * @see MigrationPhase
 * @see MigrationPhaseResult
 */
public abstract class AbstractPomMigrator extends MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(AbstractPomMigrator.class);

    protected final String targetSpringBootVersion;

    /**
     * Constructor for POM migrator.
     * 
     * @param targetSpringBootVersion target Spring Boot version (e.g.,
     *                                "2.2.13.RELEASE", "2.3.12.RELEASE")
     * @param dryRun                  if true, no files will be modified (preview
     *                                mode)
     */
    protected AbstractPomMigrator(String targetSpringBootVersion, boolean dryRun) {
        super(dryRun);
        this.targetSpringBootVersion = targetSpringBootVersion;
    }

    /**
     * Execute POM migration.
     * 
     * <p>
     * Template method that orchestrates the POM migration process:
     * <ol>
     * <li>Locate and read POM file</li>
     * <li>Update Spring Boot parent version</li>
     * <li>Apply version-specific dependency rules</li>
     * <li>Validate version-specific requirements</li>
     * <li>Write modified POM</li>
     * </ol>
     * 
     * @return result of POM migration
     */
    @Override
    public final MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path pomPath = PomUtils.resolvePomPath();
        if (pomPath == null) {
            result.addError("Could not find pom.xml");
            return result;
        }

        try {
            Model model = PomUtils.readPomModel(pomPath);
            boolean modified = false;

            // Update Spring Boot parent version
            if (updateSpringBootParent(model, result)) {
                modified = true;
            }

            // Apply version-specific dependency rules
            applyVersionSpecificDependencyRules(model, result);

            // Validate version-specific requirements
            validateVersionSpecificRequirements(model, result);

            // Write POM if modifications were made
            if (modified && !dryRun) {
                PomUtils.writePomModel(pomPath, model);
                logger.info("POM migration completed successfully");
            }

        } catch (Exception e) {
            logger.error("Error during POM migration", e);
            result.addError("POM migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Update Spring Boot parent version to target version.
     * 
     * <p>
     * This method is final to ensure consistent parent version updates across all
     * Spring Boot versions.
     * 
     * @param model  Maven model
     * @param result migration result to add changes/warnings
     * @return true if parent version was updated
     */
    protected final boolean updateSpringBootParent(Model model, MigrationPhaseResult result) {
        Parent parent = model.getParent();

        if (parent == null) {
            result.addWarning("No parent POM found");
            return false;
        }

        if (!"org.springframework.boot".equals(parent.getGroupId()) ||
                !"spring-boot-starter-parent".equals(parent.getArtifactId())) {
            result.addWarning("Parent is not spring-boot-starter-parent");
            return false;
        }

        String currentVersion = parent.getVersion();
        String targetVersion = extractVersionPrefix(targetSpringBootVersion);
        String currentPrefix = extractVersionPrefix(currentVersion);

        if (currentPrefix.equals(targetVersion)) {
            logger.info("Spring Boot parent already at {}.x: {}", targetVersion, currentVersion);
            return false;
        }

        if (dryRun) {
            result.addChange(String.format("Would update Spring Boot parent: %s → %s",
                    currentVersion, targetSpringBootVersion));
        } else {
            parent.setVersion(targetSpringBootVersion);
            result.addChange(String.format("Updated Spring Boot parent: %s → %s",
                    currentVersion, targetSpringBootVersion));
            logger.info("Updated Spring Boot parent version to {}", targetSpringBootVersion);
        }

        return true;
    }

    /**
     * Apply version-specific dependency rules.
     * 
     * <p>
     * Subclasses should implement version-specific logic such as:
     * <ul>
     * <li>Migrating deprecated dependencies</li>
     * <li>Adding new required dependencies</li>
     * <li>Updating dependency versions</li>
     * <li>Removing obsolete dependencies</li>
     * </ul>
     * 
     * <p>
     * Use provided utility methods to modify the model:
     * <ul>
     * <li>{@link #hasDependency(Model, String, String)}</li>
     * <li>{@link #addDependency(Model, String, String, String, MigrationPhaseResult)}</li>
     * <li>{@link #findDependency(Model, String, String)}</li>
     * </ul>
     * 
     * @param model  Maven model
     * @param result migration result to add changes/warnings/errors
     */
    protected abstract void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result);

    /**
     * Validate version-specific requirements.
     * 
     * <p>
     * Subclasses should implement validation logic such as:
     * <ul>
     * <li>Checking minimum dependency versions</li>
     * <li>Validating build tool versions (Gradle, Maven)</li>
     * <li>Checking for incompatible dependencies</li>
     * </ul>
     * 
     * <p>
     * Add warnings or errors to the result as appropriate.
     * 
     * @param model  Maven model
     * @param result migration result to add warnings/errors
     */
    protected abstract void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result);

    /**
     * Check if a dependency exists in the POM.
     * 
     * @param model      Maven model
     * @param groupId    dependency group ID
     * @param artifactId dependency artifact ID
     * @return true if dependency exists
     */
    protected final boolean hasDependency(Model model, String groupId, String artifactId) {
        return model.getDependencies().stream()
                .anyMatch(dep -> groupId.equals(dep.getGroupId()) &&
                        artifactId.equals(dep.getArtifactId()));
    }

    /**
     * Find a dependency in the POM.
     * 
     * @param model      Maven model
     * @param groupId    dependency group ID
     * @param artifactId dependency artifact ID
     * @return dependency if found, null otherwise
     */
    protected final Dependency findDependency(Model model, String groupId, String artifactId) {
        return model.getDependencies().stream()
                .filter(dep -> groupId.equals(dep.getGroupId()) &&
                        artifactId.equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Add a dependency to the POM.
     * 
     * @param model      Maven model
     * @param groupId    dependency group ID
     * @param artifactId dependency artifact ID
     * @param scope      dependency scope (null for default)
     * @param result     migration result to add changes
     * @return true if dependency was added
     */
    protected final boolean addDependency(Model model, String groupId, String artifactId,
            String scope, MigrationPhaseResult result) {
        if (hasDependency(model, groupId, artifactId)) {
            result.addChange(String.format("%s:%s already present", groupId, artifactId));
            return false;
        }

        if (dryRun) {
            result.addChange(String.format("Would add dependency: %s:%s%s",
                    groupId, artifactId, scope != null ? " (scope: " + scope + ")" : ""));
        } else {
            Dependency dependency = new Dependency();
            dependency.setGroupId(groupId);
            dependency.setArtifactId(artifactId);
            if (scope != null) {
                dependency.setScope(scope);
            }
            model.addDependency(dependency);

            result.addChange(String.format("Added dependency: %s:%s%s",
                    groupId, artifactId, scope != null ? " (scope: " + scope + ")" : ""));
            logger.info("Added dependency: {}:{}", groupId, artifactId);
        }

        return true;
    }

    /**
     * Get all dependencies with a specific groupId.
     * 
     * @param model   Maven model
     * @param groupId group ID to filter by
     * @return list of matching dependencies
     */
    protected final List<Dependency> getDependenciesByGroupId(Model model, String groupId) {
        return model.getDependencies().stream()
                .filter(dep -> groupId.equals(dep.getGroupId()))
                .collect(Collectors.toList());
    }

    /**
     * Extract version prefix (e.g., "2.2" from "2.2.13.RELEASE").
     */
    private String extractVersionPrefix(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return version;
    }
}
