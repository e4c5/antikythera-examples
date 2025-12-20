# Spring Boot 2.4 to 2.5 Migration Guide

> **Implementation Status**: ✅ **COMPLETE** - Automated migration tool is fully implemented and available in:
> - `src/main/java/com/raditha/spring/SpringBoot24to25Migrator.java`
> - See [Implementation Guide](#implementation-guide-for-automated-migration) for usage

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

## Automation Summary

This migration guide is designed for both manual and **automated migration tools**. Each breaking change includes:

- **Detection patterns** (AST analysis, dependency scanning, property detection)
- **Complexity ratings** (LOW, MEDIUM, HIGH)
- **Automated transformation strategies** with code examples
- **Validation checklists** for verification
- **Automation confidence scores** (0-100%)

### Automation Capabilities by Component

| Component | Automation Level | Complexity | Confidence | Manual Review |
|-----------|-----------------|------------|------------|---------------|
| **SQL Script Properties** | Full Automation | MEDIUM | 95% | NO |
| **/info Endpoint Configuration** | Guided Generation | MEDIUM | 70% | YES (Security) |
| **Cassandra Throttling** | Conditional Config | LOW | 90% | NO |
| **Groovy/Spock Upgrade** | Full Automation | LOW | 90% | If tests fail |
| **POM Version Update** | Full Automation | LOW | 100% | NO |
| **Deprecated Code Detection** | Detection Only | MEDIUM | 100% | YES |
| **EL Implementation** | Detection Only | LOW | N/A | As needed |
| **Error Message Handling** | Detection Only | LOW | N/A | As needed |

### Implementation Pattern

See `SpringBoot24to25Migrator.java` for a complete implementation following the pattern established in:
- `SpringBoot21to22Migrator.java` - Property and dependency migrations
- `SpringBoot22to23Migrator.java` - Configuration and code migrations
- `SpringBoot23to24Migrator.java` - Complex multi-phase migrations

Each migrator extends `AbstractSpringBootMigrator` and implements:
1. **POM Migration** - Dependency version updates
2. **Property Migration** - Configuration file transformations
3. **Code Migration** - AST-based code transformations
4. **Validation** - Compilation and test execution

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

#### Automated SQL Script Initialization Migration

> [!NOTE]
> This is a **CRITICAL** migration with HIGH automation potential. Property migrations can be fully automated.

**Detection Strategy**

**Complexity**: MEDIUM - Property file parsing and transformation required

**Step 1: Detect Old Property Usage**

```yaml
# Scan application.yml, application.properties, and all profile-specific configs
# for deprecated property patterns:

# Pattern 1: initialization-mode
spring:
  datasource:
    initialization-mode: always

# Pattern 2: schema/data locations
spring:
  datasource:
    schema: classpath:schema.sql
    data: classpath:data.sql

# Pattern 3: Platform-specific scripts
spring:
  datasource:
    platform: postgresql
    schema: classpath:schema-postgresql.sql

# Pattern 4: Additional SQL init properties
spring:
  datasource:
    continue-on-error: false
    separator: ;
    sql-script-encoding: UTF-8
```

**Step 2: Detect JPA Usage**

Check for JPA/Hibernate configuration to determine if `defer-datasource-initialization` is needed:

```yaml
# If JPA is enabled and schema auto-generation is used:
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop  # or create, update, validate

# This indicates schema creation by Hibernate
# which happens BEFORE SQL scripts in 2.5
# Requires defer-datasource-initialization: true
```

**Automated Transformation Strategy**

**Complexity**: LOW-MEDIUM - Simple property replacement with conditional logic

**Action 1: Transform Properties in YAML Files**

For each YAML configuration file containing old properties:

```yaml
# BEFORE (Spring Boot 2.4):
spring:
  datasource:
    initialization-mode: always
    schema: classpath:schema.sql
    data: classpath:data.sql
    platform: postgresql
    continue-on-error: false
    separator: ;

# AFTER (Spring Boot 2.5):
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
      data-locations: classpath:data.sql
      platform: postgresql
      continue-on-error: false
      separator: ;
```

**Action 2: Transform Properties in .properties Files**

For each .properties configuration file:

```properties
# BEFORE (Spring Boot 2.4):
spring.datasource.initialization-mode=always
spring.datasource.schema=classpath:schema.sql
spring.datasource.data=classpath:data.sql
spring.datasource.platform=postgresql
spring.datasource.continue-on-error=false
spring.datasource.separator=;

# AFTER (Spring Boot 2.5):
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql
spring.sql.init.data-locations=classpath:data.sql
spring.sql.init.platform=postgresql
spring.sql.init.continue-on-error=false
spring.sql.init.separator=;
```

**Action 3: Add defer-datasource-initialization if Needed**

If JPA/Hibernate DDL generation detected AND data.sql is present:

```yaml
spring:
  jpa:
    defer-datasource-initialization: true  # AUTO-ADDED: Run SQL scripts AFTER Hibernate
  sql:
    init:
      mode: always
      data-locations: classpath:data.sql
```

**Property Mapping Table**

| Old Property (2.4) | New Property (2.5) | Transformation Rule |
|--------------------|-------------------|---------------------|
| `spring.datasource.initialization-mode` | `spring.sql.init.mode` | Direct 1:1 mapping |
| `spring.datasource.schema` | `spring.sql.init.schema-locations` | Direct 1:1 mapping |
| `spring.datasource.data` | `spring.sql.init.data-locations` | Direct 1:1 mapping |
| `spring.datasource.platform` | `spring.sql.init.platform` | Direct 1:1 mapping |
| `spring.datasource.continue-on-error` | `spring.sql.init.continue-on-error` | Direct 1:1 mapping |
| `spring.datasource.separator` | `spring.sql.init.separator` | Direct 1:1 mapping |
| `spring.datasource.sql-script-encoding` | `spring.sql.init.encoding` | Direct 1:1 mapping |

**Validation Strategy**:
- All deprecated properties replaced in configuration files
- JPA defer logic correctly applied if needed
- Application starts successfully
- SQL scripts execute in correct order
- Tables/data created as expected
- No deprecated property warnings in logs

**Risk Level**: LOW-MEDIUM
- Property transformation: Very safe
- JPA defer logic: Needs testing to verify ordering

**Automation Confidence**: 95% (property transformation is deterministic, JPA defer detection is straightforward)

**Recommendation**: 
- Fully automated property transformation
- Automated detection and addition of `defer-datasource-initialization`
- Add warning comment when defer is auto-added to explain why
- Include validation checklist in migration report

**Automation Output Example**:
```
[INFO] SQL Script Initialization Migration Started
[INFO] Scanning configuration files for deprecated properties...

Configuration Files Analyzed:
✓ src/main/resources/application.yml
✓ src/main/resources/application-dev.properties
✓ src/main/resources/application-prod.yml

Detection Results:
[FOUND] application.yml:12 - spring.datasource.initialization-mode=always
[FOUND] application.yml:13 - spring.datasource.schema=classpath:schema.sql
[FOUND] application.yml:14 - spring.datasource.data=classpath:data.sql
[FOUND] application-prod.yml:8 - spring.datasource.platform=postgresql

JPA Configuration Analysis:
[FOUND] spring.jpa.hibernate.ddl-auto=create-drop
[FOUND] data.sql file exists
[ACTION] Will add spring.jpa.defer-datasource-initialization=true

Transformations Applied:
[UPDATED] application.yml
  - spring.datasource.initialization-mode → spring.sql.init.mode
  - spring.datasource.schema → spring.sql.init.schema-locations
  - spring.datasource.data → spring.sql.init.data-locations
  
[UPDATED] application-prod.yml
  - spring.datasource.platform → spring.sql.init.platform

[ADDED] application.yml
  + spring.jpa.defer-datasource-initialization: true
  + Comment: "# Run SQL scripts AFTER Hibernate schema creation"

[SUCCESS] SQL Script Initialization Migration Complete

Validation Checklist:
☐ Start application and verify no property errors
☐ Check logs for "Executing SQL script" messages
☐ Verify tables created by Hibernate first
☐ Verify data.sql executes after table creation
☐ Check data is inserted correctly

Manual Review Required: NO
```

**Step 1: Automated detection**
```bash
# Find old properties in all configuration files
find src/main/resources -name "application*.yml" -o -name "application*.properties" | \
  xargs grep -l "spring.datasource.initialization-mode\|spring.datasource.schema\|spring.datasource.data"
```

**Step 2: Use properties migrator (temporary)**
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

**Step 3: Automated transformation (recommended)**

Use a programmatic SQL script properties migrator (see `SqlScriptPropertiesMigrator` implementation)

**Step 4: Test thoroughly**
```bash
# Verify SQL scripts execute correctly
mvn spring-boot:run

# Check application logs for script execution
grep -i "Executing SQL script" logs/application.log

# Verify correct ordering with JPA
grep -i "Hibernate.*schema\|Executing SQL" logs/application.log
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

#### Automated Actuator Info Endpoint Migration

> [!NOTE]
> /info endpoint migration combines **detection** with **guided configuration generation**.

**Detection Strategy**

**Complexity**: MEDIUM - Requires checking dependencies, properties, and code

**Step 1: Detect Spring Security Usage**

```xml
<!-- Check pom.xml for Spring Security dependency -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- OR Spring Security OAuth2 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

**Step 2: Detect /info Endpoint Dependencies**

Check if application is monitoring /info endpoint:

```
1. Search for /actuator/info references in:
   - Application code  (REST clients, monitoring)
   - Documentation
   - Test code
   - Monitoring/health check scripts
   
2. Check existing actuator exposure:
   management.endpoints.web.exposure.include property
```

**Step 3: Detect Existing Security Configuration**

```java
// AST Analysis: Look for existing Security configuration:

// Pattern 1: WebSecurityConfigurerAdapter (Spring Security 5.4 and earlier)
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Existing security rules
    }
}

// Pattern 2: SecurityFilterChain (Spring Security 5.7+)
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Existing security rules
    }
}
```

**Automated Transformation Strategy**

**Complexity**: MEDIUM - Requires AST modification for Security config

**Action 1: Add Actuator Exposure Configuration**

If /info endpoint usage detected:

```yaml
# Auto-add to application.yml:
management:
  endpoints:
    web:
      exposure:
        include: info  # Expose /info endpoint
  endpoint:
    info:
      enabled: true
```

**Action 2: Generate Spring Security Configuration**

**Case A: No Spring Security dependency**

```
[INFO] Spring Security not detected
[INFO] Adding management.endpoints.web.exposure.include=info
[NOTE] /info endpoint will be publicly accessible (no authentication required)
```

**Case B: Spring Security present, No Security Config found**

Generate new security configuration:

```java
// AUTO-GENERATED: Spring Security configuration for actuator endpoints

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class ActuatorSecurityConfig {
    
    /**
     * Security configuration for actuator endpoints.
     * Auto-generated during Spring Boot 2.4 to 2.5 migration.
     * 
     * TODO: Review and adjust security rules based on your requirements.
     */
    @Bean
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/info").permitAll()  // Allow public access
                .anyRequest().authenticated()
            )
            .httpBasic(withDefaults());
        return http.build();
    }
}
```

**Case C: Spring Security present, WebSecurityConfigurerAdapter found**

Modify existing security configuration:

```java
// BEFORE (Spring Boot 2.4):
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .anyRequest().authenticated()
            .and()
            .httpBasic();
    }
}

// AFTER (Spring Boot 2.5) - AUTO-MODIFIED:
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/actuator/info").permitAll()  // AUTO-ADDED: Allow public access to /info
                .anyRequest().authenticated()
            .and()
            .httpBasic();
    }
}
```

**Case D: Spring Security present, SecurityFilterChain found**

Modify existing SecurityFilterChain:

```java
// BEFORE (Spring Boot 2.4):
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .httpBasic(withDefaults());
    return http.build();
}

// AFTER (Spring Boot 2.5) - AUTO-MODIFIED:
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/info").permitAll()  // AUTO-ADDED: Allow public access
            .anyRequest().authenticated()
        )
        .httpBasic(withDefaults());
    return http.build();
}
```

**Decision Logic for Public vs Authenticated Access**

```
IF /info endpoint contains sensitive data (check info contributors):
    -> Keep authenticated access (don't add permitAll)
    -> Add warning comment to manually review

ELSE IF /info endpoint used for health checks:
    -> Add permitAll (public access)
    -> Most common case for monitoring tools

DEFAULT:
    -> Add permitAll with TODO comment for manual review
```

**Validation Strategy**:
- Application compiles successfully
- Spring Security configuration valid
- /actuator/info endpoint accessible (with or without auth based on config)
- /info endpoint returns expected information
- Other endpoints still properly secured
- No security regressions introduced

**Risk Level**: MEDIUM
- Property changes: Very safe
- Security config generation: Requires careful review
- Security config modification: Medium risk, might affect existing rules

**Automation Confidence**: 70%
- Detection: 100%
- Property addition: 100%
- Security config generation/modification: 50% (requires manual review)

**Recommendation**: 
- Automated detection and property addition
- Generate Security configuration as suggested code
- Add TODO comments for manual security review
- Flag for manual review if sensitive info detected in /info response

**Automation Output Example**:
```
[INFO] Actuator /info Endpoint Migration Started
[INFO] Analyzing actuator and security configuration...

Detection Results:
[FOUND] Management endpoints in use
[FOUND] /info endpoint referenced in MonitoringService.java:45
[FOUND] spring-boot-starter-security dependency
[FOUND] Existing SecurityConfig.java with WebSecurityConfigurerAdapter

Analysis:
✓ /info endpoint is actively used by monitoring
✓ Spring Security is configured
✓ No sensitive info contributors detected in /info endpoint

Transformations Applied:
[UPDATED] application.yml
  + management.endpoints.web.exposure.include: info
  + management.endpoint.info.enabled: true

[UPDATED] SecurityConfig.java
  + Added: .antMatchers("/actuator/info").permitAll()
  + Comment: "// Allow public access to /info for monitoring tools"

[SUCCESS] Actuator /info Endpoint Migration Complete

Security Review Required:
⚠️  Spring Security configuration was automatically modified
⚠️  Please review SecurityConfig.java to ensure /info access aligns with security policy
⚠️  Consider if /info should require authentication in your environment

Validation Checklist:
☐ Test /info endpoint access: curl http://localhost:8080/actuator/info
☐ Verify monitoring tools can access /info
☐ Confirm other endpoints still require authentication
☐ Review info contributors for sensitive data exposure
☐ Test with your authentication mechanism (if /info should be protected)

Manual Review Required: YES (Security configuration change)
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

### 6.3 Detection and Automated Migration

#### Automated Cassandra Throttling Migration

> [!NOTE]
> Cassandra throttling migration is **detection + conditional configuration**.

**Detection Strategy**

**Complexity**: LOW - Dependency and property detection

**Step 1: Detect Cassandra Usage**

```xml
<!-- Check pom.xml for Cassandra dependency -->
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

```bash
# Check if using Cassandra
grep -r "spring.data.cassandra" src/main/resources/

# Check for throttler configuration
grep -r "request.throttler" src/main/resources/
```

**Step 2: Detect Existing Throttling Configuration**

```yaml
# Check if any throttler properties already defined:
spring:
  data:
    cassandra:
      request:
        throttler:
          type: ???  # If present, user has explicit config
```

**Automated Transformation Strategy**

**Complexity**: LOW - Simple property addition

**Decision Logic**:

```
IF Cassandra dependency found:
    IF throttler configuration exists:
        -> Keep existing config (user has made explicit choice)
        -> Add comment noting default removal
    ELSE:
        -> Check application usage patterns
        -> Generate recommended throttling config OR disable explicitly
ELSE:
    -> No action needed
```

**Action 1: Add Recommended Throttling Configuration**

If Cassandra found but no throttler config:

```yaml
# Auto-add to application.yml:
spring:
  data:
    cassandra:
      request:
        throttler:
          # Spring Boot 2.5 removed default throttling values
          # Added during migration with recommended production values
          type: rate-limiting  # Options: rate-limiting, concurrency-limiting, none
          max-queue-size: 10000
          max-concurrent-requests: 1000
          max-requests-per-second: 10000
          drain-interval: 10ms
```

**Action 2: Alternative - Explicitly Disable**

For development/low-load environments:

```yaml
spring:
  data:
    cassandra:
      request:
        throttler:
          type: none  # Throttling disabled
```

**Validation Strategy**:
- Cassandra dependency detected correctly
- Appropriate throttling configuration added
- Application starts without throttling errors
- Cassandra requests execute successfully
- No performance degradation

**Risk Level**: LOW
- Only adds configuration, no code changes
- Default values are conservative and safe

**Automation Confidence**: 90%
- Detection: 100%
- Configuration addition: 85% (recommended values work for most cases)

**Recommendation**: 
- Automated detection and configuration addition
- Add conservative default values with explanatory comments
- Flag for performance tuning based on actual load

**Automation Output Example**:
```
[INFO] Cassandra Throttling Migration Started
[INFO] Analyzing Cassandra configuration...

Detection Results:
[FOUND] spring-boot-starter-data-cassandra dependency
[NOT FOUND] Existing throttler configuration
[INFO] Spring Boot 2.5 removed default throttling values

Transformations Applied:
[UPDATED] application.yml
  + spring.data.cassandra.request.throttler.type: rate-limiting
  + spring.data.cassandra.request.throttler.max-queue-size: 10000
  + spring.data.cassandra.request.throttler.max-concurrent-requests: 1000
  + spring.data.cassandra.request.throttler.max-requests-per-second: 10000
  + spring.data.cassandra.request.throttler.drain-interval: 10ms
  + Comment: "# Recommended production values - tune based on your load"

[SUCCESS] Cassandra Throttling Configuration Added

Validation Checklist:
☐ Start application and verify Cassandra connection
☐ Monitor request throttling metrics
☐ Tune throttling values based on load testing
☐ Consider disabling throttling in development (type: none)

Manual Review Required: NO (consider tuning for production)
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

### 8.3 Detection and Automated Migration

#### Automated Groovy/Spock Migration

> [!NOTE]
> Groovy 3.x upgrade requires **Spock 2.0+**. This migration is highly automatable.

**Detection Strategy**

**Complexity**: LOW - Dependency detection and version update

**Step 1: Detect Groovy Usage**

```bash
# Check if using Spock tests
find src/test -name "*.groovy"

# Check for Spock dependency
grep -r "spockframework" pom.xml

# Check for Groovy files
find src -name "*.groovy" | wc -l
```

**Step 2: Detect Current Spock Version**

```xml
<!-- Check pom.xml for Spock version -->
<dependency>
    <groupId>org.spockframework</groupId>
    <artifactId>spock-core</artifactId>
    <version>???</version>  <!-- Detect current version -->
    <scope>test</scope>
</dependency>
```

**Automated Transformation Strategy**

**Complexity**: LOW - Simple dependency version update

**Decision Logic**:

```
IF Spock dependency found:
    IF Spock version < 2.0:
        -> Upgrade to Spock 2.0-groovy-3.0
        -> Set Groovy version to 3.0.x
        -> Update related dependencies
    ELSE:
        -> Already compatible, no action needed
ELSE IF *.groovy files found but no Spock:
    -> Just update Groovy version
ELSE:
    -> No action needed
```

**Action 1: Upgrade Spock to 2.0+**

```xml
<!-- Update in pom.xml -->
<properties>
    <groovy.version>3.0.8</groovy.version>
    <spock.version>2.0-groovy-3.0</spock.version>
</properties>

<dependencies>
    <!-- Spock Core -->
    <dependency>
        <groupId>org.spockframework</groupId>
        <artifactId>spock-core</artifactId>
        <version>${spock.version}</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Spock Spring (if found in dependencies) -->
    <dependency>
        <groupId>org.spockframework</groupId>
        <artifactId>spock-spring</artifactId>
        <version>${spock.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**Action 2: Update Gradle (if applicable)**

For Gradle projects:

```gradle
// Update in build.gradle
ext {
    groovyVersion = '3.0.8'
    spockVersion = '2.0-groovy-3.0'
}

dependencies {
    testImplementation "org.spockframework:spock-core:${spockVersion}"
    testImplementation "org.spockframework:spock-spring:${spockVersion}"
}
```

**Validation Strategy**:
- Spock 2.0+ dependency added
- Groovy 3.0.x version set
- All Spock test files compile
- All tests pass with new versions
- No Groovy compatibility errors

**Risk Level**: LOW-MEDIUM
- Spock 2.0 is mostly backward compatible
- Rare edge cases may require test adjustments

**Automation Confidence**: 90%
- Detection: 100%
- Version upgrade: 95%
- Test compatibility: 85% (most tests work without changes)

**Recommendation**: 
- Automated dependency version updates
- Run test suite after migration
- Flag any test failures for manual review

**Automation Output Example**:
```
[INFO] Groovy/Spock Migration Started
[INFO] Analyzing Groovy and Spock usage...

Detection Results:
[FOUND] 47 Groovy test files in src/test
[FOUND] Spock dependency with version 1.3-groovy-2.5
[FOUND] Spock Spring dependency
[INFO] Spock 1.x is incompatible with Groovy 3.x

Transformations Applied:
[UPDATED] pom.xml
  - groovy.version: 2.5.14 → 3.0.8
  - spock.version: 1.3-groovy-2.5 → 2.0-groovy-3.0
  
[UPDATED] Dependencies:
  ✓ org.spockframework:spock-core: 1.3-groovy-2.5 → 2.0-groovy-3.0
  ✓ org.spockframework:spock-spring: 1.3-groovy-2.5 → 2.0-groovy-3.0

[SUCCESS] Groovy/Spock Migration Complete

Next Steps:
1. Recompile project: mvn clean compile
2. Run test suite: mvn test
3. Review any test failures

Validation Checklist:
☐ All Groovy files compile successfully
☐ All Spock tests run without ClassNotFoundException
☐ Test results match pre-migration results
☐ Check for Spock 2.0 breaking changes in failed tests

Manual Review Required: NO (unless tests fail)

Spock 2.0 Breaking Changes (if tests fail):
- @Unroll now requires explicit test names
- Some power assertion improvements may change error messages
- Refer to: https://spockframework.org/spock/docs/2.0/migration_guide.html
```

**Verify Spock compatibility**:
```bash
# Run tests after migration
mvn test

# Check for compatibility issues
# Before migration - Groovy 2.5 + Spock 1.x
# After migration - Groovy 3.0 + Spock 2.0
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

## Implementation Guide for Automated Migration

The automated Spring Boot 2.4 to 2.5 migrator has been **fully implemented** and is available in the codebase.

### Usage

**Command-line execution:**

```bash
# Dry-run mode (preview changes without modifying files)
java com.raditha.spring.SpringBoot24to25Migrator --dry-run --project-path /path/to/project

# Execute migration (modifies files)
java com.raditha.spring.SpringBoot24to25Migrator --project-path /path/to/project
```

**Programmatic usage:**

```java
SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(false);
MigrationResult result = migrator.migrateAll();
migrator.printReport();
```

### Implementation Structure

The migrator is implemented following the established pattern from `SpringBoot23to24Migrator`:

```java
package com.raditha.spring;

/**
 * Main orchestrator for Spring Boot 2.4 to 2.5 migration.
 * 
 * Coordinates all migration phases including:
 * - POM dependency updates
 * - SQL script property transformations
 * - Actuator endpoint configuration
 * - Cassandra throttling configuration
 * - Groovy/Spock version upgrades
 * - Deprecated code detection
 * - Validation and reporting
 */
public class SpringBoot24to25Migrator extends AbstractSpringBootMigrator {
    
    // Migration components
    private PomMigrator24to25 pomMigrator;
    private SqlScriptPropertiesMigrator sqlScriptMigrator;
    private ActuatorInfoMigrator actuatorMigrator;
    private CassandraThrottlingMigrator cassandraMigrator;
    private GroovySpockMigrator groovyMigrator;
    private DeprecatedCodeDetector deprecatedCodeDetector;
    
    @Override
    protected void initializeComponents() throws IOException {
        this.pomMigrator = new PomMigrator24to25(dryRun);
        this.sqlScriptMigrator = new SqlScriptPropertiesMigrator(dryRun);
        this.actuatorMigrator = new ActuatorInfoMigrator(dryRun);
        this.cassandraMigrator = new CassandraThrottlingMigrator(dryRun);
        this.groovyMigrator = new GroovySpockMigrator(dryRun);
        this.deprecatedCodeDetector = new DeprecatedCodeDetector(dryRun);
        this.validator = new MigrationValidator(dryRun);
    }
    
    @Override
    protected MigrationPhaseResult migratePom() throws Exception {
        return pomMigrator.migrate();
    }
    
    @Override
    protected MigrationPhaseResult migrateProperties() {
        return sqlScriptMigrator.migrate();
    }
    
    @Override
    protected void executeVersionSpecificMigrations() throws Exception {
        // Phase 3: Configuration Migrations
        MigrationPhaseResult actuatorResult = actuatorMigrator.migrate();
        result.addPhase("Actuator /info Endpoint", actuatorResult);
        
        MigrationPhaseResult cassandraResult = cassandraMigrator.migrate();
        result.addPhase("Cassandra Throttling", cassandraResult);
        
        MigrationPhaseResult groovyResult = groovyMigrator.migrate();
        result.addPhase("Groovy/Spock Upgrade", groovyResult);
        
        // Phase 4: Detection
        MigrationPhaseResult deprecatedResult = deprecatedCodeDetector.migrate();
        result.addPhase("Deprecated Code Detection", deprecatedResult);
    }
    
    @Override
    protected String getSourceVersion() { return "2.4"; }
    
    @Override
    protected String getTargetVersion() { return "2.5"; }
}
```

### Component Implementation Details

#### 1. SqlScriptPropertiesMigrator

```java
/**
 * Migrates SQL script initialization properties from spring.datasource.* to spring.sql.init.*
 * 
 * Automation Level: FULL
 * Complexity: MEDIUM
 * Confidence: 95%
 */
public class SqlScriptPropertiesMigrator extends AbstractPropertyFileMigrator {
    
    private static final Map<String, String> PROPERTY_MAPPINGS = Map.of(
        "spring.datasource.initialization-mode", "spring.sql.init.mode",
        "spring.datasource.schema", "spring.sql.init.schema-locations",
        "spring.datasource.data", "spring.sql.init.data-locations",
        "spring.datasource.platform", "spring.sql.init.platform",
        "spring.datasource.continue-on-error", "spring.sql.init.continue-on-error",
        "spring.datasource.separator", "spring.sql.init.separator",
        "spring.datasource.sql-script-encoding", "spring.sql.init.encoding"
    );
    
    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();
        
        // 1. Find and transform YAML files
        transformYamlProperties(result);
        
        // 2. Find and transform .properties files
        transformPropertiesFiles(result);
        
        // 3. Check if defer-datasource-initialization needed
        addDeferPropertyIfNeeded(result);
        
        return result;
    }
}
```

#### 2. ActuatorInfoMigrator

```java
/**
 * Configures /info actuator endpoint exposure and Spring Security rules
 * 
 * Automation Level: GUIDED
 * Complexity: MEDIUM
 * Confidence: 70% (requires security review)
 */
public class ActuatorInfoMigrator extends AbstractConfigMigrator {
    
    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();
        
        // 1. Detect /info endpoint usage
        if (!detectInfoEndpointUsage()) {
            result.addNote("No /info endpoint usage detected");
            return result;
        }
        
        // 2. Add actuator exposure configuration
        addActuatorExposureConfig(result);
        
        // 3. Handle Spring Security configuration
        if (hasSpringSecurityDependency()) {
            generateOrModifySecurityConfig(result);
            result.setManualReviewRequired(true);
            result.addWarning("Spring Security configuration modified - manual review required");
        }
        
        return result;
    }
}
```

#### 3. CassandraThrottlingMigrator

```java
/**
 * Adds Cassandra request throttling configuration since defaults were removed
 * 
 * Automation Level: CONDITIONAL
 * Complexity: LOW
 * Confidence: 90%
 */
public class CassandraThrottlingMigrator extends AbstractConfigMigrator {
    
    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();
        
        // 1. Check for Cassandra dependency
        if (!hasCassandraDependency()) {
            return result;
        }
        
        // 2. Check if throttling already configured
        if (hasExistingThrottlingConfig()) {
            result.addNote("Throttling already configured - keeping existing settings");
            return result;
        }
        
        // 3. Add recommended throttling configuration
        addRecommendedThrottlingConfig(result);
        result.addNote("Consider tuning throttling values based on your load");
        
        return result;
    }
}
```

#### 4. GroovySpockMigrator

```java
/**
 * Upgrades Groovy to 3.x and Spock to 2.0+
 * 
 * Automation Level: FULL
 * Complexity: LOW
 * Confidence: 90%
 */
public class GroovySpockMigrator extends AbstractPomMigrator {
    
    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();
        
        // 1. Detect Spock usage
        if (!hasSpockDependency()) {
            return result;
        }
        
        // 2. Check Spock version
        String currentVersion = getSpockVersion();
        if (isSpock2OrHigher(currentVersion)) {
            result.addNote("Spock 2.0+ already configured");
            return result;
        }
        
        // 3. Upgrade Groovy and Spock versions
        updateGroovyVersion("3.0.8", result);
        updateSpockVersion("2.0-groovy-3.0", result);
        
        // 4. Flag for test execution
        result.addNote("Run tests to verify Spock 2.0 compatibility");
        
        return result;
    }
}
```

### Migration Execution Flow

```
1. Initialize
   └─ Load configuration
   └─ Pre-process source files (AST parsing)
   └─ Initialize migration components

2. Phase 1: POM Migration
   └─ Update Spring Boot parent version to 2.5.15
   └─ Update Groovy/Spock versions if needed

3. Phase 2: Property Migration
   └─ Transform SQL script properties
   └─ Add JPA defer property if needed
   └─ Add actuator exposure configuration

4. Phase 3: Configuration Migration
   └─ Configure Cassandra throttling
   └─ Generate/modify Spring Security config
   
5. Phase 4: Detection
   └─ Detect deprecated code usage
   └─ Generate deprecation report

6. Phase 5: Validation
   └─ Compile project (mvn compile)
   └─ Run tests (mvn test)
   └─ Generate migration report

7. Report Generation
   └─ Summary of changes
   └─ Manual review items
   └─ Validation checklist
```

### Testing Strategy

```java
@Test
public void testSqlScriptPropertiesMigration() {
    // Given
    createTestProject("2.4");
    addPropertyFile("spring.datasource.initialization-mode=always");
    
    // When
    SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(false);
    MigrationResult result = migrator.migrateAll();
    
    // Then
    assertTrue(result.isSuccessful());
    assertPropertyExists("spring.sql.init.mode=always");
    assertPropertyNotExists("spring.datasource.initialization-mode");
}

@Test
public void testActuatorInfoMigrationWithSecurity() {
    // Given
    createTestProject("2.4");
    addDependency("spring-boot-starter-security");
    addInfoEndpointUsage();
    
    // When
    MigrationResult result = new SpringBoot24to25Migrator(false).migrateAll();
    
    // Then
    assertTrue(result.requiresManualReview());
    assertTrue(findSecurityConfig().contains("permitAll()"));
    assertPropertyExists("management.endpoints.web.exposure.include=info");
}
```

### Key Implementation Considerations

1. **Property File Handling**
   - Support both YAML and .properties formats
   - Preserve comments and formatting where possible
   - Handle profile-specific files (application-dev.yml, etc.)

2. **AST Modifications**
   - Use JavaParser for Java code transformations
   - Use SnakeYAML for YAML transformations
   - Preserve code formatting with lexical preservation

3. **Dependency Detection**
   - Parse pom.xml with Maven model
   - Check for transitive dependencies
   - Handle both Spring Boot starters and explicit dependencies

4. **Validation**
   - Compile after each transformation
   - Run tests to detect breaking changes
   - Generate detailed reports for manual review items

5. **Error Handling**
   - Continue migration on non-critical errors
   - Collect all errors for final report
   - Provide rollback capability (dry-run mode)

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
