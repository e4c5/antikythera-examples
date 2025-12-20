package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates Spring Cloud version compatibility with Spring Boot 2.3.
 * 
 * <p>
 * Ensures Spring Cloud version is compatible:
 * <ul>
 * <li>Hoxton.SR8+ (for Spring Boot 2.2-2.3)</li>
 * <li>2020.0.x (Spring Cloud 2020.0.0+, recommended for Spring Boot 2.3+)</li>
 * </ul>
 * 
 * <p>
 * Incompatible versions:
 * <ul>
 * <li>Greenwich.x (only compatible with Spring Boot 2.1)</li>
 * <li>Hoxton.SR0-SR7 (compatibility issues with Spring Boot 2.3)</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class SpringCloudVersionMigrator extends MigrationPhase {

    private static final String MIN_HOXTON_VERSION = "Hoxton.SR8";
    private static final String RECOMMENDED_VERSION = "2020.0.3";


    public SpringCloudVersionMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            Path pomPath = resolvePomPath();
            if (pomPath == null) {
                result.addChange("No pom.xml found - Spring Cloud check skipped");
                return result;
            }

            Model model = readPomModel(pomPath);

            // Check if Spring Cloud is used
            if (model.getDependencyManagement() == null ||
                    model.getDependencyManagement().getDependencies() == null) {
                result.addChange("No dependency management found - Spring Cloud not used");
                return result;
            }

            Dependency springCloudBom = model.getDependencyManagement().getDependencies().stream()
                    .filter(dep -> "org.springframework.cloud".equals(dep.getGroupId()) &&
                            "spring-cloud-dependencies".equals(dep.getArtifactId()))
                    .findFirst()
                    .orElse(null);

            if (springCloudBom == null) {
                result.addChange("Spring Cloud not used in project");
                return result;
            }

            // Validate version
            String version = springCloudBom.getVersion();
            if (version == null || version.startsWith("${")) {
                result.addWarning("Spring Cloud version is a property reference: " + version);
                result.addWarning("Verify Spring Cloud version property is compatible with Spring Boot 2.3");
                return result;
            }

            validateVersion(version, result);

        } catch (Exception e) {
            logger.error("Error validating Spring Cloud version", e);
            result.addError("Spring Cloud validation failed: " + e.getMessage());
        }

        return result;
    }

    private void validateVersion(String version, MigrationPhaseResult result) {
        if (version.startsWith("Greenwich")) {
            result.addError("INCOMPATIBLE: Spring Cloud " + version + " does NOT work with Spring Boot 2.3");
            result.addError("Must upgrade to " + MIN_HOXTON_VERSION + " or " + RECOMMENDED_VERSION);
            result.addWarning("CRITICAL: Upgrade Spring Cloud to compatible version");
            logger.error("Incompatible Spring Cloud version: {}", version);
        } else if (version.startsWith("Hoxton")) {
            if (compareHoxtonVersions(version, MIN_HOXTON_VERSION) < 0) {
                result.addWarning("Spring Cloud " + version + " may have compatibility issues with Spring Boot 2.3");
                result.addWarning("Recommend upgrading to " + MIN_HOXTON_VERSION + " or later");
                result.addWarning("Consider upgrading Spring Cloud to " + RECOMMENDED_VERSION);
                logger.warn("Spring Cloud version may be incompatible: {}", version);
            } else {
                result.addChange("Spring Cloud " + version + " is compatible with Spring Boot 2.3 ✓");
                logger.info("Spring Cloud version is compatible: {}", version);
            }
        } else if (version.startsWith("2020.0")) {
            result.addChange("Spring Cloud " + version + " is fully compatible with Spring Boot 2.3 ✓");
            logger.info("Spring Cloud 2020.0.x is recommended version: {}", version);
        } else if (version.startsWith("2021.0") || version.startsWith("2022.0")) {
            result.addWarning("Spring Cloud " + version + " is designed for Spring Boot 2.6+");
            result.addWarning("May work with Spring Boot 2.3 but not officially supported");
            logger.warn("Newer Spring Cloud version detected: {}", version);
        } else {
            result.addWarning("Unknown Spring Cloud version pattern: " + version);
            result.addWarning("Verify Spring Cloud " + version + " compatibility with Spring Boot 2.3");
            logger.warn("Unknown Spring Cloud version: {}", version);
        }
    }

    private int compareHoxtonVersions(String v1, String v2) {
        // Extract SR number from "Hoxton.SRx"
        int sr1 = extractSRNumber(v1);
        int sr2 = extractSRNumber(v2);
        return Integer.compare(sr1, sr2);
    }

    private int extractSRNumber(String version) {
        try {
            // Parse "Hoxton.SR8" -> 8
            if (version.contains(".SR")) {
                String srPart = version.substring(version.indexOf(".SR") + 3);
                return Integer.parseInt(srPart.replaceAll("[^0-9]", ""));
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Path resolvePomPath() {
        try {
            // Check if Settings is initialized
            if (Settings.getBasePath() == null) {
                logger.warn("Settings not initialized, cannot resolve POM path");
                return null;
            }

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

    @Override
    public String getPhaseName() {
        return "Spring Cloud Version Migration";
    }

    @Override
    public int getPriority() {
        return 25;
    }
}
