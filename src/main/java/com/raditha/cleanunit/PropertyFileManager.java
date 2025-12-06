package com.raditha.cleanunit;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Manages reading and writing of YAML and properties configuration files.
 * Specifically designed for modifying test property files to replace live
 * connections with embedded alternatives.
 */
public class PropertyFileManager {
    private static final Logger logger = LoggerFactory.getLogger(PropertyFileManager.class);

    /**
     * Read a YAML file into a Map structure.
     *
     * @param file path to YAML file
     * @return Map representation of YAML content
     * @throws IOException if file cannot be read
     */
    public Map<String, Object> readYaml(Path file) throws IOException {
        logger.debug("Reading YAML file: {}", file);
        Yaml yaml = new Yaml();
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            Map<String, Object> data = yaml.load(fis);
            return data != null ? data : new LinkedHashMap<>();
        }
    }

    /**
     * Write a Map structure to a YAML file, preserving structure and formatting.
     *
     * @param file path to YAML file
     * @param data Map to write
     * @throws IOException if file cannot be written
     */
    public void writeYaml(Path file, Map<String, Object> data) throws IOException {
        logger.debug("Writing YAML file: {}", file);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);

        Yaml yaml = new Yaml(options);

        try (FileWriter writer = new FileWriter(file.toFile())) {
            yaml.dump(data, writer);
        }
    }

    /**
     * Read a Java properties file.
     *
     * @param file path to properties file
     * @return Properties object
     * @throws IOException if file cannot be read
     */
    public Properties readProperties(Path file) throws IOException {
        logger.debug("Reading properties file: {}", file);
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            props.load(fis);
        }
        return props;
    }

    /**
     * Write a Properties object to a file.
     *
     * @param file  path to properties file
     * @param props Properties to write
     * @throws IOException if file cannot be written
     */
    public void writeProperties(Path file, Properties props) throws IOException {
        logger.debug("Writing properties file: {}", file);
        try (FileWriter writer = new FileWriter(file.toFile())) {
            props.store(writer, "Modified by TestFixer - Embedded Resource Conversion");
        }
    }

    /**
     * Replace Kafka bootstrap servers configuration with embedded placeholder.
     * Handles both standard Spring Boot and custom property names.
     *
     * @param config YAML configuration map
     * @return true if any modifications were made
     */
    @SuppressWarnings("unchecked")
    public boolean replaceKafkaWithEmbedded(Map<String, Object> config) {
        boolean modified = false;

        // Handle spring.kafka.bootstrap-servers
        if (config.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) config.get("spring");
            if (spring.containsKey("kafka")) {
                Map<String, Object> kafka = (Map<String, Object>) spring.get("kafka");
                if (kafka.containsKey("bootstrap-servers")) {
                    kafka.put("bootstrap-servers", "${spring.embedded.kafka.brokers}");
                    modified = true;
                    logger.info("Replaced spring.kafka.bootstrap-servers with embedded placeholder");
                }
            }
        }

        // Handle custom kafka.bootstrap-servers (common in some projects)
        if (config.containsKey("kafka")) {
            Map<String, Object> kafka = (Map<String, Object>) config.get("kafka");
            if (kafka.containsKey("bootstrap-servers")) {
                kafka.put("bootstrap-servers", "${spring.embedded.kafka.brokers}");
                modified = true;
                logger.info("Replaced kafka.bootstrap-servers with embedded placeholder");
            }
        }

        return modified;
    }

    /**
     * Replace database configuration with H2 in-memory database.
     *
     * @param config YAML configuration map
     * @return true if any modifications were made
     */
    @SuppressWarnings("unchecked")
    public boolean replaceDatabaseWithH2(Map<String, Object> config) {
        boolean modified = false;

        if (config.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) config.get("spring");
            if (spring.containsKey("datasource")) {
                Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");

                // Check if it's a live database URL
                if (datasource.containsKey("url")) {
                    String url = datasource.get("url").toString();
                    if (isLiveDatabaseUrl(url)) {
                        datasource.put("url", "jdbc:h2:mem:testdb");
                        datasource.put("driver-class-name", "org.h2.Driver");
                        datasource.remove("username");
                        datasource.remove("password");
                        modified = true;
                        logger.info("Replaced live database URL with H2 in-memory database");
                    }
                }
            }
        }

        return modified;
    }

    /**
     * Replace Redis configuration with embedded settings.
     *
     * @param config YAML configuration map
     * @return true if any modifications were made
     */
    @SuppressWarnings("unchecked")
    public boolean replaceRedisWithEmbedded(Map<String, Object> config) {
        boolean modified = false;

        if (config.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) config.get("spring");
            if (spring.containsKey("redis")) {
                Map<String, Object> redis = (Map<String, Object>) spring.get("redis");

                // Set localhost and default embedded port
                if (redis.containsKey("host") || redis.containsKey("port")) {
                    redis.put("host", "localhost");
                    redis.put("port", 6370); // embedded-redis default port
                    modified = true;
                    logger.info("Replaced Redis configuration with embedded settings");
                }
            }
        }

        return modified;
    }

    /**
     * Replace MongoDB configuration with embedded settings.
     *
     * @param config YAML configuration map
     * @return true if any modifications were made
     */
    @SuppressWarnings("unchecked")
    public boolean replaceMongoWithEmbedded(Map<String, Object> config) {
        boolean modified = false;

        if (config.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) config.get("spring");
            if (spring.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) spring.get("data");
                if (data.containsKey("mongodb")) {
                    Map<String, Object> mongodb = (Map<String, Object>) data.get("mongodb");

                    // Remove URI to let embedded MongoDB auto-configure
                    if (mongodb.containsKey("uri")) {
                        mongodb.remove("uri");
                        modified = true;
                        logger.info("Removed MongoDB URI to use embedded MongoDB");
                    }
                }
            }
        }

        return modified;
    }

    /**
     * Check if URL points to a live database (not H2, not HSQLDB).
     *
     * @param url database URL
     * @return true if it's a live database URL
     */
    private boolean isLiveDatabaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        return url.contains("jdbc:postgresql:")
                || url.contains("jdbc:mysql:")
                || url.contains("jdbc:mariadb:")
                || url.contains("jdbc:oracle:");
    }

    /**
     * Replace Kafka configuration in a properties object.
     *
     * @param props Properties object
     * @return true if any modifications were made
     */
    public boolean replaceKafkaWithEmbedded(Properties props) {
        boolean modified = false;

        if (props.containsKey("spring.kafka.bootstrap-servers")) {
            props.setProperty("spring.kafka.bootstrap-servers", "${spring.embedded.kafka.brokers}");
            modified = true;
            logger.info("Replaced spring.kafka.bootstrap-servers in properties");
        }

        if (props.containsKey("kafka.bootstrap-servers")) {
            props.setProperty("kafka.bootstrap-servers", "${spring.embedded.kafka.brokers}");
            modified = true;
            logger.info("Replaced kafka.bootstrap-servers in properties");
        }

        return modified;
    }

    /**
     * Replace database configuration in a properties object.
     *
     * @param props Properties object
     * @return true if any modifications were made
     */
    public boolean replaceDatabaseWithH2(Properties props) {
        boolean modified = false;

        if (props.containsKey("spring.datasource.url")) {
            String url = props.getProperty("spring.datasource.url");
            if (isLiveDatabaseUrl(url)) {
                props.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb");
                props.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
                props.remove("spring.datasource.username");
                props.remove("spring.datasource.password");
                modified = true;
                logger.info("Replaced live database URL with H2 in properties");
            }
        }

        return modified;
    }

    /**
     * Replace Redis configuration with embedded settings (Properties overload).
     */
    public boolean replaceRedisWithEmbedded(Properties props) {
        boolean modified = false;

        if (props.containsKey("spring.redis.host")) {
            props.setProperty("spring.redis.host", "localhost");
            modified = true;
            logger.debug("Updated spring.redis.host to localhost");
        }

        if (props.containsKey("spring.redis.port")) {
            props.setProperty("spring.redis.port", "6370");
            modified = true;
            logger.debug("Updated spring.redis.port to 6370");
        }

        return modified;
    }

    /**
     * Replace MongoDB URI to enable autoconfiguration of embedded MongoDB
     * (Properties overload).
     */
    public boolean replaceMongoWithEmbedded(Properties props) {
        boolean modified = false;

        if (props.containsKey("spring.data.mongodb.uri")) {
            props.remove("spring.data.mongodb.uri");
            modified = true;
            logger.debug("Removed spring.data.mongodb.uri to enable embedded MongoDB");
        }

        return modified;
    }
}
