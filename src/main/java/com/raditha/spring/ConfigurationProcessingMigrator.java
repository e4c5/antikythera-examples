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
 * Migrates configuration file processing for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 completely redesigned how application.properties and
 * application.yml
 * files are processed. This migrator handles:
 * <ul>
 * <li>Legacy profile syntax migration: {@code spring.profiles} →
 * {@code spring.config.activate.on-profile}</li>
 * <li>Detection of complex multi-document YAML files requiring manual
 * review</li>
 * <li>Profile groups detection and warnings for processing order changes</li>
 * <li>Support for both YAML and properties files</li>
 * <li>Optional addition of {@code spring.config.use-legacy-processing=true} for
 * complex cases</li>
 * </ul>
 * 
 * <p>
 * Key changes in Spring Boot 2.4 configuration processing:
 * <ul>
 * <li>Profile-specific documents now processed BEFORE profile-specific
 * files</li>
 * <li>External files now consistently OVERRIDE packaged files</li>
 * <li>Profile activation syntax changed</li>
 * <li>Profile groups processing order changed</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class ConfigurationProcessingMigrator extends MigrationPhase {


    public ConfigurationProcessingMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            Path basePath = Paths.get(Settings.getBasePath());
            List<Path> yamlFiles = findPropertyFiles(basePath, "*.yml", "*.yaml");
            List<Path> propFiles = findPropertyFiles(basePath, "*.properties");

            if (yamlFiles.isEmpty() && propFiles.isEmpty()) {
                result.addChange("No configuration files found");
                return result;
            }

            result.addChange(String.format("Found %d YAML and %d properties configuration file(s)", 
                    yamlFiles.size(), propFiles.size()));

            // Process YAML files
            for (Path yamlFile : yamlFiles) {
                processYamlFile(yamlFile, result);
            }

            // Process properties files
            for (Path propFile : propFiles) {
                processPropertiesFile(propFile, result);
            }

            if (result.requiresManualReview()) {
                result.addWarning("Complex multi-document YAML files detected - manual review recommended");
                result.addWarning("Consider using spring.config.use-legacy-processing=true temporarily");
            }

        } catch (Exception e) {
            result.addError("Configuration processing migration failed: " + e.getMessage());
            logger.error("Configuration processing migration failed", e);
        }

        return result;
    }

    /**
     * Find all property files matching the given patterns.
     */
    private List<Path> findPropertyFiles(Path basePath, String... patterns) throws Exception {
        List<Path> files = new ArrayList<>();
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            return files;
        }

        try (Stream<Path> paths = Files.walk(resourcesPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        for (String pattern : patterns) {
                            String regex = pattern.replace("*", ".*");
                            if (fileName.matches(regex)) {
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
     * Process a single YAML file for configuration changes.
     */
    private void processYamlFile(Path yamlFile, MigrationPhaseResult result) {
        try {
            Yaml yaml = YamlUtils.createYaml();

            // Check for multiple documents
            Iterable<Object> documents;
            try (InputStream input = Files.newInputStream(yamlFile)) {
                documents = yaml.loadAll(input);
            }

            List<Map<String, Object>> docList = new ArrayList<>();
            boolean hasLegacyProfileDocuments = false;
            boolean hasNewProfileActivationDocuments = false;
            boolean hasProfileGroups = false;

            for (Object doc : documents) {
                if (doc instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> docMap = (Map<String, Object>) doc;
                    docList.add(docMap);

                    hasLegacyProfileDocuments |= hasLegacyProfileSyntax(docMap);
                    hasNewProfileActivationDocuments |= hasNewProfileActivationSyntax(docMap);
                    hasProfileGroups |= hasProfileGroupsSyntax(docMap);
                }
            }

            // Profile groups can affect processing order in Spring Boot 2.4+
            if (hasProfileGroups) {
                result.setRequiresManualReview(true);
                result.addWarning(String.format("%s: Profile groups detected - verify processing order",
                        yamlFile.getFileName()));
            }

            // If multi-document file contains profile-specific documents (legacy or new syntax), flag for manual review
            boolean hasProfileDocuments = hasLegacyProfileDocuments || hasNewProfileActivationDocuments;
            if (docList.size() > 1 && hasProfileDocuments) {
                result.setRequiresManualReview(true);
                result.addWarning(String.format("%s: Multi-document YAML with profiles requires manual review",
                        yamlFile.getFileName()));

                // Only add legacy-processing flag when legacy profile syntax is detected.
                // Multi-document YAML files already using the new activation syntax should be reviewed,
                // but we avoid mutating them automatically.
                if (hasLegacyProfileDocuments) {
                    addLegacyProcessingFlag(yamlFile, result);
                }
                return;
            }

            // For simple files, transform the profile syntax
            if (docList.size() == 1) {
                Map<String, Object> data = docList.get(0);
                if (transformYamlData(data, result, yamlFile.getFileName().toString())) {
                    if (!dryRun) {
                        try (OutputStream output = Files.newOutputStream(yamlFile)) {
                            yaml.dump(data, new OutputStreamWriter(output));
                        }
                    }
                }
            }

        } catch (Exception e) {
            result.addError(String.format("Failed to process %s: %s",
                    yamlFile.getFileName(), e.getMessage()));
            logger.error("Failed to process YAML file: {}", yamlFile, e);
        }
    }

    /**
     * Process a single properties file for configuration changes.
     */
    private void processPropertiesFile(Path propFile, MigrationPhaseResult result) {
        try {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            boolean modified = false;

            // Check for deprecated spring.profiles property
            if (props.containsKey("spring.profiles")) {
                String profileValue = props.getProperty("spring.profiles");
                props.remove("spring.profiles");
                props.setProperty("spring.config.activate.on-profile", profileValue);
                
                result.addChange(String.format("%s: spring.profiles → spring.config.activate.on-profile",
                        propFile.getFileName()));
                modified = true;
            }

            // Check for profile groups (requires manual review)
            boolean hasProfileGroups = props.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("spring.profiles.group."));
            
            if (hasProfileGroups) {
                result.setRequiresManualReview(true);
                result.addWarning(String.format("%s: Profile groups detected - verify processing order",
                        propFile.getFileName()));
            }

            // Write back if modified
            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Spring Boot 2.4 - Configuration Processing Migration");
                }
            }

        } catch (Exception e) {
            result.addError(String.format("Failed to process %s: %s",
                    propFile.getFileName(), e.getMessage()));
            logger.error("Failed to process properties file: {}", propFile, e);
        }
    }

    /**
     * Check if YAML data contains legacy profile syntax.
     */
    @SuppressWarnings("unchecked")
    private boolean hasLegacyProfileSyntax(Map<String, Object> data) {
        if (data.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");
            if (spring != null && spring.containsKey("profiles") && spring.get("profiles") instanceof String) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if YAML data contains the new Spring Boot 2.4+ profile activation syntax.
     */
    @SuppressWarnings("unchecked")
    private boolean hasNewProfileActivationSyntax(Map<String, Object> data) {
        if (!data.containsKey("spring")) {
            return false;
        }
        Object springObj = data.get("spring");
        if (!(springObj instanceof Map)) {
            return false;
        }
        Map<String, Object> spring = (Map<String, Object>) springObj;
        Object configObj = spring.get("config");
        if (!(configObj instanceof Map)) {
            return false;
        }
        Map<String, Object> config = (Map<String, Object>) configObj;
        Object activateObj = config.get("activate");
        if (!(activateObj instanceof Map)) {
            return false;
        }
        Map<String, Object> activate = (Map<String, Object>) activateObj;
        return activate.containsKey("on-profile");
    }

    /**
     * Check if YAML data contains profile groups (spring.profiles.group.*).
     */
    @SuppressWarnings("unchecked")
    private boolean hasProfileGroupsSyntax(Map<String, Object> data) {
        if (!data.containsKey("spring")) {
            return false;
        }
        Object springObj = data.get("spring");
        if (!(springObj instanceof Map)) {
            return false;
        }
        Map<String, Object> spring = (Map<String, Object>) springObj;
        Object profilesObj = spring.get("profiles");
        if (!(profilesObj instanceof Map)) {
            return false;
        }
        Map<String, Object> profiles = (Map<String, Object>) profilesObj;
        return profiles.containsKey("group");
    }

    /**
     * Transform YAML data from legacy profile syntax to new format.
     */
    @SuppressWarnings("unchecked")
    private boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        if (data.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");

            // Check for old "spring.profiles" syntax
            if (spring != null && spring.containsKey("profiles") && spring.get("profiles") instanceof String) {
                String profileName = (String) spring.remove("profiles");

                // Create new structure: spring.config.activate.on-profile
                if (!spring.containsKey("config")) {
                    spring.put("config", new LinkedHashMap<>());
                }
                Map<String, Object> config = (Map<String, Object>) spring.get("config");

                if (!config.containsKey("activate")) {
                    config.put("activate", new LinkedHashMap<>());
                }
                Map<String, Object> activate = (Map<String, Object>) config.get("activate");
                activate.put("on-profile", profileName);

                result.addChange(String.format("%s: spring.profiles → spring.config.activate.on-profile", fileName));
                modified = true;
            }

            // Check for profile groups (requires manual review)
            if (hasProfileGroupsSyntax(data)) {
                result.setRequiresManualReview(true);
                result.addWarning(String.format("%s: Profile groups detected - verify processing order", fileName));
            }
        }

        return modified;
    }

    /**
     * Add legacy processing flag for complex configurations.
     */
    private void addLegacyProcessingFlag(Path yamlFile, MigrationPhaseResult result) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data == null) {
                data = new LinkedHashMap<>();
            }

            if (!data.containsKey("spring")) {
                data.put("spring", new LinkedHashMap<>());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");

            if (!spring.containsKey("config")) {
                spring.put("config", new LinkedHashMap<>());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) spring.get("config");
            config.put("use-legacy-processing", true); // Temporary workaround

            if (!dryRun) {
                try (OutputStream output = Files.newOutputStream(yamlFile)) {
                    yaml.dump(data, new OutputStreamWriter(output));
                }
            }

            result.addWarning(
                    String.format("%s: Added spring.config.use-legacy-processing=true - requires manual review",
                            yamlFile.getFileName()));

        } catch (Exception e) {
            result.addError("Failed to add legacy processing flag: " + e.getMessage());
        }
    }

    @Override
    public String getPhaseName() {
        return "Configuration File Processing Migration";
    }

    @Override
    public int getPriority() {
        return 30;
    }
}
