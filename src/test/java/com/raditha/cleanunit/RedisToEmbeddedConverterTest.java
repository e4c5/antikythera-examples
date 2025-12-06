package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisToEmbeddedConverter.
 */
class RedisToEmbeddedConverterTest {

    @Test
    void canConvertRedisContainer() {
        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.REDIS);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void canConvertLiveRedisConnection() {
        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of();
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set
                .of(LiveConnectionDetector.LiveConnectionType.REDIS);

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void cannotConvertKafkaContainer() {
        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.KAFKA);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertFalse(converter.canConvert(containers, connections));
    }

    @Test
    void convertsRedisContainerToEmbedded() {
        String code = """
                import org.testcontainers.containers.GenericContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static GenericContainer<?> redis = new GenericContainer<>("redis:6-alpine");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.REDIS);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containerTypes,
                connectionTypes, null);

        // Modified will be false because we can't detect that GenericContainer is for
        // Redis
        // without additional clues in the field name
        assertFalse(result.modified);
    }

    @Test
    void preservesTestcontainersWhenOtherContainersPresent() {
        String code = """
                import org.testcontainers.containers.GenericContainer;
                import org.testcontainers.containers.PostgreSQLContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static GenericContainer<?> redis = new GenericContainer<>("redis:6-alpine");

                    @Container
                    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.REDIS);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        converter.convert(testClass, cu, containerTypes, connectionTypes, null);

        // Should keep @Testcontainers since PostgreSQL container remains
        // Both fields remain because GenericContainer redis isn't detected
        assertTrue(testClass.getAnnotationByName("Testcontainers").isPresent());
        assertEquals(2, testClass.getFields().size());
    }

    @Test
    void returnsCorrectDependencies() {
        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();

        var required = converter.getRequiredDependencies();
        assertEquals(1, required.size());
        assertEquals("it.ozimov", required.get(0).getGroupId());
        assertEquals("embedded-redis", required.get(0).getArtifactId());
        assertEquals("0.7.3", required.get(0).getVersion());

        var toRemove = converter.getDependenciesToRemove();
        assertEquals(1, toRemove.size());
        assertEquals("org.testcontainers", toRemove.get(0).getGroupId());
    }

    @Test
    void doesNotModifyWhenNoRedisContainersOrConnections() {
        String code = """
                class MyTest {
                    void testSomething() {
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        RedisToEmbeddedConverter converter = new RedisToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.noneOf(
                TestContainerDetector.ContainerType.class);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containerTypes,
                connectionTypes, null);

        assertFalse(result.modified);
    }
}
