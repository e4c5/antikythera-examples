package com.raditha.cleanunit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PropertyFileManager.
 */
class PropertyFileManagerTest {

    private final PropertyFileManager manager = new PropertyFileManager();

    @Test
    void testReadWriteYaml(@TempDir Path tempDir) throws IOException {
        // Given
        Path yamlFile = tempDir.resolve("test.yml");
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("key1", "value1");
        original.put("key2", 42);

        // When
        manager.writeYaml(yamlFile, original);
        Map<String, Object> read = manager.readYaml(yamlFile);

        // Then
        assertEquals("value1", read.get("key1"));
        assertEquals(42, read.get("key2"));
    }

    @Test
    void testReadWriteProperties(@TempDir Path tempDir) throws IOException {
        // Given
        Path propsFile = tempDir.resolve("test.properties");
        Properties original = new Properties();
        original.setProperty("key1", "value1");
        original.setProperty("key2", "value2");

        // When
        manager.writeProperties(propsFile, original);
        Properties read = manager.readProperties(propsFile);

        // Then
        assertEquals("value1", read.getProperty("key1"));
        assertEquals("value2", read.getProperty("key2"));
    }

    @Test
    void testReplaceKafkaWithEmbedded_Yaml() {
        // Given
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("bootstrap-servers", "localhost:9092");
        spring.put("kafka", kafka);
        config.put("spring", spring);

        // When
        boolean modified = manager.replaceKafkaWithEmbedded(config);

        // Then
        assertTrue(modified);
        @SuppressWarnings("unchecked")
        Map<String, Object> springMap = (Map<String, Object>) config.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> kafkaMap = (Map<String, Object>) springMap.get("kafka");
        assertEquals("${spring.embedded.kafka.brokers}", kafkaMap.get("bootstrap-servers"));
    }

    @Test
    void testReplaceKafkaWithEmbedded_Properties() {
        // Given
        Properties props = new Properties();
        props.setProperty("spring.kafka.bootstrap-servers", "localhost:9092");

        // When
        boolean modified = manager.replaceKafkaWithEmbedded(props);

        // Then
        assertTrue(modified);
        assertEquals("${spring.embedded.kafka.brokers}",
                props.getProperty("spring.kafka.bootstrap-servers"));
    }

    @Test
    void testReplaceKafkaWithEmbedded_AlreadyEmbedded() {
        // Given
        Properties props = new Properties();
        props.setProperty("spring.kafka.bootstrap-servers", "${spring.embedded.kafka.brokers}");

        // When
        boolean modified = manager.replaceKafkaWithEmbedded(props);

        // Then
        assertFalse(modified);
    }

    @Test
    void testReplaceDatabaseWithH2_Yaml_PostgreSQL() {
        // Given
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("url", "jdbc:postgresql://localhost:5432/mydb");
        datasource.put("driver-class-name", "org.postgresql.Driver");
        spring.put("datasource", datasource);
        config.put("spring", spring);

        // When
        boolean modified = manager.replaceDatabaseWithH2(config);

        // Then
        assertTrue(modified);
        @SuppressWarnings("unchecked")
        Map<String, Object> springMap = (Map<String, Object>) config.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> dsMap = (Map<String, Object>) springMap.get("datasource");
        assertEquals("jdbc:h2:mem:testdb", dsMap.get("url"));
        assertEquals("org.h2.Driver", dsMap.get("driver-class-name"));
    }

    @Test
    void testReplaceDatabaseWithH2_Properties_MySQL() {
        // Given
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/mydb");
        props.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");

        // When
        boolean modified = manager.replaceDatabaseWithH2(props);

        // Then
        assertTrue(modified);
        assertEquals("jdbc:h2:mem:testdb", props.getProperty("spring.datasource.url"));
        assertEquals("org.h2.Driver", props.getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void testReplaceRedisWithEmbedded_Yaml() {
        // Given
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", "prod-redis.example.com");
        redis.put("port", 6379);
        spring.put("redis", redis);
        config.put("spring", spring);

        // When
        boolean modified = manager.replaceRedisWithEmbedded(config);

        // Then
        assertTrue(modified);
        @SuppressWarnings("unchecked")
        Map<String, Object> springMap = (Map<String, Object>) config.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> redisMap = (Map<String, Object>) springMap.get("redis");
        assertEquals("localhost", redisMap.get("host"));
        assertEquals(6370, redisMap.get("port"));
    }

    @Test
    void testReplaceRedisWithEmbedded_Properties() {
        // Given
        Properties props = new Properties();
        props.setProperty("spring.redis.host", "prod-redis.example.com");
        props.setProperty("spring.redis.port", "6379");

        // When
        boolean modified = manager.replaceRedisWithEmbedded(props);

        // Then
        assertTrue(modified);
        assertEquals("localhost", props.getProperty("spring.redis.host"));
        assertEquals("6370", props.getProperty("spring.redis.port"));
    }

    @Test
    void testReplaceMongoWithEmbedded_Yaml() {
        // Given
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> mongodb = new LinkedHashMap<>();
        mongodb.put("uri", "mongodb://prod-server:27017/mydb");
        data.put("mongodb", mongodb);
        spring.put("data", data);
        config.put("spring", spring);

        // When
        boolean modified = manager.replaceMongoWithEmbedded(config);

        // Then
        assertTrue(modified);
        @SuppressWarnings("unchecked")
        Map<String, Object> springMap = (Map<String, Object>) config.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) springMap.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> mongoMap = (Map<String, Object>) dataMap.get("mongodb");
        assertFalse(mongoMap.containsKey("uri"));
    }

    @Test
    void testReplaceMongoWithEmbedded_Properties() {
        // Given
        Properties props = new Properties();
        props.setProperty("spring.data.mongodb.uri", "mongodb://prod-server:27017/mydb");

        // When
        boolean modified = manager.replaceMongoWithEmbedded(props);

        // Then
        assertTrue(modified);
        assertNull(props.getProperty("spring.data.mongodb.uri"));
    }

    @Test
    void testReadYaml_FileNotExists(@TempDir Path tempDir) {
        // Given
        Path nonExistent = tempDir.resolve("nonexistent.yml");

        // When/Then
        assertThrows(IOException.class, () -> manager.readYaml(nonExistent));
    }

    @Test
    void testReadProperties_FileNotExists(@TempDir Path tempDir) {
        // Given
        Path nonExistent = tempDir.resolve("nonexistent.properties");

        // When/Then
        assertThrows(IOException.class, () -> manager.readProperties(nonExistent));
    }

    @Test
    void testReplaceKafkaWithEmbedded_NoKafkaConfig() {
        // Given
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("someOtherKey", "value");

        // When
        boolean modified = manager.replaceKafkaWithEmbedded(config);

        // Then
        assertFalse(modified);
    }

    @Test
    void testReplaceDatabaseWithH2_NoDatabaseConfig() {
        // Given
        Properties props = new Properties();
        props.setProperty("some.other.property", "value");

        // When
        boolean modified = manager.replaceDatabaseWithH2(props);

        // Then
        assertFalse(modified);
    }
}
