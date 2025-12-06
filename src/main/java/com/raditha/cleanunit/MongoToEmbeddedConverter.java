package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import org.apache.maven.model.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converter for migrating from MongoDB Testcontainers to embedded MongoDB.
 */
public class MongoToEmbeddedConverter implements EmbeddedResourceConverter {
    private static final Logger logger = LoggerFactory.getLogger(MongoToEmbeddedConverter.class);
    private static final String ORG_TESTCONTAINERS = "org.testcontainers";

    @Override
    public boolean canConvert(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        return containerTypes.contains(TestContainerDetector.ContainerType.MONGODB)
                || connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.MONGODB);
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

        // Remove MongoDB container fields & @Testcontainers
        if (containerTypes.contains(TestContainerDetector.ContainerType.MONGODB)) {
            modified |= removeMongoContainerFields(testClass, cu);
        }

        // Add @DataMongoTest annotation for Spring Data MongoDB tests
        modified |= addDataMongoTestAnnotation(testClass, cu);

        // Modify test property files
        if (projectRoot != null) {
            modified |= modifyPropertyFiles(projectRoot);
        }

        if (!modified) {
            return ConversionResult.noChange("No MongoDB containers or live connections found");
        }

        String reason = buildConversionReason(containerTypes, connectionTypes);
        return ConversionResult.success("Embedded MongoDB (de.flapdoodle.embed:de.flapdoodle.embed.mongo)", reason);
    }

    /**
     * Remove MongoDB container fields and @Testcontainers annotation.
     */
    private boolean removeMongoContainerFields(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        boolean modified = false;

        // Find and remove MongoDB container fields
        List<FieldDeclaration> fieldsToRemove = new ArrayList<>();
        for (FieldDeclaration field : testClass.getFields()) {
            String typeName = field.getCommonType().asString();
            if (typeName.contains("Mongo") && typeName.contains("Container")) {
                fieldsToRemove.add(field);
                logger.info("Removing MongoDB container field: {}", field.getVariable(0).getNameAsString());
            }
        }

        for (FieldDeclaration field : fieldsToRemove) {
            field.remove();
            modified = true;
        }

        // Remove @Testcontainers if no more containers
        if (!fieldsToRemove.isEmpty()) {
            boolean hasRemainingContainers = testClass.getFields().stream()
                    .anyMatch(f -> f.getAnnotationByName("Container").isPresent());

            if (!hasRemainingContainers) {
                testClass.getAnnotationByName("Testcontainers").ifPresent(annotation -> {
                    annotation.remove();
                    logger.info("Removed @Testcontainers annotation");
                });
            }
        }

        return modified;
    }

    /**
     * Add @DataMongoTest annotation if not present.
     */
    private boolean addDataMongoTestAnnotation(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        // Check if annotation already exists
        if (testClass.getAnnotationByName("DataMongoTest").isPresent()) {
            return false;
        }

        // Add @DataMongoTest annotation
        MarkerAnnotationExpr dataMongoTest = new MarkerAnnotationExpr("DataMongoTest");
        testClass.getAnnotations().addFirst(dataMongoTest);
        cu.addImport("org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest");
        logger.info("Added @DataMongoTest to {}", testClass.getNameAsString());
        return true;
    }

    /**
     * Modify test property files to use embedded MongoDB.
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
                    if (propManager.replaceMongoWithEmbedded(config)) {
                        propManager.writeYaml(yamlPath, config);
                        modified = true;
                        logger.info("Modified {} to use embedded MongoDB", filename);
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
                    if (propManager.replaceMongoWithEmbedded(props)) {
                        propManager.writeProperties(propPath, props);
                        modified = true;
                        logger.info("Modified {} to use embedded MongoDB", filename);
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

        if (containerTypes.contains(TestContainerDetector.ContainerType.MONGODB)) {
            reasons.add("Removed MongoDB container");
        }

        if (connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.MONGODB)) {
            reasons.add("Replaced live MongoDB connection");
        }

        return String.join("; ", reasons);
    }

    @Override
    public List<Dependency> getRequiredDependencies() {
        List<Dependency> deps = new ArrayList<>();

        // Embedded MongoDB (Flapdoodle)
        Dependency embeddedMongo = new Dependency();
        embeddedMongo.setGroupId("de.flapdoodle.embed");
        embeddedMongo.setArtifactId("de.flapdoodle.embed.mongo");
        embeddedMongo.setVersion("4.7.0");
        embeddedMongo.setScope("test");
        deps.add(embeddedMongo);

        return deps;
    }

    @Override
    public List<Dependency> getDependenciesToRemove() {
        List<Dependency> deps = new ArrayList<>();

        // Testcontainers MongoDB module
        Dependency mongoContainer = new Dependency();
        mongoContainer.setGroupId(ORG_TESTCONTAINERS);
        mongoContainer.setArtifactId("mongodb");
        deps.add(mongoContainer);

        // Generic testcontainers
        Dependency testcontainers = new Dependency();
        testcontainers.setGroupId(ORG_TESTCONTAINERS);
        testcontainers.setArtifactId("testcontainers");
        deps.add(testcontainers);

        return deps;
    }
}
