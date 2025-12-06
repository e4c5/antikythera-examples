package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import org.apache.maven.model.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts database test containers to embedded H2 database.
 * 
 * Removes @Container database fields and adds @AutoConfigureTestDatabase.
 * Follows simple method patterns with early returns.
 */
public class DatabaseToEmbeddedConverter implements EmbeddedResourceConverter {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseToEmbeddedConverter.class);
    public static final String ORG_TESTCONTAINERS = "org.testcontainers";

    @Override
    public boolean canConvert(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        TestContainerDetector detector = new TestContainerDetector();
        return detector.hasDatabaseContainer(containerTypes)
                || connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.DATABASE);
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
        if (!containerTypes.isEmpty()) {
            modified |= removeContainerFields(testClass);
            modified |= removeTestcontainersAnnotation(testClass);
        }

        // Add embedded database annotation
        modified |= addEmbeddedDatabaseAnnotation(testClass, cu);

        if (!modified) {
            return ConversionResult.noChange("No database containers or live connections found");
        }

        String reason = buildConversionReason(containerTypes, connectionTypes);
        return ConversionResult.success("@AutoConfigureTestDatabase with H2", reason);
    }

    /**
     * Remove container fields annotated with @Container.
     */
    private boolean removeContainerFields(ClassOrInterfaceDeclaration testClass) {
        List<FieldDeclaration> toRemove = new ArrayList<>();

        for (FieldDeclaration field : testClass.getFields()) {
            if (isDatabaseContainerField(field)) {
                toRemove.add(field);
                logger.debug("Removing database container field: {}",
                        field.getVariable(0).getNameAsString());
            }
        }

        toRemove.forEach(FieldDeclaration::remove);
        return !toRemove.isEmpty();
    }

    /**
     * Check if field is a database container.
     */
    private boolean isDatabaseContainerField(FieldDeclaration field) {
        if (!field.getAnnotationByName("Container").isPresent()) {
            return false;
        }

        String typeName = field.getElementType().asString();
        return typeName.contains("PostgreSQLContainer")
                || typeName.contains("MySQLContainer")
                || typeName.contains("MariaDBContainer");
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
     * Add @AutoConfigureTestDatabase annotation.
     */
    private boolean addEmbeddedDatabaseAnnotation(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        if (testClass.getAnnotationByName("AutoConfigureTestDatabase").isPresent()) {
            return false;
        }

        testClass.addAnnotation(new MarkerAnnotationExpr("AutoConfigureTestDatabase"));
        cu.addImport("org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase");
        logger.info("Added @AutoConfigureTestDatabase to {}", testClass.getNameAsString());
        return true;
    }

    /**
     * Build descriptive reason for conversion.
     */
    private String buildConversionReason(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes) {
        List<String> reasons = new ArrayList<>();

        if (!containerTypes.isEmpty()) {
            reasons.add("Removed " + containerTypes + " containers");
        }

        if (connectionTypes.contains(LiveConnectionDetector.LiveConnectionType.DATABASE)) {
            reasons.add("Replaced live DB connection");
        }

        return String.join("; ", reasons);
    }

    @Override
    public List<Dependency> getRequiredDependencies() {
        List<Dependency> deps = new ArrayList<>();

        // H2 database for testing
        Dependency h2 = new Dependency();
        h2.setGroupId("com.h2database");
        h2.setArtifactId("h2");
        h2.setScope("test");
        deps.add(h2);

        return deps;
    }

    @Override
    public List<Dependency> getDependenciesToRemove() {
        List<Dependency> deps = new ArrayList<>();

        // Testcontainers database modules
        deps.add(createDependency(ORG_TESTCONTAINERS, "postgresql"));
        deps.add(createDependency(ORG_TESTCONTAINERS, "mysql"));
        deps.add(createDependency(ORG_TESTCONTAINERS, "mariadb"));
        deps.add(createDependency(ORG_TESTCONTAINERS, "testcontainers"));

        return deps;
    }

    /**
     * Helper to create dependency object.
     */
    private Dependency createDependency(String groupId, String artifactId) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        return dep;
    }
}
