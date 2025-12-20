package com.raditha.spring;

import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Optionally adds lazy initialization configuration to test profiles.
 * 
 * Lazy initialization in Spring Boot 2.2+ can significantly speed up test startup
 * by deferring bean creation until they're actually needed.
 * 
 * This is particularly useful for large applications with many beans.
 * 
 * Note: This is disabled by default and should be enabled via configuration flag.
 */
public class LazyInitializationConfigurer extends MigrationPhase {

    private final boolean enableLazyInit;

    public LazyInitializationConfigurer(boolean dryRun, boolean enableLazyInit) {
        super(dryRun);
        this.enableLazyInit = enableLazyInit;
    }

    /**
     * Add lazy initialization to test profiles if enabled.
     */
    public MigrationPhaseResult migrate() throws IOException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        if (!enableLazyInit) {
            result.addChange("Lazy initialization not enabled - skipping");
            result.addChange("💡 Tip: Enable lazy initialization for faster test startup");
            result.addChange("   Add spring.main.lazy-initialization=true to application-test.yml");
            return result;
        }

        Path basePath = Paths.get(Settings.getBasePath());

        // Find test profile files
        List<Path> testProfiles = findTestProfileFiles(basePath);

        if (testProfiles.isEmpty()) {
            result.addWarning("No test profile files found (application-test.yml/properties)");
            result.addChange("Create application-test.yml with spring.main.lazy-initialization=true for faster tests");
            return result;
        }

        for (Path profileFile : testProfiles) {
            addLazyInitialization(profileFile, result);
        }

        return result;
    }

    /**
     * Find test profile files.
     */
    private List<Path> findTestProfileFiles(Path basePath) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(basePath)) {
            return files;
        }

        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals("application-test.yml") ||
                               fileName.equals("application-test.yaml") ||
                               fileName.equals("application-test.properties");
                    })
                    .forEach(files::add);
        }

        return files;
    }

    /**
     * Add lazy initialization to a profile file.
     */
    @SuppressWarnings("unchecked")
    private void addLazyInitialization(Path profileFile, MigrationPhaseResult result) throws IOException {
        String fileName = profileFile.getFileName().toString();

        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            addToYamlFile(profileFile, result);
        } else if (fileName.endsWith(".properties")) {
            addToPropertiesFile(profileFile, result);
        }

    }

    /**
     * Add lazy initialization to YAML file.
     */
    @SuppressWarnings("unchecked")
    private void addToYamlFile(Path yamlFile, MigrationPhaseResult result) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }

        if (data == null) {
            data = new HashMap<>();
        }

        // Add spring.main.lazy-initialization
        Map<String, Object> spring = (Map<String, Object>) data.computeIfAbsent("spring", k -> new HashMap<>());
        Map<String, Object> main = (Map<String, Object>) spring.computeIfAbsent("main", k -> new HashMap<>());

        if (!main.containsKey("lazy-initialization")) {
            if (!dryRun) {
                main.put("lazy-initialization", true);
                
                try (Writer writer = Files.newBufferedWriter(yamlFile)) {
                    yaml.dump(data, writer);
                }
                
                result.addChange(yamlFile.getFileName() + ": Added spring.main.lazy-initialization=true");
            } else {
                result.addChange(yamlFile.getFileName() + ": Would add spring.main.lazy-initialization=true");
            }
        } else {
            result.addChange(yamlFile.getFileName() + ": lazy-initialization already configured");
        }
    }

    /**
     * Add lazy initialization to properties file.
     */
    private void addToPropertiesFile(Path propFile, MigrationPhaseResult result) throws IOException {
        Properties props = new Properties();

        try (InputStream input = Files.newInputStream(propFile)) {
            props.load(input);
        }

        String key = "spring.main.lazy-initialization";
        
        if (!props.containsKey(key)) {
            if (!dryRun) {
                props.setProperty(key, "true");
                
                try (OutputStream output = Files.newOutputStream(propFile)) {
                    props.store(output, "Added lazy initialization for faster test startup");
                }
                
                result.addChange(propFile.getFileName() + ": Added spring.main.lazy-initialization=true");
            } else {
                result.addChange(propFile.getFileName() + ": Would add spring.main.lazy-initialization=true");
            }
        } else {
            result.addChange(propFile.getFileName() + ": lazy-initialization already configured");
        }
    }

    @Override
    public String getPhaseName() {
        return "Lazy Initialization Configuration";
    }

    @Override
    public int getPriority() {
        return 60;
    }
}
