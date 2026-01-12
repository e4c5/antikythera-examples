package sa.com.cloudsolutions.antikythera.examples;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SpringBootVersionDetectorTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @MethodSource("providePomContents")
    void testDetectSpringBootVersion(String pomContent, String expectedVersion) throws IOException, XmlPullParserException {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        SpringBootVersionDetector detector = new SpringBootVersionDetector(pomPath);
        String version = detector.detectSpringBootVersion();

        assertEquals(expectedVersion, version);
    }

    private static String createPom(String content) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    
                    %s
                </project>
                """.formatted(content);
    }

    static Stream<Arguments> providePomContents() {
        return Stream.of(
            Arguments.of(createPom("""
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.4</version>
                    </parent>
                """), "3.3.4"),
            Arguments.of(createPom("""
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>2.7.18</version>
                        </dependency>
                    </dependencies>
                """), "2.7.18"),
            Arguments.of(createPom("""
                    <properties>
                        <spring-boot.version>3.2.1</spring-boot.version>
                    </properties>
                """), "3.2.1"),
            Arguments.of(createPom("""
                    <properties>
                        <spring.boot.version>3.1.5</spring.boot.version>
                    </properties>
                    
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>${spring.boot.version}</version>
                    </parent>
                """), "3.1.5"),
            Arguments.of(createPom("""
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.0.0</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                """), "3.0.0"),
            Arguments.of(createPom("""
                    <dependencies>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                        </dependency>
                    </dependencies>
                """), null),
            Arguments.of(createPom("""
                    <properties>
                        <base.version>3.2.5</base.version>
                        <spring-boot.version>${base.version}</spring-boot.version>
                    </properties>
                    
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>${spring-boot.version}</version>
                    </parent>
                """), "3.2.5")
        );
    }

    @Test
    void testRealWorldExample() throws IOException, XmlPullParserException {
        // Test with the actual antikythera-sample-project pom.xml
        Path pomPath = Path.of("/home/raditha/csi/Antikythera/antikythera-sample-project/pom.xml");
        
        if (!Files.exists(pomPath)) {
            // Skip test if the sample project is not available
            return;
        }

        SpringBootVersionDetector detector = new SpringBootVersionDetector(pomPath);
        String version = detector.detectSpringBootVersion();

        assertNotNull(version);
        assertTrue(version.startsWith("3."), "Expected Spring Boot 3.x version, got: " + version);
    }
}
