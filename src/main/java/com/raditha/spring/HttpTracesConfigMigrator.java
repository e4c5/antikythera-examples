package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Detects HTTP trace configuration for Spring Boot 2.4 migration.
 * 
 * <p>
 * Spring Boot 2.4 changed the default behavior for HTTP traces:
 * <ul>
 * <li>Request cookies are now EXCLUDED by default</li>
 * <li>Set-Cookie response headers are now EXCLUDED by default</li>
 * <li>Must explicitly include cookie-headers if needed</li>
 * </ul>
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects presence of {@code management.trace.http} configuration</li>
 * <li>Warns about changed cookie exclusion behavior</li>
 * <li>Provides guidance on restoring previous behavior if needed</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class HttpTracesConfigMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(HttpTracesConfigMigrator.class);

    private final boolean dryRun;

    public HttpTracesConfigMigrator(boolean dryRun) {
        this.dryRun = dryRun;
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

            List<Path> yamlFiles = findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
            List<Path> propFiles = findPropertyFiles(resourcesPath, "*.properties");

            boolean hasHttpTraceConfig = false;

            // Check YAML files
            for (Path yamlFile : yamlFiles) {
                if (hasHttpTraceConfigYaml(yamlFile)) {
                    hasHttpTraceConfig = true;
                    result.addChange(String.format("%s: HTTP trace configuration detected", 
                            yamlFile.getFileName()));
                }
            }

            // Check properties files
            for (Path propFile : propFiles) {
                if (hasHttpTraceConfigProperties(propFile)) {
                    hasHttpTraceConfig = true;
                    result.addChange(String.format("%s: HTTP trace configuration detected", 
                            propFile.getFileName()));
                }
            }

            if (hasHttpTraceConfig) {
                result.addWarning("HTTP_TRACES: Spring Boot 2.4 excludes cookies from HTTP traces by default");
                result.addWarning("HTTP_TRACES: Previously, request cookies and Set-Cookie headers were included");
                result.addWarning("HTTP_TRACES: To restore previous behavior, add:");
                result.addWarning("  management.trace.http.include=cookie-headers,request-headers,response-headers");
                result.setRequiresManualReview(true);
                logger.warn("HTTP trace configuration detected - review cookie exclusion behavior");
            } else {
                result.addChange("No HTTP trace configuration detected");
            }

        } catch (Exception e) {
            result.addError("HTTP trace configuration detection failed: " + e.getMessage());
            logger.error("HTTP trace configuration detection failed", e);
        }

        return result;
    }

    /**
     * Find all property files matching the given patterns.
     */
    private List<Path> findPropertyFiles(Path basePath, String... patterns) throws Exception {
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
     * Check if YAML file has HTTP trace configuration.
     */
    @SuppressWarnings("unchecked")
    private boolean hasHttpTraceConfigYaml(Path yamlFile) {
        try {
            Yaml yaml = YamlUtils.createYaml();
            Iterable<Object> documents;
            try (InputStream input = Files.newInputStream(yamlFile)) {
                documents = yaml.loadAll(input);
            }

            for (Object doc : documents) {
                if (!(doc instanceof Map)) {
                    continue;
                }

                Map<String, Object> data = (Map<String, Object>) doc;
                if (data.containsKey("management")) {
                    Object managementObj = data.get("management");
                    if (!(managementObj instanceof Map)) {
                        continue;
                    }

                    Map<String, Object> management = (Map<String, Object>) managementObj;
                    if (management.containsKey("trace")) {
                        Object traceObj = management.get("trace");
                        if (!(traceObj instanceof Map)) {
                            continue;
                        }

                        Map<String, Object> trace = (Map<String, Object>) traceObj;
                        if (trace.containsKey("http")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking HTTP trace config in {}", yamlFile, e);
        }

        return false;
    }

    /**
     * Check if properties file has HTTP trace configuration.
     */
    private boolean hasHttpTraceConfigProperties(Path propFile) {
        try {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(propFile)) {
                props.load(input);
            }

            return props.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("management.trace.http"));

        } catch (Exception e) {
            logger.debug("Error checking HTTP trace config in {}", propFile, e);
        }

        return false;
    }

    @Override
    public String getPhaseName() {
        return "HTTP Trace Configuration Detection";
    }

    @Override
    public int getPriority() {
        return 70;
    }
}
