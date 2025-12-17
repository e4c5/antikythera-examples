# Spring Boot 2.1 to 2.2 Migration Guide

## Overview

This comprehensive guide covers all changes required to migrate from Spring Boot 2.1 to Spring Boot 2.2, with specific focus on Hibernate/JPA, Kafka, Redis, testing frameworks, and other critical aspects of enterprise applications.

Spring Boot 2.2 represents a significant update that brings performance improvements, new features, and important deprecation removals. This guide will help you navigate the upgrade process systematically.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Dependency Upgrades](#dependency-upgrades)
3. [Hibernate and JPA Changes](#hibernate-and-jpa-changes)
4. [Kafka Changes](#kafka-changes)
5. [Redis Changes](#redis-changes)
6. [Testing Framework Changes](#testing-framework-changes)
7. [Configuration Property Changes](#configuration-property-changes)
8. [Actuator Changes](#actuator-changes)
9. [Jakarta EE Migration](#jakarta-ee-migration)
10. [Performance Optimizations](#performance-optimizations)
11. [Breaking Changes and Deprecations](#breaking-changes-and-deprecations)
12. [Migration Checklist](#migration-checklist)
13. [Troubleshooting](#troubleshooting)
14. [References](#references)

---

## Executive Summary

### Key Changes at a Glance

| Category | Major Changes |
|----------|---------------|
| **Spring Framework** | 5.1.x → 5.2.x |
| **Hibernate** | 5.3.x → 5.4.x (with performance improvements) |
| **Spring Kafka** | 2.2.x → 2.3.x (requires kafka-clients 2.3.0+) |
| **Spring Data** | Lovelace → Moore (includes Redis 2.2) |
| **JUnit** | JUnit 5 now default in `spring-boot-starter-test` |
| **Mockito** | 2.x → 3.1.0 |
| **JMX** | Disabled by default (enable with `spring.jmx.enabled=true`) |
| **Actuator** | HTTP Trace and Auditing disabled by default |
| **Jakarta EE** | Migration from `javax.*` to `jakarta.*` begins |

### Migration Complexity

- **Low Risk**: Configuration property renames (automated via `spring-boot-properties-migrator`)
- **Medium Risk**: Testing framework updates, JMX/Actuator changes
- **High Risk**: Kafka version compatibility, custom Hibernate configurations, Jakarta EE namespace changes

---

## Dependency Upgrades

### 1.1 Update Spring Boot Version

**pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.2.13.RELEASE</version> <!-- Latest 2.2.x release -->
    <relativePath/>
</parent>
```

Or in `gradle.properties`:
```properties
springBootVersion=2.2.13.RELEASE
```

### 1.2 Major Dependency Version Changes

| Dependency | Spring Boot 2.1 | Spring Boot 2.2 | Notes |
|-----------|-----------------|-----------------|-------|
| Spring Framework | 5.1.x | 5.2.x | Performance improvements, coroutines support |
| Spring Data | Lovelace | Moore | New repository query derivation keywords |
| Spring AMQP | 2.1.x | 2.2.x | Enhanced error handling |
| Spring Batch | 4.1.x | 4.2.x | Java 8 Date/Time support |
| Spring Integration | 5.1.x | 5.2.x | Reactive streams enhancements |
| Spring Kafka | 2.2.x | 2.3.x | **Breaking changes - see Kafka section** |
| Spring Security | 5.1.x | 5.2.x | OAuth2/OIDC improvements |
| Elasticsearch | 6.4.x | 6.7.x | Transport client deprecated |
| Flyway | 5.x | 6.0.x | New migration features |
| Jackson | 2.9.x | 2.10.x | Java 13 support |
| JUnit Jupiter | 5.3.x | 5.5.x | Enhanced parameter injection |
| Mockito | 2.23.x | 3.1.0 | **Requires Java 8+** |
| Micrometer | 1.1.x | 1.3.x | New meter types |

### 1.3 Using Properties Migrator

To automatically detect and report deprecated properties:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

This dependency will:
- Detect renamed or removed properties at startup
- Provide warnings with corrected property names
- Temporarily apply legacy properties for smooth migration
- Should be removed after migration is complete

---

## Hibernate and JPA Changes

### 3.1 Hibernate Version Upgrade

Spring Boot 2.2 upgrades from **Hibernate 5.3.x to 5.4.x**, bringing significant improvements:

#### Performance Improvements

1. **Disabled Hibernate Entity Scanning**
   - Spring Boot 2.2 now fully manages the `PersistenceUnit`
   - Hibernate's redundant entity scanning is disabled
   - Results in faster application startup

2. **Automatic Dialect Selection**
   - Hibernate can now auto-detect database dialects
   - Explicit dialect configuration is optional (but still recommended for production)

**Before (Spring Boot 2.1):**
```yaml
spring:
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQL95Dialect
```

**After (Spring Boot 2.2) - Optional:**
```yaml
spring:
  jpa:
    # Dialect auto-detection enabled - can remove explicit configuration
    # OR keep for predictability:
    database-platform: org.hibernate.dialect.PostgreSQL10Dialect
```

### 3.2 JPA 2.2 Support

Hibernate 5.4 uses **JPA 2.2** (Java EE 8). Key features:
- `@Repeatable` for query hints
- Stream result support
- Enhanced CDI integration

### 3.3 Jakarta EE Transition

> [!WARNING]
> Spring Boot 2.2 begins transitioning from Java EE (`javax.*`) to Jakarta EE (`jakarta.*`).

While Spring Boot 2.2 maintains `javax.persistence.*` imports, be aware:

**Current (Still valid in 2.2):**
```java
import javax.persistence.Entity;
import javax.persistence.Id;
```

**Future (Jakarta EE - coming in Spring Boot 3.x):**
```java
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
```

**Migration Impact**: No immediate action required for Spring Boot 2.2, but plan for Jakarta EE transition in future versions.

### 3.4 Hibernate Custom Type Deprecations

> [!CAUTION]
> `@TypeDef` and `@Type` are deprecated in Hibernate 5.4 and will be removed in Hibernate 6.

**Deprecated Pattern:**
```java
@TypeDef(name = "json", typeClass = JsonStringType.class)
@Entity
public class MyEntity {
    @Type(type = "json")
    @Column(columnDefinition = "json")
    private Map<String, Object> attributes;
}
```

**Recommended Alternatives:**
1. Use `@Convert` with `AttributeConverter`
2. Use native database types with appropriate column definitions
3. Leverage Spring Data projections for complex mappings

**Example with `AttributeConverter`:**
```java
@Converter(autoApply = true)
public class JsonAttributeConverter implements AttributeConverter<Map<String, Object>, String> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

@Entity
public class MyEntity {
    @Convert(converter = JsonAttributeConverter.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> attributes;
}
```

   #### Automated Hibernate Type Migration

> [!NOTE]
> This migration is complex and best handled with partial automation.

**Detection Patterns**

**Pattern 1: Detect @TypeDef Usage**
```java
// AST Detection on class level
@TypeDef(name = "json", typeClass = JsonStringType.class)
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
class MyEntity { ... }

// Automation Strategy:
// 1. Scan for @TypeDef annotations on classes
// 2. Extract: name attribute, typeClass attribute
// 3. Find all @Type(type = "{name}") field usages
```

**Pattern 2: Detect @Type Usage**
```java
// Field-level detection
@Type(type = "json")
@Column(columnDefinition = "json")
private Map<String, Object> attributes;

// Automation Strategy:
// 1. Match @Type annotation on fields
// 2. Extract type name from annotation
// 3. Determine field type for converter generation
```

**Automated Transformation Strategy**

**Complexity**: HIGH - Requires code generation

**Step 1: Generate AttributeConverter (Stub)**
```java
// Template location: src/main/java/{package}/converters/{TypeName}Converter.java
// Generated stub with TODO comments:

@Converter
public class JsonAttributeConverter implements AttributeConverter<Map<String, Object>, String> {
    
    // TODO: Configure ObjectMapper as needed
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        // TODO: Add null checks and error handling
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e); // TODO: Consider better exception handling
        }
    }
    
    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        // TODO: Add null checks and error handling
        try {
            return objectMapper.readValue(dbData, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e); // TODO: Consider better exception handling
        }
    }
}
```

**Step 2: Remove @TypeDef**
```java
// Action: Remove @TypeDef annotation from entity class
// Action: Remove import org.hibernate.annotations.TypeDef
// Action: Remove import org.hibernate.annotations.TypeDefs (if no other TypeDefs remain)
```

**Step 3: Replace @Type with @Convert**
```java
// Before
import org.hibernate.annotations.Type;
@Type(type = "json")
private Map<String, Object> attributes;

// After
import javax.persistence.Convert;
@Convert(converter = JsonAttributeConverter.class)
private Map<String, Object> attributes;

// Actions:
// - Remove import org.hibernate.annotations.Type
// - Add import javax.persistence.Convert
// - Add import {package}.converters.JsonAttributeConverter
// - Replace annotation
```

**Validation Strategy**:
- Ensure converter class doesn't already exist
- Verify field type matches converter generic types
- Check that custom type library (e.g., hibernate-types) is still needed
- Compile after transformation
- Mark generated converters with TODO comments for manual review

**Risk Level**: MEDIUM-HIGH - Generates code stubs requiring manual completion

**Automation Confidence**: 40% (generates correct structure, requires manual tuning)

**Recommendation**: 
- Generate converter stub with TODO comments
- Add to migration report: "Manual review required: Complete AttributeConverter implementation"
- Provide link to generated file for review

### 3.5 Query Performance Tuning

Hibernate 5.4 introduces better query plan caching. Consider reviewing:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        query.plan_cache_max_size: 2048  # Default changed
        query.plan_parameter_metadata_max_size: 128
```

### 3.6 Second-Level Cache Changes

If using Hibernate's second-level cache, verify cache provider compatibility:

```xml
<!-- Example: EhCache 3.x -->
<dependency>
    <groupId>org.hibernate</groupId>
    <artifactId>hibernate-jcache</artifactId>
</dependency>
<dependency>
    <groupId>org.ehcache</groupId>
    <artifactId>ehcache</artifactId>
</dependency>
```

---

## Kafka Changes

### 4.1 Spring Kafka Version Upgrade

Spring Boot 2.2 upgrades **Spring Kafka from 2.2.x to 2.3.x**, requiring **kafka-clients 2.3.0+**.

> [!WARNING]
> This upgrade may introduce breaking changes for Kafka applications.

#### Critical Changes

| Change | Impact | Action Required |
|--------|--------|-----------------|
| `kafka-clients` version | Requires 2.3.0+ | Update to compatible Kafka broker version |
| `TopicPartitionInitialOffset` | Deprecated | Use `TopicPartitionOffset` instead |
| `ContainerProperties` location | Package moved | Update imports if used directly |
| Header mapping | Changed default behavior | May break cross-version communication |

### 4.2 Kafka Client Compatibility

**Spring Boot 2.1:**
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <!-- Provides kafka-clients 2.0.x -->
</dependency>
```

**Spring Boot 2.2:**
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <!-- Now requires kafka-clients 2.3.0+ -->
</dependency>
```

**Kafka Version Compatibility Matrix:**

| spring-kafka | kafka-clients | Apache Kafka Broker |
|--------------|---------------|---------------------|
| 2.2.x | 2.0.0+ | 2.0.x - 2.2.x |
| 2.3.x | 2.3.0+ | 2.0.x - 2.3.x |

### 4.3 Package and Class Changes

#### Deprecated Classes

**Replace:**
```java
import org.springframework.kafka.support.TopicPartitionInitialOffset; // DEPRECATED
```

**With:**
```java
import org.springframework.kafka.support.TopicPartitionOffset;
```

**Migration Example:**
```java
// Before (Spring Kafka 2.2)
TopicPartitionInitialOffset[] topicPartitions = new TopicPartitionInitialOffset[] {
    new TopicPartitionInitialOffset("topic1", 0, 0L)
};

// After (Spring Kafka 2.3)
TopicPartitionOffset[] topicPartitions = new TopicPartitionOffset[] {
    new TopicPartitionOffset("topic1", 0, 0L)
};
```

### 4.4 Configuration Changes

#### JMX Impact on Kafka

> [!IMPORTANT]
> JMX is disabled by default in Spring Boot 2.2, affecting Kafka metrics.

If you rely on Kafka JMX metrics:

```yaml
spring:
  jmx:
    enabled: true  # Re-enable JMX for Kafka metrics
```

#### Consumer Factory Configuration

For applications using custom consumer factories, verify configuration:

```java
@Bean
public ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZE_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    
    // Ensure compatibility with kafka-clients 2.3.0+
    return new DefaultKafkaConsumerFactory<>(props);
}
```

### 4.5 Kafka Metrics with Micrometer

With Micrometer 1.3.x upgrade, Kafka consumer metrics integration improved:

```java
@Configuration
public class KafkaMetricsConfig {
    
    @Bean
    public MicrometerConsumerListener consumerListener(MeterRegistry meterRegistry) {
        return new MicrometerConsumerListener<>(meterRegistry);
    }
    
    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            MicrometerConsumerListener consumerListener) {
        DefaultKafkaConsumerFactory<String, String> factory = 
            new DefaultKafkaConsumerFactory<>(consumerConfigs());
        factory.addListener(consumerListener);
        return factory;
    }
}
```

### 4.6 Header Mapping Issues

> [!CAUTION]
> Spring Kafka 2.3.x changed header mapping behavior, potentially breaking microservice communication.

**Issue**: Services using Spring Boot 2.1 with Spring Kafka 2.2.x may not correctly deserialize messages from Spring Boot 2.2 services.

**Solutions**:
1. **Upgrade all services simultaneously** (recommended)
2. **Configure explicit header mappers** for backward compatibility
3. **Use String-based header serialization**

**Explicit Header Mapper Example:**
```java
@Bean
public KafkaTemplate<String, String> kafkaTemplate() {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
    
    // Configure explicit header mapper for compatibility
    DefaultKafkaHeaderMapper headerMapper = new DefaultKafkaHeaderMapper();
    headerMapper.setRawMappedHeaders(Arrays.asList("__TypeId__"));
    template.setHeaderMapper(headerMapper);
    
    return template;
}
```

### 4.7 Testing with Embedded Kafka

Embedded Kafka tests remain largely compatible:

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "test-topic" })
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
public class KafkaIntegrationTest {
    // Tests remain the same
}
```

---

## Redis Changes

### 5.1 Spring Data Redis Upgrade

Spring Boot 2.2 includes **Spring Data Redis 2.2** (part of Spring Data Moore release train).

#### Key Enhancements

1. **Redis Streams Support** (Redis 5.0+)
2. **Refined Set Operations**
3. **Jedis 3 Upgrade**
4. **Scripting Support for Jedis Cluster**

### 5.2 Redis Streams

> [!TIP]
> Redis Streams is a new Redis 5.0 data structure, ideal for event sourcing and message queuing.

**Configuration:**
```yaml
spring:
  redis:
    host: localhost
    port: 6379
```

**Using Redis Streams:**
```java
@Configuration
@EnableRedisRepositories
public class RedisStreamsConfig {
    
    @Bean
    public StreamMessageListenerContainer<String, ObjectRecord<String, MyEvent>> 
            streamMessageListenerContainer(
                    RedisConnectionFactory connectionFactory) {
        
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, 
            ObjectRecord<String, MyEvent>> options = 
                StreamMessageListenerContainer
                    .StreamMessageListenerContainerOptions
                    .builder()
                    .pollTimeout(Duration.ofMillis(100))
                    .targetType(MyEvent.class)
                    .build();
        
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }
}

@Service
public class RedisStreamProducer {
    
    @Autowired
    private StreamOperations<String, Object, Object> streamOperations;
    
    public RecordId publishEvent(String streamKey, MyEvent event) {
        ObjectRecord<String, MyEvent> record = 
            StreamRecords.newRecord()
                .ofObject(event)
                .withStreamKey(streamKey);
        
        return streamOperations.add(record);
    }
}
```

### 5.3 Jedis 3 Upgrade

**Jedis Version**: 2.x → 3.x

**Key Changes**:
- Enhanced connection pooling
- Better cluster support
- Improved error handling

**If using Jedis explicitly:**
```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <!-- Version managed by Spring Boot, now 3.x -->
</dependency>
```

**Configuration (unchanged):**
```yaml
spring:
  redis:
    client-type: jedis  # or lettuce (default)
    jedis:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
        max-wait: -1ms
```

### 5.4 Refined Set Operations

Set operations now accept a single collection of keys for cleaner code.

**Before (Spring Data Redis 2.1):**
```java
Set<String> union = redisTemplate.opsForSet()
    .union("key1", Arrays.asList("key2", "key3"));
```

**After (Spring Data Redis 2.2):**
```java
Set<String> union = redisTemplate.opsForSet()
    .union(Arrays.asList("key1", "key2", "key3"));
```

**All affected operations**:
- `union(Collection<K> keys)`
- `intersect(Collection<K> keys)`
- `difference(Collection<K> keys)`

### 5.5 Redis Cache Configuration

Redis cache configuration remains similar but benefits from performance improvements:

```java
@Configuration
@EnableCaching
public class RedisCacheConfig {
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
            .defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .build();
    }
}
```

### 5.6 Lettuce vs Jedis

Spring Boot 2.2 defaults to **Lettuce** (unchanged from 2.1), but Jedis 3 is now a viable alternative.

| Feature | Lettuce | Jedis 3 |
|---------|---------|---------|
| Async/Reactive Support | ✅ Yes | ❌ No |
| Thread-safe | ✅ Yes | ⚠️ Pool required |
| Cluster Support | ✅ Excellent | ✅ Good |
| Performance | ✅ High | ✅ High |
| Default in Spring Boot | ✅ Yes | ❌ No |

**Recommendation**: Stick with Lettuce unless you have specific Jedis requirements.

---

## Testing Framework Changes

### 6.1 JUnit 5 as Default

> [!IMPORTANT]
> `spring-boot-starter-test` now includes JUnit 5 by default with JUnit 4 vintage engine for backward compatibility.

**Spring Boot 2.1:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
    <!-- Provides JUnit 4 by default -->
</dependency>
```

**Spring Boot 2.2:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
    <!-- Now provides:
         - JUnit 5 (Jupiter)
         - JUnit 5 Vintage Engine (for JUnit 4 tests)
         - Mockito 3.x
         - AssertJ
         - Hamcrest
         - Spring Test
    -->
</dependency>
```

### 6.2 Mixed JUnit 4 and JUnit 5 Support

You can run both JUnit 4 and JUnit 5 tests in the same project:

**JUnit 4 Test (still works):**
```java
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class MyJUnit4Test {
    @Test
    public void testSomething() {
        // JUnit 4 test
    }
}
```

**JUnit 5 Test (recommended for new tests):**
```java
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class MyJUnit5Test {
    @Test
    void testSomething() {
        // JUnit 5 test
    }
}
```

### 6.3 Mockito 3 Upgrade

Spring Boot 2.2 upgrades to **Mockito 3.1.0**, which requires **Java 8+**.

**Key Changes**:
- Better JUnit 5 integration via `@ExtendWith(MockitoExtension.class)`
- Improved error messages
- Enhanced strict stubbing

**JUnit 5 + Mockito Pattern:**
```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    
    @Mock
    private UserRepository userRepository;
    
    @InjectMocks
    private UserService userService;
    
    @Test
    void testFindUser() {
        when(userRepository.findById(1L))
            .thenReturn(Optional.of(new User("John")));
        
        User user = userService.findUser(1L);
        assertEquals("John", user.getName());
    }
}
```

### 6.4 Test Slices Improvements

Test slices (`@WebMvcTest`, `@DataJpaTest`, etc.) work seamlessly with JUnit 5:

**Spring Boot 2.1 (JUnit 4):**
```java
@RunWith(SpringRunner.class)
@WebMvcTest(UserController.class)
public class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private UserService userService;
    
    @Test
    public void testGetUser() throws Exception {
        // test code
    }
}
```

**Spring Boot 2.2 (JUnit 5):**
```java
@WebMvcTest(UserController.class)
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private UserService userService;
    
    @Test
    void testGetUser() throws Exception {
        // test code - note: public not required
    }
}
```

> [!TIP]
> JUnit 5 tests don't require `public` modifiers on test classes or methods.

### 6.5 Migrating to JUnit 5

For comprehensive JUnit 4 to 5 migration, refer to:
- [junit4to5_migration_spec.md](junit4to5_migration_spec.md) in this directory

**Quick Migration Summary**:

| JUnit 4 | JUnit 5 |
|---------|---------|
| `@Before` | `@BeforeEach` |
| `@After` | `@AfterEach` |
| `@BeforeClass` | `@BeforeAll` |
| `@AfterClass` | `@AfterAll` |
| `@Ignore` | `@Disabled` |
| `@RunWith(SpringRunner.class)` | `@ExtendWith(SpringExtension.class)` or omit (auto-configured) |
| `@Test(expected = Exception.class)` | `assertThrows(Exception.class, () -> {...})` |
| `import org.junit.Test` | `import org.junit.jupiter.api.Test` |

### 6.6 Embedded Resources Testing

If using TestContainers or embedded databases:

**Recommendation**: Consider migrating to embedded alternatives for faster tests.

See [test_fixer.md](test_fixer.md) for automated conversion from TestContainers to embedded resources.

### 6.7 Global Lazy Initialization (Testing)

> [!TIP]
> Enable lazy initialization in tests to speed up context loading.

```yaml
# application-test.properties
spring:
  main:
    lazy-initialization: true
```

**Benefits**:
- Faster test startup
- Reduced memory consumption
- Only beans used in tests are initialized

**Caveats**:
- Startup failures may be delayed
- May hide configuration errors

---

## Configuration Property Changes

### 7.1 Logging Properties

> [!WARNING]
> Logging file/path properties have been renamed.

**Deprecated (Spring Boot 2.1):**
```yaml
logging:
  file: /var/log/app.log
  path: /var/log
```

**New (Spring Boot 2.2):**
```yaml
logging:
  file:
    name: /var/log/app.log
    path: /var/log
```

**Migration Tip**: Add `spring-boot-properties-migrator` to auto-detect and warn about these changes.

### 7.2 Server Properties

#### Connection Timeout

**Deprecated:**
```yaml
server:
  connection-timeout: 5000
```

**New:**
```yaml
server:
  tomcat:
    connection-timeout: 5000
```

#### Forward Headers

**Deprecated:**
```yaml
server:
  use-forward-headers: true
```

**New:**
```yaml
server:
  forward-headers-strategy: native  # or 'framework'
```

**Options**:
- `native`: Use servlet container's native support
- `framework`: Use Spring's `ForwardedHeaderFilter`
- `none`: Disable forward header handling

### 7.3 JMX Configuration

> [!IMPORTANT]
> JMX is now **disabled by default**.

**To enable:**
```yaml
spring:
  jmx:
    enabled: true
```

**Impact**:
- JConsole/VisualVM monitoring requires explicit enablement
- Kafka JMX metrics disabled by default
- Custom MBeans won't be registered unless enabled

#### Automated JMX Configuration Detection

> [!NOTE]
> An automation tool can detect JMX usage and auto-enable JMX.

**Detection Strategy**

**Step 1: Detect JMX Usage in Code**
```java
// AST/Import Detection Patterns:
// 1. Imports containing: javax.management.*
// 2. Annotations: @ManagedResource, @ManagedOperation, @ManagedAttribute
// 3. Interface implementations: MBeanRegistration, DynamicMBean
// 4. Class references: MBeanServer, ObjectName, JMX
```

**Step 2: Detect Kafka with JMX Metrics**
```xml
<!-- POM Detection -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>

<!-- If Kafka dependency exists, JMX likely needed for metrics -->
```

```java
// Code Detection
// - Classes with @KafkaListener
// - KafkaTemplate usage
// - Custom ConsumerFactory/ProducerFactory
```

**Step 3: Auto-Add Configuration Property**
```yaml
# If JMX usage detected, add to application.yml:
spring:
  jmx:
    enabled: true  # Auto-added by migration tool
```

**Automation Output Example**:
```
[INFO] JMX usage detected in project
[INFO] Found: @ManagedResource in MetricsExporter.java
[INFO] Found: Spring Kafka dependency (JMX metrics support)
[INFO] Added spring.jmx.enabled=true to application.yml
[WARN] JMX is disabled by default in Spring Boot 2.2
```

**Risk Level**: LOW - Safe to enable if JMX was implicitly used

**Automation Confidence**: HIGH (detection) + HIGH (safe transformation)

**Validation**:
- Application starts successfully
- JMX beans are registered (check with JConsole)
- Kafka metrics are available (if applicable)

### 7.4 Lazy Initialization

**New feature** in Spring Boot 2.2:

```yaml
spring:
  main:
    lazy-initialization: true
```

**Benefits**:
- Faster application startup
- Reduced memory footprint

**Trade-offs**:
- Configuration errors detected later
- First request slower
- Less predictable behavior

**Recommendation**: Use selectively in development/test environments.

### 7.5 Configuration Properties Scanning

**New feature**: `@ConfigurationPropertiesScan`

**Before (Spring Boot 2.1):**
```java
@SpringBootApplication
@EnableConfigurationProperties({
    AppProperties.class,
    DatabaseProperties.class,
    CacheProperties.class
})
public class Application {
    // ...
}
```

**After (Spring Boot 2.2):**
```java
@SpringBootApplication
@ConfigurationPropertiesScan  // Auto-scans for @ConfigurationProperties
public class Application {
    // ...
}
```

**Benefits**:
- Less boilerplate
- Automatic discovery
- Cleaner configuration

#### Automated @ConfigurationPropertiesScan Migration

> [!NOTE]
> This transformation requires package structure validation.

**Detection Pattern**

```java
// Detect this pattern:
@SpringBootApplication
@EnableConfigurationProperties({
    AppProperties.class,
    DatabaseProperties.class,
    CacheProperties.class
})
public class Application { ... }
```

**Transformation Strategy**

**Complexity**: MEDIUM - Requires validation

**Step 1: Detect @EnableConfigurationProperties**
```java
// AST Detection:
// - Annotation: @EnableConfigurationProperties
// - Has attributes: value or classes array
// - Extract list of @ConfigurationProperties classes
```

**Step 2: Validate All Classes Are in Scan Path**
```java
// Validation Logic:
// 1. Get @SpringBootApplication base package (e.g., com.example.app)
// 2. Check if all @ConfigurationProperties classes are in or below base package
// 3. Decision:
//    - If ALL within scan path → safe to migrate
//    - If SOME outside scan path → keep @EnableConfigurationProperties for those
```

**Step 3: Transform Annotation**
```java
// Scenario A: All classes in scan path
// Before:
@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, DbProperties.class})
public class Application { }

// After:
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application { }

// Actions:
// - Remove @EnableConfigurationProperties
// - Add @ConfigurationPropertiesScan
// - Remove import org.springframework.boot.context.properties.EnableConfigurationProperties
// - Add import org.springframework.boot.context.properties.ConfigurationPropertiesScan
```

```java
// Scenario B: Mixed (some classes outside scan path)
// Before:
@SpringBootApplication
@EnableConfigurationProperties({
    InternalProps.class,        // in com.example.app.config
    ExternalLibProps.class      // in com.external.library
})

// After:
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({ExternalLibProps.class})  // Keep for external
public class Application { }

// Actions:
// - Add @ConfigurationPropertiesScan for internal classes
// - Keep @EnableConfigurationProperties with only external classes
```

**Step 4: Add Import**
```java
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
```

**Risk Level**: LOW - Safe transformation with proper validation

**Automation Confidence**: HIGH (with package validation)

**Validation Strategy**:
- Application starts successfully
- All @ConfigurationProperties beans are discovered
- No duplicate bean definition errors
- Check with: `@Autowired ApplicationContext context; context.getBeansWithAnnotation(ConfigurationProperties.class)`

**Recommendation**:
- Perform package validation before transformation
- Add to migration report if external classes detected
- Test application startup after migration

---

## Actuator Changes

### 8.1 HTTP Trace

> [!CAUTION]
> HTTP Trace is **disabled by default** in Spring Boot 2.2.

**Reason**: In-memory implementation can consume excessive resources in production.

**To re-enable:**
```java
@Configuration
public class ActuatorConfig {
    @Bean
    public HttpTraceRepository httpTraceRepository() {
        return new InMemoryHttpTraceRepository();
    }
}
```

**Recommendation**: Implement custom persistent storage for production:
```java
@Component
public class CustomHttpTraceRepository implements HttpTraceRepository {
    // Store traces in database, Redis, or external service
}
```

### 8.2 Auditing

**Auditing is disabled by default** for the same reasons.

**To enable:**
```java
@Configuration
public class AuditConfig {
    @Bean
    public InMemoryAuditEventRepository auditEventRepository() {
        return new InMemoryAuditEventRepository();
    }
}
```

### 8.3 Actuator Endpoints

Actuator endpoints remain largely unchanged. Verify your security configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
```

---

## Jakarta EE Migration

### 9.1 Namespace Transition

Spring Boot 2.2 begins the transition from **Java EE (`javax.*`)** to **Jakarta EE (`jakarta.*`)**.

> [!IMPORTANT]
> This is a gradual migration. Full transition comes in Spring Boot 3.x.

#### Dependency Changes

**Java Mail** (now Jakarta Mail):
```xml
<!-- Old groupId (deprecated) -->
<dependency>
    <groupId>javax.mail</groupId>
    <artifactId>javax.mail-api</artifactId>
</dependency>

<!-- New groupId (Spring Boot 2.2+) -->
<dependency>
    <groupId>com.sun.mail</groupId>
    <artifactId>jakarta.mail</artifactId>
</dependency>
```

**Expression Language** (EL):
```xml
<!-- Old -->
<dependency>
    <groupId>org.glassfish</groupId>
    <artifactId>javax.el</artifactId>
</dependency>

<!-- New -->
<dependency>
    <groupId>org.glassfish</groupId>
    <artifactId>jakarta.el</artifactId>
</dependency>
```

### 9.2 Import Statements

**Current state in Spring Boot 2.2** (no changes required):
```java
import javax.persistence.Entity;
import javax.validation.constraints.NotNull;
import javax.servlet.http.HttpServletRequest;
```

**Future state (Spring Boot 3.x)**:
```java
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
```

### 9.3 Migration Timeline

| Version | Status |
|---------|--------|
| Spring Boot 2.2 | Dual support (`javax.*` and `jakarta.*` dependencies) |
| Spring Boot 2.7 | Final 2.x version, still `javax.*` |
| Spring Boot 3.0 | **Full switch to `jakarta.*`** |

**Recommendation**: No immediate action needed for Spring Boot 2.2, but plan for Jakarta EE transition in future upgrades.

### 9.4 Optional: Jakarta EE Preparatory Comments

> [!TIP]
> While not required for Spring Boot 2.2, an automation tool can optionally add preparatory comments for future planning.

**Strategy: Add TODO Comments for Future Migration**

```java
// Before (current valid code)
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.validation.constraints.NotNull;
import javax.servlet.http.HttpServletRequest;

// After (with preparatory comments - optional feature)
import javax.persistence.Entity; // TODO [Spring Boot 3.x]: Migrate to jakarta.persistence.Entity
import javax.persistence.Id; // TODO [Spring Boot 3.x]: Migrate to jakarta.persistence.Id
import javax.validation.constraints.NotNull; // TODO [Spring Boot 3.x]: Migrate to jakarta.validation.constraints.NotNull
import javax.servlet.http.HttpServletRequest; // TODO [Spring Boot 3.x]: Migrate to jakarta.servlet.http.HttpServletRequest
```

**Implementation Strategy**:
- Detect all `javax.*` imports that will change in Jakarta EE
- Add inline comment with migration note
- Optional feature (disabled by default)
- Helps teams plan for future Spring Boot 3.x upgrades

**Affected Packages** (javax → jakarta):
```java
// Common packages that will change:
javax.persistence.*           → jakarta.persistence.*
javax.validation.*            → jakarta.validation.*
javax.servlet.*               → jakarta.servlet.*
javax.transaction.*           → jakarta.transaction.*
javax.annotation.*            → jakarta.annotation.*
javax.inject.*                → jakarta.inject.*
javax.xml.bind.*              → jakarta.xml.bind.*
javax.ws.rs.*                 → jakarta.ws.rs.*
javax.jms.*                   → jakarta.jms.*
javax.mail.*                  → jakarta.mail.* (already migrated in dependency)
javax.ejb.*                   → jakarta.ejb.*
```

**Automation Configuration**:
```yaml
# generator.yml
jakarta-ee-prep:
  enabled: false  # Disabled by default
  add-comments: true
  comment-format: "TODO [Spring Boot 3.x]: Migrate to {new_package}"
```

**Risk Level**: NONE (comments only, no code changes)

**Automation Confidence**: HIGH (straightforward text replacement)

**Benefits**:
- Future-proofs codebase
- Helps with upgrade planning
- Provides visibility of Jakarta EE impact
- No functional changes to current code

**Migration Report Example**:
```
[INFO] Jakarta EE Preparatory Comments Added (Optional)
[INFO] Added TODO comments to 47 javax.* imports
[INFO] Breakdown:
  - javax.persistence.*: 23 imports
  - javax.validation.*: 12 imports
  - javax.servlet.*: 8 imports
  - javax.transaction.*: 4 imports
[INFO] These comments help plan for Spring Boot 3.x migration
```

---

## Performance Optimizations

### 10.1 Startup Performance

Spring Boot 2.2 provides several startup optimizations:

1. **Lazy Initialization**
```yaml
spring:
  main:
    lazy-initialization: true
```

2. **Disabled Hibernate Entity Scanning**
   - Automatic in Spring Boot 2.2
   - Results in 10-20% faster startup for JPA applications

3. **Optimized Auto-configuration**
   - Conditional beans evaluated more efficiently
   - Reduced bootstrap time

### 10.2 Memory Consumption

**Typical improvements**:
- 5-15% reduction in heap usage
- Better garbage collection behavior
- Optimized string deduplication

**Tuning JVM (if needed):**
```bash
java -Xms512m -Xmx1024m \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar application.jar
```

### 10.3 Database Connection Pooling

With HikariCP (default), verify optimal settings:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### 10.4 Caching Improvements

With Micrometer 1.3.x, cache metrics are more efficient:

```yaml
spring:
  cache:
    type: redis  # or caffeine, ehcache
management:
  metrics:
    enable:
      cache: true
```

---

## Breaking Changes and Deprecations

### 11.1 Removed Deprecations

Classes/methods deprecated in Spring Boot 2.1 are **removed** in 2.2:

#### Removed Configuration Properties

| Removed Property | Replacement |
|------------------|-------------|
| `logging.file` | `logging.file.name` |
| `logging.path` | `logging.file.path` |
| `server.connection-timeout` | `server.tomcat.connection-timeout` |
| `server.use-forward-headers` | `server.forward-headers-strategy` |
| `spring.http.multipart.*` | `spring.servlet.multipart.*` |

#### Removed Classes

Check release notes for specific removed classes. Common removals:
- Legacy metric exporters (replaced by Micrometer)
- Deprecated auto-configuration classes

### 11.2 Maven Plugin Changes

**Breaking Change**: Maven plugin now forks by default.

**Before (Spring Boot 2.1):**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <fork>false</fork>  <!-- Default -->
    </configuration>
</plugin>
```

**After (Spring Boot 2.2):**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <!-- Now forks by default - explicit config not needed -->
</plugin>
```

**To disable forking (if needed):**
```xml
<configuration>
    <fork>false</fork>
</configuration>
```

### 11.3 Elasticsearch Changes

> [!WARNING]
> Elasticsearch transport client and Jest client are deprecated.

**Deprecated:**
- `TransportClient`
- `JestClient`

**Recommended:**
```java
@Configuration
public class ElasticsearchConfig {
    
    @Bean
    public RestHighLevelClient elasticsearchClient() {
        return new RestHighLevelClient(
            RestClient.builder(
                new HttpHost("localhost", 9200, "http")
            )
        );
    }
}
```

---

## Migration Checklist

### Pre-Migration

- [ ] Review current Spring Boot version and dependency tree
- [ ] Run all tests to establish baseline
- [ ] Document custom configurations and workarounds
- [ ] Back up `pom.xml` / `build.gradle`
- [ ] Review official Spring Boot 2.2 release notes

### Dependency Migration

- [ ] Update Spring Boot parent version to 2.2.13.RELEASE (or latest 2.2.x)
- [ ] Add `spring-boot-properties-migrator` as runtime dependency
- [ ] Run `mvn dependency:tree` to verify dependency resolution
- [ ] Check for dependency conflicts
- [ ] Update custom-managed dependencies if needed

### Configuration Migration

- [ ] Rename deprecated logging properties (`logging.file` → `logging.file.name`)
- [ ] Update server properties (`server.connection-timeout` → `server.tomcat.connection-timeout`)
- [ ] Review and update `server.use-forward-headers` → `server.forward-headers-strategy`
- [ ] Enable JMX if required (`spring.jmx.enabled=true`)
- [ ] Configure HTTP trace/auditing if needed

### Hibernate/JPA Migration

- [ ] Test Hibernate 5.4 compatibility
- [ ] Review custom Hibernate configurations
- [ ] Migrate from `@TypeDef`/`@Type` to `@Convert` (if applicable)
- [ ] Verify database dialect auto-detection works correctly
- [ ] Test entity scanning and persistence unit configuration
- [ ] Review and optimize query caching settings

### Kafka Migration

- [ ] Verify Kafka broker version compatibility with kafka-clients 2.3.0+
- [ ] Replace deprecated `TopicPartitionInitialOffset` with `TopicPartitionOffset`
- [ ] Update any direct `ContainerProperties` imports
- [ ] Test consumer/producer functionality thoroughly
- [ ] Verify Kafka metrics if using Micrometer
- [ ] Test cross-service communication (header mapping)
- [ ] Enable JMX if using Kafka JMX metrics

### Redis Migration

- [ ] Test Jedis 3 compatibility (if using Jedis)
- [ ] Update set operation method calls to use collection parameters
- [ ] Test Redis cache functionality
- [ ] Explore Redis Streams if using Redis 5.0+
- [ ] Verify Redis connection pooling configuration

### Testing Migration

- [ ] Run existing JUnit 4 tests (should work via vintage engine)
- [ ] Consider migrating critical tests to JUnit 5
- [ ] Update Mockito usage for version 3.x compatibility
- [ ] Test all test slices (`@WebMvcTest`, `@DataJpaTest`, etc.)
- [ ] Verify MockMvc and MockBean functionality
- [ ] Test embedded resource tests (Kafka, databases)
- [ ] Consider enabling lazy initialization in test profiles

### Actuator Migration

- [ ] Implement custom HTTP trace repository if needed
- [ ] Implement custom audit event repository if needed
- [ ] Verify actuator endpoint security
- [ ] Test metrics endpoints (Prometheus, etc.)

### Jakarta EE Preparation

- [ ] Document current `javax.*` dependencies
- [ ] Plan for future Jakarta EE migration
- [ ] Update Java Mail dependencies to Jakarta Mail

### Post-Migration

- [ ] Compile application (`mvn clean compile`)
- [ ] Run all unit tests (`mvn test`)
- [ ] Run all integration tests
- [ ] Perform smoke testing in dev/staging environment
- [ ] Review application startup logs for warnings
- [ ] Monitor startup time and memory usage
- [ ] Load test critical paths
- [ ] Remove `spring-boot-properties-migrator` after successful migration
- [ ] Update documentation and README
- [ ] Commit changes with descriptive message

### Validation

- [ ] Application starts without errors/warnings
- [ ] All tests pass
- [ ] Database operations work correctly
- [ ] Kafka consumers/producers function properly
- [ ] Redis caching works as expected
- [ ] Actuator endpoints accessible (if configured)
- [ ] JMX metrics available (if enabled)
- [ ] No performance degradation
- [ ] Logs don't show deprecation warnings

---

## Migration Validation Strategy

> [!IMPORTANT]
> Proper validation is critical for successful automated migration.

### Validation Levels

#### Level 1: Syntax Validation ✅
**Goal**: Ensure code compiles after transformation

**Method**: 
```bash
mvn clean compile
# or
gradle clean build
```

**Required**: YES (must pass)

**Automation**: Full

**Success Criteria**:
- Zero compilation errors
- All generated code is syntactically correct
- Import statements are valid

---

#### Level 2: Dependency Validation ✅
**Goal**: Ensure all dependencies resolve correctly

**Method**:
```bash
mvn dependency:tree
mvn dependency:analyze
```

**Check for**:
- Dependency conflicts
- kafka-clients version >= 2.3.0
- No deprecated dependency warnings

**Required**: YES (must pass)

**Automation**: Full

**Success Criteria**:
- Clean dependency tree
- No version conflicts
- All transitive dependencies compatible

---

#### Level 3: Property Validation ⚠️
**Goal**: Ensure no deprecated properties remain

**Method**:
```xml
<!-- Add to pom.xml temporarily -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

**Start application and check logs**:
```
[WARN] Property 'logging.file' is deprecated. Use 'logging.file.name' instead.
```

**Required**: RECOMMENDED

**Automation**: Full (add dependency + parse startup logs)

**Success Criteria**:
- No deprecated property warnings
- All properties migrated correctly
- Application starts successfully

---

#### Level 4: Unit Test Validation ✅
**Goal**: Ensure tests still pass

**Method**:
```bash
mvn test
# or
gradle test
```

**Required**: YES (must pass)

**Automation**: Full

**Success Criteria**:
- All tests pass (100%)
- No test configuration errors
- JUnit 4 tests run via vintage engine

---

#### Level 5: Integration Test Validation ⚠️
**Goal**: Ensure integration tests pass

**Method**:
```bash
mvn verify
# or
gradle integrationTest
```

**Required**: RECOMMENDED

**Automation**: Full (but may take time)

**Success Criteria**:
- All integration tests pass
- Kafka/Redis/Database connections work
- HTTP endpoints respond correctly

---

### Automated Validation Checklist

Example output from automation tool:

```
╔════════════════════════════════════════════════════════════════╗
║     Spring Boot 2.1 → 2.2 Migration Validation Report         ║
╚════════════════════════════════════════════════════════════════╝

Project: my-spring-application
Date: 2025-12-16 14:30:00
Mode: Full Migration

┌─────────────────────────────────────────────────────────────────┐
│ VALIDATION RESULTS                                              │
├─────────────────────────────────────────────────────────────────┤
│ ✅ Level 1: Compilation            [PASSED]                     │
│    - Duration: 12.4s                                            │
│    - Files compiled: 234                                        │
│    - Errors: 0, Warnings: 0                                     │
│                                                                 │
│ ✅ Level 2: Dependency Resolution  [PASSED]                     │
│    - kafka-clients version: 2.3.1 ✓                            │
│    - No conflicts detected                                      │
│    - Dependencies analyzed: 87                                  │
│                                                                 │
│ ✅ Level 3: Property Validation    [PASSED]                     │
│    - No deprecated properties found                             │
│    - Application startup: SUCCESS                               │
│    - Properties migrator: No warnings                           │
│                                                                 │
│ ✅ Level 4: Unit Tests             [PASSED]                     │
│    - Tests run: 156                                             │
│    - Passed: 156 (100%)                                         │
│    - Failed: 0                                                  │
│    - Duration: 34.2s                                            │
│                                                                 │
│ ✅ Level 5: Integration Tests      [PASSED]                     │
│    - Tests run: 23                                              │
│    - Passed: 23 (100%)                                          │
│    - Failed: 0                                                  │
│    - Duration: 2m 15s                                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ MANUAL REVIEW REQUIRED                                          │
├─────────────────────────────────────────────────────────────────┤
│ ⚠️  3 items require manual review:                              │
│                                                                 │
│ 1. Forward Headers Strategy (application.yml:23)               │
│    - Migrated to: server.forward-headers-strategy: native      │
│    - Action: Review if 'native' or 'framework' is appropriate  │
│                                                                 │
│ 2. Redis Set Operations (RedisService.java:45,67,89)           │
│    - Method signatures updated                                  │
│    - Action: Verify collection handling logic                   │
│                                                                 │
│ 3. Hibernate AttributeConverter (JsonAttributeConverter.java)  │
│    - Generated converter stub with TODO comments                │
│    - Action: Complete implementation and test                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ MIGRATION SUMMARY                                               │
├─────────────────────────────────────────────────────────────────┤
│ Total Changes: 47                                               │
│ ├─ POM Updates: 5                                               │
│ ├─ Property Changes: 8                                          │
│ ├─ Code Changes: 34                                             │
│ └─ Generated Files: 1 (JsonAttributeConverter.java)             │
│                                                                 │
│ Automation Rate: 94% (44/47 fully automated)                    │
│ Manual Review Items: 3                                          │
│                                                                 │
│ Overall Status: ✅ SUCCESS                                      │
└─────────────────────────────────────────────────────────────────┘

Next Steps:
1. ✅ Review manual action items listed above
2. ✅ Test in staging environment
3. ✅ Deploy to production when ready
4. ✅ Remove spring-boot-properties-migrator dependency

Rollback: Run `git checkout .` to revert all changes
Backups: Located in .migration-backup/ directory
```

### Rollback Strategy

For each transformation, the automation tool should maintain:

**1. File Backups**
```bash
.migration-backup/
├── pom.xml.backup
├── application.yml.backup
└── src/
    └── main/
        └── java/
            └── com/
                └── example/
                    ├── KafkaService.java.backup
                    └── RedisService.java.backup
```

**2. Transformation Log**
```json
{
  "timestamp": "2025-12-16T14:30:00Z",
  "project": "my-spring-application",
  "migrations": [
    {
      "file": "pom.xml",
      "type": "DEPENDENCY_UPDATE",
      "changes": [
        {
          "line": 12,
          "before": "<version>2.1.6.RELEASE</version>",
          "after": "<version>2.2.13.RELEASE</version>"
        }
      ]
    },
    {
      "file": "src/main/resources/application.yml",
      "type": "PROPERTY_RENAME",
      "changes": [
        {
          "line": 5,
          "before": "logging.file: /var/log/app.log",
          "after": "logging.file.name: /var/log/app.log"
        }
      ]
    }
  ]
}
```

**3. Rollback Command**
```bash
# Rollback all changes
java -jar antikythera.jar --rollback-migration \
  --migration-id=20251216143000

# Rollback specific file
java -jar antikythera.jar --rollback-file \
  --file=application.yml \
  --migration-id=20251216143000
```

**4. Dry-Run Mode**
```bash
# Preview changes without applying
java -jar antikythera.jar --spring-boot-migrate \
  --source-version=2.1 \
  --target-version=2.2 \
  --dry-run \
  --verbose

# Output:
[DRY RUN] Would update pom.xml:
  Line 12: 2.1.6.RELEASE → 2.2.13.RELEASE
  
[DRY RUN] Would update application.yml:
  Line 5: logging.file → logging.file.name
  Line 6: logging.path → logging.file.path
  
[DRY RUN] Would update KafkaService.java:
  Line 23: TopicPartitionInitialOffset → TopicPartitionOffset
  Line 45: TopicPartitionInitialOffset → TopicPartitionOffset
  
[DRY RUN] Would add spring.jmx.enabled=true to application.yml
  Reason: Detected JMX usage in MetricsExporter.java

Total Changes: 47 files
Estimated Time: 3-5 seconds
```

---

## Troubleshooting

### Issue: Application Fails to Start

**Symptom**: Application fails with bean creation errors.

**Solutions**:
1. Enable debug logging:
```yaml
logging:
  level:
    org.springframework: DEBUG
```

2. Check for deprecated bean factories or custom auto-configurations
3. Verify all dependencies are compatible with Spring Boot 2.2

### Issue: Kafka Connection Errors

**Symptom**: `org.apache.kafka.common.errors.UnsupportedVersionException`

**Solution**:
- Ensure Kafka broker version is compatible with kafka-clients 2.3.0+
- Verify `spring.kafka.bootstrap-servers` configuration
- Check network connectivity and firewall rules

### Issue: Hibernate Entity Not Found

**Symptom**: `Entity not found` exceptions despite correct annotations.

**Solution**:
- Verify `@EntityScan` configuration if using non-standard package structure
- Check that entity classes have proper `@Entity` annotation
- Review package scanning configuration in `@SpringBootApplication`

### Issue: Tests Fail with NoSuchMethodError

**Symptom**: Tests fail with method-related errors.

**Solution**:
- Verify Mockito version is 3.1.0+
- Check for dependency conflicts: `mvn dependency:tree | grep mockito`
- Ensure JUnit 5 vintage engine is present for JUnit 4 tests

### Issue: Metrics Not Appearing

**Symptom**: Actuator metrics endpoint returns empty or missing metrics.

**Solution**:
- Enable JMX: `spring.jmx.enabled=true`
- For Kafka metrics, add `MicrometerConsumerListener`
- Verify Micrometer registry is configured correctly

### Issue: Lazy Initialization Causing Errors

**Symptom**: Beans fail to initialize on first access.

**Solution**:
- Disable lazy initialization: `spring.main.lazy-initialization=false`
- Or selectively exclude problematic beans from lazy init
- Review bean initialization order and dependencies

### Issue: Redis Connection Pool Exhaustion

**Symptom**: `Could not get a resource from the pool`

**Solution**:
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 16
        max-idle: 8
        min-idle: 0
```

### Issue: Deprecated Property Warnings

**Symptom**: Logs show property deprecation warnings.

**Solution**:
1. Add `spring-boot-properties-migrator`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

2. Review and update properties based on warnings
3. Remove migrator after migration is complete

---

## Automation Tool Development

> [!TIP]
> This section provides guidance for developing an automated Spring Boot 2.1 to 2.2 migration tool using the Antikythera framework.

### Tool Architecture

Based on the established `JUnit425Migrator` pattern:

```
SpringBoot21to22Migrator (Main Orchestrator)
├── PomDependencyMigrator
│   ├── Update Spring Boot parent version (2.1.x → 2.2.13)
│   ├── Migrate Jakarta dependencies (javax.mail → jakarta.mail)
│   └── Validate Kafka client version compatibility
├── PropertyFileMigrator
│   ├── YamlPropertyTransformer
│   │   ├── Migrate logging.file → logging.file.name
│   │   ├── Migrate server.connection-timeout → server.tomcat.connection-timeout
│   │   └── Transform server.use-forward-headers → server.forward-headers-strategy
│   └── PropertiesFileTransformer (same transformations for .properties files)
├── KafkaCodeMigrator
│   ├── ImportReplacer (TopicPartitionInitialOffset → TopicPartitionOffset)
│   └── TypeUsageReplacer (all type references)
├── RedisCodeMigrator
│   └── SetOperationTransformer (union/intersect/difference method calls)
├── HibernateCodeMigrator (Optional - requires manual review)
│   ├── TypeDefDetector
│   ├── AttributeConverterGenerator (stub generation)
│   └── TypeAnnotationReplacer
├── ConfigurationAnnotationMigrator
│   ├── ConfigurationPropertiesScanAdder
│   └── EnableConfigurationPropertiesOptimizer
├── JmxConfigurationDetector
│   ├── CodeAnalyzer (detect JMX usage)
│   └── PropertyInjector (add spring.jmx.enabled=true)
└── MigrationValidator
    ├── CompilationValidator (mvn compile)
    ├── DependencyTreeValidator (check kafka-clients version)
    ├── PropertyValidator (detect remaining deprecated properties)
    └── ReportGenerator (markdown/HTML migration report)
```

### Implementation Phases

#### Phase 1: Foundation (P0 - Critical)

**Components**:
- PomDependencyMigrator
- PropertyFileMigrator (YAML + Properties)
- MigrationValidator (compilation + basic checks)

**Rationale**: These are low-risk, high-confidence transformations that must complete successfully.

#### Phase 2: Code Transformations (P1 - High Priority)

**Components**:
- KafkaCodeMigrator
- JmxConfigurationDetector
- ConfigurationAnnotationMigrator

**Rationale**: Common patterns with clear transformation rules.

#### Phase 3: Advanced Transformations (P2 - Medium Priority)

**Components**:
- RedisCodeMigrator
- HibernateCodeMigrator (stub generation only)

**Rationale**: More complex transformations that may require manual review.

### Configuration File (generator.yml)

```yaml
spring-boot-migration:
  enabled: true
  source-version: "2.1"
  target-version: "2.2"
  
  pom:
    enabled: true
    spring-boot-version: "2.2.13.RELEASE"
    update-jakarta-mail: true
    validate-kafka-version: true
    
  properties:
    enabled: true
    yaml-files:
      - "src/main/resources/application*.yml"
      - "src/main/resources/application*.yaml"
      - "src/test/resources/application*.yml"
    properties-files:
      - "src/main/resources/application*.properties"
      - "src/test/resources/application*.properties"
    backup-original: true
    
  code:
    kafka:
      enabled: true
      replace-topic-partition-offset: true
      
    redis:
      enabled: true
      transform-set-operations: true
      
    hibernate:
      enabled: false  # Disabled by default - requires manual review
      generate-converter-stubs: true
      
    jmx:
      auto-detect-usage: true
      auto-enable: true
      
    configuration-properties-scan:
      enabled: true
      validate-package-structure: true
      
  validation:
    compile-after-migration: true
    run-unit-tests: false  # Optional
    check-dependency-tree: true
    generate-report: true
    
  options:
    dry-run: false
    verbose-logging: true
    create-backups: true
    fail-on-error: false
```

### Usage Example

```java
// Command-line invocation
java -jar antikythera.jar --spring-boot-migrate \
  --source-version=2.1 \
  --target-version=2.2 \
  --project-path=/path/to/project \
  --dry-run

// Programmatic usage
SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(
    projectRoot,
    dryRun,
    migrationConfig
);

MigrationResult result = migrator.migrate();

// Print report
System.out.println(result.generateReport());
```

### Migration Report Format

```markdown
# Spring Boot 2.1 → 2.2 Migration Report

**Project**: my-spring-application
**Date**: 2025-12-16 14:30:00
**Mode**: Dry Run

## Summary

- ✅ POM Migration: SUCCESS
- ✅ Property Migration: SUCCESS  
- ✅ Kafka Migration: SUCCESS
- ⚠️  Redis Migration: PARTIAL (manual review required)
- ⏭️  Hibernate Migration: SKIPPED (manual migration recommended)
- ✅ Validation: PASSED

## Details

### POM Changes (3 changes)
- ✅ Updated Spring Boot parent: 2.1.6.RELEASE → 2.2.13.RELEASE
- ✅ Migrated javax.mail → jakarta.mail
- ✅ Verified kafka-clients version: 2.3.1 (compatible)

### Property File Changes (5 changes)
- ✅ application.yml: logging.file → logging.file.name
- ✅ application.yml: logging.path → logging.file.path
- ✅ application.yml: server.connection-timeout → server.tomcat.connection-timeout
- ⚠️  application.yml: server.use-forward-headers → server.forward-headers-strategy (review: native vs framework)
- ✅ application-test.yml: logging.file → logging.file.name

### Code Changes

#### Kafka Migration (12 occurrences)
- ✅ KafkaConsumerService.java: TopicPartitionInitialOffset → TopicPartitionOffset (4 occurrences)
- ✅ KafkaProducerConfig.java: TopicPartitionInitialOffset → TopicPartitionOffset (2 occurrences)
- ✅ KafkaTestHelper.java: TopicPartitionInitialOffset → TopicPartitionOffset (6 occurrences)

#### Redis Migration (3 occurrences)  
- ⚠️  RedisService.java: union() method signature updated (line 45)
- ⚠️  RedisService.java: intersect() method signature updated (line 67)
- ⚠️  CacheService.java: difference() method signature updated (line 89)

#### JMX Configuration
- ✅ Detected JMX usage: @ManagedResource in MetricsExporter.java
- ✅ Added spring.jmx.enabled=true to application.yml

### Manual Review Required

1. **Forward Headers Strategy** (application.yml:23)
   - Current: `server.use-forward-headers: true`
   - Migrated to: `server.forward-headers-strategy: native`
   - Action: Verify if 'native' or 'framework' is appropriate for your deployment

2. **Redis Set Operations** (3 files)
   - Verify collection handling in transformed method calls
   - Test Redis operations after migration

### Validation Results

- ✅ Compilation: SUCCESS (mvn compile)
- ✅ Dependency Tree: No conflicts detected
- ✅ Deprecated Properties: None remaining
- ℹ️  Tests: Not executed (use --run-tests flag)

## Next Steps

1. Review manual action items above
2. Run unit tests: `mvn test`
3. Run integration tests: `mvn verify`
4. Deploy to test environment
5. Remove backups after successful validation

## Rollback Instructions

If migration fails:
```bash
git checkout pom.xml
git checkout src/main/resources/application*.yml
git checkout src/main/java/**/*.java
```

Or restore from backups:
```bash
cp pom.xml.backup pom.xml
cp application.yml.backup src/main/resources/application.yml
```
```

### Testing Strategy

```java
@Test
public void testPomMigration() {
    // Given: Project with Spring Boot 2.1.6
    Path pomFile = createTestPom("2.1.6.RELEASE");
    
    // When: Migrate
    PomDependencyMigrator migrator = new PomDependencyMigrator(false);
    boolean result = migrator.migrate(pomFile);
    
    // Then: Version updated
    assertTrue(result);
    assertTrue(pomContains(pomFile, "2.2.13.RELEASE"));
}

@Test
public void testKafkaClassMigration() {
    // Given: Code using TopicPartitionInitialOffset
    CompilationUnit cu = parseTestFile("KafkaTest.java");
    
    // When: Migrate
    KafkaCodeMigrator migrator = new KafkaCodeMigrator();
    boolean result = migrator.migrate(cu);
    
    // Then: Class replaced
    assertTrue(result);
    assertFalse(cu.toString().contains("TopicPartitionInitialOffset"));
    assertTrue(cu.toString().contains("TopicPartitionOffset"));
}

@Test
public void testYamlPropertyMigration() {
    // Given: application.yml with old properties
    String yaml = "logging:\n  file: /var/log/app.log\n";
    
    // When: Transform
    PropertyFileMigrator migrator = new PropertyFileMigrator();
    String result = migrator.transformYaml(yaml);
    
    // Then: Property nested correctly
    assertTrue(result.contains("logging:\n  file:\n    name: /var/log/app.log"));
}
```

### Error Handling

```java
public MigrationResult migrate() {
    MigrationResult result = new MigrationResult();
    
    try {
        // Phase 1: POM (critical)
        result.addPhase(migratePom());
        if (result.hasErrors() && !options.failOnError) {
            result.addWarning("POM migration failed, continuing with other migrations");
        }
        
        // Phase 2: Properties (critical)
        result.addPhase(migrateProperties());
        
        // Phase 3: Code (best effort)
        try {
            result.addPhase(migrateCode());
        } catch (Exception e) {
            result.addError("Code migration partially failed: " + e.getMessage());
            result.addAction("Manual review required for code changes");
        }
        
        // Phase 4: Validation
        if (!options.dryRun) {
            result.addPhase(validate());
        }
        
    } catch (Exception e) {
        result.addError("Migration failed: " + e.getMessage());
        result.suggestRollback();
    }
    
    return result;
}
```

### Integration with Existing Tools

The Spring Boot migration tool can reuse existing Antikythera components:

- **JUnit425Migrator**: For any JUnit 4→5 migrations needed
- **PomDependencyMigrator**: Base class for POM transformations
- **PropertyFileManager**: For property file handling
- **ConversionOutcome**: For result tracking

**Example**:
```java
public class SpringBoot21to22Migrator {
    private final JUnit425Migrator junitMigrator;
    private final PropertyFileManager propertyManager;
    
    public MigrationResult migrate() {
        MigrationResult result = new MigrationResult();
        
        // Reuse existing migrators
        if (hasJUnit4Tests()) {
            result.add(junitMigrator.migrateAll(compilationUnit));
        }
        
        // Spring Boot specific migrations
        result.add(springBootSpecificMigrations());
        
        return result;
    }
}
```

---

## Springfox / Swagger Migration

> [!WARNING]
> Springfox 2.x (especially versions before 3.0.0) has compatibility issues with Spring Boot 2.2+.

### Issue: Springfox 2.x Compatibility

**Affected Versions**: Springfox 2.7.0, 2.8.0, 2.9.x

**Symptoms**:
- Application fails to start
- Swagger UI returns 404 errors
- Conflicts with Spring Framework 5.2+ used in Spring Boot 2.2

###Solution Options

#### Option 1: Upgrade to Springfox 3.0.0 (Breaking Changes)

**Update dependency**:
```xml
<!-- Old -->
<dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-swagger-ui</artifactId>
    <version>2.7.0</version>
</dependency>
<dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-swagger2</artifactId>
    <version>2.7.0</version>
</dependency>

<!-- New (Spring Boot 2.2+ compatible) -->
<dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

**Code Changes Required**:

```java
// Before (Springfox 2.x)
import springfox.documentation.swagger2.annotations.EnableSwagger2;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.spi.DocumentationType;

@Configuration
@EnableSwagger2
public class SwaggerConfig {
    @Bean
    public Docket api() {
        return new Docket(DocumentationType.SWAGGER_2)
            .select()
            .apis(RequestHandlerSelectors.basePackage("com.example"))
            .paths(PathSelectors.any())
            .build();
    }
}

// After (Springfox 3.x)
import springfox.documentation.oas.annotations.EnableOpenApi;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.spi.DocumentationType;

@Configuration
@EnableOpenApi  // Changed from @EnableSwagger2
public class SwaggerConfig {
    @Bean
    public Docket api() {
        return new Docket(DocumentationType.OAS_30)  // Changed from SWAGGER_2
            .select()
            .apis(RequestHandlerSelectors.basePackage("com.example"))
            .paths(PathSelectors.any())
            .build();
    }
}
```

**URL Changes**:
- Old: `/swagger-ui.html`
- New: `/swagger-ui/index.html` or `/swagger-ui/`

#### Option 2: Migrate to SpringDoc OpenAPI (Recommended)

SpringDoc is a **modern, actively maintained alternative** with better Spring Boot integration.

**Replace dependencies**:
```xml
<!-- Remove Springfox -->
<!-- <dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-swagger-ui</artifactId>
    <version>2.7.0</version>
</dependency> -->

<!-- Add SpringDoc -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.6.14</version>
</dependency>
```

**Simplified Configuration**:
```java
// Zero configuration required - works out of the box!

// Optional customization:
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("My API")
                .version("1.0.0")
                .description("API Documentation"));
    }
}
```

**Advantages of SpringDoc**:
- No `@EnableSwagger2` annotation needed
- Automatic OpenAPI 3.0 spec generation
- Better Spring WebFlux support
- Active development and Spring Boot 3.x compatibility
- Same UI URL: `/swagger-ui.html`

### Migration Automation

**Detection Pattern**:
```java
// Scan for outdated Springfox versions
<dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-swagger2</artifactId>
    <version>\u003c3.0.0</version>  // Flag if < 3.0.0
</dependency>
```

**Automation Recommendation**:
- **Detection**: Check `pom.xml` for Springfox version < 3.0.0
- **Action**: Flag for manual review with migration options
- **Report**: "Springfox 2.x detected - upgrade to 3.0 or migrate to SpringDoc OpenAPI"

---

## Enhanced Jedis 2.x to 3.x Migration

> [!IMPORTANT]
> Spring Boot 2.2 upgrades Jedis from 2.x to 3.x, introducing breaking API changes.

### Breaking Changes Overview

| Change | Jedis 2.x | Jedis 3.x |
|--------|-----------|-----------|
| Connection Configuration | Simple setters | `RedisStandaloneConfiguration` |
| Pool Configuration | Direct properties | Enhanced `JedisPoolConfig` |
| Client Configuration | N/A | `JedisClientConfiguration` |
| SSL Support | Basic | Enhanced with SSL parameters |

### Migration Steps

#### 1. Connection Factory Configuration

**Before (Jedis 2.x)**:
```java
@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    JedisConnectionFactory factory = new JedisConnectionFactory();
    factory.setHostName("localhost");
    factory.setPort(6379);
    factory.setDatabase(0);
    factory.setPassword("password");
    return factory;
}
```

**After (Jedis 3.x)**:
```java
@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    // Step 1: Standalone configuration
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
    config.setHostName("localhost");
    config.setPort(6379);
    config.setDatabase(0);
    config.setPassword(RedisPassword.of("password"));
    
    // Step 2: Client configuration
    JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
        .usePooling()
        .poolConfig(jedisPoolConfig())
        .build();
    
    // Step 3: Create factory
    return new JedisConnectionFactory(config, clientConfig);
}
```

####  2. Connection Pool Configuration

**Enhanced pool config with best practices**:
```java
@Bean
public JedisPoolConfig jedisPoolConfig() {
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    
    // Pool size configuration
    poolConfig.setMaxTotal(10);
    poolConfig.setMaxIdle(5);
    poolConfig.setMinIdle(1);
    
    // NEW in Jedis 3.x - Connection validation
    poolConfig.setTestOnBorrow(true);   // Validate connection before use
    poolConfig.setTestOnReturn(true);   // Validate connection after use
    poolConfig.setTestWhileIdle(true);  // Validate idle connections
    
    // Eviction policy
    poolConfig.setTimeBetweenEvictionRunsMillis(60000);
    poolConfig.setMinEvictableIdleTimeMillis(300000);
    
    return poolConfig;
}
```

#### 3. SSL Configuration (New in Jedis 3.x)

```java
@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
    config.setHostName("secure-redis.example.com");
    config.setPort(6380);
    
    // SSL Configuration
    JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
        .useSsl()
        .sslParameters(createSSLParameters())
        .and()
        .usePooling()
        .poolConfig(jedisPoolConfig())
        .build();
    
    return new JedisConnectionFactory(config, clientConfig);
}

private SSLParameters createSSLParameters() {
    SSLParameters sslParameters = new SSLParameters();
    // Configure SSL as needed
    return sslParameters;
}
```

#### 4. Cluster Configuration

**Before (Jedis 2.x)**:
```java
@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    RedisClusterConfiguration clusterConfig = 
        new RedisClusterConfiguration(Arrays.asList("node1:6379", "node2:6379"));
    
    return new JedisConnectionFactory(clusterConfig);
}
```

**After (Jedis 3.x)**:
```java
@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    // Cluster configuration
    RedisClusterConfiguration clusterConfig = 
        new RedisClusterConfiguration(Arrays.asList("node1:6379", "node2:6379"));
    clusterConfig.setMaxRedirects(3);
    
    // Client configuration
    JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
        .usePooling()
        .poolConfig(jedisPoolConfig())
        .readTimeout(Duration.ofMillis(2000))
        .connectTimeout(Duration.ofMillis(1000))
        .build();
    
    return new JedisConnectionFactory(clusterConfig, clientConfig);
}
```

### Alternative: Migrate to Lettuce

If Jedis 3.x migration is complex, consider **Lettuce** (Spring Boot's default):

```xml
<!-- Remove Jedis -->
<!-- <dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency> -->

<!-- Simply don't exclude Lettuce from spring-boot-starter-data-redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
    <!-- Lettuce is the default - no exclusions needed -->
</dependency>
```

**Lettuce advantages**:
- Thread-safe (no connection pooling needed)
- Reactive programming support
- Lower memory footprint
- Better async/non-blocking operations

**Simple Lettuce configuration**:
```java
@Bean
public LettuceConnectionFactory redisConnectionFactory() {
    return new LettuceConnectionFactory("localhost", 6379);
}
```

### Testing Jedis Migration

```java
@SpringBootTest
class RedisConnectionTest {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Test
    void testConnectionAndBasicOperations() {
        // Test connection
        String key = "test:key";
        String value = "test-value";
        
        redisTemplate.opsForValue().set(key, value);
        String retrieved = redisTemplate.opsForValue().get(key);
        
        assertEquals(value, retrieved);
        
        // Cleanup
        redisTemplate.delete(key);
    }
}
```

---

## Spring Cloud Version Compatibility

> [!WARNING]
> Spring Cloud versions must match Spring Boot versions for compatibility.

### Version Compatibility Matrix

| Spring Boot Version | Spring Cloud Release Train | Release Status |
|---------------------|---------------------------|----------------|
| 2.1.x | **Greenwich** | EOL (End of Life) |
| 2.2.x | **Hoxton** | Maintenance |
| 2.3.x | Hoxton.SR8+ or 2020.0.x | Active |

### Required Spring Cloud Upgrade for Spring Boot 2.2

If using Spring Cloud (e.g., for Config Server, Service Discovery, Circuit Breakers):

**Update parent POM or dependency management**:

```xml
<properties>
    <!-- Old (Spring Boot 2.1) -->
    <spring-cloud.version>Greenwich.SR4</spring-cloud.version>
    
    <!-- New (Spring Boot 2.2) -->
    <spring-cloud.version>Hoxton.SR12</spring-cloud.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Spring Cloud Component Changes

#### Spring Cloud Contract (Testing)

**Hoxton improvements**:
- Better Kotlin DSL support
- Enhanced stub generation
- WireMock version upgrade

**No breaking changes** for standard usage:
```java
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.example:user-service:+:stubs:8090",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
class ContractTest {
    // Tests unchanged
}
```

#### Spring Cloud Config

**Minimum versions**:
- Spring Boot 2.2.x → Spring Cloud Config 2.2.x (Hoxton)

**Configuration remains backward compatible**:
```yaml
spring:
  cloud:
    config:
      uri: http://localhost:8888
      fail-fast: true
```

### Automation Detection

**Check Spring Cloud version in POM**:
```yaml
# generator.yml
spring-cloud:
  detect-incompatible:
    Greenwich: ["2.2.x", "2.3.x"]  # Greenwich incompatible with Spring Boot 2.2+
  auto-suggest-version:
    spring-boot-2.2: "Hoxton.SR12"
```

---

## ShedLock Version Management

> [!TIP]
> ShedLock provides distributed locking for scheduled tasks - ensure version consistency.

### Issue: Version Mismatch

Applications may have **mismatched ShedLock dependency versions**, causing runtime issues.

**Example of problematic configuration**:
```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-jdbc-template</artifactId>
    <version>2.1.0</version>  <!-- MISMATCH! -->
</dependency>
```

### Solution: Version Synchronization

**Sync all ShedLock dependencies**:
```xml
<properties>
    <shedlock.version>2.6.0</shedlock.version>  <!-- Spring Boot 2.2 compatible -->
</properties>

<dependencies>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
        <version>${shedlock.version}</version>
    </dependency>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
        <version>${shedlock.version}</version>
    </dependency>
</dependencies>
```

### Spring Boot 2.2 Compatible Versions

| ShedLock Version | Spring Boot Compatibility | Notes |
|------------------|--------------------------|-------|
| 2.x | 2.1.x, 2.2.x | Legacy, but compatible |
| 3.x | 2.2.x, 2.3.x | Improved error handling |
| 4.x | 2.2.x, 2.3.x | Latest features, Java 11+ |

### Configuration (Unchanged)

```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerConfig {
    
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}
```

**Usage**:
```java
@Component
public class ScheduledTasks {
    
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(
        name = "processReports",
        lockAtMostFor = "PT10M",
        lockAtLeastFor = "PT5M"
    )
    public void processReports() {
        // Only one instance will execute this
    }
}
```

### Automation Recommendation

```yaml
# generator.yml
shedlock:
  detect-version-mismatch: true
  recommend-sync: true
  compatible-versions:
    spring-boot-2.2: ["2.6.0", "3.0.0"]
```

**Detection Logic**:
```java
// Detect version mismatch
Map<String, String> shedlockVersions = new HashMap<>();
for (Dependency dep : dependencies) {
    if (dep.getGroupId().equals("net.javacrumbs.shedlock")) {
        shedlockVersions.put(dep.getArtifactId(), dep.getVersion());
    }
}

if (shedlockVersions.values().stream().distinct().count() > 1) {
    report.addWarning("ShedLock version mismatch detected");
    report.addAction("Sync all ShedLock dependencies to same version");
}
```

---

## References


### Official Documentation

- [Spring Boot 2.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.2-Release-Notes)
- [Spring Framework 5.2 What's New](https://docs.spring.io/spring-framework/docs/5.2.x/spring-framework-reference/)
- [Spring Data Moore Release Train](https://spring.io/blog/2019/09/30/spring-data-moore-goes-ga)
- [Spring Kafka 2.3 Documentation](https://docs.spring.io/spring-kafka/docs/2.3.x/reference/html/)
- [Hibernate 5.4 Migration Guide](https://github.com/hibernate/hibernate-orm/blob/5.4/migration-guide.adoc)

### Related Antikythera Documentation

- [JUnit 4 to 5 Migration Specification](junit4to5_migration_spec.md)
- [SDET Reference Guide](sdet_reference_guide.md)
- [SDET Reference Guide - JUnit 5](sdet_reference_guide_junit5.md)
- [Test Fixer](test_fixer.md)

### Migration Tools

- **Spring Boot Properties Migrator**: Automatically detects and reports deprecated properties
- **Antikythera TestFixer**: Automates test framework migration (`--425` flag for JUnit 4→5)
- **IntelliJ IDEA Migration**: Built-in inspection tools for Spring Boot upgrades

### Community Resources

- [Spring Boot GitHub Issues](https://github.com/spring-projects/spring-boot/issues)
- [Stack Overflow - spring-boot-2.2](https://stackoverflow.com/questions/tagged/spring-boot-2.2)
- [Spring Community Forums](https://spring.io/community)

---

## Summary

Migrating from Spring Boot 2.1 to 2.2 involves:

1. **Dependency Updates**: Hibernate 5.4, Spring Kafka 2.3, Mockito 3, JUnit 5
2. **Configuration Changes**: Logging properties, server properties, JMX enablement
3. **Breaking Changes**: Kafka version requirements, deprecated property removals
4. **Testing Improvements**: JUnit 5 as default, better test slice support
5. **Performance Gains**: Faster startup, reduced memory usage

**Key Takeaways**:
- Use `spring-boot-properties-migrator` to identify deprecated properties
- Test thoroughly, especially Kafka and Hibernate functionality
- JUnit 4 tests continue to work via vintage engine
- Jakarta EE transition begins but doesn't impact existing code
- Review and enable JMX if needed for monitoring

**Next Steps**:
- Follow the [Migration Checklist](#migration-checklist)
- Test in a non-production environment first
- Plan for future Spring Boot 3.x migration (Jakarta EE)

---

*Last Updated: 2025-12-16*  
*Document Version: 1.0*
