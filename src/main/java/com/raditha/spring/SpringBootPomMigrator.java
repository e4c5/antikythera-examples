package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

/**
 * Handles Maven POM migrations specific to Spring Boot 2.1 to 2.2 upgrade.
 * 
 * <p>
 * Extends {@link AbstractPomMigrator} and implements version-specific dependency rules:
 * <ul>
 * <li>Update Spring Boot parent version from 2.1.x to 2.2.13.RELEASE</li>
 * <li>Migrate javax.mail to jakarta.mail</li>
 * <li>Validate kafka-clients version (≥ 2.3.0 required)</li>
 * <li>Upgrade Spring Cloud from Greenwich to Hoxton</li>
 * <li>Sync ShedLock versions</li>
 * <li>Upgrade Springfox to 3.0.0</li>
 * <li>Add spring-boot-properties-migrator for validation</li>
 * </ul>
 *
 * @see AbstractPomMigrator
 */
public class SpringBootPomMigrator extends AbstractPomMigrator {

    private static final String TARGET_SPRING_BOOT_VERSION = "2.2.13.RELEASE";
    private static final String MIN_KAFKA_CLIENTS_VERSION = "2.3.0";
    private static final String TARGET_SPRING_CLOUD_VERSION = "Hoxton.SR12";
    private static final String TARGET_SPRINGFOX_VERSION = "3.0.0";
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
    private static final String SPRINGFOX_GROUP_ID = "io.springfox";

    public SpringBootPomMigrator(boolean dryRun) {
        super(TARGET_SPRING_BOOT_VERSION, dryRun);
    }

    @Override
    protected void applyVersionSpecificDependencyRules(Model model, MigrationPhaseResult result) {
        // Migrate Jakarta dependencies
        migrateJakartaDependencies(model, result);

        // Upgrade Spring Cloud from Greenwich to Hoxton
        upgradeSpringCloud(model, result);

        // Sync ShedLock versions
        syncShedLockVersions(model, result);

        // Upgrade Springfox to 3.0.0
        upgradeSpringfox(model, result);

        // Add spring-boot-properties-migrator for validation
        addPropertiesMigrator(model, result);
    }

    @Override
    protected void validateVersionSpecificRequirements(Model model, MigrationPhaseResult result) {
        // Validate and upgrade Kafka version if needed
        validateKafkaClientVersion(model, result);
    }

    /**
     * Migrate Java Mail dependency from javax.mail to jakarta.mail.
     */
    private void migrateJakartaDependencies(Model model, MigrationPhaseResult result) {
        // Check for javax.mail dependency
        Dependency javaxMail = model.getDependencies().stream()
                .filter(dep -> "javax.mail".equals(dep.getGroupId()) &&
                        "javax.mail-api".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (javaxMail != null) {
            if (dryRun) {
                result.addChange("Would migrate: javax.mail:javax.mail-api → com.sun.mail:jakarta.mail");
            } else {
                migrateJavaXMail(model, result, javaxMail);
            }
        }
    }

    /**
     * Validate kafka-clients version and warn if incompatible.
     */
    private void validateKafkaClientVersion(Model model, MigrationPhaseResult result) {
        // Check for kafka-clients direct dependency
        Dependency kafkaClients = model.getDependencies().stream()
                .filter(dep -> "org.apache.kafka".equals(dep.getGroupId()) &&
                        "kafka-clients".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

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

        // Check for spring-kafka dependency (indicates Kafka usage)
        boolean hasSpringKafka = model.getDependencies().stream()
                .anyMatch(dep -> "org.springframework.kafka".equals(dep.getGroupId()) &&
                        "spring-kafka".equals(dep.getArtifactId()));

        if (hasSpringKafka && kafkaClients == null) {
            result.addWarning("spring-kafka dependency found but kafka-clients version not explicit");
            result.addWarning("Spring Boot 2.2 uses kafka-clients 2.3.x - verify broker compatibility");
        }
    }

    /**
     * Upgrade Spring Cloud from Greenwich to Hoxton.SR12.
     */
    private void upgradeSpringCloud(Model model, MigrationPhaseResult result) {
        // Check for spring-cloud.version property
        String currentVersion = model.getProperties().getProperty("spring-cloud.version");
        
        if (currentVersion == null) {
            return;
        }

        // Check if it's Greenwich (incompatible with Spring Boot 2.2)
        if (currentVersion.contains("Greenwich")) {
            if (dryRun) {
                result.addChange(String.format("Would upgrade Spring Cloud: %s → %s",
                        currentVersion, TARGET_SPRING_CLOUD_VERSION));
            } else {
                model.getProperties().setProperty("spring-cloud.version", TARGET_SPRING_CLOUD_VERSION);
                result.addChange(String.format("Upgraded Spring Cloud: %s → %s",
                        currentVersion, TARGET_SPRING_CLOUD_VERSION));
            }
        }
    }

    /**
     * Sync all ShedLock dependencies to the same version.
     */
    private void syncShedLockVersions(Model model, MigrationPhaseResult result) {
        // Find all ShedLock dependencies
        java.util.List<Dependency> shedlockDeps = model.getDependencies().stream()
                .filter(dep -> "net.javacrumbs.shedlock".equals(dep.getGroupId()))
                .toList();

        if (shedlockDeps.isEmpty()) {
            return;
        }

        // Check for version mismatches
        java.util.Set<String> versions = shedlockDeps.stream()
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.startsWith("${"))
                .collect(java.util.stream.Collectors.toSet());

        if (versions.size() <= 1) {
            return; // All same version or using property
        }

        // Find the highest version to sync to
        String targetVersion = versions.stream()
                .max(MavenHelper::compareVersions)
                .orElse(null);

        if (targetVersion == null) {
            return;
        }

        if (dryRun) {
            result.addChange(String.format("Would sync ShedLock versions to %s", targetVersion));
        } else {
            for (Dependency dep : shedlockDeps) {
                if (dep.getVersion() != null && !dep.getVersion().startsWith("${")) {
                    dep.setVersion(targetVersion);
                }
            }
            result.addChange(String.format("Synced %d ShedLock dependencies to version %s",
                    shedlockDeps.size(), targetVersion));
        }
    }

    /**
     * Upgrade Springfox from 2.x to 3.0.0.
     * Also suggests SpringDoc OpenAPI as modern alternative.
     */
    private void upgradeSpringfox(Model model, MigrationPhaseResult result) {

        // Find Springfox dependencies
        java.util.List<Dependency> springfoxDeps = model.getDependencies().stream()
                .filter(dep -> SPRINGFOX_GROUP_ID.equals(dep.getGroupId()))
                .toList();

        for (Dependency dep : springfoxDeps) {
            String version = dep.getVersion();
            if (version != null && !version.startsWith("${") && version.startsWith("2.")) {
                if (dryRun) {
                    result.addChange(String.format("Would upgrade %s: %s → %s",
                            dep.getArtifactId(), version, TARGET_SPRINGFOX_VERSION));
                } else {
                    dep.setVersion(TARGET_SPRINGFOX_VERSION);
                    result.addChange(String.format("Upgraded %s: %s → %s",
                            dep.getArtifactId(), version, TARGET_SPRINGFOX_VERSION));
                }
            }
        }

        // Check if we need to replace swagger2 with boot-starter
        Dependency swagger2 = model.getDependencies().stream()
                .filter(dep -> SPRINGFOX_GROUP_ID.equals(dep.getGroupId()) &&
                        "springfox-swagger2".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        Dependency swaggerUi = model.getDependencies().stream()
                .filter(dep -> SPRINGFOX_GROUP_ID.equals(dep.getGroupId()) &&
                        "springfox-swagger-ui".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (swagger2 != null && swaggerUi != null) {
            if (dryRun) {
                result.addChange("Would replace springfox-swagger2 + springfox-swagger-ui with springfox-boot-starter");
            } else {
                // Remove both and add springfox-boot-starter
                model.getDependencies().remove(swagger2);
                model.getDependencies().remove(swaggerUi);

                Dependency bootStarter = new Dependency();
                bootStarter.setGroupId(SPRINGFOX_GROUP_ID);
                bootStarter.setArtifactId("springfox-boot-starter");
                bootStarter.setVersion(TARGET_SPRINGFOX_VERSION);
                model.addDependency(bootStarter);

                result.addChange("Replaced springfox-swagger2 + springfox-swagger-ui with springfox-boot-starter");
            }
        }

        // Suggest SpringDoc OpenAPI as modern alternative
        if (!springfoxDeps.isEmpty()) {
            result.addWarning("💡 Consider migrating from Springfox to SpringDoc OpenAPI (more modern, actively maintained)");
            result.addWarning("   SpringDoc dependency: org.springdoc:springdoc-openapi-ui:1.6.15");
            result.addWarning("   SpringDoc offers better Spring Boot integration and OpenAPI 3.0 support");
            result.addWarning("   Migration guide: https://springdoc.org/#migrating-from-springfox");
        }
    }

    /**
     * Add spring-boot-properties-migrator dependency for validation.
     * This dependency helps detect deprecated properties at runtime.
     */
    private void addPropertiesMigrator(Model model, MigrationPhaseResult result) {
        // Check if dependency already exists
        boolean hasPropertiesMigrator = model.getDependencies().stream()
                .anyMatch(dep -> SPRING_BOOT_GROUP_ID.equals(dep.getGroupId()) &&
                        "spring-boot-properties-migrator".equals(dep.getArtifactId()));

        if (hasPropertiesMigrator) {
            result.addChange("spring-boot-properties-migrator already present");
            return;
        }

        if (dryRun) {
            result.addChange("Would add spring-boot-properties-migrator dependency (scope: runtime)");
        } else {
            Dependency propertiesMigrator = new Dependency();
            propertiesMigrator.setGroupId(SPRING_BOOT_GROUP_ID);
            propertiesMigrator.setArtifactId("spring-boot-properties-migrator");
            propertiesMigrator.setScope("runtime");
            model.addDependency(propertiesMigrator);

            result.addChange("Added spring-boot-properties-migrator (scope: runtime) - helps detect deprecated properties");
            result.addWarning("Remember to remove spring-boot-properties-migrator after migration validation is complete");
        }
    }

    @Override
    public String getPhaseName() {
        return "POM Migration";
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
