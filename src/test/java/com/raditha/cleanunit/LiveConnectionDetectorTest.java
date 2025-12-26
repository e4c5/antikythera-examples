package com.raditha.cleanunit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LiveConnectionDetectorTest {

    static Stream<Arguments> kafkaDetectionTestCases() {
        return Stream.of(
                Arguments.of(
                        "application-test.properties",
                        "kafka.bootstrap-servers=172.15.100.145:9092,172.15.100.145:9093\n",
                        true,
                        "Should detect Kafka with custom property name 'kafka.bootstrap-servers'"
                ),
                Arguments.of(
                        "application-test.properties",
                        "spring.kafka.bootstrap-servers=localhost:9092\n",
                        true,
                        "Should detect Kafka with standard property name 'spring.kafka.bootstrap-servers'"
                ),
                Arguments.of(
                        "application-test.properties",
                        "kafka.bootstrap-servers=kafka-server:9093\n",
                        true,
                        "Should detect Kafka on port 9093"
                ),
                Arguments.of(
                        "application-test.properties",
                        "kafka.bootstrap-servers=${KAFKA_HOST:localhost}:9092\n",
                        true,
                        "Should detect Kafka - generic placeholders are not recognized as embedded"
                ),
                Arguments.of(
                        "application-test.yml",
                        """
                        kafka:
                          bootstrap-servers: 172.15.100.145:9092,172.15.100.145:9093,172.15.100.210:9092
                        """,
                        true,
                        "Should detect Kafka from YAML with IP:port patterns"
                )
        );
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("kafkaDetectionTestCases")
    void testKafkaDetection(String filename, String content, boolean shouldDetect, String description,
                            @TempDir Path tempDir) throws Exception {
        Path resourcesDir = tempDir.resolve("src/test/resources");
        Files.createDirectories(resourcesDir);

        Path configFile = resourcesDir.resolve(filename);
        Files.writeString(configFile, content);

        LiveConnectionDetector detector = new LiveConnectionDetector();
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration mockClass =
                new com.github.javaparser.ast.body.ClassOrInterfaceDeclaration();

        Set<LiveConnectionDetector.LiveConnectionType> connections =
                detector.detectLiveConnections(mockClass, tempDir);

        assertEquals(shouldDetect,
                connections.contains(LiveConnectionDetector.LiveConnectionType.KAFKA),
                description);
    }
}
