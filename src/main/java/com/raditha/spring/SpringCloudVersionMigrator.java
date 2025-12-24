package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.nio.file.Path;

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

    private final MavenHelper mavenHelper = new MavenHelper();

    public SpringCloudVersionMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path pomPath = mavenHelper.getPomPath();
        if (pomPath == null || !pomPath.toFile().exists()) {
            result.addChange("No pom.xml found - Spring Cloud check skipped");
            return result;
        }

        Model model = mavenHelper.readPomFile();

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

        return result;
    }

    private void validateVersion(String version, MigrationPhaseResult result) {
        if (version.startsWith("Greenwich")) {
            result.addError("INCOMPATIBLE: Spring Cloud " + version + " does NOT work with Spring Boot 2.3");
            result.addError("Must upgrade to " + MIN_HOXTON_VERSION + " or " + RECOMMENDED_VERSION);
            result.addWarning("CRITICAL: Upgrade Spring Cloud to compatible version");
        } else if (version.startsWith("Hoxton")) {
            if (compareHoxtonVersions(version, MIN_HOXTON_VERSION) < 0) {
                result.addWarning("Spring Cloud " + version + " may have compatibility issues with Spring Boot 2.3");
                result.addWarning("Recommend upgrading to " + MIN_HOXTON_VERSION + " or later");
                result.addWarning("Consider upgrading Spring Cloud to " + RECOMMENDED_VERSION);
            } else {
                result.addChange("Spring Cloud " + version + " is compatible with Spring Boot 2.3 ✓");
            }
        } else if (version.startsWith("2020.0")) {
            result.addChange("Spring Cloud " + version + " is fully compatible with Spring Boot 2.3 ✓");
        } else if (version.startsWith("2021.0") || version.startsWith("2022.0")) {
            result.addWarning("Spring Cloud " + version + " is designed for Spring Boot 2.6+");
            result.addWarning("May work with Spring Boot 2.3 but not officially supported");
        } else {
            result.addWarning("Unknown Spring Cloud version pattern: " + version);
            result.addWarning("Verify Spring Cloud " + version + " compatibility with Spring Boot 2.3");
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

    @Override
    public String getPhaseName() {
        return "Spring Cloud Version Migration";
    }

    @Override
    public int getPriority() {
        return 25;
    }
}
