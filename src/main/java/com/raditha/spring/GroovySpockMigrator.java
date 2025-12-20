package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Upgrades Groovy and Spock versions for Spring Boot 2.5.
 * 
 * <p>
 * Spring Boot 2.5 changes:
 * <ul>
 * <li>Groovy upgraded from 2.5.x to 3.0.x</li>
 * <li>Spock 1.x is incompatible with Groovy 3.x</li>
 * <li>Spock 2.0+ required for Groovy 3.x compatibility</li>
 * </ul>
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects Spock dependency usage</li>
 * <li>Checks current Spock version</li>
 * <li>Upgrades Groovy to 3.0.8 and Spock to 2.0-groovy-3.0</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class GroovySpockMigrator extends AbstractConfigMigrator {
    private static final Logger logger = LoggerFactory.getLogger(GroovySpockMigrator.class);

    private static final String TARGET_GROOVY_VERSION = "3.0.8";
    private static final String TARGET_SPOCK_VERSION = "2.0-groovy-3.0";

    public GroovySpockMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path basePath = Paths.get(Settings.getBasePath());
        Path pomPath = basePath.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            result.addChange("No pom.xml found - skipping Groovy/Spock migration");
            return result;
        }

        // Read POM
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model;
        try (InputStream input = Files.newInputStream(pomPath)) {
            model = reader.read(input);
        }

        // Check for Spock dependency
        boolean hasSpock = model.getDependencies().stream()
                .anyMatch(dep -> "org.spockframework".equals(dep.getGroupId()));

        if (!hasSpock) {
            result.addChange("No Spock dependency detected - no Groovy/Spock migration needed");
            return result;
        }

        Properties props = model.getProperties();
        boolean modified = false;

        // Upgrade Groovy version
        String currentGroovyVersion = props.getProperty("groovy.version");
        if (currentGroovyVersion != null && !currentGroovyVersion.startsWith("3.")) {
            props.setProperty("groovy.version", TARGET_GROOVY_VERSION);
            modified = true;
            result.addChange(String.format("Updated groovy.version: %s → %s",
                    currentGroovyVersion, TARGET_GROOVY_VERSION));
            logger.info("Upgraded Groovy version from {} to {}", currentGroovyVersion, TARGET_GROOVY_VERSION);
        } else if (currentGroovyVersion != null) {
            result.addChange("Groovy 3.x already configured");
        }

        // Upgrade Spock version
        String currentSpockVersion = props.getProperty("spock.version");
        if (currentSpockVersion != null && 
                (currentSpockVersion.startsWith("1.") || currentSpockVersion.contains("groovy-2"))) {
            props.setProperty("spock.version", TARGET_SPOCK_VERSION);
            modified = true;
            result.addChange(String.format("Updated spock.version: %s → %s",
                    currentSpockVersion, TARGET_SPOCK_VERSION));
            logger.info("Upgraded Spock version from {} to {}", currentSpockVersion, TARGET_SPOCK_VERSION);
        } else if (currentSpockVersion != null && currentSpockVersion.startsWith("2.")) {
            result.addChange("Spock 2.x already configured");
        }

        // Update Spock dependencies if they have hardcoded versions
        for (Dependency dep : model.getDependencies()) {
            if ("org.spockframework".equals(dep.getGroupId())) {
                String version = dep.getVersion();
                if (version != null && !version.contains("${") && 
                        (version.startsWith("1.") || version.contains("groovy-2"))) {
                    dep.setVersion(TARGET_SPOCK_VERSION);
                    modified = true;
                    result.addChange(String.format("Updated %s dependency: %s → %s",
                            dep.getArtifactId(), version, TARGET_SPOCK_VERSION));
                }
            }
        }

        // Write updated POM
        if (modified && !dryRun) {
            MavenXpp3Writer writer = new MavenXpp3Writer();
            try (OutputStream output = Files.newOutputStream(pomPath)) {
                writer.write(output, model);
            }
        }

        if (modified) {
            result.addWarning("IMPORTANT: Run tests to verify Spock 2.0 compatibility");
            result.addWarning("Spock 2.0 breaking changes: https://spockframework.org/spock/docs/2.0/migration_guide.html");
            result.addWarning("Common issues: @Unroll now requires explicit test names");
        }

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Groovy/Spock Version Upgrade";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
