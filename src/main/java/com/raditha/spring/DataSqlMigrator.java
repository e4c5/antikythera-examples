package com.raditha.spring;

import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Migrates data.sql processing configuration for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 changed when data.sql executes relative to Hibernate
 * initialization.
 * In Spring Boot 2.3, data.sql ran AFTER Hibernate created the schema.
 * In Spring Boot 2.4, data.sql runs BEFORE Hibernate by default.
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects presence of data.sql or schema.sql files</li>
 * <li>Checks if Hibernate DDL auto is enabled (create, update,
 * create-drop)</li>
 * <li>Adds {@code spring.jpa.defer-datasource-initialization=true} to restore
 * 2.3 behavior</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class DataSqlMigrator extends AbstractConfigMigrator {

    public DataSqlMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            Path basePath = Paths.get(Settings.getBasePath());
            Path resourcesPath = basePath.resolve("src/main/resources");

            if (!Files.exists(resourcesPath)) {
                result.addChange("No resources directory found");
                return result;
            }

            // Check for data.sql files
            List<Path> sqlScripts = findSqlScripts(resourcesPath);
            boolean hasDataSql = sqlScripts.stream()
                    .anyMatch(path -> path.getFileName().toString().equals("data.sql"));

            if (!hasDataSql) {
                result.addChange("No data.sql file detected");
                return result;
            }

            result.addChange("Found data.sql - checking Hibernate DDL configuration");

            // Check for Hibernate DDL auto configuration
            List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
            List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

            boolean hasHibernateDdlAuto = false;
            for (Path yamlFile : yamlFiles) {
                if (hasHibernateDdlAuto(yamlFile)) {
                    hasHibernateDdlAuto = true;
                    break;
                }
            }

            if (!hasHibernateDdlAuto) {
                for (Path propFile : propFiles) {
                    if (hasHibernateDdlAutoProperties(propFile)) {
                        hasHibernateDdlAuto = true;
                        break;
                    }
                }
            }

            if (!hasHibernateDdlAuto) {
                result.addChange("data.sql found but Hibernate DDL auto not detected - no action needed");
                return result;
            }

            // Risky combination detected - add defer-datasource-initialization
            result.addWarning("CRITICAL: data.sql + Hibernate DDL auto detected");
            result.addWarning("In Spring Boot 2.4, data.sql runs BEFORE Hibernate (was AFTER in 2.3)");
            result.addChange("Adding spring.jpa.defer-datasource-initialization=true");

            // Add to the first YAML or properties file found
            if (!yamlFiles.isEmpty()) {
                addDeferDatasourceInitYaml(yamlFiles.get(0), result);
            } else if (!propFiles.isEmpty()) {
                addDeferDatasourceInitProperties(propFiles.get(0), result);
            } else {
                result.addError("No configuration file found to add defer-datasource-initialization");
            }

        } catch (Exception e) {
            result.addError("Data.sql migration failed: " + e.getMessage());
            logger.error("Data.sql migration failed", e);
        }

        return result;
    }

    /**
     * Find SQL script files.
     */
    private List<Path> findSqlScripts(Path resourcesPath) throws Exception {
        List<Path> scripts = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(resourcesPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals("data.sql") || fileName.equals("schema.sql");
                    })
                    .forEach(scripts::add);
        }

        return scripts;
    }

    /**
     * Check if YAML file has Hibernate DDL auto configuration.
     */
    @SuppressWarnings("unchecked")
    private boolean hasHibernateDdlAuto(Path yamlFile) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data != null && data.containsKey("spring")) {
                Map<String, Object> spring = (Map<String, Object>) data.get("spring");

                if (spring != null && spring.containsKey("jpa")) {
                    Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");

                    if (jpa != null && jpa.containsKey("hibernate")) {
                        Map<String, Object> hibernate = (Map<String, Object>) jpa.get("hibernate");

                        if (hibernate != null && hibernate.containsKey("ddl-auto")) {
                            String ddlAuto = hibernate.get("ddl-auto").toString();
                            // Only create, update, create-drop are risky
                            return !ddlAuto.equals("none") && !ddlAuto.equals("validate");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking Hibernate DDL auto in {}", yamlFile, e);
        }

        return false;
    }

    /**
     * Check if properties file has Hibernate DDL auto configuration.
     */
    private boolean hasHibernateDdlAutoProperties(Path propFile) {
        try {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            String ddlAuto = props.getProperty("spring.jpa.hibernate.ddl-auto");
            if (ddlAuto != null) {
                return !ddlAuto.equals("none") && !ddlAuto.equals("validate");
            }
        } catch (Exception e) {
            logger.debug("Error checking Hibernate DDL auto in {}", propFile, e);
        }

        return false;
    }

    /**
     * Add defer-datasource-initialization to YAML file.
     */
    @SuppressWarnings("unchecked")
    private void addDeferDatasourceInitYaml(Path yamlFile, MigrationPhaseResult result) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null) {
                data = new LinkedHashMap<>();
            }

            // Navigate to spring.jpa section
            if (!data.containsKey("spring")) {
                data.put("spring", new LinkedHashMap<>());
            }
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");

            if (!spring.containsKey("jpa")) {
                spring.put("jpa", new LinkedHashMap<>());
            }
            Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");

            // Check if already set
            if (jpa.containsKey("defer-datasource-initialization")) {
                result.addChange("defer-datasource-initialization already configured");
                return;
            }

            // Add property
            jpa.put("defer-datasource-initialization", true);

            if (!dryRun) {
                try (OutputStream output = Files.newOutputStream(yamlFile)) {
                    yaml.dump(data, new OutputStreamWriter(output));
                }
            }

            result.addChange(String.format("%s: Added spring.jpa.defer-datasource-initialization=true",
                    yamlFile.getFileName()));

        } catch (Exception e) {
            result.addError("Failed to add defer-datasource-initialization: " + e.getMessage());
        }
    }

    /**
     * Add defer-datasource-initialization to properties file.
     */
    private void addDeferDatasourceInitProperties(Path propFile, MigrationPhaseResult result) {
        try {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            if (props.containsKey("spring.jpa.defer-datasource-initialization")) {
                result.addChange("defer-datasource-initialization already configured");
                return;
            }

            props.setProperty("spring.jpa.defer-datasource-initialization", "true");

            if (!dryRun) {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Spring Boot 2.4 - data.sql compatibility");
                }
            }

            result.addChange(String.format("%s: Added spring.jpa.defer-datasource-initialization=true",
                    propFile.getFileName()));

        } catch (Exception e) {
            result.addError("Failed to add defer-datasource-initialization: " + e.getMessage());
        }
    }

    @Override
    public String getPhaseName() {
        return "Data.sql Processing Migration";
    }

    @Override
    public int getPriority() {
        return 35;
    }
}
