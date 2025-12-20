package com.raditha.spring;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
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
 * Configures Cassandra request throttling for Spring Boot 2.5.
 * 
 * <p>
 * Spring Boot 2.5 changes:
 * <ul>
 * <li>Default throttling property values removed</li>
 * <li>Explicit configuration now required if using throttling</li>
 * <li>Properties: max-queue-size, max-concurrent-requests, max-requests-per-second, drain-interval</li>
 * </ul>
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects Cassandra dependency usage</li>
 * <li>Checks for existing throttling configuration</li>
 * <li>Adds recommended throttling configuration if missing</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class CassandraThrottlingMigrator extends AbstractConfigMigrator {

    public CassandraThrottlingMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path basePath = Paths.get(Settings.getBasePath());

        // Step 1: Check for Cassandra dependency
        if (!hasCassandraDependency(basePath, result)) {
            result.addChange("No Cassandra dependency detected - no changes needed");
            return result;
        }

        // Step 2: Check if throttling configuration already exists
        if (hasExistingThrottlingConfig(basePath, result)) {
            result.addChange("Cassandra throttling already configured - keeping existing settings");
            return result;
        }

        // Step 3: Add recommended throttling configuration
        addRecommendedThrottlingConfig(basePath, result);

        return result;
    }

    /**
     * Check if Cassandra dependency is present.
     */
    private boolean hasCassandraDependency(Path basePath, MigrationPhaseResult result) {
        Path pomPath = basePath.resolve("pom.xml");
        
        if (!Files.exists(pomPath)) {
            return false;
        }

        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model;
            try (InputStream input = Files.newInputStream(pomPath)) {
                model = reader.read(input);
            }

            boolean hasCassandra = model.getDependencies().stream()
                    .anyMatch(dep -> (dep.getArtifactId() != null && dep.getArtifactId().contains("cassandra")));

            if (hasCassandra) {
                result.addChange("Cassandra dependency detected");
            }

            return hasCassandra;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if throttling configuration already exists.
     */
    @SuppressWarnings("unchecked")
    private boolean hasExistingThrottlingConfig(Path basePath, MigrationPhaseResult result) throws IOException {
        Path resourcesPath = basePath.resolve("src/main/resources");
        
        if (!Files.exists(resourcesPath)) {
            return false;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        // Check YAML files
        for (Path yamlFile : yamlFiles) {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(yamlFile)) {
                data = yaml.load(input);
            }

            if (data != null && data.containsKey("spring")) {
                Map<String, Object> spring = (Map<String, Object>) data.get("spring");
                if (spring != null && spring.containsKey("data")) {
                    Map<String, Object> dataSection = (Map<String, Object>) spring.get("data");
                    if (dataSection != null && dataSection.containsKey("cassandra")) {
                        Map<String, Object> cassandra = (Map<String, Object>) dataSection.get("cassandra");
                        if (cassandra != null && cassandra.containsKey("request")) {
                            Map<String, Object> request = (Map<String, Object>) cassandra.get("request");
                            if (request != null && request.containsKey("throttler")) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // Check properties files
        for (Path propFile : propFiles) {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            if (props.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("spring.data.cassandra.request.throttler"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add recommended throttling configuration.
     */
    @SuppressWarnings("unchecked")
    private void addRecommendedThrottlingConfig(Path basePath, MigrationPhaseResult result) throws IOException {
        Path resourcesPath = basePath.resolve("src/main/resources");
        
        if (!Files.exists(resourcesPath)) {
            result.addWarning("No resources directory found - cannot add Cassandra throttling configuration");
            return;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        // Prefer YAML files
        if (!yamlFiles.isEmpty()) {
            Path mainYaml = yamlFiles.stream()
                    .filter(p -> p.getFileName().toString().equals("application.yml") ||
                                 p.getFileName().toString().equals("application.yaml"))
                    .findFirst()
                    .orElse(yamlFiles.get(0));
            
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(mainYaml)) {
                data = yaml.load(input);
            }

            if (data == null) {
                data = new LinkedHashMap<>();
            }

            // Create structure: spring.data.cassandra.request.throttler
            if (!data.containsKey("spring")) {
                data.put("spring", new LinkedHashMap<>());
            }

            Map<String, Object> spring = (Map<String, Object>) data.get("spring");
            if (!spring.containsKey("data")) {
                spring.put("data", new LinkedHashMap<>());
            }

            Map<String, Object> dataSection = (Map<String, Object>) spring.get("data");
            if (!dataSection.containsKey("cassandra")) {
                dataSection.put("cassandra", new LinkedHashMap<>());
            }

            Map<String, Object> cassandra = (Map<String, Object>) dataSection.get("cassandra");
            if (!cassandra.containsKey("request")) {
                cassandra.put("request", new LinkedHashMap<>());
            }

            Map<String, Object> request = (Map<String, Object>) cassandra.get("request");
            Map<String, Object> throttler = new LinkedHashMap<>();
            throttler.put("type", "rate-limiting");
            throttler.put("max-queue-size", 10000);
            throttler.put("max-concurrent-requests", 1000);
            throttler.put("max-requests-per-second", 10000);
            throttler.put("drain-interval", "10ms");
            
            request.put("throttler", throttler);

            if (!dryRun) {
                try (OutputStream output = Files.newOutputStream(mainYaml)) {
                    yaml.dump(data, new OutputStreamWriter(output));
                }
            }
            
            result.addChange(String.format("[%s] Added Cassandra throttling configuration with recommended values",
                    mainYaml.getFileName()));
            result.addWarning("IMPORTANT: Tune throttling values based on your actual load requirements");
        } else if (!propFiles.isEmpty()) {
            Path mainProp = propFiles.stream()
                    .filter(p -> p.getFileName().toString().equals("application.properties"))
                    .findFirst()
                    .orElse(propFiles.get(0));
            
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(mainProp)) {
                props.load(input);
            }

            props.setProperty("spring.data.cassandra.request.throttler.type", "rate-limiting");
            props.setProperty("spring.data.cassandra.request.throttler.max-queue-size", "10000");
            props.setProperty("spring.data.cassandra.request.throttler.max-concurrent-requests", "1000");
            props.setProperty("spring.data.cassandra.request.throttler.max-requests-per-second", "10000");
            props.setProperty("spring.data.cassandra.request.throttler.drain-interval", "10ms");

            if (!dryRun) {
                try (OutputStream output = Files.newOutputStream(mainProp)) {
                    props.store(output, "Updated by CassandraThrottlingMigrator for Spring Boot 2.5");
                }
            }
            
            result.addChange(String.format("[%s] Added Cassandra throttling configuration with recommended values",
                    mainProp.getFileName()));
            result.addWarning("IMPORTANT: Tune throttling values based on your actual load requirements");
        }
    }

    @Override
    public String getPhaseName() {
        return "Cassandra Throttling Configuration";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
