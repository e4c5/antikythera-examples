package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POM migrator for Spring Boot 2.2 to 2.3 upgrade.
 * 
 * <p>
 * Handles version-specific dependency migrations:
 * <ul>
 * <li>Spring Cloud compatibility validation (Hoxton.SR8+ or 2020.0.x)</li>
 * <li>Gradle minimum version check (6.3+)</li>
 * <li>Cassandra driver version check (warns about v4 breaking changes)</li>
 * <li>Elasticsearch version check (warns about TransportClient
 * deprecation)</li>
 * <li>Validation starter (handled separately by ValidationStarterDetector)</li>
 * </ul>
 * 
 * @see AbstractPomMigrator
 */
public class PomMigrator22to23 extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomMigrator22to23.class);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.3.12.RELEASE";
    private static final String MIN_SPRING_CLOUD_HOXTON_VERSION = "Hoxton.SR8";

    public PomMigrator22to23(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Check Spring Cloud compatibility
        validateSpringCloudCompatibility(model, result);

        // Check Cassandra driver version
        checkCassandraDriver(model, result);

        // Check Elasticsearch version
        checkElasticsearchVersion(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Gradle version validation (informational only)
        result.addWarning("Ensure Gradle version is 6.3+ if using Gradle (Maven users can ignore this)");
    }

    /**
     * Validate Spring Cloud version compatibility with Spring Boot 2.3.
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
                // Check if using compatible version
                if (version.startsWith("Hoxton")) {
                    // Hoxton.SR8+ is compatible with Spring Boot 2.3
                    if (compareVersions(version, MIN_SPRING_CLOUD_HOXTON_VERSION) < 0) {
                        result.addWarning(String.format(
                                "Spring Cloud %s may have compatibility issues with Spring Boot 2.3. " +
                                        "Recommend upgrading to %s or later",
                                version, MIN_SPRING_CLOUD_HOXTON_VERSION));
                    } else {
                        result.addChange(String.format("Spring Cloud %s is compatible with Spring Boot 2.3", version));
                    }
                } else if (version.startsWith("2020.0")) {
                    // 2020.0.x is fully compatible
                    result.addChange(String.format("Spring Cloud %s is compatible with Spring Boot 2.3", version));
                } else if (version.startsWith("Greenwich")) {
                    result.addError(String.format(
                            "Spring Cloud %s is NOT compatible with Spring Boot 2.3. " +
                                    "Must upgrade to Hoxton.SR8+ or 2020.0.x",
                            version));
                }
            }
        }
    }

    /**
     * Check for Cassandra driver and warn about v4 breaking changes.
     */
    private void checkCassandraDriver(Model model, MigrationPhaseResult result) {
        boolean hasCassandra = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> "spring-boot-starter-data-cassandra".equals(dep.getArtifactId()));

        if (!hasCassandra) {
            hasCassandra = getDependenciesByGroupId(model, "com.datastax.cassandra").stream()
                    .anyMatch(dep -> dep.getArtifactId().startsWith("cassandra"));
        }

        if (hasCassandra) {
            result.addWarning("CASSANDRA: Spring Boot 2.3 upgrades to Cassandra Driver v4 with BREAKING CHANGES");
            result.addWarning("CASSANDRA: Review CassandraCodeMigrator output for required changes");
            result.addWarning("Cassandra driver v4 migration requires manual code changes");
            logger.warn("Cassandra driver detected - v4 migration required");
        }
    }

    /**
     * Check for Elasticsearch and warn about TransportClient deprecation.
     */
    private void checkElasticsearchVersion(Model model, MigrationPhaseResult result) {
        boolean hasElasticsearch = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                .anyMatch(dep -> "spring-boot-starter-data-elasticsearch".equals(dep.getArtifactId()));

        if (!hasElasticsearch) {
            hasElasticsearch = getDependenciesByGroupId(model, "org.elasticsearch.client").stream()
                    .anyMatch(dep -> dep.getArtifactId().contains("transport"));
        }

        if (hasElasticsearch) {
            result.addWarning("ELASTICSEARCH: TransportClient is deprecated, migrate to REST High Level Client");
            result.addWarning("ELASTICSEARCH: Review ElasticsearchCodeMigrator output for migration guide");
            result.addWarning("Elasticsearch REST client migration requires manual code changes");
            logger.warn("Elasticsearch detected - REST client migration recommended");
        }
    }

    @Override
    public String getPhaseName() {
        return "POM Migration (2.2→2.3)";
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
