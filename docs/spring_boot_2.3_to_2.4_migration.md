# Spring Boot 2.3 to 2.4 Migration Guide

## Overview

This comprehensive guide covers all changes required to migrate from Spring Boot 2.3 to Spring Boot 2.4. This release introduced significant improvements to configuration processing, new features like config data imports, and several breaking changes that require careful attention.

Spring Boot 2.4 marked a shift in the versioning scheme (dropping `.RELEASE` suffix) and brought major updates to configuration file processing, Neo4j support, and dependency management.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Dependency Upgrades](#dependency-upgrades)
3. [Breaking Changes](#breaking-changes)
4. [Configuration File Processing](#configuration-file-processing)
5. [Config Data Imports](#config-data-imports)
6. [JUnit Vintage Engine Removal](#junit-vintage-engine-removal)
7. [Neo4j Support Changes](#neo4j-support-changes)
8. [SQL Script Data Location Changes](#sql-script-data-location-changes)
9. [Logback Configuration Changes](#logback-configuration-changes)
10. [Elasticsearch RestClient Changes](#elasticsearch-restclient-changes)
11. [Other Important Changes](#other-important-changes)
12. [New Features](#new-features)
13. [Migration Checklist](#migration-checklist)
14. [Troubleshooting](#troubleshooting)
15. [References](#references)

---

## Executive Summary

### Key Changes at a Glance

| Category | Major Changes |
|----------|---------------|
| **Spring Framework** | 5.2.x → 5.3.x |
| **Spring Data** | Neumann → 2020.0 (Ockham) |
| **Java Support** | Added Java 15 support |
| **Versioning** | New scheme: `2.4.0` (not `2.4.0.RELEASE`) |
| **Config Processing** | Complete overhaul of property file processing |
| **JUnit Vintage** | Removed from `spring-boot-starter-test` |
| **Neo4j** | Complete support overhaul, OGM removed |
| **SQL Scripts** | `data.sql` processing changed |
| **Hazelcast** | Upgraded to 4.x (breaking changes) |
| **Flyway** | Upgraded to 7.x (callback ordering changes) |

### Migration Complexity

- **Critical (Must Address)**: Config file processing, JUnit Vintage removal, Neo4j property changes, data.sql location
- **High Priority**: Logback property renames, Hazelcast 4.x upgrade
- **Medium Priority**: Config data imports adoption, HTTP trace configuration
- **Optional**: Startup endpoint, version alignment features

---

## Dependency Upgrades

### 2.1 Update Spring Boot Version

**pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.4.13</version> <!-- Latest 2.4.x release -->
    <relativePath/>
</parent>
```

> [!NOTE]
> Notice the version format change: `2.4.13` instead of `2.4.13.RELEASE`

### 2.2 Major Dependency Version Changes

| Dependency | Spring Boot 2.3 | Spring Boot 2.4 | Notes |
|-----------|-----------------|-----------------|-------|
| Spring Framework | 5.2.x | 5.3.x | Performance improvements |
| Spring Data | Neumann | 2020.0 (Ockham) | New data access patterns |
| Spring Security | 5.3.x | 5.4.x | OAuth2 enhancements |
| Hibernate | 5.4.15+ | 5.4.27+ | Bug fixes |
| Hazelcast | 3.x | **4.1+** | **Breaking changes** |
| Flyway | 6.x | **7.x** | **Callback ordering changes** |
| Elasticsearch | 7.6.2 | 7.10+ | Version bump |
| Neo4j Driver | 4.0.x | **4.2+** | With breaking property changes |
| Micrometer | 1.5.x | 1.6.x | New metrics |
| R2DBC | Borca-SR1 | **Moved to Spring Framework** |

---

## Breaking Changes

### 3.1 Configuration File Processing (CRITICAL)

> [!CAUTION]
> **Most Critical Change**: Spring Boot 2.4 completely redesigned how `application.properties` and `application.yml` files are processed.

**What Changed**:
- **Profile-specific documents** now processed **before** profile-specific files
- **External files** now consistently **override** packaged files (regardless of profile-specific status)
- **Profile groups** processing order changed
- **Multi-document YAML** files now processed differently

**Impact**: Complex configurations with profile-specific properties may **break**.

**Legacy Mode (Temporary Solution)**:
```properties
# application.properties
# Revert to Spring Boot 2.3 config processing (NOT recommended long-term)
spring.config.use-legacy-processing=true
```

> [!WARNING]
> `spring.config.use-legacy-processing` is a temporary workaround. You should migrate to the new config processing model.

**Recommended: Migrate to New Processing Model**

**Before (Spring Boot 2.3)**:
```
application.yml (default profile)
application-dev.yml (dev profile)
application-prod.yml (prod profile)
```

**After (Spring Boot 2.4)** - Same structure works, but **processing order differs**:

```yaml
# application.yml
spring:
  application:
    name: myapp
  profiles:
    active: dev  # Active profile
    
---
# This section processed BEFORE application-dev.yml
spring.config.activate.on-profile: dev
server:
  port: 8080
```

**Key Rule**: Profile-specific **documents** (within same file) are now processed **before** profile-specific **files**.

**Processing Order (Spring Boot 2.4)**:
1. `application.yml` (default)
2. `application.yml` (profile-specific documents with `---`)
3. `application-{profile}.yml` (profile-specific files)
4. External `application.yml`
5. External `application-{profile}.yml`

**Migration Strategy**:
1. **Test with legacy mode first** (`spring.config.use-legacy-processing=true`)
2. **Identify issues** in your specific configuration
3. **Migrate incrementally** to new processing model
4. **Remove legacy mode** once stable

### 3.2 Profile Activation Changes

**Old way (deprecated in 2.4)**:
```yaml
spring:
  profiles: dev  # DEPRECATED
server:
  port: 8080
```

**New way (Spring Boot 2.4+)**:
```yaml
spring:
  config:
    activate:
      on-profile: dev  # New format
server:
  port: 8080
```

**Automation replacement**:
```bash
# Find old format
grep -r "spring.profiles:" src/main/resources/

# Replace with new format
sed -i 's/spring.profiles:/spring.config.activate.on-profile:/g' application*.yml
```

---

## Config Data Imports

### 4.1 New spring.config.import Property

**Purpose**: Import additional configuration files, especially useful for volume-mounted configs in Kubernetes.

**Basic usage**:
```yaml
# application.yml
spring:
  config:
    import:
      - classpath:custom-config.yml
      - file:///etc/config/application.yml
      - optional:file:///opt/config/  # Directory import (optional)
```

**Kubernetes ConfigMap example**:
```yaml
# application.yml
spring:
  config:
    import:
      - optional:file:/config/  # Kubernetes volume mount
```

**Multiple imports**:
```yaml
spring:
  config:
    import:
      - classpath:defaults.yml
      - optional:classpath:custom/${spring.application.name}.yml
      - file://${user.home}/.${spring.application.name}/config.yml
```

**Property placeholders supported**:
```yaml
spring:
  config:
    import:
      - optional:file:${config.dir}/app-config.yml
```

**Optional imports** (won't fail if file missing):
```yaml
spring:
  config:
    import:
      - optional:file:/etc/myapp/config.yml
```

### 4.2 Config Tree Support

**For Kubernetes ConfigMaps as directory trees**:

Kubernetes ConfigMap structure:
```
/config/
  application/
    name
  server/
    port
  database/
    url
    username
    password
```

**Import as config tree**:
```yaml
spring:
  config:
    import:
      - optional:configtree:/config/
```

Spring Boot will read each file as a property:
- `/config/application/name` → `application.name`
- `/config/server/port` → `server.port`
- `/config/database/url` → `database.url`

---

## JUnit Vintage Engine Removal

> [!WARNING]
> **JUnit 4 support removed** from `spring-boot-starter-test` by default.

### 5.1 Migration Options

**Option 1: Automated Migration to JUnit 5** (Recommended)

> [!TIP]
> Use the **JUnit 4 to 5 migration tool** to automatically convert your tests.

**Run the migration tool**:
```bash
# Using Antikythera's JUnit425Migrator
java -jar antikythera-examples.jar \
  --migrate-junit \
  --project-path=/path/to/your/project

# Or programmatically
JUnit425Migrator migrator = new JUnit425Migrator();
List<ConversionOutcome> results = migrator.migrateAll(compilationUnit);
```

**What the tool does**:
- Updates POM dependencies (removes JUnit 4, adds JUnit 5)
- Converts imports (`org.junit.Test` → `org.junit.jupiter.api.Test`)
- Replaces annotations (`@Before` → `@BeforeEach`, `@After` → `@AfterEach`)
- Converts assertions (`Assert.assertEquals` → `assertEquals` with static import)
- Updates `@RunWith(SpringRunner.class)` → removes (not needed in JUnit 5)
- Converts test class modifiers (can be package-private in JUnit 5)

**Manual example for reference**:
```java
// Before (JUnit 4) - automated tool will convert this
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class MyTest {
    @Test
    public void testSomething() {
        // test code
    }
}

// After (JUnit 5) - automated output
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MyTest {
    @Test
    void testSomething() {
        // test code
    }
}
```

**Option 2: Keep JUnit 4 Tests** (Temporary)

If automated migration is not feasible immediately, add the vintage engine:

**Before (Spring Boot 2.3)**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
    <!-- JUnit 4 vintage engine included -->
</dependency>
```

**After (Spring Boot 2.4)** - Add explicitly:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- Add vintage engine for JUnit 4 tests -->
<dependency>
    <groupId>org.junit.vintage</groupId>
    <artifactId>junit-vintage-engine</artifactId>
    <scope>test</scope>
</dependency>
```

### 5.2 Detection

**Check if you have JUnit 4 tests**:
```bash
# Scan for JUnit 4 imports
grep -r "@RunWith\|import org.junit.Test" src/test/

# Count JUnit 4 test files
find src/test -name "*.java" -exec grep -l "import org.junit.Test" {} \; | wc -l
```

### 5.3 Migration Validation

**After running the migration tool, verify**:
```bash
# All tests should pass
mvn test

# Check for any remaining JUnit 4 imports
grep -r "import org.junit.Test\|import org.junit.Before" src/test/

# Should find none or only in files you intentionally kept
```

---

## Neo4j Support Changes

> [!CAUTION]
> **Major breaking change**: Neo4j support completely overhauled. Neo4j OGM support removed.

### 6.1 Property Migration

**Removed properties** (`spring.data.neo4j.*`):
- `spring.data.neo4j.uri`
- `spring.data.neo4j.username`
- `spring.data.neo4j.password`
- `spring.data.neo4j.embedded.enabled`
- All Neo4j OGM properties

**New properties** (`spring.neo4j.*`):
```yaml
# Before (Spring Boot 2.3)
spring:
  data:
    neo4j:
      uri: bolt://localhost:7687
      username: neo4j
      password: secret

# After (Spring Boot 2.4)
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: secret
```

### 6.2 Driver Configuration

**New namespace for Neo4j driver**:
```yaml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: secret
    connection-timeout: 30s
    max-connection-lifetime: 1h
    max-connection-pool-size: 50
    connection-acquisition-timeout: 60s
```

### 6.3 Code Changes

**If using Neo4j OGM** (removed):
```java
// REMOVED in Spring Boot 2.4
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
```

**Use Neo4j SDN-RX** (new approach):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>
```

```java
// New reactive repository
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;

public interface PersonRepository extends ReactiveNeo4jRepository<Person, Long> {
    Flux<Person> findByName(String name);
}
```

### 6.4 Migration Strategy

1. **Update properties** to use `spring.neo4j.*` namespace
2. **Remove** any Neo4j OGM dependencies
3. **Migrate** from Neo4j OGM to Neo4j SDN-RX if needed
4. **Update** repository interfaces to use `ReactiveNeo4jRepository`
5. **Test** all Neo4j interactions thoroughly

---

## SQL Script Data Location Changes

> [!IMPORTANT]
> **`data.sql` processing changed**: Now runs **before** Hibernate initialization by default.

### 7.1 The Problem

**Before (Spring Boot 2.3)**:
- `schema.sql` runs first
- Hibernate initializes schema
- `data.sql` runs after Hibernate

**After (Spring Boot 2.4)**:
- `schema.sql` && `data.sql` run **before** Hibernate
- This can cause issues if `data.sql` expects Hibernate-created schema

### 7.2 Symptoms

```
Error: Table 'my_table' doesn't exist
```

**When running `data.sql` that references Hibernate entities.**

### 7.3 Solutions

**Option 1: Use new defer-datasource-initialization** (recommended)
```yaml
spring:
  jpa:
    defer-datasource-initialization: true  # Run data.sql AFTER Hibernate
```

**Option 2: Switch to Hibernate import mechanism**:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create  # or update
    properties:
      hibernate:
        hbm2ddl:
          import_files: import.sql  # Hibernate's SQL import
```

Rename `data.sql` to `import.sql` and place in `src/main/resources/`.

**Option 3: Use separate initialization mechanism**:
```java
@Component
public class DatabaseInitializer implements ApplicationRunner {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Override
    public void run(ApplicationArguments args) {
        // Custom data initialization after Hibernate
        jdbcTemplate.execute("INSERT INTO users...");
    }
}
```

### 7.4 Detection

```bash
# Check if using data.sql
find src/main/resources -name "data.sql"

# Check if using Hibernate ddl-auto
grep -r "spring.jpa.hibernate.ddl-auto" src/main/resources/
```

---

## Logback Configuration Changes

### 8.1 Property Renames

**Several Logback-specific properties renamed** to reflect their Logback-only nature.

| Old Property (2.3) | New Property (2.4) | Deprecated |
|--------------------|-------------------|------------|
| `logging.pattern.rolling-file-name` | `logging.logback.rollingpolicy.file-name-pattern` | Yes |
| `logging.file.max-size` | `logging.logback.rollingpolicy.max-file-size` | Yes |
| `logging.file.max-history` | `logging.logback.rollingpolicy.max-history` | Yes |
| `logging.file.total-size-cap` | `logging.logback.rollingpolicy.total-size-cap` | Yes |
| `logging.file.clean-history-on-start` | `logging.logback.rollingpolicy.clean-history-on-start` | Yes |

**Migration**:
```yaml
# Before (Spring Boot 2.3)
logging:
  file:
    name: /var/log/app.log
    max-size: 10MB
    max-history: 30
    total-size-cap: 1GB

# After (Spring Boot 2.4)
logging:
  file:
    name: /var/log/app.log
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 30
      total-size-cap: 1GB
      clean-history-on-start: false
```

**Properties Migrator Support**:
```xml
<!-- Temporarily add to detect deprecated properties -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 8.2 Automation

```bash
# Detect old properties
grep -r "logging.file.max-size\|logging.pattern.rolling-file-name" src/main/resources/

# Automated replacement (be careful!)
sed -i 's/logging.file.max-size/logging.logback.rollingpolicy.max-file-size/g' application*.yml
sed -i 's/logging.file.max-history/logging.logback.rollingpolicy.max-history/g' application*.yml
```

---

## Elasticsearch RestClient Changes

### 9.1 Low-Level RestClient No Longer Auto-Configured

**Breaking change**: `RestClient` (low-level) bean no longer automatically created.

**Before (Spring Boot 2.3)**:
```java
@Autowired
private RestClient restClient;  // Auto-configured
```

**After (Spring Boot 2.4)**:
```java
// Low-level RestClient NO LONGER auto-configured
// Use RestHighLevelClient instead (still auto-configured):

@Autowired
private RestHighLevelClient restHighLevelClient;
```

**If you need low-level RestClient**, create manually:
```java
@Configuration
public class ElasticsearchConfig {
    
    @Bean
    public RestClient restClient(RestHighLevelClient restHighLevelClient) {
        return restHighLevelClient.getLowLevelClient();
    }
}
```

**Or configure directly**:
```java
@Bean
public RestClient restClient() {
    return RestClient.builder(
        new HttpHost("localhost", 9200, "http")
    ).build();
}
```

---

## Other Important Changes

### 10.1 Default Servlet Registration

**Change**: `DefaultServlet` no longer registered automatically.

**Impact**: Low for most applications (rarely used).

**If needed**:
```java
@Bean
public ServletRegistrationBean<DefaultServlet> defaultServlet() {
    ServletRegistrationBean<DefaultServlet> registration = 
        new ServletRegistrationBean<>(new DefaultServlet(), "/*");
    registration.setLoadOnStartup(1);
    return registration;
}
```

### 10.2 HTTP Traces Exclude Cookies

**Change**: HTTP traces no longer include cookie headers by default.

**Before (Spring Boot 2.3)**:
- Request cookies included
- `Set-Cookie` response headers included

**After (Spring Boot 2.4)**:
- Cookies and  `Set-Cookie` headers **excluded** by default

**To restore previous behavior**:
```yaml
management:
  trace:
    http:
      include:
        - cookie-headers  # Include cookies
        - request-headers
        - response-headers
```

### 10.3 Hazelcast 4.x Upgrade

**Breaking change**: Hazelcast upgraded from 3.x to 4.x.

**Key Hazelcast 4.x changes**:
- Package names changed: `com.hazelcast.*` (mostly compatible)
- Configuration API changes
- Some deprecated methods removed

**Configuration changes**:
```java
// Hazelcast 3.x
Config config = new Config();
config.getNetworkConfig().setPort(5701);

// Hazelcast 4.x (same API, but verify deprecated methods)
Config config = new Config();
config.getNetworkConfig().setPort(5701);
```

**Recommendation**: Review [Hazelcast 4.0 migration guide](https://docs.hazelcast.com/hazelcast/4.0/migration-guides/4.0)

### 10.4 Flyway 7.x Callback Ordering

**Change**: Flyway callback registration order changed.

**Impact**: If you rely on specific callback execution order, this may break.

**Migration**: Review your Flyway callbacks:
```java
@Component
public class MyFlywayCallback implements Callback {
    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }
    
    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }
    
    @Override
    public void handle(Event event, Context context) {
        // Callback logic - verify execution order
    }
}
```

### 10.5 R2DBC Moved to Spring Framework

**Change**: R2DBC infrastructure moved from Spring Boot to Spring Framework 5.3.

**New module**: `spring-r2dbc`

**Impact**: Minimal - auto-configuration remains in Spring Boot.

**Verify imports** (should still work):
```java
import org.springframework.r2dbc.core.DatabaseClient;
```

### 10.6 exec-maven-plugin Version Management Removed

**Change**: Spring Boot no longer manages `exec-maven-plugin` version.

**If using**:
```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <!-- Spring Boot 2.3: version managed -->
    <!-- Spring Boot 2.4: MUST specify version -->
    <version>3.0.0</version>
</plugin>
```

### 10.7 Gradle bootJar Task DSL Change

**If using Gradle**:

**Before**:
```gradle
bootJar {
    mainClassName = 'com.example.Application'
}
```

**After**:
```gradle
bootJar {
    mainClass = 'com.example.Application'  // Changed from mainClassName
}
```

---

## New Features

### 11.1 Startup Endpoint

New Actuator endpoint for analyzing application startup:

**Enable**:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: startup
  endpoint:
    startup:
      cache:
        time-to-live: 10s
```

**Access**:
```bash
curl http://localhost:8080/actuator/startup
```

**Response** (identifies slow-starting beans):
```json
{
  "springBootVersion": "2.4.0",
  "timeline": {
    "startTime": "2024-01-01T10:00:00.000Z",
    "events": [
      {
        "startTime": "2024-01-01T10:00:01.234Z",
        "duration": "PT2.5S",
        "startupStep": {
          "name": "spring.beans.instantiate",
          "tags": [
            {"key": "beanName", "value": "slowBean"}
          ]
        }
      }
    ]
  }
}
```

### 11.2 Java 15 Support

Spring Boot 2.4 adds official Java 15 support:

```xml
<properties>
    <java.version>15</java.version>
</properties>
```

### 11.3 Enhanced Docker/Buildpack Support

**Private registry authentication**:
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <image>
            <name>myregistry.io/myapp:${project.version}</name>
            <publish>true</publish>
        </image>
        <docker>
            <publishRegistry>
                <username>${docker.username}</username>
                <password>${docker.password}</password>
                <url>https://myregistry.io</url>
            </publishRegistry>
        </docker>
    </configuration>
</plugin>
```

### 11.4 Origin Chains

Enhanced configuration origin tracking:

```java
@ConfigurationProperties("app")
public class AppProperties {
    private String name;
    
    // Spring Boot 2.4 provides full origin chain
    // Can trace exactly where property value came from
}
```

**Example origin chain**:
```
app.name = "MyApp"
  Origin: class path resource [application-prod.yml] - line 5, column 10
  Origin: file [/config/application.yml] - line 3, column 8 (overridden)
```

---

## Migration Checklist

### Pre-Migration
- [ ] Review all breaking changes
- [ ] Backup current configuration files
- [ ] Identify JUnit 4 tests (if any)
- [ ] Check if using Neo4j
- [ ] Check if using `data.sql` with Hibernate
- [ ] Review Logback configuration
- [ ] Check Elasticsearch RestClient usage

### Dependency Updates
- [ ] Update Spring Boot version to 2.4.13
- [ ] Add JUnit Vintage engine (if using JUnit 4)
- [ ] Update any custom Hazelcast configuration
- [ ] Specify exec-maven-plugin version (if used)
- [ ] Update Gradle bootJar DSL (if using Gradle)

### Configuration Changes
- [ ] Test with `spring.config.use-legacy-processing=true` first
- [ ] Migrate profile activation (`spring.profiles` → `spring.config.activate.on-profile`)
- [ ] Update Neo4j properties (`spring.data.neo4j.*` → `spring.neo4j.*`)
- [ ] Update Logback properties (rolling policy)
- [ ] Add `spring.jpa.defer-datasource-initialization=true` if using `data.sql`
- [ ] Review HTTP trace configuration (cookies)
- [ ] Consider adopting `spring.config.import` for external configs

### Code Updates
- [ ] Migrate Neo4j OGM to Neo4j SDN-RX (if applicable)
- [ ] Create RestClient bean manually (if using low-level Elasticsearch client)
- [ ] Review Flyway callback ordering (if used)
- [ ] Update Hazelcast 4.x API usage

### Testing
- [ ] Run with legacy config processing first
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Verify config file loading (all profiles)
- [ ] Test Neo4j connectivity (if used)
- [ ] Test data initialization (`data.sql`)
- [ ] Check Logback rolling file configuration
- [ ] Smoke test in staging

### Post-Migration
- [ ] Remove `spring.config.use-legacy-processing` flag
- [ ] Remove `spring-boot-properties-migrator` dependency
- [ ] Update documentation
- [ ] Train team on new config processing model
- [ ] Consider using startup endpoint for performance analysis

---

## Troubleshooting

### Issue 1: Configuration Not Loading Correctly

**Symptom**: Properties from profile-specific files not applying

**Cause**: New config processing order

**Solution**:
1. **Temporary**: Add `spring.config.use-legacy-processing=true`
2. **Permanent**: Restructure config files following new processing model
3. **Debug**: Enable config logging:
   ```yaml
   logging:
     level:
       org.springframework.boot.context.config: DEBUG
   ```

### Issue 2: JUnit 4 Tests Fail

**Symptom**:
```
java.lang.ClassNotFoundException: org.junit.vintage.engine.VintageTestEngine
```

**Solution**: Add vintage engine dependency:
```xml
<dependency>
    <groupId>org.junit.vintage</groupId>
    <artifactId>junit-vintage-engine</artifactId>
    <scope>test</scope>
</dependency>
```

### Issue 3: Neo4j Connection Failure

**Symptom**:
```
Config property 'spring.data.neo4j.uri' is not recognized
```

**Solution**: Update to new property namespace:
```yaml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: secret
```

### Issue 4: data.sql Table Not Found

**Symptom**:
```
Error: Table 'my_table' doesn't exist when running data.sql
```

**Solution**: Defer datasource initialization:
```yaml
spring:
  jpa:
    defer-datasource-initialization: true
```

### Issue 5: Elasticsearch RestClient Bean Not Found

**Symptom**:
```
NoSuchBeanDefinitionException: No qualifying bean of type 'RestClient'
```

**Solution**: Use `RestHighLevelClient` or create `RestClient` bean manually:
```java
@Bean
public RestClient restClient(RestHighLevelClient client) {
    return client.getLowLevelClient();
}
```

### Issue 6: Hazelcast Configuration Error

**Symptom**: API not found or deprecated method errors

**Solution**: Review Hazelcast 4.x migration guide and update configuration

### Issue 7: Logback Rolling File Not Working

**Symptom**: Log files not rolling despite configuration

**Cause**: Using old property names

**Solution**: Update to new Logback-specific properties:
```yaml
logging:
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 30
```

---

## References

### Official Documentation

- [Spring Boot 2.4 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.4-Release-Notes)
- [Spring Boot 2.4.0 Release](https://github.com/spring-projects/spring-boot/releases/tag/v2.4.0)
- [Config Data Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-Config-Data-Migration-Guide)
- [Spring Framework 5.3 What's New](https://docs.spring.io/spring-framework/docs/5.3.x/spring-framework-reference/)

### Related Guides

- [Spring Boot 2.2 to 2.3 Migration](spring_boot_2.2_to_2.3_migration.md)
- [Neo4j SDN-RX Documentation](https://neo4j.com/docs/spring-data-neo4j/current/)
- [Hazelcast 4.0 Migration Guide](https://docs.hazelcast.com/hazelcast/4.0/migration-guides/4.0)
- [Flyway 7.x Documentation](https://flywaydb.org/documentation/learnmore/releaseNotes)

### Tools

- [Spring Boot Properties Migrator](https://docs.spring.io/spring-boot/docs/current/reference/html/appendix-configuration-metadata.html#configuration-metadata.annotation-processor)
