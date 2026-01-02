# Spring Boot 2.2 to 2.3 Migration Guide

## Overview

This comprehensive guide covers all changes required to migrate from Spring Boot 2.2 to Spring Boot 2.3. This release brings significant cloud-native features, improved container support, graceful shutdown capabilities, and important breaking changes that require careful attention.

Spring Boot 2.3 represents a major shift towards cloud-native application development with features like Cloud Native Buildpacks, layered JARs, liveness/readiness probes, and graceful shutdown support.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Dependency Upgrades](#dependency-upgrades)
3. [Breaking Changes](#breaking-changes)
4. [Validation Starter Changes](#validation-starter-changes)
5. [Cloud Native Features](#cloud-native-features)
6. [Graceful Shutdown](#graceful-shutdown)
7. [Liveness and Readiness Probes](#liveness-and-readiness-probes)
8. [Kafka Changes](#kafka-changes)
9. [Spring Data Changes](#spring-data-changes)
10. [Testing Framework Updates](#testing-framework-updates)
11. [Configuration Property Changes](#configuration-property-changes)
12. [Actuator Improvements](#actuator-improvements)
13. [Deprecations](#deprecations)
14. [Migration Checklist](#migration-checklist)
15. [Troubleshooting](#troubleshooting)
16. [References](#references)

---

## Executive Summary

### Key Changes at a Glance

| Category | Major Changes |
|----------|---------------|
| **Spring Framework** | 5.2.x → 5.2.6+ |
| **Spring Data** | Moore → Neumann (includes R2DBC GA support) |
| **Spring Kafka** | 2.3.x → 2.5.x (requires kafka-clients 2.5.0+) |
| **Java Support** | Added Java 14 support |
| **Validation** | **No longer included by default in web starters** |
| **Gradle** | Requires 6.3+ (5.6.x deprecated) |
| **Cloud Native** | Buildpacks integration, layered JARs |
| **Graceful Shutdown** | All embedded servers supported |
| **Health Probes** | Kubernetes liveness/readiness probes |
| **Elasticsearch** | 7.6.2 (transport client removed) |
| **Cassandra** | Driver v4 (breaking changes) |

### Migration Complexity

- **Critical (Must Address)**: Validation starter removal, Elasticsearch transport removal, Cassandra driver upgrade
- **High Priority**: Enable graceful shutdown, configure health probes for Kubernetes
- **Medium Priority**: Adopt cloud-native buildpacks, update Kafka configuration
- **Optional**: Leverage layered JARs, configure wildcard config locations

---

## Dependency Upgrades

### 2.1 Update Spring Boot Version

**pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.3.12.RELEASE</version> <!-- Latest 2.3.x release -->
    <relativePath/>
</parent>
```

### 2.2 Major Dependency Version Changes

| Dependency | Spring Boot 2.2 | Spring Boot 2.3 | Notes |
|-----------|-----------------|-----------------|-------|
| Spring Framework | 5.2.x | 5.2.6+ | Performance improvements |
| Spring Data | Moore | Neumann | R2DBC GA, Cassandra v4, Elasticsearch 7.6+ |
| Spring Kafka | 2.3.x | 2.5.x | **Breaking changes in error handling** |
| Spring Security | 5.2.x | 5.3.x | SAML improvements |
| Hibernate | 5.4.x | 5.4.15+ | Bug fixes |
| Elasticsearch | 6.8.x / 7.5.x | 7.6.2 | **Transport client removed** |
| Cassandra Driver | 3.x | **4.x** | **Major breaking changes** |
| Micrometer | 1.3.x | 1.5.x | New meter types |
| R2DBC | Arabba | **Borca-SR1 (GA)** | Production-ready reactive SQL |

### 2.3 Minimum Requirements

> [!WARNING]
> Spring Boot 2.3 has updated minimum version requirements.

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| Gradle | 6.3+ | Gradle 5.6.x still supported but deprecated |
| Jetty | 9.4.22+ | If using embedded Jetty |
| Java | 8+ | Java 14 support added |

---

## Breaking Changes

### 3.1 Validation Starter No Longer Included

> [!CAUTION]
> **CRITICAL**: The `spring-boot-starter-validation` is NO LONGER automatically included in web starters.

**Impact**: Applications using `@Valid`, `@Validated`, or any `javax.validation.*` annotations will fail at runtime.

**Before (Spring Boot 2.2):**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <!-- Validation was included automatically -->
</dependency>
```

**After (Spring Boot 2.3) - REQUIRED:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- MUST add explicitly if using validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**Detection**: Look for these imports in your code:
```java
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
```

**Error without validation starter:**
```
javax.validation.NoProviderFoundException: Unable to create a Configuration, 
because no Bean Validation provider could be found.
```

### 3.2 Unique DataSource Name Generation

> [!IMPORTANT]
> DataSource names are now generated uniquely by default, impacting H2 console usage.

**Before (Spring Boot 2.2):**
```yaml
# H2 console worked with default name
spring:
  h2:
    console:
      enabled: true
```

**After (Spring Boot 2.3):**
```yaml
spring:
  datasource:
    generate-unique-name: false  # Disable if using H2 console
  h2:
    console:
      enabled: true
```

**Why**: Prevents DataSource naming conflicts in applications with multiple DataSources.

#### Automated H2 Console Configuration

> [!NOTE]
> If H2 console is enabled, unique name generation should be disabled automatically.

**Detection Strategy**

**Complexity**: LOW - Simple property and dependency detection

**Step 1: Detect H2 Usage**
```xml
<!-- POM Detection: Check for H2 dependency -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- OR in Gradle -->
runtimeOnly 'com.h2database:h2'
```

**Step 2: Detect H2 Console Enabled**
```yaml
# Check application.yml/application.properties for:
spring:
  h2:
    console:
      enabled: true

# OR in properties format:
spring.h2.console.enabled=true
```

**Step 3: Check if generate-unique-name is Set**
```yaml
# Check if property already exists:
spring:
  datasource:
    generate-unique-name: false  # Already configured
```

**Automated Transformation Strategy**

If H2 dependency present AND H2 console enabled AND generate-unique-name NOT set:

**Action: Add Configuration Property**

**For YAML files:**
```yaml
# Add under spring.datasource:
spring:
  datasource:
    generate-unique-name: false  # Added for H2 console compatibility
  h2:
    console:
      enabled: true
```

**For Properties files:**
```properties
# Add property:
spring.datasource.generate-unique-name=false
spring.h2.console.enabled=true
```

**Validation Strategy**:
- Application starts successfully
- H2 console accessible at /h2-console
- Database connection works in H2 console
- No naming conflicts with other DataSources

**Risk Level**: NONE - Only adds configuration, no code changes

**Automation Confidence**: 100% (safe when H2 console is enabled)

**Recommendation**: 
- Fully automated for H2 console use cases
- Add warning if multiple DataSources detected (may need manual review)

**Automation Output Example**:
```
[INFO] H2 database detected in dependencies
[INFO] H2 console enabled in application.yml
[INFO] Adding spring.datasource.generate-unique-name=false for H2 console compatibility
[SUCCESS] H2 configuration updated
[NOTE] If using multiple DataSources, review naming strategy manually
```

### 3.3 Application Context Runner Changes

Bean overriding is now disabled by default in `ApplicationContextRunner`:

**Before:**
```java
new ApplicationContextRunner()
    .withUserConfiguration(MyConfig.class)
    .run(context -> {
        // Bean overriding worked by default
    });
```

**After:**
```java
new ApplicationContextRunner()
    .withPropertyValues("spring.main.allow-bean-definition-overriding=true")
    .withUserConfiguration(MyConfig.class)
    .run(context -> {
        // Explicitly enable if needed
    });
```

### 3.4 Spring Cloud Connectors Removed

> [!WARNING]
> `spring-cloud-connectors` starter has been removed (deprecated in 2.2).

**Migration Path**: Use **Java CFEnv** instead.

**Before:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cloud-connectors</artifactId>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>io.pivotal.cfenv</groupId>
    <artifactId>java-cfenv-boot</artifactId>
    <version>2.2.4.RELEASE</version>
</dependency>
```

---

## Validation Starter Changes

### 4.1 When to Add Validation Starter

Add `spring-boot-starter-validation` if your application uses:

**Bean Validation Annotations:**
```java
@Entity
public class User {
    @NotNull
    @Size(min = 2, max = 30)
    private String name;
    
    @Email
    private String email;
    
    @Min(18)
    private Integer age;
}
```

**Controller Validation:**
```java
@RestController
public class UserController {
    @PostMapping("/users")
    public ResponseEntity<User> createUser(@Valid @RequestBody User user) {
        // Validation requires spring-boot-starter-validation
    }
}
```

**Service Layer Validation:**
```java
@Service
@Validated
public class UserService {
    public void processUser(@Valid User user) {
        // Validation requires spring-boot-starter-validation
    }
}
```

### 4.2 Gradle Configuration

**build.gradle:**
```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation' // Add this
}
```

### 4.3 Automated Detection

Search your codebase for validation usage:

```bash
# Find @Valid annotations
grep -r "@Valid" src/main/java

# Find validation constraint annotations
grep -rE "@NotNull|@NotEmpty|@Size|@Email|@Pattern|@Min|@Max" src/main/java

# Find @Validated annotations
grep -r "@Validated" src/main/java
```

#### Automated Validation Starter Migration

> [!NOTE]
> This is the **most critical** migration for Spring Boot 2.3. Automated detection and dependency injection is highly recommended.

**Detection Patterns**

**Pattern 1: Detect @Valid and @Validated Usage**
```java
// AST/Import Detection Patterns:
// 1. Imports: javax.validation.Valid, javax.validation.constraints.*
// 2. Annotations: @Valid, @Validated on parameters, fields, or classes
// 3. Constraint annotations: @NotNull, @NotEmpty, @Size, @Email, etc.

// Example usages to detect:
@RestController
public class UserController {
    @PostMapping("/users")
    public ResponseEntity<User> create(@Valid @RequestBody User user) {
        // Requires validation starter
    }
}

@Service
@Validated
public class UserService {
    public void process(@Valid User user) {
        // Requires validation starter
    }
}

@Entity
public class User {
    @NotNull
    @Size(min = 2, max = 50)
    private String name;
    
    @Email
    private String email;
}
```

**Detection Strategy**

**Complexity**: LOW - Simple import and annotation scanning

**Step 1: Scan for Validation Imports**
```java
// Detection rules:
// - Scan all .java files for imports:
//   - javax.validation.Valid
//   - javax.validation.Validated
//   - javax.validation.constraints.*
//   - org.springframework.validation.annotation.Validated
```

**Step 2: Scan for Validation Annotations**
```java
// AST Analysis:
// 1. Find @Valid annotations on:
//    - Method parameters (especially in @RestController, @Controller)
//    - Fields
//    - Return types
// 2. Find @Validated annotations on:
//    - Classes (typically @Service, @Component)
//    - Method parameters
// 3. Find constraint annotations:
//    - @NotNull, @NotEmpty, @NotBlank
//    - @Size, @Min, @Max
//    - @Email, @Pattern
//    - @Positive, @Negative
//    - @Past, @Future, @PastOrPresent, @FutureOrPresent
```

**Step 3: Check POM for Validation Starter**
```xml
<!-- Detection logic: -->
<!-- Check if dependency already exists: -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**Automated Transformation Strategy**

**Complexity**: LOW - Simple dependency injection

**Action: Add Validation Starter to POM**

If validation usage is detected AND starter is not present:

```xml
<!-- Add to pom.xml dependencies section: -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

For Gradle projects:

```gradle
// Add to build.gradle dependencies:
implementation 'org.springframework.boot:spring-boot-starter-validation'
```

**Validation Strategy**:
- Application compiles successfully
- Application starts without `NoProviderFoundException`
- All @Valid/@Validated annotations function correctly
- Validation constraints trigger expected errors

**Risk Level**: NONE - Only adds dependency, no code changes

**Automation Confidence**: 100% (safe, deterministic transformation)

**Recommendation**: 
- Fully automated
- Add to migration report with count of validation usages detected
- Provide list of files using validation for reference

**Automation Output Example**:
```
[INFO] Validation usage detected in project
[INFO] Found @Valid annotations: 23 occurrences across 8 files
[INFO] Found @Validated annotations: 5 occurrences across 4 classes
[INFO] Found constraint annotations: 147 occurrences across 34 entities
[INFO] Adding spring-boot-starter-validation dependency to pom.xml
[SUCCESS] Validation starter added successfully

Files using validation:
- UserController.java: @Valid on method parameters
- OrderController.java: @Valid on method parameters
- ProductService.java: @Validated class annotation
- User.java: @NotNull, @Size, @Email constraint annotations
- Order.java: @NotNull, @Min, @Max constraint annotations
... (29 more files)
```

---

## Cloud Native Features

### 5.1 Cloud Native Buildpacks

> [!TIP]
> Build production-ready container images without writing Dockerfiles.

Spring Boot 2.3 integrates **Cloud Native Buildpacks** (CNBs) for streamlined container image creation.

#### Maven Plugin

**Build an OCI image:**
```bash
mvn spring-boot:build-image
```

**Configuration:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <image>
            <name>myapp:${project.version}</name>
            <builder>paketobuildpacks/builder:base</builder>
            <env>
                <BP_JVM_VERSION>11.*</BP_JVM_VERSION>
            </env>
        </image>
    </configuration>
</plugin>
```

#### Gradle Plugin

**Build an OCI image:**
```bash
./gradlew bootBuildImage
```

**Configuration:**
```gradle
bootBuildImage {
    imageName = "myapp:${version}"
    builder = "paketobuildpacks/builder:base"
    environment = [
        "BP_JVM_VERSION" : "11.*"
    ]
}
```

#### Benefits

- **No Dockerfile needed**: Automated best practices
- **Optimized layers**: Automatic dependency layering
- **Security**: Regularly updated base images
- **Reproducible builds**: Consistent across environments
- **OCI compliant**: Works with Docker, Kubernetes, etc.

### 5.2 Layered JARs

> [!TIP]
> Optimize Docker build times by separating dependencies into layers.

**Enable layered JAR creation:**

**pom.xml:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

**build.gradle:**
```gradle
bootJar {
    layered {
        enabled = true
    }
}
```

#### Default Layers

1. **dependencies**: Third-party dependencies
2. **spring-boot-loader**: Spring Boot loader classes
3. **snapshot-dependencies**: Snapshot dependencies
4. **application**: Application classes and resources

#### Extract Layers

**List layers:**
```bash
java -Djarmode=layertools -jar myapp.jar list
```

**Extract layers:**
```bash
java -Djarmode=layertools -jar myapp.jar extract
```

#### Dockerfile with Layers

```dockerfile
FROM adoptopenjdk:11-jre-hotspot as builder
WORKDIR application
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract

FROM adoptopenjdk:11-jre-hotspot
WORKDIR application
COPY --from=builder application/dependencies/ ./
COPY --from=builder application/spring-boot-loader/ ./
COPY --from=builder application/snapshot-dependencies/ ./
COPY --from=builder application/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
```

**Benefits:**
- Only rebuild changed layers
- Faster Docker builds
- Smaller image updates
- Better caching

#### Custom Layer Configuration

**layers.xml:**
```xml
<layers xmlns="http://www.springframework.org/schema/boot/layers"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.springframework.org/schema/boot/layers
                            https://www.springframework.org/schema/boot/layers/layers-2.3.xsd">
    <application>
        <into layer="spring-boot-loader">
            <include>org/springframework/boot/loader/**</include>
        </into>
        <into layer="application" />
    </application>
    <dependencies>
        <into layer="snapshot-dependencies">
            <include>*:*:*SNAPSHOT</include>
        </into>
        <into layer="company-dependencies">
            <include>com.mycompany:*</include>
        </into>
        <into layer="dependencies"/>
    </dependencies>
    <layerOrder>
        <layer>dependencies</layer>
        <layer>spring-boot-loader</layer>
        <layer>snapshot-dependencies</layer>
        <layer>company-dependencies</layer>
        <layer>application</layer>
    </layerOrder>
</layers>
```

---

## Graceful Shutdown

### 6.1 Overview

> [!TIP]
> Spring Boot 2.3 introduces graceful shutdown for all embedded web servers.

Graceful shutdown ensures:
- No new requests are accepted during shutdown
- Active requests complete within a grace period
- Resources are released properly
- Data integrity is maintained

**Supported Servers:**
- Tomcat
- Jetty
- Reactor Netty
- Undertow

### 6.2 Configuration

**Enable graceful shutdown:**

```yaml
server:
  shutdown: graceful  # Default is "immediate"
  
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # Grace period (default: 30s)
```

**Properties:**

| Property | Values | Default | Description |
|----------|--------|---------|-------------|
| `server.shutdown` | `immediate`, `graceful` | `immediate` | Shutdown mode |
| `spring.lifecycle.timeout-per-shutdown-phase` | Duration | `30s` | Max wait time for active requests |

### 6.3 Behavior

**During graceful shutdown:**

1. Application stops accepting new requests (returns 503 Service Unavailable)
2. Waits for active requests to complete
3. If timeout expires, forces shutdown
4. Calls lifecycle callbacks (`@PreDestroy`, `DisposableBean`)

**Example:**
```java
@Component
public class GracefulShutdownAware {
    
    @PreDestroy
    public void cleanup() {
        log.info("Performing cleanup during graceful shutdown");
        // Close connections, flush caches, etc.
    }
}
```

### 6.4 Kubernetes Integration

Graceful shutdown works seamlessly with Kubernetes:

**deployment.yaml:**
```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: myapp
        image: myapp:latest
        lifecycle:
          preStop:
            exec:
              command: ["sh", "-c", "sleep 10"]
        terminationGracePeriodSeconds: 40  # Should exceed timeout
```

**Recommendation**: Set `terminationGracePeriodSeconds` > `timeout-per-shutdown-phase`

### 6.5 Programmatic Control

**Custom shutdown hook:**
```java
@Configuration
public class ShutdownConfig {
    
    @Bean
    public ApplicationListener<ContextClosedEvent> shutdownListener() {
        return event -> {
            log.info("Application is shutting down gracefully");
            // Custom cleanup logic
        };
    }
}
```

---

## Liveness and Readiness Probes

### 7.1 Overview

> [!IMPORTANT]
> Spring Boot 2.3 adds Kubernetes-style liveness and readiness probes.

**Liveness Probe**: Determines if the application is running (should it be restarted?)  
**Readiness Probe**: Determines if the application can accept traffic (should it receive requests?)

### 7.2 Enable Health Probes

**application.yaml:**
```yaml
management:
  health:
    probes:
      enabled: true  # Enable liveness/readiness endpoints
  endpoint:
    health:
      show-details: when-authorized
```

**Auto-enabled on Kubernetes**: When running on Kubernetes, probes are automatically enabled.

### 7.3 Health Endpoints

**Liveness:**
```
GET /actuator/health/liveness
```

**Readiness:**
```
GET /actuator/health/readiness
```

**Response:**
```json
{
  "status": "UP"
}
```

### 7.4 Application State

Spring Boot tracks application availability through:

**LivenessState**:
- `CORRECT`: Application is running
- `BROKEN`: Application failed, restart required

**ReadinessState**:
- `ACCEPTING_TRAFFIC`: Ready to serve requests
- `REFUSING_TRAFFIC`: Not ready (still starting or overloaded)

### 7.5 Custom Health Indicators

**Custom liveness check:**
```java
@Component
public class CustomLivenessHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        // Check critical application state
        boolean isHealthy = checkCriticalComponents();
        
        if (isHealthy) {
            return Health.up().build();
        } else {
            return Health.down()
                .withDetail("reason", "Critical component failure")
                .build();
        }
    }
    
    private boolean checkCriticalComponents() {
        // Implement health check logic
        return true;
    }
}
```

**Custom readiness check:**
```java
@Component
public class CustomReadinessHealthIndicator implements HealthIndicator {
    
    @Autowired
    private DataSource dataSource;
    
    @Override
    public Health health() {
        // Check if application is ready to serve traffic
        try {
            dataSource.getConnection().close();
            return Health.up().build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("reason", "Database not ready")
                .build();
        }
    }
}
```

### 7.6 Kubernetes Configuration

**deployment.yaml:**
```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: myapp
        image: myapp:latest
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 8081
          name: management
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: management
          initialDelaySeconds: 30
          periodSeconds: 10
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: management
          initialDelaySeconds: 10
          periodSeconds: 5
          failureThreshold: 3
```

### 7.7 Programmatic Control

**Manually update application availability:**
```java
@Service
public class AppService {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    public void markAsNotReady() {
        AvailabilityChangeEvent.publish(
            eventPublisher,
            this,
            ReadinessState.REFUSING_TRAFFIC
        );
    }
    
    public void markAsReady() {
        AvailabilityChangeEvent.publish(
            eventPublisher,
            this,
            ReadinessState.ACCEPTING_TRAFFIC
        );
    }
}
```

**Listen to availability events:**
```java
@Component
public class AvailabilityListener {
    
    @EventListener
    public void onStateChange(AvailabilityChangeEvent<?> event) {
        log.info("Availability changed: {}", event.getState());
    }
}
```

---

## Kafka Changes

### 8.1 Spring Kafka 2.5 Upgrade

> [!IMPORTANT]
> Spring Boot 2.3 upgrades to Spring Kafka 2.5, requiring kafka-clients 2.5.0+.

**Version Changes:**

| Component | Spring Boot 2.2 | Spring Boot 2.3 |
|-----------|-----------------|-----------------|
| spring-kafka | 2.3.x | 2.5.x |
| kafka-clients | 2.3.x | 2.5.x |

### 8.2 Error Handling Changes

> [!WARNING]
> Default error handlers have changed.

**Spring Kafka 2.5 defaults:**

| Listener Type | Default Error Handler |
|--------------|----------------------|
| Record Listener | `SeekToCurrentErrorHandler` |
| Batch Listener | `RecoveringBatchErrorHandler` |

**Old default (2.3.x):**
- Record: `LoggingErrorHandler`
- Batch: `BatchLoggingErrorHandler`

**Migration:**

If relying on old behavior, explicitly configure:

```java
@Configuration
public class KafkaConfig {
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> 
            kafkaListenerContainerFactory(
                ConsumerFactory<String, String> consumerFactory) {
        
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        
        // Explicitly set error handler if needed
        factory.setErrorHandler(new SeekToCurrentErrorHandler());
        
        return factory;
    }
}
```

### 8.3 Producer Enhancements

**Shared Producer Factory:**

Multiple `KafkaTemplate` instances can share a single `DefaultKafkaProducerFactory`:

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfigs());
    }
    
    @Bean
    public KafkaTemplate<String, String> defaultKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
    
    @Bean
    public KafkaTemplate<String, String> customKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // Override producer properties
        template.setProducerListener(new CustomProducerListener());
        return template;
    }
}
```

**Producer Fencing** (Kafka 2.5+ brokers):
```yaml
spring:
  kafka:
    producer:
      transaction-id-prefix: tx-
```

**Dynamic Producer Configuration:**
```java
DefaultKafkaProducerFactory<String, String> factory = 
    new DefaultKafkaProducerFactory<>(config);

// Update configuration dynamically (e.g., credential rotation)
Map<String, Object> newConfig = new HashMap<>(config);
newConfig.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, newLocation);
factory.updateConfigs(newConfig);
```

### 8.4 Consumer Improvements

**Static Group Membership:**
```yaml
spring:
  kafka:
    consumer:
      properties:
        group.instance.id: consumer-1  # Static membership
```

**Seek Operations:**
```java
@KafkaListener(topics = "my-topic")
public class MyListener implements ConsumerSeekAware {
    
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, 
                                     ConsumerSeekCallback callback) {
        // Reset partitions to initial offset using wildcards
        assignments.keySet().forEach(tp -> 
            callback.seekToBeginning(tp.topic(), tp.partition())
        );
    }
    
    @Override
    public void registerSeekCallback(ConsumerSeekCallback callback) {
        // Register callback
    }
}
```

### 8.5 Metrics Integration

**Native Kafka metrics** are now auto-configured:

```yaml
management:
  metrics:
    enable:
      kafka: true  # Default: true
```

**No JMX required**: Metrics are published via Micrometer without JMX.

**Available metrics:**
- `kafka.consumer.*`
- `kafka.producer.*`
- `kafka.stream.*`

### 8.6 Topic Builder

**New TopicBuilder API:**

```java
@Configuration
public class KafkaTopicConfig {
    
    @Bean
    public NewTopic userTopic() {
        return TopicBuilder.name("users")
            .partitions(10)
            .replicas(3)
            .compact()
            .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip")
            .build();
    }
    
    @Bean
    public NewTopic orderTopic() {
        return TopicBuilder.name("orders")
            .partitions(5)
            .replicas(2)
            .build();
    }
}
```

---

## Spring Data Changes

### 9.1 Spring Data Neumann

Spring Boot 2.3 includes **Spring Data Neumann**, bringing:
- R2DBC GA support (production-ready reactive SQL)
- Cassandra Driver v4 upgrade (breaking changes)
- Elasticsearch 7.6+ (transport client removed)

### 9.2 Cassandra Changes

> [!CAUTION]
> Cassandra driver upgraded from 3.x to 4.x with breaking changes.

**Key Changes:**

1. **ClusterBuilderCustomizer removed**
2. **local-datacenter property required**
3. **New customization interfaces**

**Before (Spring Boot 2.2):**
```java
@Bean
public ClusterBuilderCustomizer clusterBuilderCustomizer() {
    return builder -> builder
        .withPort(9042)
        .withLoadBalancingPolicy(new RoundRobinPolicy());
}
```

**After (Spring Boot 2.3):**
```java
@Bean
public DriverConfigLoaderBuilderCustomizer driverConfigCustomizer() {
    return builder -> builder
        .withString(DefaultDriverOption.REQUEST_TIMEOUT, "5000ms");
}

@Bean
public CqlSessionBuilderCustomizer sessionBuilderCustomizer() {
    return builder -> builder
        .withLocalDatacenter("datacenter1");  // REQUIRED
}
```

**Configuration:**
```yaml
spring:
  data:
    cassandra:
      contact-points: localhost:9042
      local-datacenter: datacenter1  # REQUIRED in driver v4
      keyspace-name: mykeyspace
      schema-action: create-if-not-exists
```

#### Automated Cassandra Migration Detection

> [!IMPORTANT]
> Due to complexity, Cassandra migration is **detection + manual review** rather than fully automated.

**Detection Strategy**

**Complexity**: HIGH - Requires code analysis and configuration validation

**Step 1: Detect Cassandra Usage**
```xml
<!-- POM Detection: Check for Spring Data Cassandra -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-cassandra</artifactId>
</dependency>

<!-- OR reactive variant -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-cassandra-reactive</artifactId>
</dependency>
```

**Step 2: Detect Deprecated Patterns**
```java
// AST Detection for deprecated classes/interfaces:
// 1. ClusterBuilderCustomizer usage
import org.springframework.boot.autoconfigure.cassandra.ClusterBuilderCustomizer;

@Bean
public ClusterBuilderCustomizer clusterBuilderCustomizer() {
    // DEPRECATED - must be replaced
}

// 2. Cluster class usage (driver 3.x)
import com.datastax.driver.core.Cluster;

// 3. Session class usage (driver 3.x)
import com.datastax.driver.core.Session;
```

**Step 3: Check Required Configuration**
```yaml
# Check application.yml for local-datacenter property:
spring:
  data:
    cassandra:
      local-datacenter: ???  # REQUIRED - check if present

# If missing, flag as CRITICAL ERROR
```

**Generation Strategy**

**Since full automation is unsafe, generate migration guide instead:**

**Action 1: Generate Migration Report**

Create `cassandra-migration-guide.md` with:
- List of deprecated patterns found and their locations
- Required configuration changes
- Code examples for each deprecated pattern
- Testing checklist

**Action 2: Add Required Configuration**

If `local-datacenter` property is missing:

```yaml
# Auto-add to application.yml with TODO comment:
spring:
  data:
    cassandra:
      # TODO: Set your Cassandra datacenter name (required for driver v4)
      # Common values: datacenter1, DC1, us-east-1, etc.
      # Run: nodetool status | grep ^DC to find your datacenter name
      local-datacenter: datacenter1  # REPLACE WITH YOUR DATACENTER NAME
      contact-points: localhost:9042
      keyspace-name: ${existing-keyspace-name}
```

**Action 3: Flag for Manual Code Review**

For each detected `ClusterBuilderCustomizer`:

```java
// Generate migration template adjacent to original:

// OLD (Spring Boot 2.2) - TO BE REMOVED:
@Bean
public ClusterBuilderCustomizer clusterBuilderCustomizer() {
    return builder -> builder
        .withPort(9042)
        .withLoadBalancingPolicy(new RoundRobinPolicy());
}

// TODO: MIGRATE TO (Spring Boot 2.3):
// Replace above bean with the following two beans:

/*
@Bean
public DriverConfigLoaderBuilderCustomizer driverConfigCustomizer() {
    return builder -> builder
        .withString(DefaultDriverOption.CONTACT_POINTS, "localhost:9042")
        .withString(DefaultDriverOption.REQUEST_TIMEOUT, "5000ms");
}

@Bean
public CqlSessionBuilderCustomizer sessionBuilderCustomizer() {
    return builder -> builder
        .withLocalDatacenter("datacenter1");  // REQUIRED
}
*/
```

**Validation Strategy**:
- Cassandra usage detected via dependencies
- Deprecated patterns identified and documented
- Required `local-datacenter` property flagged if missing
- Migration guide generated with specific file/line numbers
- Manual review required flag set in migration result

**Risk Level**: HIGH - Manual migration required

**Automation Confidence**: 40% (detection only, transformation requires manual review)

**Recommendation**: 
- Automated detection and reporting
- Generate migration guide with specific instructions
- Add TODO comments to configuration files
- Flag as requiring manual review and testing
- Provide migration templates for common patterns

**Automation Output Example**:
```
[WARNING] Cassandra driver v3 usage detected - breaking changes in v4

Detection Summary:
[FOUND] spring-boot-starter-data-cassandra dependency
[FOUND] 2 deprecated patterns requiring migration

Deprecated Patterns Found:
1. CassandraConfig.java:34-42
   - Pattern: ClusterBuilderCustomizer
   - Severity: HIGH - Must be replaced
   - Migration: Replace with DriverConfigLoaderBuilderCustomizer + CqlSessionBuilderCustomizer

2. CassandraConfig.java:45
   - Pattern: import com.datastax.driver.core.Cluster
   - Severity: MEDIUM - Driver v3 API
   - Migration: Use com.datastax.oss.driver.api.core.CqlSession instead

Configuration Issues:
[ERROR] CRITICAL: Missing required property 'spring.data.cassandra.local-datacenter'
[ACTION] Adding property to application.yml with TODO comment

Generated Files:
✓ cassandra-migration-guide.md - Detailed migration instructions
✓ application.yml - Added local-datacenter property with TODO

Manual Review Required:
⚠️  This migration requires manual code changes and testing
⚠️  Review cassandra-migration-guide.md for detailed instructions
⚠️  Test Cassandra connectivity after migration
⚠️  Verify CQL queries work with driver v4 API
```

**Migration Guide Template**:

The generated `cassandra-migration-guide.md` should include:

```markdown
# Cassandra Driver v4 Migration Guide

## Summary
- **Deprecated Patterns**: 2 found
- **Configuration Issues**: 1 critical
- **Manual Changes Required**: Yes

## Required Actions

### 1. Add Required Configuration Property [CRITICAL]

File: `src/main/resources/application.yml`

Add the following property (already added with TODO):
\```yaml
spring:
  data:
    cassandra:
      local-datacenter: datacenter1  # REPLACE WITH YOUR DATACENTER NAME
\```

**How to find your datacenter name:**
\```bash
# Connect to your Cassandra node and run:
nodetool status | grep ^DC

# Or check cqlsh:
cqlsh> SELECT data_center FROM system.local;
\```

### 2. Replace ClusterBuilderCustomizer

**File**: `CassandraConfig.java:34-42`

**Current Code** (DEPRECATED):
\```java
@Bean
public ClusterBuilderCustomizer clusterBuilderCustomizer() {
    return builder -> builder
        .withPort(9042)
        .withLoadBalancingPolicy(new RoundRobinPolicy());
}
\```

**Replacement Code**:
\```java
@Bean
public DriverConfigLoaderBuilderCustomizer driverConfigCustomizer() {
    return builder -> builder
        .withString(DefaultDriverOption.CONTACT_POINTS, "localhost:9042")
        .withString(DefaultDriverOption.REQUEST_TIMEOUT, "5000ms");
}

@Bean
public CqlSessionBuilderCustomizer sessionBuilderCustomizer() {
    return builder -> builder
        .withLocalDatacenter("datacenter1");
}
\```

**Required Imports**:
\```java
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import org.springframework.boot.autoconfigure.cassandra.DriverConfigLoaderBuilderCustomizer;
import org.springframework.boot.autoconfigure.cassandra.CqlSessionBuilderCustomizer;
\```

## Testing Checklist

- [ ] Update `local-datacenter` property to match your Cassandra datacenter
- [ ] Replace all ClusterBuilderCustomizer beans
- [ ] Update imports from driver v3 to v4
- [ ] Compile application: `mvn compile`
- [ ] Start Cassandra and test connection
- [ ] Run integration tests: `mvn verify`
- [ ] Verify CQL queries execute correctly
- [ ] Test connection pooling and timeout settings
```

### 9.3 Elasticsearch Changes

> [!CAUTION]
> Transport client and Jest library support removed.

**Removed:**
- `TransportClient` (deprecated by Elasticsearch)
- `JestClient` (no longer maintained)

**Required:**
- Use `RestHighLevelClient` (only supported client)

**Before (Spring Boot 2.2):**
```java
@Autowired
private TransportClient transportClient;  // NO LONGER SUPPORTED
```

**After (Spring Boot 2.3):**
```java
@Configuration
public class ElasticsearchConfig {
    
    @Bean
    public RestHighLevelClient elasticsearchClient() {
        ClientConfiguration clientConfig = ClientConfiguration.builder()
            .connectedTo("localhost:9200")
            .withConnectTimeout(Duration.ofSeconds(5))
            .withSocketTimeout(Duration.ofSeconds(3))
            .build();
            
        return RestClients.create(clientConfig).rest();
    }
}
```

**Spring Data Elasticsearch:**
```java
public interface UserRepository extends ElasticsearchRepository<User, String> {
    List<User> findByName(String name);
}
```

### 9.4 Couchbase Changes

**Couchbase SDK upgraded to v3**:

**Before:**
```yaml
spring:
  couchbase:
    bootstrap-hosts: localhost
    bucket:
      name: mybucket
      password: mypassword
```

**After:**
```yaml
spring:
  couchbase:
    connection-string: couchbase://localhost  # New property
    username: myuser
    password: mypassword
# Bucket no longer auto-configured - manual configuration required
```

**Manual bucket configuration:**
```java
@Configuration
public class CouchbaseConfig {
    
    @Bean
    public Bucket bucket(Cluster cluster) {
        return cluster.bucket("mybucket");
    }
}
```

**Custom environment:**
```java
@Bean
public ClusterEnvironmentBuilderCustomizer customizer() {
    return builder -> builder
        .timeoutConfig(TimeoutConfig.kvTimeout(Duration.ofSeconds(5)));
}
```

### 9.5 R2DBC (Reactive SQL)

**R2DBC is now GA (General Availability)** - production-ready!

**Add dependency:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>

<!-- Driver (example: PostgreSQL) -->
<dependency>
    <groupId>io.r2dbc</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
```

**Configuration:**
```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/mydb
    username: user
    password: password
```

**Repository:**
```java
public interface UserRepository extends ReactiveCrudRepository<User, Long> {
    Flux<User> findByLastName(String lastName);
}
```

**Usage:**
```java
@Service
public class UserService {
    @Autowired
    private UserRepository repository;
    
    public Flux<User> getUsers() {
        return repository.findAll();
    }
}
```

---

## Testing Framework Updates

### 10.1 Test Configuration Improvements

JUnit 5 and testing features remain largely unchanged from 2.2, with minor enhancements:

**Test slices work seamlessly:**
```java
@WebMvc Test(UserController.class)
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private UserService userService;
    
    @Test
    void testGetUser() throws Exception {
        // Testing unchanged
    }
}
```

### 10.2 Embedded Kafka Testing

**Auto-configuration improvements:**

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "test-topic" })
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
class KafkaIntegrationTest {
    // Tests unchanged
}
```

### 10.3 Test Slices

All test slice annotations work as expected:
- `@DataJpaTest`
- `@WebMvcTest`
- `@RestClientTest`
- `@JsonTest`
- `@DataRedisTest`

---

## Configuration Property Changes

### 11.1 HTTP Properties Renamed

> [!WARNING]
> Several `spring.http.*` properties have been moved.

**Deprecated:**
```yaml
spring:
  http:
    encoding:
      charset: UTF-8
      enabled: true
```

**New:**
```yaml
server:
  servlet:
    encoding:
      charset: UTF-8
      enabled: true
```

#### Automated HTTP Encoding Property Migration

> [!NOTE]
> Property path transformation - straightforward nesting migration.

**Detection Strategy**

**Complexity**: LOW - Simple property path replacement

**Pattern Detection**
```yaml
# Detect these deprecated properties:
spring:
  http:
    encoding:
      charset: <VALUE>
      enabled: <VALUE>
      force: <VALUE>
      force-request: <VALUE>
      force-response: <VALUE>
      mapping: <VALUE>
```

**Automated Transformation Strategy**

**Step 1: Transform YAML Files**
```yaml
# Before (Spring Boot 2.2):
spring:
  http:
    encoding:
      charset: UTF-8
      enabled: true
      force: false

# After (Spring Boot 2.3):
server:
  servlet:
    encoding:
      charset: UTF-8
      enabled: true
      force: false
```

**Actions**:
- Remove `spring.http.encoding` node
- Create/update `server.servlet.encoding` node
- Preserve all sub-properties and values
- Maintain comments if using lexical preservation

**Step 2: Transform Properties Files**
```properties
# Before:
spring.http.encoding.charset=UTF-8
spring.http.encoding.enabled=true
spring.http.encoding.force=false

# After:
server.servlet.encoding.charset=UTF-8
server.servlet.encoding.enabled=true
server.servlet.encoding.force=false
```

**Property Mappings**:
```
spring.http.encoding.charset        → server.servlet.encoding.charset
spring.http.encoding.enabled        → server.servlet.encoding.enabled
spring.http.encoding.force          → server.servlet.encoding.force
spring.http.encoding.force-request  → server.servlet.encoding.force-request
spring.http.encoding.force-response → server.servlet.encoding.force-response
spring.http.encoding.mapping        → server.servlet.encoding.mapping
```

**Validation Strategy**:
- Application compiles successfully
- Properties loaded correctly at startup
- Character encoding applied to HTTP requests/responses
- No deprecation warnings in logs

**Risk Level**: NONE - Simple property renaming

**Automation Confidence**: 100% (deterministic transformation)

**Recommendation**: 
- Fully automated
- Apply to all application*.yml and application*.properties files
- Check both src/main/resources and src/test/resources

**Automation Output Example**:
```
[INFO] Property migration started
[INFO] Scanning property files...
[INFO] Found 3 files to migrate

File: application.yml
[MIGRATE] spring.http.encoding.charset → server.servlet.encoding.charset
[MIGRATE] spring.http.encoding.enabled → server.servlet.encoding.enabled

File: application-prod.yml
[MIGRATE] spring.http.encoding.charset → server.servlet.encoding.charset
[MIGRATE] spring.http.encoding.force → server.servlet.encoding.force

[SUCCESS] Migrated 2 property files with 5 total transformations
```

**Additional Property Migrations**:

Other deprecated `spring.http.*` properties:

```yaml
# spring.http.converters.* → spring.mvc.converters.*
# Before:
spring:
  http:
    converters:
      preferred-json-mapper: jackson

# After:
spring:
  mvc:
    converters:
      preferred-json-mapper: jackson
```

Mapping for all HTTP properties:
```
spring.http.encoding.*    → server.servlet.encoding.*
spring.http.converters.*  → spring.mvc.converters.*
spring.http.log-request-details → (use logging.level.web=DEBUG instead)
```

### 11.2 Wildcard Config Locations

> [!TIP]
> Spring Boot 2.3 supports wildcards in configuration file locations.

**Useful for Kubernetes ConfigMaps:**

```yaml
# application.yaml
spring:
  config:
    import: "classpath:/config/*/"  # Load all configs from /config/*
```

**Example directory structure:**
```
/config/
  ├── database/
  │   └── application.yaml
  ├── messaging/
  │   └── application.yaml
  └── security/
      └── application.yaml
```

All files are merged in alphabetical order.

### 11.3 Date-Time Formatting

**New configuration for web applications:**

```yaml
spring:
  mvc:
    format:
      date: yyyy-MM-dd
      time: HH:mm:ss
      date-time: yyyy-MM-dd'T'HH:mm:ss
  
  webflux:
    format:
      date: yyyy-MM-dd
      time: HH:mm:ss
      date-time: yyyy-MM-dd'T'HH:mm:ss
```

---

## Actuator Improvements

### 12.1 Configuration Properties Endpoint

**Enhanced `/actuator/configprops`:**

Now provides end-to-end traceability showing:
- Origin of each property
- Applied value
- Overridden values
- Property source

**Access:**
```bash
curl http://localhost:8080/actuator/configprops
```

### 12.2 Alphabetical Metrics

**Metrics are now ordered alphabetically:**

```bash
curl http://localhost:8080/actuator/metrics
```

```json
{
  "names": [
    "jvm.buffer.count",
    "jvm.buffer.memory.used",
    "jvm.buffer.total.capacity",
    "jvm.classes.loaded",
    ...
  ]
}
```

### 12.3 DataSource Health Indicator

**Query-less health check:**

No longer requires executing SQL queries for health checks:

```yaml
management:
  health:
    db:
      enabled: true
```

### 12.4 Wavefront Support

**Auto-configuration for Wavefront:**

```xml
<dependency>
    <groupId>com.wavefront</groupId>
    <artifactId>wavefront-spring-boot-starter</artifactId>
</dependency>
```

**Configuration:**
```yaml
management:
  metrics:
    export:
      wavefront:
        api-token: ${WAVEFRONT_API_TOKEN}
        uri: https://longboard.wavefront.com
```

---

## Deprecations

### 13.1 Configuration Properties

**Deprecated:**

| Old Property | New Property |
|-------------|-------------|
| `spring.http.encoding.*` | `server.servlet.encoding.*` |
| `spring.http.converters.*` | `spring.mvc.converters.*` |
| `server.error.include-stacktrace=ON_TRACE_PARAM` | `server.error.include-stacktrace=ON_PARAM` |

### 13.2 Classes and Methods

**SpringApplication:**
- `SpringApplication#refresh(ApplicationContext)` → Use `SpringApplication#refresh(ConfigurableApplicationContext)`

**RSocket:**
- `NettyRSocketServerFactory.addSocketFactoryProcessors()` → Use `NettyRSocketServerFactory.addRSocketServerCustomizers()`

### 13.3 Removed 2.2 Deprecations

All classes/methods deprecated in Spring Boot 2.2 have been removed in 2.3.

---

## Migration Checklist

### Pre-Migration

- [ ] Review current Spring Boot version
- [ ] Run all tests to establish baseline
- [ ] Document custom configurations
- [ ] Back up `pom.xml` / `build.gradle`
- [ ] Review Spring Boot 2.3 release notes

### Critical Changes

- [ ] **Add `spring-boot-starter-validation`** if using validation
- [ ] Update Cassandra configuration for driver v4 (if using Cassandra)
- [ ] Migrate from Elasticsearch Transport to RestHighLevelClient (if using Elasticsearch)
- [ ] Migrate from Spring Cloud Connectors to Java CFEnv (if using)
- [ ] Update Gradle to 6.3+ (if using Gradle 5.x)

### Dependency Migration

- [ ] Update Spring Boot parent to 2.3.12.RELEASE
- [ ] Verify all dependencies resolve correctly
- [ ] Check for dependency conflicts
- [ ] Test application starts without errors

### Validation Migration

- [ ] Search codebase for `@Valid`, `@Validated`, validation annotations
- [ ] Add `spring-boot-starter-validation` if validation is used
- [ ] Test all validation constraints work correctly
- [ ] Verify error responses for invalid requests

### Cloud Native Adoption (Optional)

- [ ] Enable layered JARs in build configuration
- [ ] Test building with Cloud Native Buildpacks
- [ ] Optimize Dockerfile using layer extraction
- [ ] Configure buildpack environment variables

### Graceful Shutdown

- [ ] Enable graceful shutdown in configuration
- [ ] Set appropriate timeout value
- [ ] Test shutdown behavior under load
- [ ] Update Kubernetes deployment for graceful termination
- [ ] Verify`@PreDestroy` hooks are called

### Health Probes

- [ ] Enable liveness/readiness probes
- [ ] Configure custom health indicators if needed
- [ ] Update Kubernetes deployment with probe configuration
- [ ] Test probe endpoints return correct status
- [ ] Verify application starts/stops correctly

### Kafka Migration

- [ ] Test Kafka consumers with new default error handler
- [ ] Configure custom error handler if needed
- [ ] Verify producer/consumer metrics are available
- [ ] Test static group membership if using
- [ ] Update topic creation to use TopicBuilder

### Spring Data Migration

- [ ] **Cassandra**: Update to driver v4 customizers
- [ ] **Cassandra**: Set `local-datacenter` property
- [ ] **Elasticsearch**: Replace transport client with REST client
- [ ] **Couchbase**: Update to SDK v3 configuration
- [ ] Test all repository operations

### Configuration Updates

- [ ] Rename deprecated `spring.http.*` properties
- [ ] Configure wildcard config locations if beneficial
- [ ] Update date-time formatting properties if needed
- [ ] Test configuration loading from all sources

### Testing

- [ ] Run all unit tests
- [ ] Run all integration tests
- [ ] Test validation scenarios
- [ ] Test Kafka consumers/producers
- [ ] Test database operations
- [ ] Test graceful shutdown
- [ ] Test health endpoints

### Post-Migration

- [ ] Verify application starts without warnings
- [ ] Check for deprecation warnings in logs
- [ ] Monitor startup time and memory usage
- [ ] Test in staging environment
- [ ] Load test critical paths
- [ ] Update documentation

### Kubernetes Deployment

- [ ] Configure liveness/readiness probes in deployment
- [ ] Set appropriate `terminationGracePeriodSeconds`
- [ ] Update preStop hooks if needed
- [ ] Test rolling updates
- [ ] Monitor pod lifecycle events

---

## Troubleshooting

### Issue: NoProviderFoundException

**Symptom:**
```
javax.validation.NoProviderFoundException: Unable to create a Configuration, 
because no Bean Validation provider could be found.
```

**Solution:**
Add `spring-boot-starter-validation` dependency.

### Issue: Cassandra Connection Fails

**Symptom:**
```
IllegalStateException: You must provide a local datacenter
```

**Solution:**
```yaml
spring:
  data:
    cassandra:
      local-datacenter: datacenter1
```

### Issue: Elasticsearch Connection Fails

**Symptom:**
```
NoClassDefFoundError: org/elasticsearch/client/transport/TransportClient
```

**Solution:**
Replace with `RestHighLevelClient`. Remove transport client dependencies.

### Issue: H2 Console Not Working

**Symptom:**
H2 console can't connect to database.

**Solution:**
```yaml
spring:
  datasource:
    generate-unique-name: false
```

### Issue: Gradle Build Fails

**Symptom:**
```
Gradle version X is not supported
```

**Solution:**
Upgrade to Gradle 6.3+:
```bash
./gradlew wrapper --gradle-version 6.9.4
```

### Issue: Graceful Shutdown Not Working

**Symptom:**
Application shuts down immediately.

**Solution:**
Ensure configuration is correct:
```yaml
server:
  shutdown: graceful  # Not "immediate"
```

### Issue: Health Probes Always Down

**Symptom:**
`/actuator/health/liveness` returns DOWN.

**Solution:**
Check health indicators:
```yaml
management:
  endpoint:
    health:
      show-details: always  # Debug mode
```

Review logs for failed health checks.

---

## Automation Tool Development

> [!TIP]
> This section provides guidance for developing an automated Spring Boot 2.2 to 2.3 migration tool using the Antikythera framework.

### Tool Architecture

Based on the established `JUnit425Migrator` pattern:

```
SpringBoot22to23Migrator (Main Orchestrator)
├── PomDependencyMigrator
│   ├── Update Spring Boot parent version (2.2.x → 2.3.12)
│   ├── Add validation starter if validation usage detected
│   ├── Update Gradle minimum version check (5.6.x → 6.3+)
│   └── Validate dependency compatibility (Cassandra, Elasticsearch)
├── ValidationStarterDetector
│   ├── JavaCodeScanner
│   │   ├── Scan for @Valid annotations
│   │   ├── Scan for @Validated annotations
│   │   ├── Scan for javax.validation.* imports
│   │   └── Scan for constraint annotations (@NotNull, @Size, etc.)
│   └── StarterInjector (add spring-boot-starter-validation)
├── PropertyFileMigrator
│   ├── YamlPropertyTransformer
│   │   ├── Migrate spring.http.encoding.* → server.servlet.encoding.*
│   │   ├── Transform server.error.include-stacktrace=ON_TRACE_PARAM → ON_PARAM
│   │   ├── Add spring.datasource.generate-unique-name=false (if H2 detected)
│   │   └── (Optional) Add graceful shutdown configuration
│   └── PropertiesFileTransformer (same transformations)
├── CassandraCodeMigrator (Complex - requires careful handling)
│   ├── DetectCassandraUsage
│   ├── ConfigurationMigrator
│   │   ├── Replace ClusterBuilderCustomizer → DriverConfigLoaderBuilderCustomizer
│   │   ├── Add CqlSessionBuilderCustomizer patterns
│   │   └── Inject local-datacenter property requirement
│   └── ManualReviewFlagging (for complex customizations)
├── ElasticsearchCodeMigrator (Complex - requires manual review)
│   ├── TransportClientDetector
│   ├── JestClientDetector
│   ├── RestHighLevelClientGenerator (stub generation)
│   └── ManualMigrationGuide (generate migration instructions)
├── CouchbaseCodeMigrator (Complex - manual review recommended)
│   ├── DetectCouchbaseUsage
│   ├── PropertyMigrator (bootstrap-hosts → connection-string)
│   ├── BucketConfigurationDetector
│   └── ManualReviewFlagging
├── CloudNativeConfigGenerator (Optional enhancement)
│   ├── LayeredJarEnabler (pom.xml/build.gradle)
│   ├── BuildpacksConfigGenerator
│   └── DockerfileOptimizer (generate layered Dockerfile)
├── GracefulShutdownConfigGenerator (Optional enhancement)
│   ├── DetectWebServerUsage
│   └── ConfigInjector (server.shutdown=graceful)
├── HealthProbeConfigGenerator (Optional - Kubernetes-specific)
│   ├── DetectKubernetesDeployment
│   ├── ActuatorConfigInjector
│   └── DeploymentYamlGenerator
└── MigrationValidator
    ├── CompilationValidator (mvn compile)
    ├── ValidationStarterValidator (ensure added if needed)
    ├── CassandraConfigValidator (check local-datacenter property)
    ├── DependencyTreeValidator (check for conflicts)
    ├── PropertyValidator (detect remaining deprecated properties)
    └── ReportGenerator (markdown/HTML migration report with manual review items)
```

### Implementation Phases

#### Phase 1: Foundation (P0 - Critical)

**Components**:
- PomDependencyMigrator (basic version update)
- ValidationStarterDetector + StarterInjector
- PropertyFileMigrator (YAML + Properties)
- MigrationValidator (compilation + validation checks)

**Rationale**: These are the most common breaking changes that affect the majority of applications.

**Success Criteria**:
- Validation starter added if validation annotations detected
- Spring Boot version updated
- All deprecated properties migrated
- Application compiles successfully

#### Phase 2: Data Layer Migration (P1 - High Priority if applicable)

**Components**:
- CassandraCodeMigrator (detection + basic migration)
- ElasticsearchCodeMigrator (detection + flagging)
- CouchbaseCodeMigrator (detection + flagging)

**Rationale**: These require complex code changes and configuration updates. Automated detection with manual review is the safest approach.

**Success Criteria**:
- Cassandra/Elasticsearch/Couchbase usage detected
- Configuration patterns identified
- Manual review guide generated for each technology
- Migration stubs generated where possible

#### Phase 3: Cloud Native Enhancements (P2 - Optional)

**Components**:
- CloudNativeConfigGenerator
- GracefulShutdownConfigGenerator
- HealthProbeConfigGenerator

**Rationale**: These are new features in 2.3 that improve production deployments but aren't required for migration.

**Success Criteria**:
- Layered JAR configuration added to build files
- Graceful shutdown configuration recommended
- Health probe configuration generated for Kubernetes projects

### Configuration File (generator.yml)

```yaml
spring-boot-migration:
  enabled: true
  source-version: "2.2"
  target-version: "2.3"
  
  pom:
    enabled: true
    spring-boot-version: "2.3.12.RELEASE"
    auto-add-validation-starter: true
    check-gradle-version: true
    minimum-gradle-version: "6.3"
    
  validation-detection:
    enabled: true
    scan-patterns:
      - "@Valid"
      - "@Validated"
      - "javax.validation"
      - "@NotNull"
      - "@NotEmpty"
      - "@Size"
      - "@Email"
      - "@Pattern"
    generate-report: true
    
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
    h2-console:
      auto-detect: true
      disable-unique-name: true  # Set generate-unique-name=false if H2 detected
      
  data-layer:
    cassandra:
      enabled: true
      detect-usage: true
      auto-migrate: false  # Too complex - generate migration guide instead
      require-local-datacenter: true
      flag-customizers: true
      
    elasticsearch:
      enabled: true
      detect-transport-client: true
      detect-jest-client: true
      auto-migrate: false  # Generate REST client migration guide
      generate-rest-client-template: true
      
    couchbase:
      enabled: true
      detect-usage: true
      auto-migrate-properties: true
      flag-bucket-config: true
      
  cloud-native:
    layered-jars:
      enabled: false  # Optional feature
      auto-configure: true
      
    buildpacks:
      enabled: false  # Optional feature
      generate-config: true
      
    graceful-shutdown:
      enabled: false  # Optional feature
      auto-configure: true
      default-timeout: "30s"
      
    health-probes:
      enabled: false  # Optional feature
      detect-kubernetes: true
      generate-deployment-yaml: true
      
  validation:
    compile-after-migration: true
    run-unit-tests: false  # Optional
    check-dependency-tree: true
    validate-cassandra-config: true
    generate-report: true
    
  options:
    dry-run: false
    verbose-logging: true
    create-backups: true
    fail-on-error: false
    generate-manual-review-guide: true
```

### Usage Example

```java
// Command-line invocation
java -jar antikythera.jar --spring-boot-migrate \
  --source-version=2.2 \
  --target-version=2.3 \
  --project-path=/path/to/project \
  --dry-run

// Programmatic usage
SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(
    projectRoot,
    dryRun,
    migrationConfig
);

MigrationResult result = migrator.migrate();

// Print report with manual review items
System.out.println(result.generateReport());
System.out.println("\nManual Review Guide:");
System.out.println(result.generateManualReviewGuide());
```

### Critical Migration Components

#### 1. Validation Starter Detector

```java
public class ValidationStarterDetector {
    private static final List<String> VALIDATION_PATTERNS = Arrays.asList(
        "@Valid", "@Validated",
        "javax.validation", 
        "@NotNull", "@NotEmpty", "@NotBlank",
        "@Size", "@Min", "@Max",
        "@Email", "@Pattern",
        "@Positive", "@Negative",
        "@Past", "@Future"
    );
    
    public boolean detectValidationUsage(Path projectRoot) {
        AtomicBoolean usesValidation = new AtomicBoolean(false);
        
        try {
            Files.walk(projectRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        for (String pattern : VALIDATION_PATTERNS) {
                            if (content.contains(pattern)) {
                                usesValidation.set(true);
                                logger.info("Validation usage detected in: {} (pattern: {})", 
                                    file, pattern);
                                return;
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("Error reading file: {}", file, e);
                    }
                });
        } catch (IOException e) {
            logger.error("Error scanning for validation usage", e);
        }
        
        return usesValidation.get();
    }
    
    public boolean addValidationStarter(Path pomFile) {
        // Add spring-boot-starter-validation dependency
        // Implementation similar to PomDependencyMigrator
        return true;
    }
}
```

#### 2. Cassandra Migration Detector

```java
public class CassandraCodeMigrator {
    
    public CassandraMigrationReport analyze(CompilationUnit cu) {
        CassandraMigrationReport report = new CassandraMigrationReport();
        
        // Detect ClusterBuilderCustomizer usage
        cu.findAll(ClassOrInterfaceType.class).forEach(type -> {
            if (type.getNameAsString().equals("ClusterBuilderCustomizer")) {
                report.addDeprecatedPattern(
                    "ClusterBuilderCustomizer",
                    type.getRange().orElse(null),
                    "Replace with DriverConfigLoaderBuilderCustomizer and CqlSessionBuilderCustomizer"
                );
            }
        });
        
        // Check for local-datacenter property
        if (!hasLocalDatacenterProperty()) {
            report.addRequiredConfig(
                "spring.data.cassandra.local-datacenter",
                "REQUIRED: Set local datacenter name (e.g., 'datacenter1')"
            );
        }
        
        return report;
    }
    
    public String generateMigrationGuide(CassandraMigrationReport report) {
        StringBuilder guide = new StringBuilder();
        guide.append("# Cassandra Driver v4 Migration Guide\n\n");
        
        if (!report.getDeprecatedPatterns().isEmpty()) {
            guide.append("## Deprecated Patterns to Replace\n\n");
            for (DeprecatedPattern pattern : report.getDeprecatedPatterns()) {
                guide.append(String.format("- **%s** at %s\n", 
                    pattern.getName(), pattern.getLocation()));
                guide.append(String.format("  - Migration: %s\n\n", 
                    pattern.getMigrationInstructions()));
            }
        }
        
        if (!report.getRequiredConfigs().isEmpty()) {
            guide.append("## Required Configuration Changes\n\n");
            for (RequiredConfig config : report.getRequiredConfigs()) {
                guide.append(String.format("- `%s`: %s\n", 
                    config.getProperty(), config.getDescription()));
            }
        }
        
        return guide.toString();
    }
}
```

### Migration Report Format

```markdown
# Spring Boot 2.2 → 2.3 Migration Report

**Project**: my-spring-application
**Date**: 2025-12-16 14:30:00
**Mode**: Live Run

## Summary

- ✅ POM Migration: SUCCESS
- ✅ Validation Starter: ADDED (usage detected)
- ✅ Property Migration: SUCCESS
- ⚠️  Cassandra Migration: MANUAL REVIEW REQUIRED
- ⚠️  Elasticsearch Migration: MANUAL REVIEW REQUIRED
- ✅ Validation: PASSED

## Critical Changes

### Validation Starter Added

**Reason**: Detected validation annotations in 47 files
**Action**: Added `spring-boot-starter-validation` to pom.xml

**Files with validation**:
- `UserController.java`: @Valid annotations
- `CreateUserRequest.java`: @NotNull, @Size, @Email annotations
- `OrderService.java`: @Validated class annotation
- ... (44 more files)

## Details

### POM Changes (2 changes)
- ✅ Updated Spring Boot parent: 2.2.6.RELEASE → 2.3.12.RELEASE
- ✅ Added dependency: spring-boot-starter-validation

### Property File Changes (4 changes)
- ✅ application.yml: spring.http.encoding.charset → server.servlet.encoding.charset
- ✅ application.yml: spring.http.encoding.enabled → server.servlet.encoding.enabled
- ✅ application.yml: server.error.include-stacktrace: ON_TRACE_PARAM → ON_PARAM
- ✅ application-test.yml: spring.datasource.generate-unique-name=false (H2 detected)

### Data Layer Changes

#### Cassandra Migration (⚠️ MANUAL REVIEW REQUIRED)

**Detected Usage**: Yes (CassandraConfig.java, UserRepository.java)

**Required Changes**:in
1. **Configuration Properties**
   - ❌ MISSING: `spring.data.cassandra.local-datacenter` (REQUIRED)
   - Recommended: Add `local-datacenter: datacenter1` to application.yml

2. **Code Changes Required**
   - File: `CassandraConfig.java:34`
     - Pattern: `ClusterBuilderCustomizer` (DEPRECATED)
     - Migration: Replace with `DriverConfigLoaderBuilderCustomizer`
     - See: Manual Migration Guide Section 3.1

**Generated Migration Guide**: `cassandra-migration-guide.md` (see below)

#### Elasticsearch Migration (⚠️ MANUAL REVIEW REQUIRED)

**Detected Usage**: Yes (ElasticsearchConfig.java, SearchService.java)

**Issues Found**:
1. **Transport Client** detected in `ElasticsearchConfig.java:45`
   - ❌ TransportClient is REMOVED in Spring Boot 2.3
   - Migration: Replace with RestHighLevelClient
   
2. **Generated REST Client Template**: `elasticsearch-rest-client-template.java`

**Manual Migration Required**: See `elasticsearch-migration-guide.md`

### Cloud Native Features (Optional - Not Applied)

The following features are available in Spring Boot 2.3 but were not automatically configured:

- ⏭️  **Layered JARs**: Enable with `--enable-cloud-native`
- ⏭️  **Graceful Shutdown**: Enable with `--enable-graceful-shutdown`
- ⏭️  **Health Probes**: Enable with `--enable-health-probes`

To add these features, run:
```bash
java -jar antikythera.jar --spring-boot-migrate \
  --enable-cloud-native \
  --enable-graceful-shutdown \
  --enable-health-probes
```

### Validation Results

- ✅ Compilation: SUCCESS (mvn compile)
- ✅ Dependency Tree: No conflicts detected
- ⚠️  Cassandra Config: Missing local-datacenter property
- ℹ️  Tests: Not executed (use --run-tests flag)

## Manual Review Guide

### 1. Cassandra Driver v4 Migration

See generated file: [cassandra-migration-guide.md](cassandra-migration-guide.md)

**Key Actions**:
1. Add `spring.data.cassandra.local-datacenter=datacenter1` to application.yml
2. Replace `ClusterBuilderCustomizer` in CassandraConfig.java:
   ```java
   // OLD (Spring Boot 2.2)
   @Bean
   public ClusterBuilderCustomizer clusterBuilderCustomizer() {
       return builder -> builder.withPort(9042);
   }
   
   // NEW (Spring Boot 2.3)
   @Bean
   public DriverConfigLoaderBuilderCustomizer driverConfigCustomizer() {
       return builder -> builder
           .withString(DefaultDriverOption.REQUEST_TIMEOUT, "5000ms");
   }
   
   @Bean
   public CqlSessionBuilderCustomizer sessionBuilderCustomizer() {
       return builder -> builder.withLocalDatacenter("datacenter1");
   }
   ```

3. Test Cassandra connection after migration

### 2. Elasticsearch REST Client Migration

See generated file: [elasticsearch-migration-guide.md](elasticsearch-migration-guide.md)

**Key Actions**:
1. Remove transport client dependency from pom.xml
2. Replace ElasticsearchConfig.java:
   ```java
   // OLD (Spring Boot 2.2)
   @Bean
   public TransportClient transportClient() {
       // NO LONGER SUPPORTED
   }
   
   // NEW (Spring Boot 2.3)
   @Bean
   public RestHighLevelClient elasticsearchClient() {
       ClientConfiguration clientConfig = ClientConfiguration.builder()
           .connectedTo("localhost:9200")
           .build();
       return RestClients.create(clientConfig).rest();
   }
   ```

3. Update all Elasticsearch queries to use REST client API

### 3. Testing Checklist

- [ ] Run unit tests: `mvn test`
- [ ] Verify validation works (test @Valid annotated endpoints)
- [ ] Test Cassandra connectivity (if using)
- [ ] Test Elasticsearch queries (if using)
- [ ] Run integration tests: `mvn verify`
- [ ] Deploy to test environment
- [ ] Verify application starts without errors

## Next Steps

1. ✅ Review and apply Cassandra migration guide
2. ✅ Review and apply Elasticsearch migration guide
3. ✅ Add missing Cassandra local-datacenter property
4. ✅ Run full test suite
5. ⏭️  Consider enabling cloud-native features (layered JARs, graceful shutdown)
6. ✅ Deploy to staging environment
7. ✅ Remove backup files after successful validation

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
public void testValidationStarterDetection() {
    // Given: Project with @Valid annotations
    Path projectRoot = createTestProject(
        "UserController.java", 
        "public void createUser(@Valid @RequestBody User user) {}"
    );
    
    // When: Detect validation usage
    ValidationStarterDetector detector = new ValidationStarterDetector();
    boolean usesValidation = detector.detectValidationUsage(projectRoot);
    
    // Then: Usage detected
    assertTrue(usesValidation);
}

@Test
public void testValidationStarterAdded() {
    // Given: POM without validation starter
    Path pomFile = createTestPom("2.2.6.RELEASE");
    
    // When: Add validation starter
    ValidationStarterDetector detector = new ValidationStarterDetector();
    detector.addValidationStarter(pomFile);
    
    // Then: Starter added
    assertTrue(pomContains(pomFile, "spring-boot-starter-validation"));
}

@Test
public void testCassandraDetection() {
    // Given: Code using Cassandra driver v3 patterns
    CompilationUnit cu = parseTestFile("CassandraConfig.java");
    
    // When: Analyze
    CassandraCodeMigrator migrator = new CassandraCodeMigrator();
    CassandraMigrationReport report = migrator.analyze(cu);
    
    // Then: Deprecated patterns detected
    assertTrue(report.hasDeprecatedPatterns());
    assertTrue(report.requiresManualReview());
}

@Test
public void testPropertyMigration() {
    // Given: application.yml with old properties
    String yaml = "spring:\n  http:\n    encoding:\n      charset: UTF-8\n";
    
    // When: Transform
    PropertyFileMigrator migrator = new PropertyFileMigrator();
    String result = migrator.transformYaml(yaml);
    
    // Then: Property path updated
    assertTrue(result.contains("server:\n  servlet:\n    encoding:"));
}

@Test
public void testH2UniqueNameConfiguration() {
    // Given: Project with H2 dependency and console enabled
    Path pomFile = createTestPomWithH2();
    Path appYml = createTestYaml("spring:\n  h2:\n    console:\n      enabled: true\n");
    
    // When: Migrate
    PropertyFileMigrator migrator = new PropertyFileMigrator();
    migrator.migrateH2Configuration(appYml);
    
    // Then: Unique name disabled
    String content = Files.readString(appYml);
    assertTrue(content.contains("generate-unique-name: false"));
}
```

### Error Handling

```java
public MigrationResult migrate() {
    MigrationResult result = new MigrationResult();
    
    try {
        // Phase 1: POM & Validation Starter (critical)
        result.addPhase(migratePom());
        result.addPhase(addValidationStarterIfNeeded());
        
        if (result.hasErrors()) {
            result.addError("Critical POM migration failed");
            return result;  // Stop if critical phase fails
        }
        
        // Phase 2: Properties (critical)
        result.addPhase(migrateProperties());
        
        // Phase 3: Data Layer (best effort - flag for manual review)
        try {
            CassandraMigrationReport cassandraReport = analyzeCassandra();
            if (cassandraReport.requiresManualReview()) {
                result.addManualReviewItem("Cassandra", cassandraReport);
                generateCassandraMigrationGuide(cassandraReport);
            }
            
            ElasticsearchMigrationReport esReport = analyzeElasticsearch();
            if (esReport.requiresManualReview()) {
                result.addManualReviewItem("Elasticsearch", esReport);
                generateElasticsearchMigrationGuide(esReport);
            }
        } catch (Exception e) {
            result.addWarning("Data layer analysis failed: " + e.getMessage());
            result.addWarning("Manual review of Cassandra/Elasticsearch configuration recommended");
        }
        
        // Phase 4: Optional Features (cloud-native, graceful shutdown)
        if (options.enableCloudNative) {
            result.addPhase(configureCloudNative());
        }
        
        // Phase 5: Validation
        if (!options.dryRun) {
            result.addPhase(validate());
            
            // Special validation for Cassandra
            if (usesCassandra() && !hasLocalDatacenterProperty()) {
                result.addError("Cassandra local-datacenter property is REQUIRED");
                result.addAction("Add spring.data.cassandra.local-datacenter to application.yml");
            }
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
- **AntikytheraRunTime**: For CompilationUnit management

**Example**:
```java
public class SpringBoot22to23Migrator {
    private final JUnit425Migrator junitMigrator;
    private final PropertyFileManager propertyManager;
    private final ValidationStarterDetector validationDetector;
    private final CassandraCodeMigrator cassandraMigrator;
    
    public MigrationResult migrate() {
        MigrationResult result = new MigrationResult();
        
        // 1. Reuse JUnit migrator if needed
        if (hasJUnit4Tests()) {
            result.add(junitMigrator.migrateAll(compilationUnit));
        }
        
        // 2. Critical: Add validation starter
        if (validationDetector.detectValidationUsage(projectRoot)) {
            result.add(validationDetector.addValidationStarter(pomFile));
        }
        
        // 3. Spring Boot 2.3 specific migrations
        result.add(migrateProperties());
        
        // 4. Data layer (flag for manual review)
        if (usesCassandra()) {
            CassandraMigrationReport report = cassandraMigrator.analyze();
            result.addManualReviewSection(report);
        }
        
        return result;
    }
}
```

### Priority Matrix

| Component | Priority | Complexity | Risk | Automation Level |
|-----------|----------|------------|------|------------------|
| Validation Starter | P0 - Critical | Low | Low | Full |
| POM Version Update | P0 - Critical | Low | Low | Full |
| Property Migration | P0 - Critical | Low | Low | Full |
| H2 Configuration | P1 - High | Low | Low | Full |
| Cassandra Detection | P1 - High | High | High | Detection + Guide |
| Elasticsearch Detection | P1 - High | High | High | Detection + Guide |
| Couchbase Detection | P2 - Medium | High | High | Detection + Guide |
| Cloud Native Features | P3 - Optional | Medium | Low | Optional Config |
| Graceful Shutdown | P3 - Optional | Low | Low | Optional Config |
| Health Probes | P3 - Optional | Low | Low | Optional Config |

### Recommended Implementation Order

1. **Sprint 1**: Core automation (POM, validation starter, properties)
2. **Sprint 2**: Data layer detection and guide generation
3. **Sprint 3**: Optional features (cloud-native, graceful shutdown)
4. **Sprint 4**: Testing, documentation, integration

---

## Spring Cloud Version Compatibility

> [!CAUTION]
> Spring Cloud Greenwich is **incompatible** with Spring Boot 2.3. You MUST upgrade to Hoxton.SR8+ or 2020.0.x.

### Version Compatibility Matrix

| Spring Boot Version | Compatible Spring Cloud Versions | Status |
|---------------------|--------------------------------|--------|
| 2.2.x | Hoxton.SR1 - Hoxton.SR12 | Maintenance |
| 2.3.x | **Hoxton.SR8+** or **2020.0.x (Ilford)** | Recommended |
| 2.4.x | 2020.0.x (Ilford) | Active |

### Critical: Upgrade from Hoxton (Early Versions)

If migrating from Spring Boot 2.2 with early Hoxton releases:

**Minimum required**:
```xml
<properties>
    <!-- Insufficient (Spring Boot 2.2) -->
    <spring-cloud.version>Hoxton.SR1</spring-cloud.version>
    
    <!-- Required for Spring Boot 2.3 -->
    <spring-cloud.version>Hoxton.SR12</spring-cloud.version>
    
    <!-- Or use newer release train -->
    <spring-cloud.version>2020.0.6</spring-cloud.version>
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

### Spring Cloud Component Updates

#### Spring Cloud Contract

**Hoxton.SR8+ improvements**:
- Enhanced Kotlin DSL
- Better WireMock integration
- Improved stub runner performance

**Configuration remains compatible**:
```java
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.example:service:+:stubs:8080",
    stubsMode = StubRunnerProperties.StubsMode.REMOTE,
    repositoryRoot = "https://repo.example.com"
)
class ContractTest {
    // Tests work with Spring Boot 2.3
}
```

#### Spring Cloud Config

**Spring Boot 2.3 compatible versions**:
- Spring Cloud Config 2.2.x (Hoxton)
- Spring Cloud Config 3.0.x (2020.0.x)

**No configuration changes required**:
```yaml
spring:
  cloud:
    config:
      uri: ${CONFIG_SERVER_URL:http://localhost:8888}
      fail-fast: true
      retry:
        max-attempts: 6
```

### Spring Cloud LoadBalancer

> [!NOTE]
> Ribbon is in maintenance mode. Spring Cloud LoadBalancer is the recommended replacement.

**Migration from Ribbon to Spring Cloud LoadBalancer**:

```xml
<!-- Remove Ribbon (deprecated) -->
<!--<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-ribbon</artifactId>
</dependency>-->

<!-- Add Spring Cloud LoadBalancer -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

**Usage (same interface)**:
```java
@Configuration
@LoadBalancerClient(name = "user-service", configuration = CustomLoadBalancerConfig.class)
public class LoadBalancerConfig {
    // Configuration
}
```

### Automation Detection

```yaml
# generator.yml
spring-cloud:
  detect-incompatible:
    Greenwich: ["2.3.x"]  # Greenwich incompatible with Spring Boot 2.3
    Hoxton:
      minimum-version: "SR8"
  auto-suggest-version:
    spring-boot-2.3: "Hoxton.SR12"
  flag-ribbon-usage: true  # Recommend migration to LoadBalancer
```

---

## Custom Internal Library Compatibility

> [!IMPORTANT]
> Custom internal/proprietary libraries may have hard dependencies on specific Spring Boot versions.

### Audit Checklist for Internal Libraries

When upgrading Spring Boot, **all internal/proprietary dependencies** must be validated for compatibility.

#### Step 1: Identify Custom Libraries

**Pattern detection**:
```xml
<!-- Typical internal library patterns -->
<dependency>
    <groupId>com.company.*</groupId>
    <artifactId>*</artifactId>
    <version>*-sb2.1</version>  <!-- Spring Boot version in artifact version -->
</dependency>
```

**Common naming patterns**:
- Version suffix: `-sb2.1`, `-boot2.2`, `-springboot-2.x`
- Group ID patterns: `com.company.*`, `org.internal.*`

#### Step 2: Compatibility Assessment

For each internal library, verify:

1. **Spring Boot version compatibility**
   ```bash
   # Check library's Maven/Gradle file for Spring Boot version
   mvn dependency:tree -Dincludes=com.company:internal-lib
   ```

2. **Transitive dependencies**
   ```bash
   # Look for version conflicts
   mvn dependency:tree | grep -i spring-boot
   ```

3. **Hard-coded Spring versions**
   ```java
   // Search library source for hard-coded versions
   grep -r "2.2.RELEASE" internal-lib/src
   ```

#### Step 3: Required Actions

**Scenario A: Version-Specific Library**
```xml
<!-- Old -->
<dependency>
    <groupId>com.company</groupId>
    <artifactId>internal-auth</artifactId>
    <version>1.0.0-sb2.2</version>
</dependency>

<!-- Action: Request/build Spring Boot 2.3 compatible version -->
<dependency>
    <groupId>com.company</groupId>
    <artifactId>internal-auth</artifactId>
    <version>1.0.0-sb2.3</version>  <!-- New version needed -->
</dependency>
```

**Scenario B: Library with Transitive Dependencies**
```xml
<!-- Internal library pulls in Spring Boot 2.2 -->
<dependency>
    <groupId>com.company</groupId>
    <artifactId>messaging-lib</artifactId>
    <version>2.0.0</version>
    <!-- Transitively depends on spring-boot-starter:2.2.x -->
</dependency>

<!-- Solution: Exclude and override -->
<dependency>
    <groupId>com.company</groupId>
    <artifactId>messaging-lib</artifactId>
    <version>2.0.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

#### Step 4: Testing Strategy

**Integration testing with internal libraries**:

```java
@SpringBootTest
@ActiveProfiles("test")
class InternalLibraryCompatibilityTest {
    
    @Autowired
    private InternalLibraryService internalService;
    
    @Test
    void testLibraryIntegrationWithSpringBoot23() {
        // Verify library works with Spring Boot 2.3
        assertNotNull(internalService);
        
        // Test critical library functionality
        var result = internalService.performCriticalOperation();
        assertNotNull(result);
    }
    
    @Test
    void testLibraryAutoConfiguration() {
        // Verify library's auto-configuration loaded
        assertThat(applicationContext)
            .hasSingleBean(InternalLibraryAutoConfiguration.class);
    }
}
```

### Internal Library Migration Checklist

- [ ] Identify all internal/proprietary dependencies
- [ ] Check each library's Spring Boot version compatibility
- [ ] Run `mvn dependency:tree` to identify conflicts
- [ ] Contact library maintainers for Spring Boot 2.3 versions
- [ ] Rebuild internal libraries against Spring Boot 2.3 if needed
- [ ] Update version naming (e.g., `-sb2.2` → `-sb2.3`)
- [ ] Test all internal library integrations
- [ ] Update internal documentation with compatibility matrix

### Automation Recommendation

```yaml
# generator.yml
custom-libraries:
  detect-patterns:
    - "com.company:*"
    - "org.internal:*"
  scan-for-spring-boot-version: true
  detect-version-suffix-pattern: "-sb\\d+\\.\\d+"
  flag-if-incompatible: true
  generate-compatibility-report: true
```

**Detection and reporting**:
```java
// Pseudo-code for automation tool
for (Dependency dep : allDependencies) {
    if (isInternalLibrary(dep)) {
        String springBootVersion = detectSpringBootVersion(dep);
        
        if (!isCompatible(springBootVersion, "2.3.x")) {
            report.addWarning(String.format(
                "Internal library %s:%s may be incompatible with Spring Boot 2.3",
                dep.getGroupId(), dep.getArtifactId()
            ));
            report.addAction(String.format(
                "Verify compatibility or request updated version from %s team",
                dep.getMaintainerTeam()
            ));
        }
    }
}
```

---

## Third-Party Library Edge Cases

### Distributed Locking: ShedLock

Spring Boot 2.3 is compatible with ShedLock 2.x, 3.x, and 4.x, but **version consistency is critical**.

**Recommended versions for Spring Boot 2.3**:
```xml
<properties>
    <shedlock.version>4.44.0</shedlock.version>  <!-- Latest stable -->
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

**Configuration (unchanged from Spring Boot 2.2)**:
```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
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

### API Documentation: Springfox vs SpringDoc

If using **Springfox** (older versions), consider migrating to **SpringDoc OpenAPI**:

**SpringDoc for Spring Boot 2.3**:
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.6.14</version>
</dependency>
```

**Zero configuration required**:
```java
// No @EnableSwagger2 needed!
// Just add dependency and access /swagger-ui.html
```

**Optional customization**:
```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("My API")
                .version("2.3.0")
                .description("API running on Spring Boot 2.3"));
    }
}
```

---

## References


### Official Documentation

- [Spring Boot 2.3 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.3-Release-Notes)
- [Spring Framework 5.2 Documentation](https://docs.spring.io/spring-framework/docs/5.2.x/spring-framework-reference/)
- [Spring Data Neumann Release](https://spring.io/blog/2020/05/12/spring-data-neumann-ga-released)
- [Spring Kafka 2.5 Documentation](https://docs.spring.io/spring-kafka/docs/2.5.x/reference/html/)
- [Cloud Native Buildpacks](https://buildpacks.io/)
- [R2DBC Documentation](https://r2dbc.io/)

### Related Antikythera Documentation

- [Spring Boot 2.1 to 2.2 Migration](spring_boot_2.1_to_2.2_migration.md)
- [JUnit 4 to 5 Migration Specification](junit4to5_migration_spec.md)
- [SDET Reference Guide](sdet_reference_guide.md)
- [Test Fixer](test_fixer.md)

### Migration Tools

- **Spring Boot Properties Migrator**: Detects deprecated properties
- **OpenRewrite**: Automated migration recipes
- **Antikythera TestFixer**: Test framework migration

### Community Resources

- [Spring Boot GitHub Issues](https://github.com/spring-projects/spring-boot/issues)
- [Stack Overflow - spring-boot-2.3](https://stackoverflow.com/questions/tagged/spring-boot-2.3)
- [Spring Community Forums](https://spring.io/community)

---

## Summary

Migrating from Spring Boot 2.2 to 2.3 involves:

1. **Critical Changes**: Add validation starter, update Cassandra/Elasticsearch clients
2. **Cloud Native**: Adopt buildpacks and layered JARs for optimized containers
3. **Graceful Shutdown**: Enable for production reliability
4. **Health Probes**: Configure for Kubernetes deployments
5. **Kafka**: Update error handlers and leverage new features
6. **Spring Data**: Major driver upgrades (Cassandra v4, Elasticsearch 7.6+)

**Key Takeaways:**
- Validation starter is no longer automatic - must add explicitly
- Cloud-native features make containerization effortless
- Graceful shutdown and health probes are essential for Kubernetes
- Cassandra and Elasticsearch require significant migration effort
- R2DBC is now production-ready for reactive SQL

**Next Steps:**
- Follow the [Migration Checklist](#migration-checklist)
- Test thoroughly in non-production environments
- Plan for future Spring Boot 2.4+ migration

---

*Last Updated: 2025-12-16*  
*Document Version: 1.0*
