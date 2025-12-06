package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import org.apache.maven.model.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts Kafka test containers to embedded Kafka.
 * 
 * Removes @Container Kafka fields and adds @EmbeddedKafka annotation.
 * Follows simple method patterns with early returns.
 */
public class KafkaToEmbeddedConverter implements EmbeddedResourceConverter {
    private static final Logger logger = LoggerFactory.getLogger(KafkaToEmbeddedConverter.class);

    @Override
    public boolean canConvert(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        TestContainerDetector detector = new TestContainerDetector();
        return detector.hasKafkaContainer(containerTypes)
                || connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.KAFKA);
    }

    @Override
    public ConversionResult convert(ClassOrInterfaceDeclaration testClass,
            CompilationUnit cu,
            Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes,
            java.nio.file.Path projectRoot) {
        if (testClass == null) {
            return ConversionResult.noChange("Test class is null");
        }

        // Only convert if Kafka is actually detected
        boolean hasKafkaContainer = containerTypes.contains(TestContainerDetector.ContainerType.KAFKA);
        boolean hasKafkaConnection = connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.KAFKA);

        if (!hasKafkaContainer && !hasKafkaConnection) {
            return ConversionResult.noChange("No Kafka containers or live connections found");
        }

        boolean modified = false;

        // Remove container fields
        if (hasKafkaContainer) {
            modified |= removeKafkaContainerFields(testClass);
            modified |= removeTestcontainersAnnotation(testClass);
        }

        // Add embedded Kafka annotation - needed for both container and connection
        // cases
        modified |= addEmbeddedKafkaAnnotation(testClass, cu);

        // Modify test property files - only once globally
        if (projectRoot != null && hasKafkaConnection) {
            modified |= modifyPropertyFiles(projectRoot);
        }

        if (!modified) {
            return ConversionResult.noChange("No changes made");
        }

        String reason = buildConversionReason(containerTypes, connectionTypes);
        return ConversionResult.success("@EmbeddedKafka", reason);
    }

    /**
     * Remove Kafka container fields.
     */
    private boolean removeKafkaContainerFields(ClassOrInterfaceDeclaration testClass) {
        List<FieldDeclaration> toRemove = new ArrayList<>();

        for (FieldDeclaration field : testClass.getFields()) {
            if (isKafkaContainerField(field)) {
                toRemove.add(field);
                logger.debug("Removing Kafka container field: {}",
                        field.getVariable(0).getNameAsString());
            }
        }

        toRemove.forEach(FieldDeclaration::remove);
        return !toRemove.isEmpty();
    }

    /**
     * Check if field is a Kafka container.
     */
    private boolean isKafkaContainerField(FieldDeclaration field) {
        if (!field.getAnnotationByName("Container").isPresent()) {
            return false;
        }

        String typeName = field.getElementType().asString();
        return typeName.contains("KafkaContainer");
    }

    /**
     * Remove @Testcontainers annotation if no containers remain.
     */
    private boolean removeTestcontainersAnnotation(ClassOrInterfaceDeclaration testClass) {
        if (!hasContainerFields(testClass)) {
            testClass.getAnnotationByName("Testcontainers").ifPresent(annotation -> {
                annotation.remove();
                logger.debug("Removed @Testcontainers annotation");
            });
            return true;
        }
        return false;
    }

    /**
     * Check if class still has any @Container fields.
     */
    private boolean hasContainerFields(ClassOrInterfaceDeclaration testClass) {
        return testClass.getFields().stream()
                .anyMatch(field -> field.getAnnotationByName("Container").isPresent());
    }

    /**
     * Add @EmbeddedKafka annotation with default configuration.
     */
    private boolean addEmbeddedKafkaAnnotation(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        if (testClass.getAnnotationByName("EmbeddedKafka").isPresent()) {
            return false;
        }

        // Add @EmbeddedKafka with default configuration
        NormalAnnotationExpr embeddedKafka = new NormalAnnotationExpr();
        embeddedKafka.setName("EmbeddedKafka");
        embeddedKafka.addPair("partitions", "1");
        testClass.addAnnotation(embeddedKafka);
        cu.addImport("org.springframework.kafka.test.context.EmbeddedKafka");

        // Add @TestPropertySource to override kafka.bootstrap-servers with embedded
        // broker
        // This resolves "${kafka.bootstrap-servers}" placeholders in the application
        // code
        NormalAnnotationExpr testPropertySource = new NormalAnnotationExpr();
        testPropertySource.setName("TestPropertySource");

        // Create array of properties using proper syntax
        testPropertySource.addPair("properties",
                "{" +
                        "\"kafka.bootstrap-servers=${spring.embedded.kafka.brokers}\", " +
                        "\"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}\"" +
                        "}");

        testClass.addAnnotation(testPropertySource);
        cu.addImport("org.springframework.test.context.TestPropertySource");

        logger.info("Added @EmbeddedKafka and @TestPropertySource to {}", testClass.getNameAsString());
        return true;
    }

    /**
     * Build descriptive reason for conversion.
     */
    private String buildConversionReason(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        List<String> reasons = new ArrayList<>();

        if (containerTypes.contains(TestContainerDetector.ContainerType.KAFKA)) {
            reasons.add("Removed Kafka container");
        }

        if (connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.KAFKA)) {
            reasons.add("Replaced live Kafka connection");
        }

        return String.join("; ", reasons);
    }

    @Override
    public List<Dependency> getRequiredDependencies() {
        List<Dependency> deps = new ArrayList<>();

        // spring-kafka-test for embedded Kafka
        Dependency kafkaTest = new Dependency();
        kafkaTest.setGroupId("org.springframework.kafka");
        kafkaTest.setArtifactId("spring-kafka-test");
        kafkaTest.setScope("test");
        deps.add(kafkaTest);

        return deps;
    }

    /**
     * Modify test property files to use embedded Kafka.
     */
    private boolean modifyPropertyFiles(java.nio.file.Path projectRoot) {
        boolean modified = false;
        PropertyFileManager propManager = new PropertyFileManager();

        // Check for YAML files
        String[] yamlFiles = { "application-test.yml", "application.yml" };
        for (String filename : yamlFiles) {
            java.nio.file.Path yamlPath = projectRoot.resolve("src/test/resources/" + filename);
            if (java.nio.file.Files.exists(yamlPath)) {
                try {
                    java.util.Map<String, Object> config = propManager.readYaml(yamlPath);
                    if (propManager.replaceKafkaWithEmbedded(config)) {
                        propManager.writeYaml(yamlPath, config);
                        modified = true;
                        logger.info("Modified {} to use embedded Kafka", filename);
                    }
                } catch (java.io.IOException e) {
                    logger.warn("Failed to modify {}: {}", filename, e.getMessage());
                }
            }
        }

        // Check for properties files
        String[] propFiles = { "application-test.properties", "application.properties" };
        for (String filename : propFiles) {
            java.nio.file.Path propPath = projectRoot.resolve("src/test/resources/" + filename);
            if (java.nio.file.Files.exists(propPath)) {
                try {
                    java.util.Properties props = propManager.readProperties(propPath);
                    if (propManager.replaceKafkaWithEmbedded(props)) {
                        propManager.writeProperties(propPath, props);
                        modified = true;
                        logger.info("Modified {} to use embedded Kafka", filename);
                    }
                } catch (java.io.IOException e) {
                    logger.warn("Failed to modify {}: {}", filename, e.getMessage());
                }
            }
        }

        return modified;
    }

    @Override
    public List<Dependency> getDependenciesToRemove() {
        List<Dependency> deps = new ArrayList<>();

        // Testcontainers Kafka module
        Dependency kafkaContainer = new Dependency();
        kafkaContainer.setGroupId("org.testcontainers");
        kafkaContainer.setArtifactId("kafka");
        deps.add(kafkaContainer);

        // Core testcontainers if no other containers
        Dependency testcontainers = new Dependency();
        testcontainers.setGroupId("org.testcontainers");
        testcontainers.setArtifactId("testcontainers");
        deps.add(testcontainers);

        return deps;
    }
}
