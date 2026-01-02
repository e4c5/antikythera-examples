package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

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
    protected final MavenHelper mavenHelper = new MavenHelper();

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
    public final MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Model model = mavenHelper.getPomModel();
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
            mavenHelper.writePomModel(model);
            logger.info("POM migration completed successfully");
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
     * <p>
     * Handles multiple scenarios:
     * <ul>
     * <li>Direct spring-boot-starter-parent inheritance</li>
     * <li>Corporate parent POM (adds property override)</li>
     * <li>Property-based version management (spring-boot.version or spring.boot.version)</li>
     * <li>Spring Boot BOM in dependencyManagement</li>
     * </ul>
     *
     * @param model  Maven model
     * @param result migration result to add changes/warnings
     * @return true if parent version was updated
     */
    protected final boolean updateSpringBootParent(Model model, MigrationPhaseResult result) {
        // Strategy 1: Direct spring-boot-starter-parent
        if (tryUpdateDirectParent(model, result)) {
            return true;
        }

        // Strategy 2: Property-based version management
        if (tryUpdateVersionProperty(model, result)) {
            return true;
        }

        // Strategy 3: Spring Boot BOM in dependencyManagement
        if (tryUpdateSpringBootBom(model, result)) {
            return true;
        }

        // Strategy 4: Corporate parent POM - add property override
        return handleCorporateParent(model, result);
    }

    /**
     * Try to update direct spring-boot-starter-parent.
     */
    private boolean tryUpdateDirectParent(Model model, MigrationPhaseResult result) {
        Parent parent = model.getParent();
        if (parent == null ||
            !"org.springframework.boot".equals(parent.getGroupId()) ||
            !"spring-boot-starter-parent".equals(parent.getArtifactId())) {
            return false;
        }

        String currentVersion = parent.getVersion();
        if (currentVersion == null || currentVersion.startsWith("${")) {
            return false;
        }

        return updateVersionIfNeeded(currentVersion,
            parent::setVersion,
            "Spring Boot parent",
            result);
    }

    /**
     * Try to update Spring Boot version property.
     */
    private boolean tryUpdateVersionProperty(Model model, MigrationPhaseResult result) {
        if (model.getProperties() == null) {
            return false;
        }

        String[] propertyKeys = {"spring-boot.version", "spring.boot.version", "springboot.version"};
        for (String key : propertyKeys) {
            if (model.getProperties().containsKey(key)) {
                String currentVersion = model.getProperties().getProperty(key);
                return updateVersionIfNeeded(currentVersion,
                    v -> model.getProperties().setProperty(key, v),
                    "property " + key,
                    result);
            }
        }
        return false;
    }

    /**
     * Try to update Spring Boot BOM in dependencyManagement.
     */
    private boolean tryUpdateSpringBootBom(Model model, MigrationPhaseResult result) {
        if (model.getDependencyManagement() == null ||
            model.getDependencyManagement().getDependencies() == null) {
            return false;
        }

        Dependency springBootBom = model.getDependencyManagement().getDependencies().stream()
            .filter(dep -> "org.springframework.boot".equals(dep.getGroupId()) &&
                          "spring-boot-dependencies".equals(dep.getArtifactId()))
            .findFirst()
            .orElse(null);

        if (springBootBom == null) {
            return false;
        }

        String currentVersion = springBootBom.getVersion();
        if (currentVersion == null || currentVersion.startsWith("${")) {
            return false;
        }

        return updateVersionIfNeeded(currentVersion,
            springBootBom::setVersion,
            "Spring Boot BOM",
            result);
    }

    /**
     * Update version if needed (DRY helper method).
     */
    private boolean updateVersionIfNeeded(String currentVersion,
                                          java.util.function.Consumer<String> versionSetter,
                                          String componentName,
                                          MigrationPhaseResult result) {
        String targetVersion = extractVersionPrefix(targetSpringBootVersion);
        String currentPrefix = extractVersionPrefix(currentVersion);

        if (currentPrefix.equals(targetVersion)) {
            logger.info("{} already at {}.x: {}", componentName, targetVersion, currentVersion);
            return false;
        }

        if (dryRun) {
            result.addChange(String.format("Would update %s: %s → %s",
                    componentName, currentVersion, targetSpringBootVersion));
        } else {
            versionSetter.accept(targetSpringBootVersion);
            result.addChange(String.format("Updated %s: %s → %s",
                    componentName, currentVersion, targetSpringBootVersion));
            logger.info("Updated {} to {}", componentName, targetSpringBootVersion);
        }
        return true;
    }

    /**
     * Handle corporate parent POM scenarios by adding a version property override.
     *
     * <p>
     * When a corporate parent POM is detected (not spring-boot-starter-parent),
     * automatically adds a {@code <spring-boot.version>} property to override
     * the Spring Boot version managed by the parent.
     *
     * @return true if property was added, false otherwise
     */
    private boolean handleCorporateParent(Model model, MigrationPhaseResult result) {
        Parent parent = model.getParent();
        if (parent != null) {
            result.addWarning(String.format("Corporate parent POM detected: %s:%s:%s",
                parent.getGroupId(), parent.getArtifactId(), parent.getVersion()));
            result.addWarning("Spring Boot version may be managed in parent POM");

            // Automatically add property override
            if (model.getProperties() == null) {
                model.setProperties(new java.util.Properties());
            }

            if (dryRun) {
                result.addChange(String.format("Would add property override: <spring-boot.version>%s</spring-boot.version>",
                    targetSpringBootVersion));
                result.addWarning("This property will override the Spring Boot version from parent POM");
            } else {
                model.getProperties().setProperty("spring-boot.version", targetSpringBootVersion);
                result.addChange(String.format("Added property override: <spring-boot.version>%s</spring-boot.version>",
                    targetSpringBootVersion));
                result.addWarning("This property overrides the Spring Boot version from parent POM");
                result.addWarning("Verify that parent POM uses ${spring-boot.version} for version management");
                logger.info("Added spring-boot.version property to override corporate parent's Spring Boot version");
            }
            return true;  // Property was added, need to write POM
        } else {
            result.addWarning("No parent POM found and no Spring Boot version property detected");
            result.addWarning("Consider adding Spring Boot dependency management or parent POM");
            return false;
        }
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
