package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestContainerDetectorTest {

    @Test
    void detectsPostgreSQLContainer() {
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

        TestContainerDetector detector = new TestContainerDetector();
        Set<TestContainerDetector.ContainerType> containers = detector.detectContainers(testClass);

        assertTrue(containers.contains(TestContainerDetector.ContainerType.POSTGRESQL));
        assertEquals(1, containers.size());
    }

    @Test
    void detectsKafkaContainer() {
        String code = """
                import org.testcontainers.containers.KafkaContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static KafkaContainer kafka = new KafkaContainer();
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        TestContainerDetector detector = new TestContainerDetector();
        Set<TestContainerDetector.ContainerType> containers = detector.detectContainers(testClass);

        assertTrue(containers.contains(TestContainerDetector.ContainerType.KAFKA));
    }

    @Test
    void detectsMultipleContainers() {
        String code = """
                import org.testcontainers.containers.PostgreSQLContainer;
                import org.testcontainers.containers.KafkaContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class MyTest {
                    @Container
                    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13");

                    @Container
                    static KafkaContainer kafka = new KafkaContainer();
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        TestContainerDetector detector = new TestContainerDetector();
        Set<TestContainerDetector.ContainerType> containers = detector.detectContainers(testClass);

        assertTrue(containers.contains(TestContainerDetector.ContainerType.POSTGRESQL));
        assertTrue(containers.contains(TestContainerDetector.ContainerType.KAFKA));
        assertEquals(2, containers.size());
    }

    @Test
    void returnsEmptyWhenNoTestcontainersAnnotation() {
        String code = """
                class MyTest {
                    static Object postgres = new Object();
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        TestContainerDetector detector = new TestContainerDetector();
        Set<TestContainerDetector.ContainerType> containers = detector.detectContainers(testClass);

        assertTrue(containers.isEmpty());
    }

    @Test
    void hasDatabaseContainerWorkCorrectly() {
        TestContainerDetector detector = new TestContainerDetector();
        Set<TestContainerDetector.ContainerType> containers = Set.of(
                TestContainerDetector.ContainerType.POSTGRESQL,
                TestContainerDetector.ContainerType.KAFKA);

        assertTrue(detector.hasDatabaseContainer(containers));
        assertTrue(detector.hasKafkaContainer(containers));
    }
}
