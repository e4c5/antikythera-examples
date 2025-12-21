package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POM migrator for Spring Boot 2.4 to 2.5 upgrade.
 * 
 * <p>
 * Handles version-specific dependency migrations:
 * <ul>
 * <li>Spring Boot version update to 2.5.15 (latest 2.5.x)</li>
 * <li>Groovy 3.x upgrade (2.5.x → 3.0.8)</li>
 * <li>Spock 2.0+ upgrade (1.x → 2.0-groovy-3.0)</li>
 * <li>Spring Cloud compatibility validation (requires 2020.0.x or
 * 2021.0.x)</li>
 * <li>Cassandra starter detection for throttling configuration</li>
 * </ul>
 * 
 * @see AbstractPomMigrator
 */
public class PomMigrator24to25 extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomMigrator24to25.class);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.5.15";
    private static final String TARGET_GROOVY_VERSION = "3.0.8";
    private static final String TARGET_SPOCK_VERSION = "2.0-groovy-3.0";

    public PomMigrator24to25(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Check for Groovy/Spock dependencies
        checkGroovySpockVersions(model, result);

        // Check for Cassandra starter (for throttling config)
        checkCassandraStarter(model, result);

        // Check Spring Cloud compatibility
        validateSpringCloudCompatibility(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Warn about critical changes
        result.addWarning("CRITICAL: SQL script initialization properties moved to spring.sql.init.*");
        result.addWarning("CRITICAL: /info actuator endpoint no longer exposed by default");
        result.addWarning("WARNING: Cassandra throttling defaults removed");
        result.addWarning("See SqlScriptPropertiesMigrator for automated property migration");
        result.addWarning("See ActuatorInfoMigrator for /info endpoint configuration");
    }

    /**
     * Check Groovy and Spock versions and recommend upgrades.
     */
    private void checkGroovySpockVersions(Model model, MigrationPhaseResult result) {
        // Check for Spock dependency
        boolean hasSpock = getDependenciesByGroupId(model, "org.spockframework").stream()
                .anyMatch(dep -> dep.getArtifactId().startsWith("spock"));

        if (hasSpock) {
            result.addWarning("SPOCK: Spring Boot 2.5 requires Groovy 3.x");
            result.addWarning("SPOCK: Spock 1.x is incompatible with Groovy 3.x");
            result.addWarning("SPOCK: Recommend upgrading to Spock 2.0-groovy-3.0");
            result.addWarning("SPOCK: See GroovySpockMigrator for automated version upgrades");
            result.addWarning("Reference: https://spockframework.org/spock/docs/2.0/migration_guide.html");
            logger.warn("Spock detected - version upgrade to 2.0+ required for Groovy 3.x");
        }

        // Check for explicit Groovy dependency
        boolean hasGroovy = getDependenciesByGroupId(model, "org.codehaus.groovy").stream()
                .anyMatch(dep -> dep.getArtifactId().startsWith("groovy"));

        if (hasGroovy || hasSpock) {
            result.addWarning("GROOVY: Spring Boot 2.5 uses Groovy 3.0.8");
            result.addWarning("GROOVY: Update groovy.version property to 3.0.8");
            logger.warn("Groovy detected - upgrade to 3.0.8 required");
        }
    }

    /**
     * Check for Cassandra starter and warn about throttling configuration.
     */
    private void checkCassandraStarter(Model model, MigrationPhaseResult result) {
        boolean hasCassandra = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> "spring-boot-starter-data-cassandra".equals(dep.getArtifactId())
                        || "spring-boot-starter-data-cassandra-reactive".equals(dep.getArtifactId()));

        if (hasCassandra) {
            result.addWarning("CASSANDRA: Spring Boot 2.5 removed default throttling configuration");
            result.addWarning("CASSANDRA: Must explicitly configure request throttling");
            result.addWarning("CASSANDRA: See CassandraThrottlingMigrator for automated configuration");
            logger.warn("Cassandra detected - throttling configuration required");
        }
    }

    /**
     * Validate Spring Cloud version compatibility with Spring Boot 2.5.
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
                // Spring Boot 2.5 requires Spring Cloud 2020.0.x (Ilford) or 2021.0.x
                if (version.startsWith("2020.0") || version.startsWith("2021.0")) {
                    result.addChange(String.format("Spring Cloud %s is compatible with Spring Boot 2.5", version));
                } else if (version.startsWith("Hoxton") || version.startsWith("2020.0") == false) {
                    result.addError(String.format(
                            "Spring Cloud %s may NOT be compatible with Spring Boot 2.5. " +
                                    "Recommend upgrading to 2020.0.x (Ilford) or 2021.0.x",
                            version));
                } else {
                    result.addWarning(String.format(
                            "Spring Cloud version %s compatibility unknown. " +
                                    "Recommend using 2020.0.x or 2021.0.x",
                            version));
                }
            }
        }
    }

    @Override
    public String getPhaseName() {
        return "POM Migration (2.4→2.5)";
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
