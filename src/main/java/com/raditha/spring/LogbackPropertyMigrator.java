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
import java.util.*;

/**
 * Migrates Logback properties for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 renamed several Logback-specific properties to reflect their
 * Logback-only nature:
 * <ul>
 * <li>{@code logging.pattern.rolling-file-name} →
 * {@code logging.logback.rollingpolicy.file-name-pattern}</li>
 * <li>{@code logging.file.max-size} →
 * {@code logging.logback.rollingpolicy.max-file-size}</li>
 * <li>{@code logging.file.max-history} →
 * {@code logging.logback.rollingpolicy.max-history}</li>
 * <li>{@code logging.file.total-size-cap} →
 * {@code logging.logback.rollingpolicy.total-size-cap}</li>
 * <li>{@code logging.file.clean-history-on-start} →
 * {@code logging.logback.rollingpolicy.clean-history-on-start}</li>
 * </ul>
 * 
 * <p>
 * This migrator automatically transforms these properties in both YAML and
 * properties files.
 * 
 * @see MigrationPhase
 */
public class LogbackPropertyMigrator extends AbstractConfigMigrator {

    // Property mappings for Logback properties
    private static final Map<String, String> PROPERTY_MAPPINGS = Map.of(
            "logging.pattern.rolling-file-name", "logging.logback.rollingpolicy.file-name-pattern",
            "logging.file.max-size", "logging.logback.rollingpolicy.max-file-size",
            "logging.file.max-history", "logging.logback.rollingpolicy.max-history",
            "logging.file.total-size-cap", "logging.logback.rollingpolicy.total-size-cap",
            "logging.file.clean-history-on-start", "logging.logback.rollingpolicy.clean-history-on-start");

    public LogbackPropertyMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws IOException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            result.addChange("No resources directory found");
            return result;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        boolean foundLogbackProperties = false;

        // Process YAML files
        for (Path yamlFile : yamlFiles) {
            if (migrateYamlFile(yamlFile, result)) {
                foundLogbackProperties = true;
            }
        }

        // Process properties files
        for (Path propFile : propFiles) {
            if (migratePropertiesFile(propFile, result)) {
                foundLogbackProperties = true;
            }
        }

        if (!foundLogbackProperties) {
            result.addChange("No deprecated Logback properties detected");
        }

        return result;
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

    /**
     * Transform Logback properties in YAML data.
     */
    @SuppressWarnings("unchecked")
    private boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        if (!data.containsKey("logging")) {
            return false;
        }

        Map<String, Object> logging = (Map<String, Object>) data.get("logging");
        if (logging == null) {
            return false;
        }

        // Migrate logging.file.* properties to logging.logback.rollingpolicy.*
        if (logging.containsKey("file") && logging.get("file") instanceof Map) {
            Map<String, Object> file = (Map<String, Object>) logging.get("file");

            Map<String, Object> propertiesToMigrate = new HashMap<>();

            // Extract properties that need migration
            for (String key : List.of("max-size", "max-history", "total-size-cap", "clean-history-on-start")) {
                if (file.containsKey(key)) {
                    propertiesToMigrate.put(key, file.get(key));
                    file.remove(key);
                }
            }

            if (!propertiesToMigrate.isEmpty()) {
                // Create logback.rollingpolicy structure
                if (!logging.containsKey("logback")) {
                    logging.put("logback", new LinkedHashMap<>());
                }
                Map<String, Object> logback = (Map<String, Object>) logging.get("logback");

                if (!logback.containsKey("rollingpolicy")) {
                    logback.put("rollingpolicy", new LinkedHashMap<>());
                }
                Map<String, Object> rollingpolicy = (Map<String, Object>) logback.get("rollingpolicy");

                // Move properties
                for (Map.Entry<String, Object> entry : propertiesToMigrate.entrySet()) {
                    rollingpolicy.put(entry.getKey(), entry.getValue());
                    result.addChange(String.format("%s: logging.file.%s → logging.logback.rollingpolicy.%s",
                            fileName, entry.getKey(), entry.getKey()));
                    modified = true;
                }
            }
        }

        // Migrate logging.pattern.rolling-file-name
        if (logging.containsKey("pattern") && logging.get("pattern") instanceof Map) {
            Map<String, Object> pattern = (Map<String, Object>) logging.get("pattern");

            if (pattern.containsKey("rolling-file-name")) {
                Object value = pattern.remove("rolling-file-name");

                if (!logging.containsKey("logback")) {
                    logging.put("logback", new LinkedHashMap<>());
                }
                Map<String, Object> logback = (Map<String, Object>) logging.get("logback");

                if (!logback.containsKey("rollingpolicy")) {
                    logback.put("rollingpolicy", new LinkedHashMap<>());
                }
                Map<String, Object> rollingpolicy = (Map<String, Object>) logback.get("rollingpolicy");

                rollingpolicy.put("file-name-pattern", value);
                result.addChange(String.format(
                        "%s: logging.pattern.rolling-file-name → logging.logback.rollingpolicy.file-name-pattern",
                        fileName));
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Migrate Logback properties in a properties file.
     */
    private boolean migratePropertiesFile(Path propFile, MigrationPhaseResult result) throws IOException {
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(propFile)) {
            props.load(input);
        }

        boolean modified = false;

        for (Map.Entry<String, String> migration : PROPERTY_MAPPINGS.entrySet()) {
            String oldKey = migration.getKey();
            String newKey = migration.getValue();

            if (props.containsKey(oldKey)) {
                String value = props.getProperty(oldKey);
                props.remove(oldKey);
                props.setProperty(newKey, value);

                result.addChange(String.format("%s: %s → %s",
                        propFile.getFileName(), oldKey, newKey));
                modified = true;
            }
        }

        if (modified && !dryRun) {
            try (OutputStream output = Files.newOutputStream(propFile)) {
                props.store(output, "Logback property migration for Spring Boot 2.4");
            }
        }

        return modified;

    }

    @Override
    public String getPhaseName() {
        return "Logback Property Migration";
    }

    @Override
    public int getPriority() {
        return 45;
    }
}
