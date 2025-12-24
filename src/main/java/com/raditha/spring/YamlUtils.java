package com.raditha.spring;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Shared YAML utilities for Spring Boot migration.
 */
public final class YamlUtils {

    private YamlUtils() {
        // Utility class
    }

    /**
     * Create a Yaml instance with proper block-style configuration.
     * 
     * @return configured Yaml instance
     */
    public static Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }
}





