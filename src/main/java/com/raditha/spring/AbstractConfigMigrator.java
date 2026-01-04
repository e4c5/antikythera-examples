package com.raditha.spring;

import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Abstract base class for configuration file migrators.
 * 
 * <p>
 * Provides common functionality for migrators that:
 * <ul>
 * <li>Find and parse YAML/properties configuration files</li>
 * <li>Modify configuration structures</li>
 * <li>Write updated configurations back to files</li>
 * </ul>
 * 
 * <p>
 * Example migrators:
 * <ul>
 * <li>H2ConfigurationMigrator - Add datasource configuration</li>
 * <li>JmxConfigDetector - Enable JMX configuration</li>
 * <li>LazyInitializationConfigurer - Configure lazy init</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public abstract class AbstractConfigMigrator extends MigrationPhase {

    protected AbstractConfigMigrator(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Find all application YAML files (application.yml, application.yaml).
     * 
     * @return list of YAML file paths
     * @throws IOException if I/O error occurs during file walking
     */
    protected List<Path> findApplicationYamlFiles() throws IOException {
        return PropertyFileUtils.findPropertyFiles(getBasePath(), "*.yml", "*.yaml");
    }

    /**
     * Find all application properties files (application.properties).
     * 
     * @return list of properties file paths
     * @throws IOException if I/O error occurs during file walking
     */
    protected List<Path> findApplicationPropertiesFiles() throws IOException {
        return PropertyFileUtils.findPropertyFiles(getBasePath(), "*.properties");
    }

    /**
     * Find a single application YAML file (prefers application.yml).
     * 
     * @return path to YAML file, or null if not found
     * @throws IOException if I/O error occurs during file walking
     */
    protected Path findApplicationYaml() throws IOException {
        Path basePath = getBasePath();
        if (basePath == null || !Files.exists(basePath)) {
            return null;
        }

        try (Stream<Path> paths = Files.walk(basePath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals("application.yml") ||
                                fileName.equals("application.yaml");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Get base path for project.
     * 
     * @return base path, or null if not initialized
     */
    protected Path getBasePath() {
        String basePathStr = Settings.getBasePath();
        return basePathStr != null ? Paths.get(basePathStr) : null;
    }

    /**
     * Migrate Logback properties in a YAML file.
     */
    protected boolean migrateYamlFile(Path yamlFile, MigrationPhaseResult result) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }

        if (data == null) {
            return false;
        }

        boolean modified = transformYamlData(data, result, yamlFile.getFileName().toString());

        if (modified && !dryRun) {
            try (OutputStream output = Files.newOutputStream(yamlFile)) {
                yaml.dump(data, new OutputStreamWriter(output));
            }
        }

        return modified;
    }

    boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String string) {
        throw new IllegalStateException("No implemented");
    }

}
