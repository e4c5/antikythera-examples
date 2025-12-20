package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POM migrator for Spring Boot 2.3 to 2.4 upgrade.
 * 
 * <p>
 * Handles version-specific dependency migrations:
 * <ul>
 * <li>Spring Boot version format change (2.4.13 instead of 2.4.13.RELEASE)</li>
 * <li>JUnit Vintage Engine removal detection (requires explicit
 * dependency)</li>
 * <li>Hazelcast 4.x upgrade validation (breaking changes)</li>
 * <li>Flyway 7.x upgrade validation (callback ordering changes)</li>
 * <li>Neo4j driver 4.2+ validation (property namespace changes)</li>
 * <li>Elasticsearch version check</li>
 * <li>exec-maven-plugin version requirement</li>
 * </ul>
 * 
 * @see AbstractPomMigrator
 */
public class PomMigrator23to24 extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomMigrator23to24.class);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.4.13";
    private static final String MIN_SPRING_CLOUD_VERSION = "2020.0.0";

    public PomMigrator23to24(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Check for JUnit 4 tests requiring vintage engine
        checkJUnitVintageEngine(model, result);

        // Check Hazelcast version (upgraded to 4.x with breaking changes)
        checkHazelcastVersion(model, result);

        // Check Neo4j driver version
        checkNeo4jDriver(model, result);

        // Check Flyway version
        checkFlywayVersion(model, result);

        // Check for exec-maven-plugin
        checkExecMavenPlugin(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Validate Spring Cloud compatibility
        validateSpringCloudCompatibility(model, result);

        // Warn about config file processing changes
        result.addWarning("CRITICAL: Spring Boot 2.4 changed configuration file processing order");
        result.addWarning("Consider using spring.config.use-legacy-processing=true temporarily");
        result.addWarning("See ConfigurationProcessingMigrator for automated migration");
    }

    /**
     * Check if JUnit 4 tests exist and vintage engine is needed.
     */
    private void checkJUnitVintageEngine(Model model, MigrationPhaseResult result) {
        // Check if spring-boot-starter-test is present
        boolean hasStarterTest = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> "spring-boot-starter-test".equals(dep.getArtifactId()));

        if (!hasStarterTest) {
            return;
        }

        // Check if vintage engine is already added
        boolean hasVintageEngine = getDependenciesByGroupId(model, "org.junit.vintage").stream()
                .anyMatch(dep -> "junit-vintage-engine".equals(dep.getArtifactId()));

        if (!hasVintageEngine) {
            result.addWarning("JUNIT: Vintage engine removed from spring-boot-starter-test");
            result.addWarning("JUNIT: If you have JUnit 4 tests, add org.junit.vintage:junit-vintage-engine");
            result.addWarning("JUNIT: Recommended: Use JUnit425Migrator to migrate tests to JUnit 5");
            logger.warn("JUnit Vintage engine not found - may be needed for JUnit 4 tests");
        } else {
            result.addChange("JUnit Vintage engine already configured for JUnit 4 compatibility");
        }
    }

    /**
     * Check Hazelcast version and warn about 4.x breaking changes.
     */
    private void checkHazelcastVersion(Model model, MigrationPhaseResult result) {
        boolean hasHazelcast = getDependenciesByGroupId(model, "com.hazelcast").stream()
                .anyMatch(dep -> dep.getArtifactId().startsWith("hazelcast"));

        if (!hasHazelcast) {
            hasHazelcast = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                    .anyMatch(dep -> "spring-boot-starter-cache".equals(dep.getArtifactId()));
        }

        if (hasHazelcast) {
            result.addWarning("HAZELCAST: Spring Boot 2.4 upgrades to Hazelcast 4.x");
            result.addWarning("HAZELCAST: Breaking API changes - review configuration and code");
            result.addWarning("HAZELCAST: See HazelcastCodeMigrator for detected usage");
            result.addWarning("Reference: https://docs.hazelcast.com/hazelcast/4.0/migration-guides/4.0");
            logger.warn("Hazelcast detected - 4.x upgrade requires manual review");
        }
    }

    /**
     * Check Neo4j driver version and warn about property changes.
     */
    private void checkNeo4jDriver(Model model, MigrationPhaseResult result) {
        boolean hasNeo4j = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> "spring-boot-starter-data-neo4j".equals(dep.getArtifactId()));

        if (!hasNeo4j) {
            hasNeo4j = getDependenciesByGroupId(model, "org.neo4j.driver").stream()
                    .anyMatch(dep -> "neo4j-java-driver".equals(dep.getArtifactId()));
        }

        if (hasNeo4j) {
            result.addWarning("NEO4J: Property namespace changed in Spring Boot 2.4");
            result.addWarning("NEO4J: spring.data.neo4j.* → spring.neo4j.*");
            result.addWarning("NEO4J: Neo4j OGM support removed - migrate to Neo4j SDN-RX");
            result.addWarning("NEO4J: See Neo4jPropertyMigrator for automated property migration");
            logger.warn("Neo4j detected - property migration required");
        }
    }

    /**
     * Check Flyway version and warn about callback ordering changes.
     */
    private void checkFlywayVersion(Model model, MigrationPhaseResult result) {
        boolean hasFlyway = getDependenciesByGroupId(model, "org.flywaydb").stream()
                .anyMatch(dep -> dep.getArtifactId().startsWith("flyway"));

        if (!hasFlyway) {
            hasFlyway = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                    .anyMatch(dep -> "spring-boot-starter-flyway".equals(dep.getArtifactId()));
        }

        if (hasFlyway) {
            result.addWarning("FLYWAY: Spring Boot 2.4 upgrades to Flyway 7.x");
            result.addWarning("FLYWAY: Callback registration order changed - verify callbacks");
            logger.warn("Flyway detected - callback ordering may need review");
        }
    }

    /**
     * Check for exec-maven-plugin and warn about version management removal.
     */
    private void checkExecMavenPlugin(Model model, MigrationPhaseResult result) {
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) {
            return;
        }

        boolean hasExecPlugin = model.getBuild().getPlugins().stream()
                .anyMatch(plugin -> "org.codehaus.mojo".equals(plugin.getGroupId()) &&
                        "exec-maven-plugin".equals(plugin.getArtifactId()));

        if (hasExecPlugin) {
            result.addWarning("MAVEN: exec-maven-plugin version no longer managed by Spring Boot");
            result.addWarning("MAVEN: Must specify version explicitly (e.g., 3.0.0)");
            logger.warn("exec-maven-plugin detected - version must be specified");
        }
    }

    /**
     * Validate Spring Cloud version compatibility with Spring Boot 2.4.
     */
    private void validateSpringCloudCompatibility(Model model, MigrationPhaseResult result) {
        if (model.getDependencyManagement() == null ||
                model.getDependencyManagement().getDependencies() == null) {
            return;
        }

        Dependency springCloudBom = model.getDependencyManagement().getDependencies().stream()
                .filter(dep -> "org.springframework.cloud".equals(dep.getGroupId()) &&
                        "spring-cloud-dependencies".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (springCloudBom != null) {
            String version = springCloudBom.getVersion();
            if (version != null) {
                // Spring Boot 2.4 requires Spring Cloud 2020.0.x (Ilford) or later
                if (version.startsWith("2020.0") || version.startsWith("2021.0")) {
                    result.addChange(String.format("Spring Cloud %s is compatible with Spring Boot 2.4", version));
                } else if (version.startsWith("Hoxton")) {
                    result.addError(String.format(
                            "Spring Cloud %s is NOT compatible with Spring Boot 2.4. " +
                                    "Must upgrade to 2020.0.x (Ilford) or later",
                            version));
                } else {
                    result.addWarning(String.format(
                            "Spring Cloud version %s compatibility unknown. " +
                                    "Recommend using 2020.0.x or later",
                            version));
                }
            }
        }
    }

    @Override
    public String getPhaseName() {
        return "POM Migration (2.3→2.4)";
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
