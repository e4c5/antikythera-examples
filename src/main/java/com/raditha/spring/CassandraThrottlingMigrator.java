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

/**
 * Migrator for Cassandra throttling configuration.
 * 
 * <p>
 * Spring Boot 2.5 removed default throttling values for Cassandra.
 * This migrator automatically adds recommended production throttling
 * configuration
 * if Cassandra is detected and no throttling is configured.
 * 
 * @see AbstractConfigMigrator
 */
public class CassandraThrottlingMigrator extends AbstractConfigMigrator {
    private static final Logger logger = LoggerFactory.getLogger(CassandraThrottlingMigrator.class);

    public CassandraThrottlingMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Checking Cassandra throttling configuration...");
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            // 1. Check for Cassandra dependency (would require POM parsing)
            // For now, check for existing Cassandra properties
            if (!hasCassandraProperties()) {
                result.addChange("No Cassandra configuration detected - skipping");
                return result;
            }

            // 2. Check if throttling already configured
            if (hasExistingThrottlingConfig()) {
                result.addChange("Cassandra throttling already configured - keeping existing settings");
                return result;
            }

            // 3. Add recommended throttling configuration
            addRecommendedThrottlingConfig(result);

        } catch (Exception e) {
            logger.error("Error during Cassandra throttling migration", e);
            result.addError("Cassandra throttling migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Check if Cassandra properties exist in configuration files.
     */
    private boolean hasCassandraProperties() throws IOException {
        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            return false;
        }

        // Check YAML files for spring.data.cassandra or spring.cassandra properties
        return Files.walk(resourcesPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("application.*\\.(yml|yaml)"))
                .anyMatch(this::containsCassandraProperties);
    }

    private boolean containsCassandraProperties(Path yamlFile) {
        try (InputStream input = Files.newInputStream(yamlFile)) {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data = yaml.load(input);
            if (data != null && data.containsKey("spring")) {
                Object springObj = data.get("spring");
                if (springObj instanceof Map) {
                    Map<?, ?> springMap = (Map<?, ?>) springObj;
                    return springMap.containsKey("cassandra") ||
                            (springMap.containsKey("data") &&
                                    springMap.get("data") instanceof Map &&
                                    ((Map<?, ?>) springMap.get("data")).containsKey("cassandra"));
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading YAML file: " + yamlFile, e);
        }
        return false;
    }

    /**
     * Check if throttling configuration already exists.
     */
    private boolean hasExistingThrottlingConfig() throws IOException {
        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            return false;
        }

        return Files.walk(resourcesPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("application.*\\.(yml|yaml)"))
                .anyMatch(this::containsThrottlingConfig);
    }

    private boolean containsThrottlingConfig(Path yamlFile) {
        try (InputStream input = Files.newInputStream(yamlFile)) {
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data = yaml.load(input);
            if (data != null && data.containsKey("spring")) {
                Object springObj = data.get("spring");
                if (springObj instanceof Map) {
                    return checkThrottlingInSpring((Map<?, ?>) springObj);
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading YAML file: " + yamlFile, e);
        }
        return false;
    }

    private boolean checkThrottlingInSpring(Map<?, ?> springMap) {
        if (springMap.containsKey("data") && springMap.get("data") instanceof Map) {
            Map<?, ?> dataMap = (Map<?, ?>) springMap.get("data");
            if (dataMap.containsKey("cassandra") && dataMap.get("cassandra") instanceof Map) {
                Map<?, ?> cassandraMap = (Map<?, ?>) dataMap.get("cassandra");
                if (cassandraMap.containsKey("request") && cassandraMap.get("request") instanceof Map) {
                    Map<?, ?> requestMap = (Map<?, ?>) cassandraMap.get("request");
                    return requestMap.containsKey("throttler");
                }
            }
        }
        return false;
    }

    /**
     * Add recommended Cassandra throttling configuration.
     */
    private void addRecommendedThrottlingConfig(MigrationPhaseResult result) throws IOException {
        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        // Find application.yml or create it
        Path yamlFile = resourcesPath.resolve("application.yml");
        if (!Files.exists(yamlFile)) {
            yamlFile = resourcesPath.resolve("application.yaml");
        }

        if (Files.exists(yamlFile)) {
            addThrottlingToExistingYaml(yamlFile, result);
        } else {
            createYamlWithThrottling(resourcesPath.resolve("application.yml"), result);
        }

        result.addChange("Added Cassandra throttling configuration");
        result.addWarning("Cassandra throttling defaults were removed in Spring Boot 2.5");
        result.addWarning("Added recommended production values - please tune based on your load");
    }

    @SuppressWarnings("unchecked")
    private void addThrottlingToExistingYaml(Path yamlFile, MigrationPhaseResult result) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
            if (data == null) {
                data = new LinkedHashMap<>();
            }
        }

        // Navigate/create spring.data.cassandra.request.throttler structure
        Map<String, Object> spring = (Map<String, Object>) data.computeIfAbsent("spring",
                k -> new LinkedHashMap<>());

        Map<String, Object> dataSection = (Map<String, Object>) spring.computeIfAbsent("data",
                k -> new LinkedHashMap<>());

        Map<String, Object> cassandra = (Map<String, Object>) dataSection.computeIfAbsent("cassandra",
                k -> new LinkedHashMap<>());

        Map<String, Object> request = (Map<String, Object>) cassandra.computeIfAbsent("request",
                k -> new LinkedHashMap<>());

        Map<String, Object> throttler = new LinkedHashMap<>();
        throttler.put("type", "rate-limiting");
        throttler.put("max-queue-size", 10000);
        throttler.put("max-concurrent-requests", 1000);
        throttler.put("max-requests-per-second", 10000);
        throttler.put("drain-interval", "10ms");

        request.put("throttler", throttler);

        // Write back
        if (!dryRun) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml dumper = new Yaml(options);

            try (Writer writer = new FileWriter(yamlFile.toFile())) {
                writer.write("# Spring Boot 2.5 Cassandra configuration\n");
                writer.write("# Modified by SpringBoot24to25Migrator\n");
                writer.write("# Added throttling configuration with recommended production values\n\n");
                dumper.dump(data, writer);
            }
        }
    }

    private void createYamlWithThrottling(Path yamlFile, MigrationPhaseResult result) throws IOException {
        if (dryRun) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> dataSection = new LinkedHashMap<>();
        Map<String, Object> cassandra = new LinkedHashMap<>();
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> throttler = new LinkedHashMap<>();

        throttler.put("type", "rate-limiting");
        throttler.put("max-queue-size", 10000);
        throttler.put("max-concurrent-requests", 1000);
        throttler.put("max-requests-per-second", 10000);
        throttler.put("drain-interval", "10ms");

        request.put("throttler", throttler);
        cassandra.put("request", request);
        dataSection.put("cassandra", cassandra);
        spring.put("data", dataSection);
        data.put("spring", spring);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml dumper = new Yaml(options);

        try (Writer writer = new FileWriter(yamlFile.toFile())) {
            writer.write("# Spring Boot 2.5 Cassandra configuration\n");
            writer.write("# Created by SpringBoot24to25Migrator\n");
            writer.write("# Added throttling configuration with recommended production values\n\n");
            dumper.dump(data, writer);
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
