package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Migrates Spring Boot property files from 2.1 to 2.2 format.
 * 
 * Handles:
 * - YAML file transformations (nesting properties)
 * - .properties file transformations
 * - Smart forward headers strategy selection
 */
public class PropertyFileMigrator {
    private static final Logger logger = LoggerFactory.getLogger(PropertyFileMigrator.class);

    private final boolean dryRun;

    // Property mappings: old key -> new key + transformation strategy
    private static final Map<String, PropertyMapping> PROPERTY_MAPPINGS = Map.of(
            "logging.file", new PropertyMapping("logging.file.name", TransformationType.NEST),
            "logging.path", new PropertyMapping("logging.file.path", TransformationType.NEST),
            "server.connection-timeout",
            new PropertyMapping("server.tomcat.connection-timeout", TransformationType.NEST),
            "server.use-forward-headers",
            new PropertyMapping("server.forward-headers-strategy", TransformationType.VALUE_TRANSFORM));

    public PropertyFileMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate all property files in the project.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            Path basePath = Paths.get(Settings.getBasePath());

            // Find all property files
            List<Path> yamlFiles = findPropertyFiles(basePath, "*.yml", "*.yaml");
            List<Path> propFiles = findPropertyFiles(basePath, "*.properties");

            // Migrate YAML files
            for (Path yamlFile : yamlFiles) {
                migrateYamlFile(yamlFile, result);
            }

            // Migrate .properties files
            for (Path propFile : propFiles) {
                migratePropertiesFile(propFile, result);
            }

        } catch (Exception e) {
            logger.error("Error during property migration", e);
            result.addError("Property migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Find property files matching patterns.
     */
    private List<Path> findPropertyFiles(Path basePath, String... patterns) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(basePath)) {
            return files;
        }

        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        for (String pattern : patterns) {
                            String regex = pattern.replace("*", ".*");
                            if (fileName.matches(regex) && fileName.startsWith("application")) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .forEach(files::add);
        }

        return files;
    }

    /**
     * Migrate a YAML file.
     */
    @SuppressWarnings("unchecked")
    private void migrateYamlFile(Path yamlFile, MigrationPhaseResult result) {
        logger.info("Migrating YAML file: {}", yamlFile);

        try {
            Yaml yaml = createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null) {
                return;
            }

            boolean modified = transformYamlData(data, result, yamlFile.getFileName().toString());

            if (modified && !dryRun) {
                try (Writer writer = Files.newBufferedWriter(yamlFile)) {
                    yaml.dump(data, writer);
                }
                logger.info("Updated YAML file: {}", yamlFile);
            }

        } catch (Exception e) {
            result.addError("Failed to migrate " + yamlFile + ": " + e.getMessage());
            logger.error("Error migrating YAML file", e);
        }
    }

    /**
     * Transform YAML data structure.
     */
    @SuppressWarnings("unchecked")
    private boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        // Transform logging properties
        if (data.containsKey("logging")) {
            Map<String, Object> logging = (Map<String, Object>) data.get("logging");

            if (logging.containsKey("file") && logging.get("file") instanceof String) {
                String fileValue = (String) logging.remove("file");
                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", fileValue);
                logging.put("file", fileMap);

                result.addChange(fileName + ": logging.file → logging.file.name");
                modified = true;
            }

            if (logging.containsKey("path") && logging.get("path") instanceof String) {
                String pathValue = (String) logging.remove("path");
                Map<String, Object> fileMap = (Map<String, Object>) logging.computeIfAbsent("file",
                        k -> new HashMap<>());
                fileMap.put("path", pathValue);

                result.addChange(fileName + ": logging.path → logging.file.path");
                modified = true;
            }
        }

        // Transform server properties
        if (data.containsKey("server")) {
            Map<String, Object> server = (Map<String, Object>) data.get("server");

            if (server.containsKey("connection-timeout")) {
                Object timeoutValue = server.remove("connection-timeout");
                Map<String, Object> tomcat = (Map<String, Object>) server.computeIfAbsent("tomcat",
                        k -> new HashMap<>());
                tomcat.put("connection-timeout", timeoutValue);

                result.addChange(fileName + ": server.connection-timeout → server.tomcat.connection-timeout");
                modified = true;
            }

            if (server.containsKey("use-forward-headers")) {
                Object forwardHeaders = server.remove("use-forward-headers");
                String strategy = determineForwardHeadersStrategy(forwardHeaders, data);
                server.put("forward-headers-strategy", strategy);

                result.addChange(
                        fileName + ": server.use-forward-headers → server.forward-headers-strategy=" + strategy);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Determine forward headers strategy based on configuration context.
     */
    private String determineForwardHeadersStrategy(Object useForwardHeaders, Map<String, Object> data) {
        if (Boolean.FALSE.equals(useForwardHeaders) || "false".equals(useForwardHeaders)) {
            return "none";
        }

        // Auto-select based on server configuration
        // Default to "native" for servlet containers (most common case)
        return "native";
    }

    /**
     * Migrate a .properties file.
     */
    private void migratePropertiesFile(Path propFile, MigrationPhaseResult result) {
        logger.info("Migrating properties file: {}", propFile);

        try {
            Properties props = new Properties();

            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            boolean modified = transformProperties(props, result, propFile.getFileName().toString());

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Migrated to Spring Boot 2.2");
                }
                logger.info("Updated properties file: {}", propFile);
            }

        } catch (Exception e) {
            result.addError("Failed to migrate " + propFile + ": " + e.getMessage());
            logger.error("Error migrating properties file", e);
        }
    }

    /**
     * Transform properties.
     */
    private boolean transformProperties(Properties props, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        for (Map.Entry<String, PropertyMapping> entry : PROPERTY_MAPPINGS.entrySet()) {
            String oldKey = entry.getKey();
            PropertyMapping mapping = entry.getValue();

            if (props.containsKey(oldKey)) {
                String value = props.getProperty(oldKey);
                props.remove(oldKey);

                String newValue = value;
                if (mapping.type == TransformationType.VALUE_TRANSFORM && "server.use-forward-headers".equals(oldKey)) {
                    newValue = "true".equals(value) ? "native" : "none";
                }

                props.setProperty(mapping.newKey, newValue);
                result.addChange(fileName + ": " + oldKey + " → " + mapping.newKey);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Create YAML instance with proper configuration.
     */
    private Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }

    // Helper classes

    private static class PropertyMapping {
        final String newKey;
        final TransformationType type;

        PropertyMapping(String newKey, TransformationType type) {
            this.newKey = newKey;
            this.type = type;
        }
    }

    private enum TransformationType {
        NEST, // Property becomes nested
        VALUE_TRANSFORM // Value needs transformation
    }
}
