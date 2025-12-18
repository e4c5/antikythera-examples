package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handles Maven POM migrations specific to Spring Boot 2.1 to 2.2 upgrade.
 * 
 * Responsibilities:
 * - Update Spring Boot parent version from 2.1.x to 2.2.13.RELEASE
 * - Migrate javax.mail to jakarta.mail
 * - Validate kafka-clients version (≥ 2.3.0 required)
 * - Auto-upgrade kafka-clients if needed
 */
public class SpringBootPomMigrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBootPomMigrator.class);

    private static final String TARGET_SPRING_BOOT_VERSION = "2.2.13.RELEASE";
    private static final String MIN_KAFKA_CLIENTS_VERSION = "2.3.0";

    private final boolean dryRun;

    public SpringBootPomMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Execute Spring Boot specific POM migrations.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path pomPath = resolvePomPath();
        if (pomPath == null) {
            result.addError("Could not find pom.xml");
            return result;
        }

        try {
            Model model = readPomModel(pomPath);
            boolean modified = false;

            // Update Spring Boot parent version
            if (updateSpringBootParent(model, result)) {
                modified = true;
            }

            // Migrate Jakarta dependencies
            if (migrateJakartaDependencies(model, result)) {
                modified = true;
            }

            // Validate and upgrade Kafka version if needed
            validateKafkaClientVersion(model, result);

            if (modified && !dryRun) {
                writePomModel(pomPath, model);
                logger.info("POM migration completed successfully");
            }

        } catch (Exception e) {
            logger.error("Error during POM migration", e);
            result.addError("POM migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Update Spring Boot parent version to 2.2.13.RELEASE.
     */
    private boolean updateSpringBootParent(Model model, MigrationPhaseResult result) {
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
        if (currentVersion.startsWith("2.2.")) {
            logger.info("Spring Boot parent already at 2.2.x: {}", currentVersion);
            return false;
        }

        if (dryRun) {
            result.addChange(String.format("Would update Spring Boot parent: %s → %s",
                    currentVersion, TARGET_SPRING_BOOT_VERSION));
        } else {
            parent.setVersion(TARGET_SPRING_BOOT_VERSION);
            result.addChange(String.format("Updated Spring Boot parent: %s → %s",
                    currentVersion, TARGET_SPRING_BOOT_VERSION));
            logger.info("Updated Spring Boot parent version to {}", TARGET_SPRING_BOOT_VERSION);
        }

        return true;
    }

    /**
     * Migrate Java Mail dependency from javax.mail to jakarta.mail.
     */
    private boolean migrateJakartaDependencies(Model model, MigrationPhaseResult result) {
        boolean modified = false;

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
                // Remove old dependency
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
        // Check for kafka-clients direct dependency
        Dependency kafkaClients = model.getDependencies().stream()
                .filter(dep -> "org.apache.kafka".equals(dep.getGroupId()) &&
                        "kafka-clients".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (kafkaClients != null) {
            String version = kafkaClients.getVersion();
            if (version != null && !version.startsWith("${")) {
                if (compareVersions(version, MIN_KAFKA_CLIENTS_VERSION) < 0) {
                    result.addWarning(String.format(
                            "kafka-clients version %s is below required %s for Spring Boot 2.2",
                            version, MIN_KAFKA_CLIENTS_VERSION));
                    result.addWarning("Spring Kafka 2.3+ requires kafka-clients 2.3.0+");
                } else {
                    logger.info("kafka-clients version {} is compatible", version);
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

    // Utility methods

    private Path resolvePomPath() {
        try {
            Path basePath = Paths.get(Settings.getBasePath());
            Path pomPath = basePath.resolve("pom.xml");

            if (!pomPath.toFile().exists()) {
                pomPath = basePath.getParent().resolve("pom.xml");
            }

            if (pomPath.toFile().exists()) {
                return pomPath;
            }
        } catch (Exception e) {
            logger.error("Error resolving POM path", e);
        }

        return null;
    }

    private Model readPomModel(Path pomPath) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomPath.toFile())) {
            return reader.read(fileReader);
        }
    }

    private void writePomModel(Path pomPath, Model model) throws IOException {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(pomPath.toFile())) {
            writer.write(fileWriter, model);
        }
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }

        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
