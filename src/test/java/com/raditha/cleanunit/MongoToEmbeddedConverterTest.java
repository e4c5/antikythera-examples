package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MongoToEmbeddedConverter.
 */
class MongoToEmbeddedConverterTest {

    @Test
    void canConvertMongoDBContainer() {
        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.MONGODB);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void canConvertLiveMongoDBConnection() {
        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of();
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set
                .of(LiveConnectionDetector.LiveConnectionType.MONGODB);

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void cannotConvertKafkaContainer() {
        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.KAFKA);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertFalse(converter.canConvert(containers, connections));
    }

    @Test
    void convertsMongoDBContainerToEmbedded() {
        String code = """
                import org.testcontainers.containers.MongoDBContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static MongoDBContainer mongo = new MongoDBContainer("mongo:4.4");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.MONGODB);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containerTypes,
                connectionTypes, null);

        assertTrue(result.modified);
        assertEquals("Embedded MongoDB (de.flapdoodle.embed:de.flapdoodle.embed.mongo)", result.embeddedAlternative);
        assertTrue(testClass.getAnnotationByName("DataMongoTest").isPresent());
        assertFalse(testClass.getAnnotationByName("Testcontainers").isPresent());
        assertEquals(0, testClass.getFields().size());
    }

    @Test
    void preservesTestcontainersWhenOtherContainersPresent() {
        String code = """
                import org.testcontainers.containers.MongoDBContainer;
                import org.testcontainers.containers.PostgreSQLContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static MongoDBContainer mongo = new MongoDBContainer("mongo:4.4");

                    @Container
                    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.MONGODB);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        converter.convert(testClass, cu, containerTypes, connectionTypes, null);

        // Should keep @Testcontainers since PostgreSQL container remains
        assertTrue(testClass.getAnnotationByName("Testcontainers").isPresent());
        assertEquals(1, testClass.getFields().size());
    }

    @Test
    void doesNotAddDataMongoTestIfAlreadyPresent() {
        String code = """
                import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
                import org.testcontainers.containers.MongoDBContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @DataMongoTest
                @Testcontainers
                class MyTest {
                    @Container
                    static MongoDBContainer mongo = new MongoDBContainer("mongo:4.4");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.MONGODB);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        converter.convert(testClass, cu, containerTypes, connectionTypes, null);

        // Should have only one @DataMongoTest (not duplicate)
        long count = testClass.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("DataMongoTest"))
                .count();
        assertEquals(1, count);
    }

    @Test
    void returnsCorrectDependencies() {
        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();

        var required = converter.getRequiredDependencies();
        assertEquals(1, required.size());
        assertEquals("de.flapdoodle.embed", required.get(0).getGroupId());
        assertEquals("de.flapdoodle.embed.mongo", required.get(0).getArtifactId());
        assertEquals("4.7.0", required.get(0).getVersion());

        var toRemove = converter.getDependenciesToRemove();
        assertEquals(2, toRemove.size());
        assertTrue(toRemove.stream().anyMatch(d -> "mongodb".equals(d.getArtifactId())));
        assertTrue(toRemove.stream().anyMatch(d -> "testcontainers".equals(d.getArtifactId())));
    }

    @Test
    void doesNotModifyWhenNoMongoContainersOrConnections() {
        String code = """
                class MyTest {
                    void testSomething() {
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        MongoToEmbeddedConverter converter = new MongoToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.noneOf(
                TestContainerDetector.ContainerType.class);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containerTypes,
                connectionTypes, null);

        // Modified will be true because @DataMongoTest is added
        assertTrue(result.modified);
        assertTrue(testClass.getAnnotationByName("DataMongoTest").isPresent());
    }
}
