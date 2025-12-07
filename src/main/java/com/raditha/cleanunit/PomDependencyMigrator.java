package com.raditha.cleanunit;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Handles Maven POM dependency updates for JUnit 4 to 5 migration.
 * 
 * Responsibilities:
 * - Remove JUnit 4 dependencies
 * - Add JUnit 5 dependencies
 * - Upgrade Mockito to version 3.x+ if needed
 * - Add mockito-junit-jupiter for JUnit 5 integration
 * - Verify/upgrade Surefire plugin to >= 2.22.0
 */
public class PomDependencyMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PomDependencyMigrator.class);

    private static final String JUNIT4_GROUP = "junit";
    private static final String JUNIT4_ARTIFACT = "junit";
    private static final String JUNIT5_GROUP = "org.junit.jupiter";
    private static final String JUNIT5_JUPITER = "junit-jupiter";
    private static final String JUNIT5_VERSION = "5.11.3";
    private static final String JUNIT_VINTAGE_GROUP = "org.junit.vintage";
    private static final String JUNIT_VINTAGE_ARTIFACT = "junit-vintage-engine";

    private static final String MOCKITO_GROUP = "org.mockito";
    private static final String MOCKITO_CORE = "mockito-core";
    private static final String MOCKITO_JUPITER = "mockito-junit-jupiter";
    private static final String MOCKITO_VERSION = "5.14.2";
    private static final int MOCKITO_MIN_MAJOR_VERSION = 5; // Upgrade to 5.x for JUnit 5 compatibility
    private static final String SUREFIRE_GROUP = "org.apache.maven.plugins";
    private static final String SUREFIRE_ARTIFACT = "maven-surefire-plugin";
    private static final String SUREFIRE_MIN_VERSION = "2.22.0";
    private static final String SUREFIRE_RECOMMENDED_VERSION = "3.2.5";

    private final boolean dryRun;
    private final List<String> changes = new ArrayList<>();

    public PomDependencyMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate POM dependencies from JUnit 4 to JUnit 5.
     * 
     * @return true if migrations were applied, false otherwise
     */
    public boolean migratePom() {
        Path pomPath = resolvePomPath();
        if (pomPath == null) {
            logger.warn("Could not find pom.xml");
            return false;
        }

        try {
            Model model = readPomModel(pomPath);
            boolean modified = false;

            // Check if JUnit 4 is present
            if (!hasJUnit4(model)) {
                logger.info("No JUnit 4 dependency found, skipping POM migration");
                return false;
            }

            if (!dryRun) {
                // Remove JUnit 4
                if (removeJUnit4(model)) {
                    changes.add("Removed: " + JUNIT4_GROUP + ":" + JUNIT4_ARTIFACT);
                    modified = true;
                }

                // Remove JUnit Vintage if present
                if (removeJUnitVintage(model)) {
                    changes.add("Removed: " + JUNIT_VINTAGE_GROUP + ":" + JUNIT_VINTAGE_ARTIFACT);
                    modified = true;
                }

                // Add JUnit 5
                if (addJUnit5(model)) {
                    changes.add("Added: " + JUNIT5_GROUP + ":" + JUNIT5_JUPITER + ":" + JUNIT5_VERSION);
                    modified = true;
                }

                // Upgrade Mockito if needed
                if (upgradeMockitoIfNeeded(model)) {
                    modified = true;
                }

                // Always add mockito-junit-jupiter if Mockito is present (for JUnit 5
                // integration)
                if (hasMockito(model) && addMockitoJupiter(model)) {
                    changes.add("Added: " + MOCKITO_GROUP + ":" + MOCKITO_JUPITER + ":" + MOCKITO_VERSION);
                    modified = true;
                }

                // Upgrade Surefire plugin if needed
                if (upgradeSurefireIfNeeded(model)) {
                    modified = true;
                }

                if (modified) {
                    writePomModel(pomPath, model);
                    logger.info("POM migration completed successfully");
                }
            } else {
                // Dry run mode - just report what would be done
                changes.add("Would remove: " + JUNIT4_GROUP + ":" + JUNIT4_ARTIFACT);

                if (hasJUnitVintage(model)) {
                    changes.add("Would remove: " + JUNIT_VINTAGE_GROUP + ":" + JUNIT_VINTAGE_ARTIFACT);
                }

                changes.add("Would add: " + JUNIT5_GROUP + ":" + JUNIT5_JUPITER + ":" + JUNIT5_VERSION);

                if (needsMockitoUpgrade(model)) {
                    changes.add("Would upgrade: " + MOCKITO_GROUP + ":" + MOCKITO_CORE + " to " + MOCKITO_VERSION);
                    changes.add("Would add: " + MOCKITO_GROUP + ":" + MOCKITO_JUPITER + ":" + MOCKITO_VERSION);
                }

                if (needsSurefireUpgrade(model)) {
                    changes.add("Would upgrade: " + SUREFIRE_GROUP + ":" + SUREFIRE_ARTIFACT + " to "
                            + SUREFIRE_RECOMMENDED_VERSION);
                }
                modified = true;
            }

            return modified;
        } catch (Exception e) {
            logger.error("Error migrating POM dependencies", e);
            return false;
        }
    }

    public List<String> getChanges() {
        return changes;
    }

    // Check if JUnit 4 is present (either junit:junit or junit-vintage-engine)
    private boolean hasJUnit4(Model model) {
        return model.getDependencies().stream()
                .anyMatch(dep -> (JUNIT4_GROUP.equals(dep.getGroupId()) &&
                        JUNIT4_ARTIFACT.equals(dep.getArtifactId())) ||
                        (JUNIT_VINTAGE_GROUP.equals(dep.getGroupId()) &&
                                JUNIT_VINTAGE_ARTIFACT.equals(dep.getArtifactId())));
    }

    // Remove JUnit 4 dependency
    private boolean removeJUnit4(Model model) {
        return model.getDependencies()
                .removeIf(dep -> JUNIT4_GROUP.equals(dep.getGroupId()) && JUNIT4_ARTIFACT.equals(dep.getArtifactId()));
    }

    // Check if JUnit Vintage is present
    private boolean hasJUnitVintage(Model model) {
        return model.getDependencies().stream()
                .anyMatch(dep -> JUNIT_VINTAGE_GROUP.equals(dep.getGroupId()) &&
                        JUNIT_VINTAGE_ARTIFACT.equals(dep.getArtifactId()));
    }

    // Remove JUnit Vintage engine dependency
    private boolean removeJUnitVintage(Model model) {
        return model.getDependencies()
                .removeIf(dep -> JUNIT_VINTAGE_GROUP.equals(dep.getGroupId()) &&
                        JUNIT_VINTAGE_ARTIFACT.equals(dep.getArtifactId()));
    }

    // Check if Mockito is present
    private boolean hasMockito(Model model) {
        return model.getDependencies().stream()
                .anyMatch(dep -> MOCKITO_GROUP.equals(dep.getGroupId()) &&
                        MOCKITO_CORE.equals(dep.getArtifactId()));
    }

    // Add JUnit 5 dependencies
    private boolean addJUnit5(Model model) {
        boolean added = false;

        // Check if junit-jupiter already present and upgrade if needed
        Dependency existingJupiter = model.getDependencies().stream()
                .filter(dep -> JUNIT5_GROUP.equals(dep.getGroupId()) &&
                        JUNIT5_JUPITER.equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (existingJupiter != null) {
            // Upgrade version if different
            String currentVersion = existingJupiter.getVersion();
            if (currentVersion != null && !currentVersion.equals(JUNIT5_VERSION)) {
                existingJupiter.setVersion(JUNIT5_VERSION);
                logger.info("Upgraded junit-jupiter from {} to {}", currentVersion, JUNIT5_VERSION);
                added = true;
            }
        } else {
            // Add junit-jupiter (aggregator dependency)
            Dependency junit5 = new Dependency();
            junit5.setGroupId(JUNIT5_GROUP);
            junit5.setArtifactId(JUNIT5_JUPITER);
            junit5.setVersion(JUNIT5_VERSION);
            junit5.setScope("test");
            model.addDependency(junit5);
            added = true;
        }

        // Check if junit-jupiter-engine exists and upgrade if needed
        Dependency existingEngine = model.getDependencies().stream()
                .filter(dep -> JUNIT5_GROUP.equals(dep.getGroupId()) &&
                        "junit-jupiter-engine".equals(dep.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (existingEngine != null) {
            // Upgrade version if different
            String currentVersion = existingEngine.getVersion();
            if (currentVersion != null && !currentVersion.equals(JUNIT5_VERSION)) {
                existingEngine.setVersion(JUNIT5_VERSION);
                logger.info("Upgraded junit-jupiter-engine from {} to {}", currentVersion, JUNIT5_VERSION);
                added = true;
            }
        } else {
            // Add junit-jupiter-engine for Surefire compatibility
            Dependency engine = new Dependency();
            engine.setGroupId(JUNIT5_GROUP);
            engine.setArtifactId("junit-jupiter-engine");
            engine.setVersion(JUNIT5_VERSION);
            engine.setScope("test");
            model.addDependency(engine);
            added = true;
        }

        if (!added) {
            logger.info("JUnit 5 already at version {}, skipping", JUNIT5_VERSION);
        }

        return added;
    }

    // Check if Mockito needs upgrade
    private boolean needsMockitoUpgrade(Model model) {
        for (Dependency dep : model.getDependencies()) {
            if (MOCKITO_GROUP.equals(dep.getGroupId()) && MOCKITO_CORE.equals(dep.getArtifactId())) {
                String version = dep.getVersion();
                if (version != null && !version.startsWith("${")) {
                    int majorVersion = getMajorVersion(version);
                    return majorVersion < MOCKITO_MIN_MAJOR_VERSION;
                }
            }
        }
        return false;
    }

    // Upgrade Mockito if version < 3.0
    private boolean upgradeMockitoIfNeeded(Model model) {
        boolean modified = false;

        for (Dependency dep : model.getDependencies()) {
            if (MOCKITO_GROUP.equals(dep.getGroupId()) && MOCKITO_CORE.equals(dep.getArtifactId())) {
                String version = dep.getVersion();
                if (version != null && !version.startsWith("${")) {
                    int majorVersion = getMajorVersion(version);
                    if (majorVersion < MOCKITO_MIN_MAJOR_VERSION) {
                        String oldVersion = version;
                        dep.setVersion(MOCKITO_VERSION);
                        changes.add("Upgraded: " + MOCKITO_GROUP + ":" + MOCKITO_CORE + " from " + oldVersion + " to "
                                + MOCKITO_VERSION);
                        modified = true;
                    }
                }
                break;
            }
        }

        return modified;
    }

    // Add mockito-junit-jupiter dependency
    private boolean addMockitoJupiter(Model model) {
        // Check if already present
        boolean hasMockitoJupiter = model.getDependencies().stream()
                .anyMatch(dep -> MOCKITO_GROUP.equals(dep.getGroupId()) &&
                        MOCKITO_JUPITER.equals(dep.getArtifactId()));

        if (hasMockitoJupiter) {
            return false;
        }

        Dependency mockitoJupiter = new Dependency();
        mockitoJupiter.setGroupId(MOCKITO_GROUP);
        mockitoJupiter.setArtifactId(MOCKITO_JUPITER);
        mockitoJupiter.setVersion(MOCKITO_VERSION);
        mockitoJupiter.setScope("test");

        model.addDependency(mockitoJupiter);
        return true;
    }

    // Check if Surefire needs upgrade
    private boolean needsSurefireUpgrade(Model model) {
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) {
            return true; // No plugin section, will need to add
        }

        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (SUREFIRE_GROUP.equals(plugin.getGroupId()) &&
                    SUREFIRE_ARTIFACT.equals(plugin.getArtifactId())) {
                String version = plugin.getVersion();
                if (version != null && !version.startsWith("${")) {
                    return compareVersions(version, SUREFIRE_MIN_VERSION) < 0;
                }
                return false; // Has plugin but version is property reference
            }
        }
        return true; // Plugin not found
    }

    // Upgrade Surefire plugin if version < 2.22.0
    private boolean upgradeSurefireIfNeeded(Model model) {
        if (model.getBuild() == null) {
            model.setBuild(new org.apache.maven.model.Build());
        }

        if (model.getBuild().getPlugins() == null) {
            model.getBuild().setPlugins(new ArrayList<>());
        }

        Plugin surefirePlugin = null;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (SUREFIRE_GROUP.equals(plugin.getGroupId()) &&
                    SUREFIRE_ARTIFACT.equals(plugin.getArtifactId())) {
                surefirePlugin = plugin;
                break;
            }
        }

        if (surefirePlugin == null) {
            // Add new plugin
            surefirePlugin = new Plugin();
            surefirePlugin.setGroupId(SUREFIRE_GROUP);
            surefirePlugin.setArtifactId(SUREFIRE_ARTIFACT);
            surefirePlugin.setVersion(SUREFIRE_RECOMMENDED_VERSION);
            model.getBuild().getPlugins().add(surefirePlugin);
            changes.add("Added: " + SUREFIRE_GROUP + ":" + SUREFIRE_ARTIFACT + ":" + SUREFIRE_RECOMMENDED_VERSION);
            return true;
        } else {
            String version = surefirePlugin.getVersion();
            if (version != null && !version.startsWith("${")) {
                if (compareVersions(version, SUREFIRE_MIN_VERSION) < 0) {
                    String oldVersion = version;
                    surefirePlugin.setVersion(SUREFIRE_RECOMMENDED_VERSION);
                    changes.add("Upgraded: " + SUREFIRE_GROUP + ":" + SUREFIRE_ARTIFACT + " from " + oldVersion + " to "
                            + SUREFIRE_RECOMMENDED_VERSION);
                    return true;
                }
            }
        }

        return false;
    }

    // Extract major version from version string
    private int getMajorVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            return Integer.parseInt(parts[0]);
        } catch (Exception e) {
            logger.warn("Could not parse version: " + version);
            return 0;
        }
    }

    // Compare two version strings
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
            // Handle versions like "2.22.0" or "3.2.5"
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Resolve path to pom.xml
    private Path resolvePomPath() {
        try {
            Path basePath = Paths.get(Settings.getBasePath());
            Path pomPath = basePath.resolve("pom.xml");

            if (!pomPath.toFile().exists()) {
                // Try parent directory
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

    // Read Maven POM model
    private Model readPomModel(Path pomPath) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomPath.toFile())) {
            return reader.read(fileReader);
        }
    }

    // Write the model back to pom.xml
    // Note: Maven XPP3 Writer uses fixed 2-space indentation by default
    private void writePomModel(Path pomPath, Model model) throws Exception {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        writer.setFileComment(null); // Don't add file comments

        try (FileWriter fileWriter = new FileWriter(pomPath.toFile())) {
            writer.write(fileWriter, model);
        }
    }
}
