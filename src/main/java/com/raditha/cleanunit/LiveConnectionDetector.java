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
                    return detectConnectionTypeFromValue(value);
                }
            }
        }
        return LiveConnectionType.NONE;
    }

    /**
     * Check application-test.properties for live connections.
     */
    private void addConnectionsFromPropertiesFile(Path projectRoot, Set<LiveConnectionType> connections) {
        Path propertiesPath = projectRoot.resolve("src/test/resources/application-test.properties");
        if (!Files.exists(propertiesPath)) {
            return;
        }

        try {
            LiveConnectionType type = analyzePropertyFile(propertiesPath);
            if (type != LiveConnectionType.NONE) {
                connections.add(type);
                logger.debug("Detected {} connection in application-test.properties", type);
            }
        } catch (IOException e) {
            logger.warn("Failed to read application-test.properties: {}", e.getMessage());
        }
    }

    /**
     * Check application-test.yml for live connections.
     */
    private void addConnectionsFromYmlFile(Path projectRoot, Set<LiveConnectionType> connections) {
        Path ymlPath = projectRoot.resolve("src/test/resources/application-test.yml");
        if (!Files.exists(ymlPath)) {
            return;
        }

        try {
            LiveConnectionType type = analyzeYmlFile(ymlPath);
            if (type != LiveConnectionType.NONE) {
                connections.add(type);
                logger.debug("Detected {} connection in application-test.yml", type);
            }
        } catch (IOException e) {
            logger.warn("Failed to read application-test.yml: {}", e.getMessage());
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

        // Check Kafka bootstrap servers
        String kafkaServers = props.getProperty("spring.kafka.bootstrap-servers");
        if (isLiveKafkaUrl(kafkaServers)) {
            return LiveConnectionType.KAFKA;
        }

        return LiveConnectionType.NONE;
    }

    /**
     * Analyze a YAML file for connection URLs.
     * Simple string-based detection for now.
     */
    private LiveConnectionType analyzeYmlFile(Path ymlFile) throws IOException {
        String content = Files.readString(ymlFile);

        if (containsLiveDatabaseUrl(content)) {
            return LiveConnectionType.DATABASE;
        }

        if (containsLiveKafkaUrl(content)) {
            return LiveConnectionType.KAFKA;
        }

        return LiveConnectionType.NONE;
    }

    /**
     * Check if a value contains connection configuration.
     */
    private LiveConnectionType detectConnectionTypeFromValue(String value) {
        if (value == null) {
            return LiveConnectionType.NONE;
        }

        if (isLiveDatabaseUrl(value)) {
            return LiveConnectionType.DATABASE;
        }

        if (isLiveKafkaUrl(value)) {
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
     * Check if URL points to a live Kafka broker (not embedded).
     */
    private boolean isLiveKafkaUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        // Live Kafka indicators (localhost:9092, etc.)
        return url.contains("localhost:9092")
                || url.contains("kafka:")
                || (url.contains(":9092") && !url.contains("${"));
    }

    /**
     * Check if YAML content contains live database URL.
     */
    private boolean containsLiveDatabaseUrl(String content) {
        return content.contains("jdbc:postgresql:")
                || content.contains("jdbc:mysql:")
                || content.contains("jdbc:mariadb:");
    }

    /**
     * Check if YAML content contains live Kafka URL.
     */
    private boolean containsLiveKafkaUrl(String content) {
        return content.contains("localhost:9092")
                || content.contains("kafka:")
                || content.contains("bootstrap-servers");
    }
}
