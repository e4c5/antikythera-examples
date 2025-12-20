package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Abstract base class for Spring Boot property file migrations.
 * 
 * <p>
 * Provides common functionality for migrating property files across Spring Boot
 * versions:
 * <ul>
 * <li>Finding property files (application*.yml, application*.properties)</li>
 * <li>Transforming YAML structures</li>
 * <li>Transforming .properties files</li>
 * <li>Handling property mappings and value transformations</li>
 * </ul>
 * 
 * <p>
 * Subclasses configure version-specific property mappings via constructor:
 * 
 * <pre>{@code
 * public class PropertyMigrator22to23 extends AbstractPropertyFileMigrator {
 *     private static final Map<String, PropertyMapping> MAPPINGS = Map.of(
 *             "spring.http.encoding.charset",
 *             new PropertyMapping("server.servlet.encoding.charset", TransformationType.NEST),
 *             "spring.http.encoding.enabled",
 *             new PropertyMapping("server.servlet.encoding.enabled", TransformationType.NEST));
 * 
 *     public PropertyMigrator22to23(boolean dryRun) {
 *         super(dryRun, MAPPINGS);
 *     }
 * }
 * }</pre>
 * 
 * @see MigrationPhase
 * @see MigrationPhaseResult
 */
public abstract class AbstractPropertyFileMigrator extends MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(AbstractPropertyFileMigrator.class);

    protected final Map<String, PropertyMapping> propertyMappings;

    /**
     * Constructor for property file migrator.
     * 
     * @param dryRun           if true, no files will be modified (preview mode)
     * @param propertyMappings map of old property keys to new property mappings
     */
    protected AbstractPropertyFileMigrator(boolean dryRun, Map<String, PropertyMapping> propertyMappings) {
        super(dryRun);
        this.propertyMappings = propertyMappings;
    }

    /**
     * Migrate all property files in the project.
     * 
     * <p>
     * This method is final to ensure consistent property file discovery and
     * migration
     * across all Spring Boot versions.
     * 
     * @return result of property migration
     */
    @Override
    public final MigrationPhaseResult migrate() {
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

            if (result.getChangeCount() == 0) {
                result.addChange("No property migrations needed");
            }

        } catch (Exception e) {
            logger.error("Error during property migration", e);
            result.addError("Property migration failed: " + e.getMessage());
        }

        return result;
    }

    // ==================== Protected Helper Methods ====================

    /**
     * Find property files matching patterns.
     * 
     * @param basePath base path to search
     * @param patterns file name patterns (e.g., "*.yml", "*.properties")
     * @return list of matching property files
     */
    protected final List<Path> findPropertyFiles(Path basePath, String... patterns) throws Exception {
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
     * 
     * @param yamlFile path to YAML file
     * @param result   migration result
     */
    protected final void migrateYamlFile(Path yamlFile, MigrationPhaseResult result) {
        logger.info("Migrating YAML file: {}", yamlFile);

        try {
            Yaml yaml = createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null || data.isEmpty()) {
                logger.info("YAML file is empty: {}", yamlFile);
                return;
            }

            boolean modified = transformYamlData(data, result, yamlFile.getFileName().toString());

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(yamlFile)) {
                    yaml.dump(data, new java.io.OutputStreamWriter(output));
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
     * 
     * @param data     YAML data
     * @param result   migration result
     * @param fileName file name for logging
     * @return true if data was modified
     */
    @SuppressWarnings("unchecked")
    protected boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        // Apply property mappings
        for (Map.Entry<String, PropertyMapping> entry : propertyMappings.entrySet()) {
            String oldKey = entry.getKey();
            PropertyMapping mapping = entry.getValue();

            // Split property path (e.g., "logging.file" -> ["logging", "file"])
            String[] oldParts = oldKey.split("\\.");
            String[] newParts = mapping.newKey.split("\\.");

            // Check if old property exists
            Map<String, Object> current = data;
            boolean exists = true;
            for (int i = 0; i < oldParts.length - 1; i++) {
                if (current.containsKey(oldParts[i]) && current.get(oldParts[i]) instanceof Map) {
                    current = (Map<String, Object>) current.get(oldParts[i]);
                } else {
                    exists = false;
                    break;
                }
            }

            if (exists && current.containsKey(oldParts[oldParts.length - 1])) {
                Object value = current.remove(oldParts[oldParts.length - 1]);

                // Transform value if needed
                if (mapping.type == TransformationType.VALUE_TRANSFORM) {
                    value = transformValue(oldKey, value);
                }

                // Create new nested structure
                Map<String, Object> newCurrent = data;
                for (int i = 0; i < newParts.length - 1; i++) {
                    if (!newCurrent.containsKey(newParts[i])) {
                        newCurrent.put(newParts[i], new java.util.LinkedHashMap<>());
                    }
                    newCurrent = (Map<String, Object>) newCurrent.get(newParts[i]);
                }
                newCurrent.put(newParts[newParts.length - 1], value);

                result.addChange(fileName + ": " + oldKey + " → " + mapping.newKey);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Migrate a .properties file.
     * 
     * @param propFile path to properties file
     * @param result   migration result
     */
    protected final void migratePropertiesFile(Path propFile, MigrationPhaseResult result) {
        logger.info("Migrating properties file: {}", propFile);

        try {
            Properties props = new Properties();

            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            boolean modified = transformProperties(props, result, propFile.getFileName().toString());

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Migrated to Spring Boot " + getTargetVersion());
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
     * 
     * @param props    properties
     * @param result   migration result
     * @param fileName file name for logging
     * @return true if properties were modified
     */
    protected final boolean transformProperties(Properties props, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        for (Map.Entry<String, PropertyMapping> entry : propertyMappings.entrySet()) {
            String oldKey = entry.getKey();
            PropertyMapping mapping = entry.getValue();

            if (props.containsKey(oldKey)) {
                String value = props.getProperty(oldKey);
                props.remove(oldKey);

                String newValue = value;
                if (mapping.type == TransformationType.VALUE_TRANSFORM) {
                    newValue = transformValue(oldKey, value).toString();
                }

                props.setProperty(mapping.newKey, newValue);
                result.addChange(fileName + ": " + oldKey + " → " + mapping.newKey);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Transform property value based on specific rules.
     * 
     * <p>
     * Subclasses can override this method to provide version-specific value
     * transformations.
     * Default implementation handles server.use-forward-headers transformation.
     * 
     * @param oldKey original property key
     * @param value  original property value
     * @return transformed value
     */
    protected Object transformValue(String oldKey, Object value) {
        // Default transformation for server.use-forward-headers
        if ("server.use-forward-headers".equals(oldKey)) {
            return "true".equals(value.toString()) ? "native" : "none";
        }
        return value;
    }

    /**
     * Get target Spring Boot version for file comments.
     * 
     * <p>
     * Subclasses should override if they want custom version strings in comments.
     * 
     * @return target version string (e.g., "2.3")
     */
    protected String getTargetVersion() {
        return "upgraded version";
    }

    /**
     * Create YAML instance with proper configuration.
     * 
     * @return configured YAML instance
     */
    protected final Yaml createYaml() {
        return YamlUtils.createYaml();
    }

    // ==================== Helper Classes ====================

    /**
     * Represents a property mapping from old key to new key with transformation
     * type.
     */
    protected static class PropertyMapping {
        /** New property key */
        public final String newKey;
        /** Type of transformation needed */
        public final TransformationType type;

        /**
         * Create a property mapping.
         * 
         * @param newKey new property key
         * @param type   transformation type
         */
        public PropertyMapping(String newKey, TransformationType type) {
            this.newKey = newKey;
            this.type = type;
        }
    }

    /**
     * Types of property transformations.
     */
    protected enum TransformationType {
        /**
         * Property path changes but value stays the same (e.g., logging.file ->
         * logging.file.name)
         */
        NEST,
        /** Property value needs transformation (e.g., true -> "native") */
        VALUE_TRANSFORM
    }
}
