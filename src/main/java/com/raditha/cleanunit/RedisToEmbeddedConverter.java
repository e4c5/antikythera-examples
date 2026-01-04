package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.apache.maven.model.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converter for migrating from Redis Testcontainers to embedded Redis.
 */
public class RedisToEmbeddedConverter implements EmbeddedResourceConverter {
    private static final Logger logger = LoggerFactory.getLogger(RedisToEmbeddedConverter.class);
    private static final String ORG_TESTCONTAINERS = "org.testcontainers";

    @Override
    public boolean canConvert(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        return containerTypes.contains(TestContainerDetector.ContainerType.REDIS)
                || connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.REDIS);
    }

    @Override
    public ConversionResult convert(ClassOrInterfaceDeclaration testClass,
            CompilationUnit cu,
            Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes,
            Path projectRoot) {
        if (testClass == null) {
            return ConversionResult.noChange("Test class is null");
        }

        boolean modified = false;

        // Remove Redis container fields & @Testcontainers
        if (containerTypes.contains(TestContainerDetector.ContainerType.REDIS)) {
            modified |= removeRedisContainerFields(testClass);
        }

        // Modify test property files
        if (projectRoot != null) {
            modified |= modifyPropertyFiles(projectRoot);
        }

        if (!modified) {
            return ConversionResult.noChange("No Redis containers or live connections found");
        }

        String reason = buildConversionReason(containerTypes, connectionTypes);
        return ConversionResult.success("Embedded Redis (it.ozimov:embedded-redis)", reason);
    }

    /**
     * Remove Redis container fields and @Testcontainers annotation.
     */
    private boolean removeRedisContainerFields(ClassOrInterfaceDeclaration testClass) {
        // Find and remove Redis container fields
        List<FieldDeclaration> fieldsToRemove = new ArrayList<>();
        for (FieldDeclaration field : testClass.getFields()) {
            String typeName = field.getCommonType().asString();
            if (typeName.contains("Redis") && typeName.contains("Container")) {
                fieldsToRemove.add(field);
                logger.info("Removing Redis container field: {}", field.getVariable(0).getNameAsString());
            }
        }

        return EmbeddedResourceConverter.removeFields(testClass, fieldsToRemove);
    }

    /**
     * Modify test property files to use embedded Redis.
     */
    private boolean modifyPropertyFiles(Path projectRoot) {
        boolean modified = false;
        PropertyFileManager propManager = new PropertyFileManager();

        // Check for YAML files
        String[] yamlFiles = { "application-test.yml", "application.yml" };
        for (String filename : yamlFiles) {
            Path yamlPath = projectRoot.resolve("src/test/resources/" + filename);
            if (Files.exists(yamlPath)) {
                try {
                    Map<String, Object> config = propManager.readYaml(yamlPath);
                    if (propManager.replaceRedisWithEmbedded(config)) {
                        propManager.writeYaml(yamlPath, config);
                        modified = true;
                        logger.info("Modified {} to use embedded Redis", filename);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to modify {}: {}", filename, e.getMessage());
                }
            }
        }

        // Check for properties files
        String[] propFiles = { "application-test.properties", "application.properties" };
        for (String filename : propFiles) {
            Path propPath = projectRoot.resolve("src/test/resources/" + filename);
            if (Files.exists(propPath)) {
                try {
                    Properties props = propManager.readProperties(propPath);
                    if (propManager.replaceRedisWithEmbedded(props)) {
                        propManager.writeProperties(propPath, props);
                        modified = true;
                        logger.info("Modified {} to use embedded Redis", filename);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to modify {}: {}", filename, e.getMessage());
                }
            }
        }

        return modified;
    }

    /**
     * Build descriptive reason for conversion.
     */
    private String buildConversionReason(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        List<String> reasons = new ArrayList<>();

        if (containerTypes.contains(TestContainerDetector.ContainerType.REDIS)) {
            reasons.add("Removed Redis container");
        }

        if (connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.REDIS)) {
            reasons.add("Replaced live Redis connection");
        }

        return String.join("; ", reasons);
    }

    @Override
    public List<Dependency> getRequiredDependencies() {
        List<Dependency> deps = new ArrayList<>();

        // Embedded Redis library
        Dependency embeddedRedis = new Dependency();
        embeddedRedis.setGroupId("it.ozimov");
        embeddedRedis.setArtifactId("embedded-redis");
        embeddedRedis.setVersion("0.7.3");
        embeddedRedis.setScope("test");
        deps.add(embeddedRedis);

        return deps;
    }

    @Override
    public List<Dependency> getDependenciesToRemove() {
        List<Dependency> deps = new ArrayList<>();

        // Testcontainers (generic - may be used for Redis)
        Dependency testcontainers = new Dependency();
        testcontainers.setGroupId(ORG_TESTCONTAINERS);
        testcontainers.setArtifactId("testcontainers");
        deps.add(testcontainers);

        return deps;
    }
}
