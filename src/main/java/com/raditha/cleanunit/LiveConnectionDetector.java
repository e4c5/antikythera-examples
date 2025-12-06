package com.raditha.cleanunit;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * Detects live connection configurations in property files and annotations.
 * 
 * Follows code quality guidelines: simple methods, early returns, minimal
 * nesting.
 */
public class LiveConnectionDetector {
    private static final Logger logger = LoggerFactory.getLogger(LiveConnectionDetector.class);

    public enum LiveConnectionType {
        DATABASE,
        KAFKA,
        NONE
    }

    /**
     * Detect live connections configured for a test class.
     * 
     * @param testClass   the test class to analyze
     * @param projectRoot the project root directory
     * @return set of detected live connection types
     */
    public Set<LiveConnectionType> detectLiveConnections(ClassOrInterfaceDeclaration testClass, Path projectRoot) {
        if (testClass == null || projectRoot == null) {
            return EnumSet.noneOf(LiveConnectionType.class);
        }

        Set<LiveConnectionType> connections = EnumSet.noneOf(LiveConnectionType.class);

        // Check @TestPropertySource annotations
        addConnectionsFromAnnotations(testClass, connections);

        // Check application-test.properties
        addConnectionsFromPropertiesFile(projectRoot, connections);

        // Check application-test.yml
        addConnectionsFromYmlFile(projectRoot, connections);

        return connections;
    }

    /**
     * Extract connection types from @TestPropertySource annotations.
     */
    private void addConnectionsFromAnnotations(ClassOrInterfaceDeclaration testClass,
            Set<LiveConnectionType> connections) {
        testClass.getAnnotationByName("TestPropertySource").ifPresent(annotation -> {
            LiveConnectionType type = analyzeTestPropertySource(annotation);
            if (type != LiveConnectionType.NONE) {
                connections.add(type);
            }
        });
    }

    /**
     * Analyze @TestPropertySource annotation for connection properties.
     */
    private LiveConnectionType analyzeTestPropertySource(AnnotationExpr annotation) {
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("properties".equals(pair.getNameAsString())) {
                    String value = pair.getValue().toString();

                    // Check for Kafka properties
                    if (value.contains("kafka.bootstrap-servers") || value.contains("spring.kafka.bootstrap-servers")) {
                        return LiveConnectionType.KAFKA;
                    }

                    // Check for database properties
                    if (value.contains("spring.datasource.url")) {
                        return LiveConnectionType.DATABASE;
                    }
                }
            }
        }
        return LiveConnectionType.NONE;
    }

    /**
     * Check various application properties files for live connections.
     * Checks both application-test.properties and application.properties.
     */
    private void addConnectionsFromPropertiesFile(Path projectRoot, Set<LiveConnectionType> connections) {
        String[] propertyFiles = { "application-test.properties", "application.properties" };

        for (String filename : propertyFiles) {
            Path propertiesPath = projectRoot.resolve("src/test/resources/" + filename);
            if (!Files.exists(propertiesPath)) {
                continue;
            }

            try {
                LiveConnectionType type = analyzePropertyFile(propertiesPath);
                if (type != LiveConnectionType.NONE) {
                    connections.add(type);
                    logger.debug("Detected {} connection in {}", type, filename);
                }
            } catch (IOException e) {
                logger.warn("Failed to read {}: {}", filename, e.getMessage());
            }
        }
    }

    /**
     * Check various application YAML files for live connections.
     * Checks application-test.yml, application.yml, and common profile-specific
     * files.
     */
    private void addConnectionsFromYmlFile(Path projectRoot, Set<LiveConnectionType> connections) {
        String[] ymlFiles = {
                "application-test.yml",
                "application.yml",
                "application-dev.yml",
                "application-h2.yml"
        };

        for (String filename : ymlFiles) {
            Path ymlPath = projectRoot.resolve("src/test/resources/" + filename);
            if (!Files.exists(ymlPath)) {
                continue;
            }

            try {
                LiveConnectionType type = analyzeYmlFile(ymlPath);
                if (type != LiveConnectionType.NONE) {
                    connections.add(type);
                    logger.debug("Detected {} connection in {}", type, filename);
                }
            } catch (IOException e) {
                logger.warn("Failed to read {}: {}", filename, e.getMessage());
            }
        }
    }

    /**
     * Analyze a properties file for connection URLs.
     */
    private LiveConnectionType analyzePropertyFile(Path propertyFile) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertyFile.toFile())) {
            props.load(fis);
        }

        // Check database URL
        String dbUrl = props.getProperty("spring.datasource.url");
        if (isLiveDatabaseUrl(dbUrl)) {
            return LiveConnectionType.DATABASE;
        }

        // Check Kafka bootstrap servers (both standard Spring Boot and custom property
        // names)
        // Just check if the property exists - if it's configured, assume it's a live
        // connection
        String kafkaServers = props.getProperty("spring.kafka.bootstrap-servers");
        if (kafkaServers == null) {
            kafkaServers = props.getProperty("kafka.bootstrap-servers");
        }
        if (kafkaServers != null && !kafkaServers.trim().isEmpty()) {
            return LiveConnectionType.KAFKA;
        }

        return LiveConnectionType.NONE;
    }

    /**
     * Analyze a YAML file for connection URLs.
     * Simple string-based detection - checks for property names.
     */
    private LiveConnectionType analyzeYmlFile(Path ymlFile) throws IOException {
        String content = Files.readString(ymlFile);

        // Check for database configuration
        if (containsLiveDatabaseUrl(content)) {
            return LiveConnectionType.DATABASE;
        }

        // Check for Kafka configuration - just look for bootstrap-servers property
        if (content.contains("bootstrap-servers:")) {
            return LiveConnectionType.KAFKA;
        }

        return LiveConnectionType.NONE;
    }

    /**
     * Check if URL points to a live database (not H2, not embedded).
     */
    private boolean isLiveDatabaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        // Live database indicators
        return url.contains("jdbc:postgresql:")
                || url.contains("jdbc:mysql:")
                || url.contains("jdbc:mariadb:");
    }

    /**
     * Check if YAML content contains live database URL.
     */
    private boolean containsLiveDatabaseUrl(String content) {
        return content.contains("jdbc:postgresql:")
                || content.contains("jdbc:mysql:")
                || content.contains("jdbc:mariadb:");
    }
}
