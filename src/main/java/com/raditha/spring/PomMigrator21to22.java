package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

/**
 * POM migrator for Spring Boot 2.1 to 2.2 upgrade.
 * 
 * <p>
 * Handles version-specific dependency migrations:
 * <ul>
 * <li>Jakarta Mail migration (javax.mail → com.sun.mail:jakarta.mail)</li>
 * <li>Kafka clients version validation (≥ 2.3.0 required)</li>
 * <li>Spring Cloud upgrade (Greenwich → Hoxton.SR12)</li>
 * <li>ShedLock version synchronization</li>
 * <li>Springfox upgrade to 3.0.0</li>
 * <li>spring-boot-properties-migrator addition</li>
 * </ul>
 * 
 * @see AbstractPomMigrator
 */
public class PomMigrator21to22 extends AbstractPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomMigrator21to22.class);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.2.13.RELEASE";
    private static final String MIN_KAFKA_CLIENTS_VERSION = "2.3.0";
    private static final String TARGET_SPRING_CLOUD_VERSION = "Hoxton.SR12";
    private static final String TARGET_SPRINGFOX_VERSION = "3.0.0";

    /**
     * Constructor.
     * 
     * @param dryRun if true, no files will be modified
     */
    public PomMigrator21to22(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Migrate Jakarta dependencies
        if (migrateJakartaDependencies(model, result)) {
            logger.info("Jakarta dependencies migrated");
        }

        // Upgrade Spring Cloud from Greenwich to Hoxton
        if (upgradeSpringCloud(model, result)) {
            logger.info("Spring Cloud upgraded");
        }

        // Sync ShedLock versions
        if (syncShedLockVersions(model, result)) {
            logger.info("ShedLock versions synchronized");
        }

        // Upgrade Springfox to 3.0.0
        if (upgradeSpringfox(model, result)) {
            logger.info("Springfox upgraded");
        }

        // Add spring-boot-properties-migrator
        if (addPropertiesMigrator(model, result)) {
            logger.info("Properties migrator added");
        }
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Validate kafka-clients version
        validateKafkaClientVersion(model, result);
    }

    /**
     * Migrate Java Mail dependency from javax.mail to jakarta.mail.
     */
    private boolean migrateJakartaDependencies(Model model, MigrationPhaseResult result) {
        boolean modified = false;

        // Check for javax.mail dependency
        Dependency javaxMail = findDependency(model, "javax.mail", "javax.mail-api");

        if (javaxMail != null) {
            if (dryRun) {
                result.addChange("Would migrate: javax.mail:javax.mail-api → com.sun.mail:jakarta.mail");
            } else {
                migrateJavaXMail(model, result, javaxMail);
                logger.info("Migrated javax.mail to jakarta.mail");
            }
            modified = true;
        }

        return modified;
    }

    /**
     * Validate kafka-clients version and warn if incompatible.
     */
    private void validateKafkaClientVersion(Model model, MigrationPhaseResult result) {
        Dependency kafkaClients = findDependency(model, "org.apache.kafka", "kafka-clients");

        migrateKafka(result, kafkaClients);
    }

    private static void migrateKafka(MigrationPhaseResult result, Dependency kafkaClients) {
        if (kafkaClients != null) {
            String version = kafkaClients.getVersion();
            if (version != null && !version.startsWith("${")) {
                 if (MavenHelper.compareVersions(version, MIN_KAFKA_CLIENTS_VERSION) < 0) {
                    result.addWarning(String.format(
                            "kafka-clients version %s is below required %s for Spring Boot 2.2",
                            version, MIN_KAFKA_CLIENTS_VERSION));
                    result.addWarning("Spring Kafka 2.3+ requires kafka-clients 2.3.0+");
                }
            }
        }
    }

    /**
     * Upgrade Spring Cloud from Greenwich to Hoxton.
     */
    private boolean upgradeSpringCloud(Model model, MigrationPhaseResult result) {
        // Check for Spring Cloud BOM in dependencyManagement
        if (model.getDependencyManagement() == null ||
                model.getDependencyManagement().getDependencies() == null) {
            return false;
        }

        Dependency springCloudBom = model.getDependencyManagement().getDependencies().stream()
                .filter(dep -> "org.springframework.cloud".equals(dep.getGroupId()) &&
                        "spring-cloud-dependencies".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (springCloudBom != null) {
            String currentVersion = springCloudBom.getVersion();
            if (currentVersion != null && currentVersion.startsWith("Greenwich")) {
                if (dryRun) {
                    result.addChange(String.format("Would upgrade Spring Cloud: %s → %s",
                            currentVersion, TARGET_SPRING_CLOUD_VERSION));
                } else {
                    springCloudBom.setVersion(TARGET_SPRING_CLOUD_VERSION);
                    result.addChange(String.format("Upgraded Spring Cloud: %s → %s",
                            currentVersion, TARGET_SPRING_CLOUD_VERSION));
                    logger.info("Upgraded Spring Cloud to {}", TARGET_SPRING_CLOUD_VERSION);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Sync ShedLock versions across multiple modules.
     */
    private boolean syncShedLockVersions(Model model, MigrationPhaseResult result) {
        java.util.List<Dependency> shedLockDeps = getDependenciesByGroupId(model, "net.javacrumbs.shedlock");

        if (shedLockDeps.isEmpty()) {
            return false;
        }

        // Find the highest version
        String highestVersion = shedLockDeps.stream()
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.startsWith("${"))
                .max(MavenHelper::compareVersions)
                .orElse(null);

        if (highestVersion == null) {
            return false;
        }

        boolean modified = false;
        for (Dependency dep : shedLockDeps) {
            String version = dep.getVersion();
            if (version != null && !version.equals(highestVersion) && !version.startsWith("${")) {
                if (dryRun) {
                    result.addChange(String.format("Would sync ShedLock version: %s:%s %s → %s",
                            dep.getGroupId(), dep.getArtifactId(), version, highestVersion));
                } else {
                    dep.setVersion(highestVersion);
                    result.addChange(String.format("Synced ShedLock version: %s:%s %s → %s",
                            dep.getGroupId(), dep.getArtifactId(), version, highestVersion));
                }
                modified = true;
            }
        }

        if (modified) {
            logger.info("Synchronized ShedLock versions to {}", highestVersion);
        }

        return modified;
    }

    /**
     * Upgrade Springfox to 3.0.0 for Spring Boot 2.2 compatibility.
     */
    private boolean upgradeSpringfox(Model model, MigrationPhaseResult result) {
        java.util.List<Dependency> springfoxDeps = getDependenciesByGroupId(model, "io.springfox");

        if (springfoxDeps.isEmpty()) {
            return false;
        }

        boolean modified = false;
        for (Dependency dep : springfoxDeps) {
            String version = dep.getVersion();
            if (version != null && !version.startsWith("${") && !version.startsWith("3.")) {
                if (dryRun) {
                    result.addChange(String.format("Would upgrade Springfox: %s:%s %s → %s",
                            dep.getGroupId(), dep.getArtifactId(), version, TARGET_SPRINGFOX_VERSION));
                } else {
                    dep.setVersion(TARGET_SPRINGFOX_VERSION);
                    result.addChange(String.format("Upgraded Springfox: %s:%s %s → %s",
                            dep.getGroupId(), dep.getArtifactId(), version, TARGET_SPRINGFOX_VERSION));
                }
                modified = true;
            }
        }

        if (modified) {
            logger.info("Upgraded Springfox to {}", TARGET_SPRINGFOX_VERSION);
        }

        return modified;
    }

    /**
     * Add spring-boot-properties-migrator to help detect deprecated properties.
     */
    private boolean addPropertiesMigrator(Model model, MigrationPhaseResult result) {
        return addDependency(model, "org.springframework.boot",
                "spring-boot-properties-migrator", "runtime", result);
    }

    @Override
    public String getPhaseName() {
        return "POM Migration (2.1→2.2)";
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
