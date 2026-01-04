package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Migrates SQL script initialization properties from spring.datasource.* to
 * spring.sql.init.* namespace.
 * 
 * <p>
 * <b>CRITICAL CHANGE</b>: Spring Boot 2.5 moved all SQL script initialization
 * properties to a new namespace.
 * This migrator automates the transformation of all property files.
 * 
 * <p>
 * Property Mappings:
 * <ul>
 * <li>spring.datasource.initialization-mode → spring.sql.init.mode</li>
 * <li>spring.datasource.schema → spring.sql.init.schema-locations</li>
 * <li>spring.datasource.data → spring.sql.init.data-locations</li>
 * <li>spring.datasource.platform → spring.sql.init.platform</li>
 * <li>spring.datasource.continue-on-error →
 * spring.sql.init.continue-on-error</li>
 * <li>spring.datasource.separator → spring.sql.init.separator</li>
 * <li>spring.datasource.sql-script-encoding → spring.sql.init.encoding</li>
 * </ul>
 * 
 * <p>
 * Additional Logic:
 * <ul>
 * <li>If JPA is enabled AND data.sql exists, adds
 * spring.jpa.defer-datasource-initialization=true</li>
 * <li>This ensures data.sql runs AFTER Hibernate schema creation</li>
 * </ul>
 * 
 * @see AbstractPropertyFileMigrator
 */
public class SqlScriptPropertiesMigrator extends MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(SqlScriptPropertiesMigrator.class);

    // Property mappings: old key → new key
    private static final Map<String, String> PROPERTY_MAPPINGS = new LinkedHashMap<>();

    static {
        PROPERTY_MAPPINGS.put("spring.datasource.initialization-mode", "spring.sql.init.mode");
        PROPERTY_MAPPINGS.put("spring.datasource.schema", "spring.sql.init.schema-locations");
        PROPERTY_MAPPINGS.put("spring.datasource.data", "spring.sql.init.data-locations");
        PROPERTY_MAPPINGS.put("spring.datasource.platform", "spring.sql.init.platform");
        PROPERTY_MAPPINGS.put("spring.datasource.continue-on-error", "spring.sql.init.continue-on-error");
        PROPERTY_MAPPINGS.put("spring.datasource.separator", "spring.sql.init.separator");
        PROPERTY_MAPPINGS.put("spring.datasource.sql-script-encoding", "spring.sql.init.encoding");
    }

    public SqlScriptPropertiesMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Migrating SQL script initialization properties...");

        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            Path basePath = Paths.get(Settings.getBasePath());
            Path resourcesPath = basePath.resolve("src/main/resources");

            // Migrate YAML files
            migrateYamlFiles(resourcesPath, result);

            // Migrate .properties files
            migratePropertiesFiles(resourcesPath, result);

            // Check if defer-datasource-initialization is needed
            if (needsDeferDatasourceInitialization(resourcesPath)) {
                addDeferDatasourceInitialization(resourcesPath, result);
            }

            if (result.getChangeCount() == 0) {
                result.addChange("No SQL script properties found to migrate");
            }

        } catch (Exception e) {
            logger.error("Error during SQL script property migration", e);
            result.addError("SQL script property migration failed: " + e.getMessage());
        }

        return result;
    }

    private void migrateYamlFiles(Path resourcesPath, MigrationPhaseResult result) throws IOException {
        if (!Files.exists(resourcesPath)) {
            return;
        }

        for (String pattern : new String[] { "application.yml", "application.yaml",
                "application-*.yml", "application-*.yaml" }) {
            Files.walk(resourcesPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String regex = pattern.replace("*", ".*");
                        return name.matches(regex);
                    })
                    .forEach(yamlFile -> migrateYamlFile(yamlFile, result));
        }
    }

    private void migrateYamlFile(Path yamlFile, MigrationPhaseResult result) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null || data.isEmpty()) {
                return;
            }

            boolean modified = transformYamlData(data, result, yamlFile.getFileName().toString());

            if (modified && !dryRun) {
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setPrettyFlow(true);
                Yaml dumper = new Yaml(options);

                try (Writer writer = new FileWriter(yamlFile.toFile())) {
                    dumper.dump(data, writer);
                }
                logger.info("Updated YAML file: {}", yamlFile);
            }

        } catch (Exception e) {
            result.addError("Failed to migrate " + yamlFile + ": " + e.getMessage());
            logger.error("Error migrating YAML file", e);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        for (Map.Entry<String, String> entry : PROPERTY_MAPPINGS.entrySet()) {
            String oldKey = entry.getKey();
            String newKey = entry.getValue();

            // Split property path
            String[] oldParts = oldKey.split("\\.");
            String[] newParts = newKey.split("\\.");

            // Navigate to old property
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

                // Create new nested structure
                Map<String, Object> newCurrent = data;
                for (int i = 0; i < newParts.length - 1; i++) {
                    if (!newCurrent.containsKey(newParts[i])) {
                        newCurrent.put(newParts[i], new LinkedHashMap<>());
                    }
                    newCurrent = (Map<String, Object>) newCurrent.get(newParts[i]);
                }
                newCurrent.put(newParts[newParts.length - 1], value);

                result.addChange(fileName + ": " + oldKey + " → " + newKey);
                modified = true;
            }
        }

        return modified;
    }

    private void migratePropertiesFiles(Path resourcesPath, MigrationPhaseResult result) throws IOException {
        if (!Files.exists(resourcesPath)) {
            return;
        }

        Files.walk(resourcesPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("application.*\\.properties"))
                .forEach(propFile -> migratePropertiesFile(propFile, result));
    }

    private void migratePropertiesFile(Path propFile, MigrationPhaseResult result) {
        try {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            boolean modified = false;
            for (Map.Entry<String, String> entry : PROPERTY_MAPPINGS.entrySet()) {
                String oldKey = entry.getKey();
                String newKey = entry.getValue();

                if (props.containsKey(oldKey)) {
                    String value = props.getProperty(oldKey);
                    props.remove(oldKey);
                    props.setProperty(newKey, value);
                    result.addChange(propFile.getFileName() + ": " + oldKey + " → " + newKey);
                    modified = true;
                }
            }

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Migrated to Spring Boot 2.5 by SpringBoot24to25Migrator");
                }
                logger.info("Updated properties file: {}", propFile);
            }

        } catch (Exception e) {
            result.addError("Failed to migrate " + propFile + ": " + e.getMessage());
            logger.error("Error migrating properties file", e);
        }
    }

    /**
     * Check if spring.jpa.defer-datasource-initialization=true is needed.
     */
    private boolean needsDeferDatasourceInitialization(Path resourcesPath) {
        try {
            // Check if JPA properties exist
            boolean hasJpa = hasJpaProperties(resourcesPath);

            // Check if data.sql exists
            boolean hasDataSql = Files.exists(resourcesPath.resolve("data.sql"));

            return hasJpa && hasDataSql;
        } catch (Exception e) {
            logger.warn("Error checking defer-datasource-initialization requirement", e);
            return false;
        }
    }

    /**
     * Check if any spring.jpa.* properties exist in configuration files.
     */
    private boolean hasJpaProperties(Path resourcesPath) {
        try {
            if (!Files.exists(resourcesPath)) {
                return false;
            }

            // Check YAML files
            boolean hasInYaml = Files.walk(resourcesPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("application.*\\.(yml|yaml)"))
                    .anyMatch(this::containsJpaProperties);

            if (hasInYaml)
                return true;

            // Check .properties files
            return Files.walk(resourcesPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("application.*\\.properties"))
                    .anyMatch(this::containsJpaPropertiesInPropertiesFile);

        } catch (Exception e) {
            logger.warn("Error checking for JPA properties", e);
            return false;
        }
    }

    private boolean containsJpaProperties(Path yamlFile) {
        try (InputStream input = Files.newInputStream(yamlFile)) {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data = yaml.load(input);
            if (data != null && data.containsKey("spring")) {
                Object springObj = data.get("spring");
                if (springObj instanceof Map) {
                    return ((Map<?, ?>) springObj).containsKey("jpa");
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading YAML file: " + yamlFile, e);
        }
        return false;
    }

    private boolean containsJpaPropertiesInPropertiesFile(Path propFile) {
        try (InputStream input = Files.newInputStream(propFile)) {
            Properties props = new Properties();
            props.load(input);
            return props.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("spring.jpa."));
        } catch (Exception e) {
            logger.warn("Error reading properties file: " + propFile, e);
        }
        return false;
    }

    /**
     * Add spring.jpa.defer-datasource-initialization=true to application.yml.
     */
    private void addDeferDatasourceInitialization(Path resourcesPath, MigrationPhaseResult result) {
        try {
            // Find application.yml or create it
            Path yamlFile = resourcesPath.resolve("application.yml");
            if (!Files.exists(yamlFile)) {
                yamlFile = resourcesPath.resolve("application.yaml");
            }

            if (Files.exists(yamlFile)) {
                addDeferPropertyToYaml(yamlFile);
            } else {
                // Create new application.yml with the property
                createYamlWithDeferProperty(resourcesPath.resolve("application.yml"));
            }

            result.addChange("Added spring.jpa.defer-datasource-initialization=true (JPA + data.sql detected)");
            result.addWarning("IMPORTANT: spring.jpa.defer-datasource-initialization=true added");
            result.addWarning("This ensures data.sql runs AFTER Hibernate schema creation");
            logger.info("Added defer-datasource-initialization property");
        } catch (Exception e) {
            result.addWarning("Could not add defer-datasource-initialization property: " + e.getMessage());
            logger.error("Error adding defer-datasource-initialization", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void addDeferPropertyToYaml(Path yamlFile) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
            if (data == null) {
                data = new LinkedHashMap<>();
            }
        }

        // Navigate/create spring.jpa structure
        Map<String, Object> spring = (Map<String, Object>) data.computeIfAbsent("spring",
                k -> new LinkedHashMap<>());

        Map<String, Object> jpa = (Map<String, Object>) spring.computeIfAbsent("jpa",
                k -> new LinkedHashMap<>());

        // Add defer property
        jpa.put("defer-datasource-initialization", true);

        // Write back
        if (!dryRun) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml dumper = new Yaml(options);

            try (Writer writer = new FileWriter(yamlFile.toFile())) {
                writer.write("# Spring Boot 2.5 SQL initialization configuration\n");
                writer.write("# Added by SpringBoot24to25Migrator\n\n");
                dumper.dump(data, writer);
            }
        }
    }

    private void createYamlWithDeferProperty(Path yamlFile) throws IOException {
        if (dryRun) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> jpa = new LinkedHashMap<>();

        jpa.put("defer-datasource-initialization", true);
        spring.put("jpa", jpa);
        data.put("spring", spring);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml dumper = new Yaml(options);

        try (Writer writer = new FileWriter(yamlFile.toFile())) {
            writer.write("# Spring Boot 2.5 SQL initialization configuration\n");
            writer.write("# Created by SpringBoot24to25Migrator\n\n");
            dumper.dump(data, writer);
        }
    }

    @Override
    public String getPhaseName() {
        return "SQL Script Properties Migration";
    }

    @Override
    public int getPriority() {
        return 20;
    }
}
