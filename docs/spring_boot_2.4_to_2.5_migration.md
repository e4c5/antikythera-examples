# Spring Boot 2.4 to 2.5 Migration Guide

## Overview

This comprehensive guide covers all changes required to migrate from Spring Boot 2.4 to Spring Boot 2.5. This release introduced SQL script initialization redesign, removal of code deprecated in 2.3, Java 16 support, and important security changes to the `/info` actuator endpoint.

Spring Boot 2.5 is a **critical milestone** as it's the first version to support Java 17, making it an essential step for teams planning to upgrade to newer Java versions.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Dependency Upgrades](#dependency-upgrades)
3. [Breaking Changes](#breaking-changes)
4. [SQL Script Initialization](#sql-script-initialization)
5. [Deprecated Code Removal](#deprecated-code-removal)
6. [Actuator Info Endpoint Security](#actuator-info-endpoint-security)
7. [Cassandra Throttling Properties](#cassandra-throttling-properties)
8. [Default EL Implementation Change](#default-el-implementation-change)
9. [Groovy 3.x Upgrade](#groovy-3x-upgrade)
10. [Error View Message Attribute](#error-view-message-attribute)
11. [New Features](#new-features)
12. [Migration Checklist](#migration-checklist)
13. [Troubleshooting](#troubleshooting)
14. [References](#references)

---

## Executive Summary

### Key Changes at a Glance

| Category | Major Changes |
|----------|---------------|
| **Spring Framework** | 5.3.x → 5.3.7+ |
| **Spring Data** | 2020.0 → 2021.0 (2021.0.0) |
| **Spring Security** | 5.4.x → 5.5.x |
| **Java Support** | **Added Java 16 support**, **First version supporting Java 17** |
| **SQL Scripts** | Complete redesign: `spring.datasource.*` → `spring.sql.init.*` |
| **Deprecation Removal** | Code deprecated in Spring Boot 2.3 removed |
| **/info Endpoint** | No longer exposed by default, requires authentication with Spring Security |
| **Cassandra** | Default throttling properties removed |
| **EL Implementation** | Changed from Glassfish to Tomcat |
| **Groovy** | Upgraded to 3.x (breaking for Spock tests) |

### Migration Complexity

- **Critical (Must Address)**: SQL script initialization property migration, /info endpoint exposure, deprecated code removal
- **High Priority**: Cassandra throttling config, Groovy/Spock version updates
- **Medium Priority**: EL implementation verification, error view message attribute handling
- **Optional**: Adopt new features (SQL init mode, environment info contributor)

---

## Dependency Upgrades

### 2.1 Update Spring Boot Version

**pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.5.15</version> <!-- Latest 2.5.x release -->
    <relativePath/>
</parent>
```

### 2.2 Major Dependency Version Changes

| Dependency | Spring Boot 2.4 | Spring Boot 2.5 | Notes |
|-----------|-----------------|-----------------|-------|
| Spring Framework | 5.3.x | 5.3.7+ | Bug fixes |
| Spring Data | 2020.0 | 2021.0 | New release train |
| Spring Security | 5.4.x | 5.5.x | Enhanced OAuth2 support |
| Spring HATEOAS | 1.2.x | 1.3.x | Improvements |
| Spring Integration | 5.4.x | 5.5.x | New patterns |
| Spring Session | 2020.0 | 2021.0 | Multi-version support |
| Hibernate | 5.4.x | 5.4.32+ | Bug fixes |
| Flyway | 7.x | 7.7+ | Migration improvements |
| Liquibase | 4.2.x | 4.3.x | Bug fixes |
| Groovy | 2.5.x | **3.0.x** | **Breaking change** |
| Micrometer | 1.6.x | 1.7.x | New metrics |

### 2.3 Java Version Support

> [!IMPORTANT]
> Spring Boot 2.5 is the **first version to support Java 17** (LTS release).

| Java Version | Support Status |
|--------------|----------------|
| Java 8 | ✅ Supported |
| Java 11 | ✅ Supported (LTS) |
| Java 16 | ✅ **Newly supported** |
| Java 17 | ✅ **Newly supported (LTS)** |

---

## Breaking Changes

### 3.1 SQL Script Initialization (CRITICAL)

> [!CAUTION]
> **Most Critical Change**: Complete redesign of SQL script (`schema.sql`/`data.sql`) initialization mechanism.

**What Changed**:
- Properties moved from `spring.datasource.*` to `spring.sql.init.*`
- Different configuration namespace
- Execution ordering controlled by new properties

**Old Properties (Spring Boot 2.4)** - DEPRECATED:
```yaml
spring:
  datasource:
    initialization-mode: always
    schema: classpath:schema.sql
    data: classpath:data.sql
    platform: postgresql
    continue-on-error: false
    separator: ;
```

**New Properties (Spring Boot 2.5)**:
```yaml
spring:
  sql:
    init:
      mode: always  # or 'never', 'embedded'
      schema-locations: classpath:schema.sql
      data-locations: classpath:data.sql
      platform: postgresql
      continue-on-error: false
      separator: ;
```

### 3.2 Complete Property Migration Map

| Old Property (2.4) | New Property (2.5) | Notes |
|--------------------|-------------------|-------|
| `spring.datasource.initialization-mode` | `spring.sql.init.mode` | Values: `always`, `never`, `embedded` |
| `spring.datasource.schema` | `spring.sql.init.schema-locations` | Accepts list |
| `spring.datasource.data` | `spring.sql.init.data-locations` | Accepts list |
| `spring.datasource.platform` | `spring.sql.init.platform` | Unchanged logic |
| `spring.datasource.continue-on-error` | `spring.sql.init.continue-on-error` | Unchanged logic |
| `spring.datasource.separator` | `spring.sql.init.separator` | Unchanged logic |
| `spring.datasource.sql-script-encoding` | `spring.sql.init.encoding` | Charset encoding |

### 3.3 Execution Ordering with JPA

**New property for controlling execution order**:
```yaml
spring:
  jpa:
    defer-datasource-initialization: true  # Run SQL scripts AFTER JPA/Hibernate
```

**Default behavior (Spring Boot 2.5)**:
- SQL scripts run **before** Hibernate schema creation
- If your `data.sql` expects Hibernate-created tables, set `defer-datasource-initialization: true`

### 3.4 Migration Strategy

**Step 1: Detect usage**
```bash
# Find old properties
grep -r "spring.datasource.initialization-mode\|spring.datasource.schema\|spring.datasource.data" src/main/resources/
```

**Step 2: Automated replacement**
```bash
# Replace in application.yml
sed -i 's/spring.datasource.initialization-mode/spring.sql.init.mode/g' application*.yml
sed -i 's/spring.datasource.schema:/spring.sql.init.schema-locations:/g' application*.yml
sed -i 's/spring.datasource.data:/spring.sql.init.data-locations:/g' application*.yml
sed -i 's/spring.datasource.platform/spring.sql.init.platform/g' application*.yml
```

**Step 3: Use properties migrator**
```xml
<!-- Temporarily add to detect deprecated properties -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

The properties migrator will:
- Detect old property names
- Log warnings with correct replacements
- Temporarily apply old properties (during migration period)

**Step 4: Test thoroughly**
```bash
# Verify SQL scripts execute correctly
mvn spring-boot:run

# Check application logs for script execution
grep -i "Executing SQL script" logs/application.log
```

---

## Deprecated Code Removal

> [!WARNING]
> All code deprecated in **Spring Boot 2.3** has been removed in 2.5.

### 4.1 Common Removals

**Classes and methods removed**:
- Classes deprecated in 2.3 have been deleted
- Deprecated configuration properties removed
- Deprecated methods on auto-configuration classes removed

### 4.2 Detection Strategy

**Compile with warnings**:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-Xlint:deprecation</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

**Find deprecation warnings**:
```bash
# Compile and capture warnings
mvn clean compile 2>&1 | grep -i "deprecated"

# Common patterns to search for
grep -r "org.springframework.boot.actuate.autoconfigure.metrics.export" src/
```

### 4.3 Common Deprecated Items

**If upgrading from Spring Boot 2.3 directly to 2.5**:
1. Review [Spring Boot 2.4 deprecations](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.4-Release-Notes#deprecations-in-spring-boot-24)
2. Check compilation errors after upgrade
3. Update code to use replacement APIs

**Example - Metrics exporters** (if you used custom metrics):
```java
// Deprecated in 2.3, removed in 2.5
// Check your code for similar patterns and update accordingly
```

---

## Actuator Info Endpoint Security

> [!IMPORTANT]
> The `/info` actuator endpoint is **no longer exposed by default** and **requires authentication** when Spring Security is present.

### 5.1 Changes to /info Endpoint

**Before (Spring Boot 2.4)**:
- `/info` exposed over web by default
- No authentication required (even with Spring Security)

**After (Spring Boot 2.5)**:
- `/info` **NOT exposed** by default
- Requires authentication if Spring Security present

### 5.2 Re-Enable Public Access

**Option 1: Expose endpoint without authentication**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: info  # Expose /info endpoint
  endpoint:
    info:
      enabled: true
```

**And configure Spring Security to allow public access**:
```java
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/actuator/info").permitAll()  // Allow public access
                .anyRequest().authenticated()
            .and()
            .httpBasic();
    }
}
```

**Or using Spring Security 5.4+ lambda DSL**:
```java
@Configuration
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(withDefaults());
        return http.build();
    }
}
```

**Option 2: Keep authentication required**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: info, health  # Expose but require auth
```

### 5.3 Detection

```bash
# Check if /info endpoint is used
curl http://localhost:8080/actuator/info

# Before migration: Returns info (200 OK)
# After migration: Returns 404 Not Found (unless explicitly exposed)
```

---

## Cassandra Throttling Properties

### 6.1 Default Values Removed

**Breaking change**: Spring Boot no longer provides default values for Cassandra request throttling properties.

**Properties affected**:
- `spring.data.cassandra.request.throttler.max-queue-size`
- `spring.data.cassandra.request.throttler.max-concurrent-requests`
- `spring.data.cassandra.request.throttler.max-requests-per-second`
- `spring.data.cassandra.request.throttler.drain-interval`

**Impact**: If you rely on these defaults, you **must** now set explicit values.

### 6.2 Solution

**If using Cassandra throttling, add explicit configuration**:
```yaml
spring:
  data:
    cassandra:
      request:
        throttler:
          type: rate-limiting  # or concurrency-limiting
          max-queue-size: 10000
          max-concurrent-requests: 1000
          max-requests-per-second: 10000
          drain-interval: 10ms
```

**If NOT using throttling** (default):
```yaml
# No configuration needed - throttling disabled by default
spring:
  data:
    cassandra:
      request:
        throttler:
          type: none  # Explicitly disable (or omit entirely)
```

### 6.3 Detection

```bash
# Check if using Cassandra
grep -r "spring.data.cassandra" src/main/resources/

# Check for throttler configuration
grep -r "request.throttler" src/main/resources/
```

---

## Default EL Implementation Change

### 7.1 EL Implementation Switch

**Change**: Default Expression Language (EL) implementation changed from Glassfish to Tomcat.

**Affected starters**:
- `spring-boot-starter-web` (Tomcat web server)
- `spring-boot-starter-validation`

**Before (Spring Boot 2.4)**:
- Used `jakarta.el` (Glassfish implementation)

**After (Spring Boot 2.5)**:
- Uses `tomcat-embed-el` (Tomcat implementation)

### 7.2 Impact

**Low for most applications**:
- Both implementations are spec-compliant
- Behavior should be identical for standard usage

**Potential issues**:
- If relying on Glassfish-specific EL features
- Custom EL resolvers with Glassfish dependencies

### 7.3 Reverting to Glassfish (if needed)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.apache.tomcat.embed</groupId>
            <artifactId>tomcat-embed-el</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- Add Glassfish EL back -->
<dependency>
    <groupId>org.glassfish</groupId>
    <artifactId>jakarta.el</artifactId>
    <version>3.0.4</version>
</dependency>
```

---

## Groovy 3.x Upgrade

### 8.1 Groovy Version Change

**Breaking change**: Default Groovy version upgraded from 2.5.x to 3.0.x.

**Impact**: If using Spock tests, they will **fail** with Groovy 3.x.

### 8.2 Solutions

**Option 1: Upgrade Spock to 2.0+** (recommended)
```xml
<properties>
    <groovy.version>3.0.8</groovy.version>  <!-- Groovy 3.x -->
    <spock.version>2.0-groovy-3.0</spock.version>  <!-- Spock 2.0 for Groovy 3.x -->
</properties>

<dependencies>
    <dependency>
        <groupId>org.spockframework</groupId>
        <artifactId>spock-core</artifactId>
        <version>${spock.version}</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.spockframework</groupId>
        <artifactId>spock-spring</artifactId>
        <version>${spock.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**Option 2: Downgrade to Groovy 2.5** (temporary)
```xml
<properties>
    <groovy.version>2.5.14</groovy.version>  <!-- Downgrade to 2.5 -->
</properties>
```

### 8.3 Detection

```bash
# Check if using Spock
find src/test -name "*.groovy"
grep -r "spockframework" pom.xml
```

**Verify Spock compatibility**:
```bash
# Run tests
mvn test

# If tests fail with Groovy 3.x, you'll see errors like:
# java.lang.NoClassDefFoundError: groovy/lang/GroovyObject
```

---

## Error View Message Attribute

### 9.1 Message Attribute Removal

**Change**: The `message` attribute in the default error view is now **removed** (not blanked) when not displayed.

**Before (Spring Boot 2.4)**:
```json
{
  "timestamp": "2024-01-01T10:00:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "",  // Blanked out
  "path": "/api/users"
}
```

**After (Spring Boot 2.5)**:
```json
{
  "timestamp": "2024-01-01T10:00:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  // "message" field completely removed
  "path": "/api/users"
}
```

### 9.2 Impact

**If parsing error responses**:
- Code expecting `message` field may break
- JSON parsers with strict schemas may fail

### 9.3 Solutions

**Option 1: Include messages**
```yaml
server:
  error:
    include-message: always  # or 'on_param', 'never'
```

**Option 2: Update error parsing logic**
```java
// Before
String message = errorResponse.get("message").asText();

// After (handle missing field)
String message = errorResponse.has("message") 
    ? errorResponse.get("message").asText() 
    : "No message available";
```

---

## New Features

### 10.1 Java 17 Support

> [!TIP]
> **Spring Boot 2.5 is the first version to support Java 17** (LTS), making it essential for Java 17 migration.

```xml
<properties>
    <java.version>17</java.version>
</properties>
```

See [Java 8 to 17 Migration Plan](java_8_to_17_migration_plan.md) for complete guidance.

### 10.2 SQL Initialization Mode

**New `mode` property for finer control**:
```yaml
spring:
  sql:
    init:
      mode: always  # Run scripts always
      # mode: never    # Never run scripts
      # mode: embedded # Only run for embedded databases (H2, HSQLDB, Derby)
```

### 10.3 Environment Info Contributor

**New actuator contributor for environment information**:
```yaml
management:
  info:
    env:
      enabled: true  # Expose environment info in /info endpoint
```

**Access environment info**:
```bash
curl http://localhost:8080/actuator/info
```

**Response**:
```json
{
  "app": {
    "name": "My Application",
    "version": "1.0.0"
  },
  "env": {
    "activeProfiles": ["prod"],
    "defaultProfiles": ["default"]
  }
}
```

### 10.4 Metrics Enhancements

Micrometer 1.7.x brings:
- New metric types
- Enhanced Prometheus support
- Better observability

### 10.5 Gradle Improvements

If using Gradle:
- Better build cache support
- Enhanced configuration cache compatibility
- Improved Docker build integration

---

## Migration Checklist

### Pre-Migration
- [ ] Review all breaking changes
- [ ] Backup current configuration
- [ ] Check for deprecated code usage (from 2.3)
- [ ] Identify SQL script usage (`schema.sql`/`data.sql`)
- [ ] Check if using `/info` actuator endpoint
- [ ] Verify Cassandra throttling configuration
- [ ] Check if using Spock tests with Groovy

### Dependency Updates
- [ ] Update Spring Boot version to 2.5.15
- [ ] Upgrade Spock to 2.0+ (if using Groovy 3.x)
- [ ] Or downgrade Groovy to 2.5.x (temporary)
- [ ] Update any custom dependencies

### Configuration Changes
- [ ] Migrate SQL script properties (`spring.datasource.*` → `spring.sql.init.*`)
- [ ] Add `spring.jpa.defer-datasource-initialization=true` if needed
- [ ] Expose `/info` endpoint if needed
- [ ] Configure Spring Security for `/info` access
- [ ] Add Cassandra throttling config (if used)
- [ ] Configure error message inclusion (if parsing errors)

### Code Updates
- [ ] Fix deprecated code from Spring Boot 2.3
- [ ] Update error response parsing (if needed)
- [ ] Review EL usage (Glassfish → Tomcat migration)

### Testing
- [ ] Add `spring-boot-properties-migrator` temporarily
- [ ] Run with new configuration
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Verify SQL scripts execute correctly
- [ ] Test `/info` endpoint access
- [ ] Verify Spock tests (if applicable)
- [ ] Check error responses
- [ ] Smoke test in staging

### Post-Migration
- [ ] Remove `spring-boot-properties-migrator`
- [ ] Review and clean up temporary workarounds
- [ ] Update documentation
- [ ] Plan for Java 16/17 upgrade (now supported)

---

## Troubleshooting

### Issue 1: SQL Scripts Not Running

**Symptom**: `schema.sql` or `data.sql` not executing

**Cause**: Using old property names

**Solution**: Update to new properties:
```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
      data-locations: classpath:data.sql
```

### Issue 2: Table Not Found in data.sql

**Symptom**:
```
Error: Table 'my_table' doesn't exist when running data.sql
```

**Cause**: SQL scripts run before Hibernate creates schema

**Solution**: Defer initialization:
```yaml
spring:
  jpa:
    defer-datasource-initialization: true
```

### Issue 3: /info Endpoint Returns 404

**Symptom**: `/actuator/info` returns 404 Not Found

**Cause**: Endpoint not exposed by default in Spring Boot 2.5

**Solution**: Expose endpoint:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: info, health
```

### Issue 4: /info Requires Authentication

**Symptom**: `/info` requires authentication when it shouldn't

**Cause**: Spring Security default behavior changed

**Solution**: Configure Spring Security:
```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/info").permitAll()
            .anyRequest().authenticated()
        );
    return http.build();
}
```

### Issue 5: Spock Tests Fail

**Symptom**:
```
java.lang.NoClassDefFoundError: groovy/lang/GroovyObject
```

**Cause**: Spock 1.x incompatible with Groovy 3.x

**Solution**: Upgrade Spock to 2.0+:
```xml
<properties>
    <spock.version>2.0-groovy-3.0</spock.version>
</properties>
```

### Issue 6: Cassandra Connection Failure

**Symptom**: Cassandra requests fail or timeout

**Cause**: Missing throttling configuration (no defaults in 2.5)

**Solution**: Add explicit throttling config:
```yaml
spring:
  data:
    cassandra:
      request:
        throttler:
          type: rate-limiting
          max-requests-per-second: 10000
```

### Issue 7: Error Response Parsing Fails

**Symptom**: JSON parsing error for error responses

**Cause**: `message` field removed from error response

**Solution**: Handle missing field:
```java
String message = errorResponse.has("message") 
    ? errorResponse.get("message").asText() 
    : "No message";
```

---

## References

### Official Documentation

- [Spring Boot 2.5 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.5-Release-Notes)
- [Spring Boot 2.5.0 Release](https://spring.io/blog/2021/05/20/spring-boot-2-5-0-available-now)
- [Configuration Changelog](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.5-Configuration-Changelog)
- [Spring Framework 5.3 Documentation](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/)

### Related Guides

- [Spring Boot 2.3 to 2.4 Migration](spring_boot_2.3_to_2.4_migration.md)
- [Java 8 to 17 Migration Plan](java_8_to_17_migration_plan.md)
- [JUnit 4 to 5 Migration](../src/main/java/com/raditha/cleanunit/JUnit425Migrator.java)

### Migration Tools

- [Spring Boot Properties Migrator](https://docs.spring.io/spring-boot/docs/current/reference/html/appendix-configuration-metadata.html)
- [OpenRewrite Spring Boot Migration](https://docs.openrewrite.org/recipes/java/spring/boot2)
