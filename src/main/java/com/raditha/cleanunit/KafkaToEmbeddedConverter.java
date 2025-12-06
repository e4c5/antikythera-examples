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
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        if (testClass == null) {
            return ConversionResult.noChange("Test class is null");
        }

        boolean modified = false;

        // Remove container fields
        if (containerTypes.contains(TestContainerDetector.ContainerType.KAFKA)) {
            modified |= removeKafkaContainerFields(testClass);
            modified |= removeTestcontainersAnnotation(testClass);
        }

        // Add embedded Kafka annotation
        modified |= addEmbeddedKafkaAnnotation(testClass, cu);

        if (!modified) {
            return ConversionResult.noChange("No Kafka containers or live connections found");
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
