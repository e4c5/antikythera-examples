package com.raditha.cleanunit;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detects testcontainer usage in test classes by scanning for @Testcontainers
 * and @Container annotations.
 * 
 * Follows code quality guidelines: simple methods, early returns, minimal
 * nesting.
 */
public class TestContainerDetector {
    private static final Logger logger = LoggerFactory.getLogger(TestContainerDetector.class);

    public enum ContainerType {
        POSTGRESQL,
        MYSQL,
        MARIADB,
        KAFKA,
        REDIS,
        MONGODB,
        NONE
    }

    /**
     * Detect all container types used in a test class.
     * 
     * @param testClass the test class to analyze
     * @return set of detected container types
     */
    public Set<ContainerType> detectContainers(ClassOrInterfaceDeclaration testClass) {
        if (testClass == null) {
            return EnumSet.noneOf(ContainerType.class);
        }

        if (!hasTestcontainersAnnotation(testClass)) {
            return EnumSet.noneOf(ContainerType.class);
        }

        Set<ContainerType> containers = EnumSet.noneOf(ContainerType.class);
        for (FieldDeclaration field : testClass.getFields()) {
            ContainerType type = identifyContainerType(field);
            if (type != ContainerType.NONE) {
                containers.add(type);
                logger.debug("Detected {} container in {}", type, testClass.getNameAsString());
            }
        }

        return containers;
    }

    /**
     * Check if class has @Testcontainers annotation.
     */
    private boolean hasTestcontainersAnnotation(ClassOrInterfaceDeclaration testClass) {
        return testClass.getAnnotationByName("Testcontainers").isPresent();
    }

    /**
     * Identify the container type from a field declaration.
     * Uses early returns for clarity.
     */
    private ContainerType identifyContainerType(FieldDeclaration field) {
        if (!hasContainerAnnotation(field)) {
            return ContainerType.NONE;
        }

        String typeName = field.getElementType().asString();
        ContainerType mapped = mapTypeNameToContainer(typeName);
        if (mapped != ContainerType.NONE) {
            return mapped;
        }

        if (typeName.contains("GenericContainer")) {
            return detectGenericContainerType(field);
        }

        return ContainerType.NONE;
    }

    /**
     * Check if field has @Container annotation.
     */
    private boolean hasContainerAnnotation(FieldDeclaration field) {
        return field.getAnnotationByName("Container").isPresent();
    }

    /**
     * Map type name to container enum.
     * Simple if-chain with early returns.
     */
    private ContainerType mapTypeNameToContainer(String typeName) {
        if (typeName.contains("PostgreSQLContainer")) {
            return ContainerType.POSTGRESQL;
        }
        if (typeName.contains("MySQLContainer")) {
            return ContainerType.MYSQL;
        }
        if (typeName.contains("MariaDBContainer")) {
            return ContainerType.MARIADB;
        }
        if (typeName.contains("KafkaContainer")) {
            return ContainerType.KAFKA;
        }
        // Dedicated container types (if they exist in testcontainers)
        if (typeName.contains("RedisContainer")) {
            return ContainerType.REDIS;
        }
        if (typeName.contains("MongoDBContainer")) {
            return ContainerType.MONGODB;
        }

        return ContainerType.NONE;
    }

    /**
     * Infer container type for GenericContainer declarations by inspecting variable
     * names and initializers.
     */
    private ContainerType detectGenericContainerType(FieldDeclaration field) {
        for (VariableDeclarator variable : field.getVariables()) {
            String name = variable.getNameAsString();
            if (containsKeyword(name, "redis")) {
                return ContainerType.REDIS;
            }
            if (containsKeyword(name, "mongo")) {
                return ContainerType.MONGODB;
            }

            if (variable.getInitializer().isPresent()) {
                String initializerText = variable.getInitializer().get().toString();
                ContainerType fromInitializer = classifyInitializer(initializerText);
                if (fromInitializer != ContainerType.NONE) {
                    return fromInitializer;
                }
            }
        }
        return ContainerType.NONE;
    }

    private boolean containsKeyword(String candidate, String keyword) {
        return candidate != null && candidate.toLowerCase().contains(keyword);
    }

    private ContainerType classifyInitializer(String initializerText) {
        if (initializerText == null) {
            return ContainerType.NONE;
        }

        String lower = initializerText.toLowerCase();
        if (lower.contains("redis")) {
            return ContainerType.REDIS;
        }
        if (lower.contains("mongo")) {
            return ContainerType.MONGODB;
        }
        return ContainerType.NONE;
    }

    /**
     * Check if any database container is detected.
     */
    public boolean hasDatabaseContainer(Set<ContainerType> containers) {
        return containers.contains(ContainerType.POSTGRESQL)
                || containers.contains(ContainerType.MYSQL)
                || containers.contains(ContainerType.MARIADB);
    }

    /**
     * Check if Kafka container is detected.
     */
    public boolean hasKafkaContainer(Set<ContainerType> containers) {
        return containers.contains(ContainerType.KAFKA);
    }
}
