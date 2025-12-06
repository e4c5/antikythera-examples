package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseToEmbeddedConverterTest {

    @Test
    void canConvertPostgreSQLContainer() {
        DatabaseToEmbeddedConverter converter = new DatabaseToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.POSTGRESQL);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void canConvertLiveDatabaseConnection() {
        DatabaseToEmbeddedConverter converter = new DatabaseToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of();
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set
                .of(LiveConnectionDetector.LiveConnectionType.DATABASE);

        assertTrue(converter.canConvert(containers, connections));
    }

    @Test
    void cannotConvertKafkaContainer() {
        DatabaseToEmbeddedConverter converter = new DatabaseToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containers = Set.of(TestContainerDetector.ContainerType.KAFKA);
        Set<LiveConnectionDetector.LiveConnectionType> connections = Set.of();

        assertFalse(converter.canConvert(containers, connections));
    }

    @Test
    void convertsPostgreSQLContainerToEmbedded() {
        String code = """
                import org.testcontainers.containers.PostgreSQLContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13");
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        DatabaseToEmbeddedConverter converter = new DatabaseToEmbeddedConverter();
        Set<TestContainerDetector.ContainerType> containerTypes = EnumSet.of(
                TestContainerDetector.ContainerType.POSTGRESQL);
        Set<LiveConnectionDetector.LiveConnectionType> connectionTypes = EnumSet.noneOf(
                LiveConnectionDetector.LiveConnectionType.class);

        // When
        EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containerTypes,
                connectionTypes, null);

        assertTrue(result.modified);
        assertEquals("@AutoConfigureTestDatabase with H2", result.embeddedAlternative);
        assertTrue(testClass.getAnnotationByName("AutoConfigureTestDatabase").isPresent());
        assertFalse(testClass.getAnnotationByName("Testcontainers").isPresent());
        assertEquals(0, testClass.getFields().size());
    }

    @Test
    void returnsCorrectDependencies() {
        DatabaseToEmbeddedConverter converter = new DatabaseToEmbeddedConverter();

        var required = converter.getRequiredDependencies();
        assertEquals(1, required.size());
        assertEquals("com.h2database", required.get(0).getGroupId());
        assertEquals("h2", required.get(0).getArtifactId());

        var toRemove = converter.getDependenciesToRemove();
        assertTrue(toRemove.size() >= 3);
        assertTrue(toRemove.stream().anyMatch(d -> "postgresql".equals(d.getArtifactId())));
    }
}
