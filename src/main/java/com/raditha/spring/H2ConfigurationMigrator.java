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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Configures H2 console settings for Spring Boot 2.3.
 * 
 * <p>
 * In Spring Boot 2.3, H2 console requires explicit datasource naming when
 * enabled.
 * This migrator detects H2 console usage and adds
 * {@code spring.datasource.generate-unique-name=false}
 * to prevent the console from being inaccessible due to randomized datasource
 * names.
 * 
 * <p>
 * Detection strategy:
 * <ul>
 * <li>Checks if {@code spring.h2.console.enabled=true} is present</li>
 * <li>Checks if {@code spring.datasource.generate-unique-name} is already
 * configured</li>
 * <li>Adds {@code spring.datasource.generate-unique-name=false} if H2 console
 * enabled but not configured</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class H2ConfigurationMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(H2ConfigurationMigrator.class);

    private final boolean dryRun;

    public H2ConfigurationMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            // Check if Settings is initialized
            if (Settings.getBasePath() == null) {
                result.addChange("Settings not initialized - H2 configuration check skipped");
                return result;
            }

            Path basePath = Paths.get(Settings.getBasePath());

            // Find application.yml or application.properties files
            List<Path> yamlFiles = findPropertyFiles(basePath, "*.yml", "*.yaml");
            List<Path> propFiles = findPropertyFiles(basePath, "*.properties");

            boolean h2ConsoleEnabled = false;
            boolean datasourceNameConfigured = false;

            // Check YAML files
            for (Path yamlFile : yamlFiles) {
                Map<String, Boolean> checks = checkYamlFile(yamlFile);
                if (checks.get("h2Enabled"))
                    h2ConsoleEnabled = true;
                if (checks.get("datasourceConfigured"))
                    datasourceNameConfigured = true;
            }

            // Check properties files
            for (Path propFile : propFiles) {
                Map<String, Boolean> checks = checkPropertiesFile(propFile);
                if (checks.get("h2Enabled"))
                    h2ConsoleEnabled = true;
                if (checks.get("datasourceConfigured"))
                    datasourceNameConfigured = true;
            }

            if (!h2ConsoleEnabled) {
                result.addChange("H2 console not enabled - no configuration needed");
                return result;
            }

            if (datasourceNameConfigured) {
                result.addChange("H2 console enabled and datasource naming already configured");
                return result;
            }

            // Add configuration
            addDatasourceConfiguration(yamlFiles, propFiles, result);

        } catch (NullPointerException e) {
            logger.warn("Settings not initialized (props is null) - H2 configuration check skipped");
            result.addChange("Settings not initialized - H2 configuration check skipped");
        } catch (Exception e) {
            logger.error("Error during H2 configuration migration", e);
            result.addError("H2 configuration failed: " + e.getMessage());
        }

        return result;
    }

    private List<Path> findPropertyFiles(Path basePath, String... patterns) throws Exception {
        java.util.List<Path> files = new java.util.ArrayList<>();

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

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> checkYamlFile(Path yamlFile) {
        boolean h2Enabled = false;
        boolean datasourceConfigured = false;

        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data != null) {
                // Check for spring.h2.console.enabled
                if (data.containsKey("spring")) {
                    Map<String, Object> spring = (Map<String, Object>) data.get("spring");
                    if (spring.containsKey("h2")) {
                        Map<String, Object> h2 = (Map<String, Object>) spring.get("h2");
                        if (h2.containsKey("console")) {
                            Map<String, Object> console = (Map<String, Object>) h2.get("console");
                            h2Enabled = Boolean.TRUE.equals(console.get("enabled"));
                        }
                    }

                    // Check for spring.datasource.generate-unique-name
                    if (spring.containsKey("datasource")) {
                        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
                        datasourceConfigured = datasource.containsKey("generate-unique-name");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error checking YAML file {}: {}", yamlFile, e.getMessage());
        }

        return Map.of("h2Enabled", h2Enabled, "datasourceConfigured", datasourceConfigured);
    }

    private Map<String, Boolean> checkPropertiesFile(Path propFile) {
        boolean h2Enabled = false;
        boolean datasourceConfigured = false;

        try {
            java.util.Properties props = new java.util.Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            h2Enabled = "true".equalsIgnoreCase(props.getProperty("spring.h2.console.enabled"));
            datasourceConfigured = props.containsKey("spring.datasource.generate-unique-name");

        } catch (Exception e) {
            logger.warn("Error checking properties file {}: {}", propFile, e.getMessage());
        }

        return Map.of("h2Enabled", h2Enabled, "datasourceConfigured", datasourceConfigured);
    }

    private void addDatasourceConfiguration(List<Path> yamlFiles, List<Path> propFiles,
            MigrationPhaseResult result) {
        // Prefer to add to first YAML file, fall back to properties
        if (!yamlFiles.isEmpty()) {
            addToYamlFile(yamlFiles.get(0), result);
        } else if (!propFiles.isEmpty()) {
            addToPropertiesFile(propFiles.get(0), result);
        } else {
            result.addWarning("H2 console enabled but no application.yml/properties found to add configuration");
            result.addWarning("Manually add: spring.datasource.generate-unique-name=false");
        }
    }

    @SuppressWarnings("unchecked")
    private void addToYamlFile(Path yamlFile, MigrationPhaseResult result) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null) {
                data = new java.util.LinkedHashMap<>();
            }

            // Ensure spring.datasource.generate-unique-name = false
            if (!data.containsKey("spring")) {
                data.put("spring", new java.util.LinkedHashMap<>());
            }
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");

            if (!spring.containsKey("datasource")) {
                spring.put("datasource", new java.util.LinkedHashMap<>());
            }
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");

            datasource.put("generate-unique-name", false);

            if (dryRun) {
                result.addChange("Would add spring.datasource.generate-unique-name=false to " + yamlFile.getFileName());
            } else {
                try (OutputStream output = Files.newOutputStream(yamlFile)) {
                    yaml.dump(data, new java.io.OutputStreamWriter(output));
                }
                result.addChange("Added spring.datasource.generate-unique-name=false to " + yamlFile.getFileName());
                result.addWarning("H2 console now accessible with fixed datasource name");
            }

        } catch (Exception e) {
            logger.error("Error adding H2 configuration to YAML", e);
            result.addError("Failed to add H2 configuration: " + e.getMessage());
        }
    }

    private void addToPropertiesFile(Path propFile, MigrationPhaseResult result) {
        try {
            java.util.Properties props = new java.util.Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            props.setProperty("spring.datasource.generate-unique-name", "false");

            if (dryRun) {
                result.addChange("Would add spring.datasource.generate-unique-name=false to " + propFile.getFileName());
            } else {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "H2 Console Configuration for Spring Boot 2.3");
                }
                result.addChange("Added spring.datasource.generate-unique-name=false to " + propFile.getFileName());
                result.addWarning("H2 console now accessible with fixed datasource name");
            }

        } catch (Exception e) {
            logger.error("Error adding H2 configuration to properties", e);
            result.addError("Failed to add H2 configuration: " + e.getMessage());
        }
    }

    @Override
    public String getPhaseName() {
        return "H2 Console Configuration";
    }

    @Override
    public int getPriority() {
        return 15;
    }
}
