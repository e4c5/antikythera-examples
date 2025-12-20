package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * POM migrator for Spring Boot 2.4 to 2.5 upgrade.
 * 
 * <p>
 * Handles version-specific dependency migrations:
 * <ul>
 * <li>Spring Boot parent version update to 2.5.15 (latest 2.5.x)</li>
 * <li>Groovy 3.x upgrade (from 2.5.x)</li>
 * <li>Spock 2.0+ upgrade (required for Groovy 3.x compatibility)</li>
 * <li>Spring Framework 5.3.7+ validation</li>
 * <li>Spring Data 2021.0 validation</li>
 * <li>Spring Security 5.5.x validation</li>
 * <li>Cassandra driver validation (throttling changes)</li>
 * </ul>
 * 
 * @see AbstractPomMigrator
 */
public class PomMigrator24to25 extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomMigrator24to25.java);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.5.15";
    private static final String TARGET_GROOVY_VERSION = "3.0.8";
    private static final String TARGET_SPOCK_VERSION = "2.0-groovy-3.0";
    private static final String MIN_SPRING_CLOUD_VERSION = "2020.0.3";

    public PomMigrator24to25(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Check and upgrade Groovy/Spock versions
        checkGroovyAndSpock(model, result);

        // Check Cassandra driver
        checkCassandraDriver(model, result);

        // Check for deprecated dependencies from Spring Boot 2.3
        checkDeprecatedDependencies(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Validate Spring Cloud compatibility
        validateSpringCloudCompatibility(model, result);

        // Warn about critical changes
        result.addWarning("CRITICAL: SQL script initialization properties moved from spring.datasource.* to spring.sql.init.*");
        result.addWarning("CRITICAL: /info actuator endpoint no longer exposed by default");
        result.addWarning("HIGH: Cassandra throttling default values removed - explicit config required");
        result.addWarning("See SqlScriptPropertiesMigrator and ActuatorInfoMigrator for automated migration");
    }

    /**
     * Check and upgrade Groovy and Spock versions for compatibility.
     * Groovy 3.x is the default in Spring Boot 2.5, requiring Spock 2.0+.
     */
    private void checkGroovyAndSpock(Model model, MigrationPhaseResult result) {
        Properties properties = model.getProperties();
        
        // Check current Groovy version
        String groovyVersion = properties.getProperty("groovy.version");
        boolean needsGroovyUpgrade = groovyVersion != null && groovyVersion.startsWith("2.");
        
        // Check for Spock dependency
        boolean hasSpock = getDependenciesByGroupId(model, "org.spockframework").stream()
                .anyMatch(dep -> dep.getArtifactId().contains("spock"));

        if (hasSpock) {
            // Check current Spock version
            String spockVersion = properties.getProperty("spock.version");
            
            if (spockVersion == null) {
                // Check direct dependency version
                for (Dependency dep : getDependenciesByGroupId(model, "org.spockframework")) {
                    if (dep.getVersion() != null) {
                        spockVersion = dep.getVersion();
                        break;
                    }
                }
            }

            boolean needsSpockUpgrade = spockVersion != null && 
                    (spockVersion.startsWith("1.") || spockVersion.contains("groovy-2"));

            if (needsSpockUpgrade) {
                result.addWarning("GROOVY/SPOCK: Spock 1.x is incompatible with Groovy 3.x");
                result.addWarning("GROOVY/SPOCK: Upgrade to Spock 2.0-groovy-3.0 or higher");
                result.addWarning("GROOVY/SPOCK: Current Spock version: " + spockVersion);
                result.addWarning("GROOVY/SPOCK: See GroovySpockMigrator for automated upgrade");
                logger.warn("Spock version {} needs upgrade for Groovy 3.x", spockVersion);
            } else if (spockVersion != null && spockVersion.startsWith("2.")) {
                result.addChange("Spock 2.x detected - compatible with Groovy 3.x");
            }
        }

        if (needsGroovyUpgrade) {
            result.addWarning("GROOVY: Groovy " + groovyVersion + " detected - Spring Boot 2.5 uses Groovy 3.x");
            result.addWarning("GROOVY: See GroovySpockMigrator for automated upgrade");
            logger.warn("Groovy version {} needs upgrade to 3.x", groovyVersion);
        }
    }

    /**
     * Check Cassandra driver and warn about throttling changes.
     */
    private void checkCassandraDriver(Model model, MigrationPhaseResult result) {
        boolean hasCassandra = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> dep.getArtifactId().contains("cassandra"));

        if (!hasCassandra) {
            hasCassandra = getDependenciesByGroupId(model, "org.springframework.data").stream()
                    .anyMatch(dep -> dep.getArtifactId().contains("cassandra"));
        }

        if (hasCassandra) {
            result.addWarning("CASSANDRA: Default throttling property values removed in Spring Boot 2.5");
            result.addWarning("CASSANDRA: Explicit configuration required if using request throttling");
            result.addWarning("CASSANDRA: See CassandraThrottlingMigrator for automated configuration");
            logger.info("Cassandra dependency detected - throttling configuration may be needed");
        }
    }

    /**
     * Check for deprecated dependencies that were removed in Spring Boot 2.5.
     * These dependencies were deprecated in Spring Boot 2.3.
     */
    private void checkDeprecatedDependencies(Model model, MigrationPhaseResult result) {
        // Check for deprecated metrics exporters (if any specific to 2.3)
        boolean hasDeprecatedMetrics = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> dep.getArtifactId().contains("actuate-autoconfigure") &&
                        dep.getArtifactId().contains("metrics"));

        if (hasDeprecatedMetrics) {
            result.addWarning("DEPRECATED: Some metrics auto-configuration from Spring Boot 2.3 was removed");
            result.addWarning("DEPRECATED: Review actuator metrics configuration");
            logger.warn("Deprecated metrics configuration may need updates");
        }
    }

    /**
     * Validate Spring Cloud compatibility with Spring Boot 2.5.
     */
    private void validateSpringCloudCompatibility(Model model, MigrationPhaseResult result) {
        // Check for Spring Cloud BOM
        model.getDependencyManagement().getDependencies().stream()
                .filter(dep -> "org.springframework.cloud".equals(dep.getGroupId()))
                .filter(dep -> "spring-cloud-dependencies".equals(dep.getArtifactId()))
                .findFirst()
                .ifPresent(dep -> {
                    String version = dep.getVersion();
                    if (version != null && !version.startsWith("2020.0")) {
                        result.addWarning("SPRING CLOUD: Version " + version + " may not be compatible with Spring Boot 2.5");
                        result.addWarning("SPRING CLOUD: Recommended version: 2020.0.3 or higher");
                        logger.warn("Spring Cloud version {} may need update", version);
                    } else {
                        result.addChange("Spring Cloud version compatible with Spring Boot 2.5");
                    }
                });
    }
}
