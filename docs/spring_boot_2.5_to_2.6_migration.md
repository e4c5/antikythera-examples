# Spring Boot 2.5 to 2.6 Migration Guide

## Overview

This comprehensive guide covers all changes required to migrate from Spring Boot 2.5 to Spring Boot 2.6. This release introduced significant changes including **circular references prohibited by default**, PathPattern-based path matching for Spring MVC, and various dependency upgrades.

> [!CAUTION]
> **PREREQUISITE**: Before upgrading to Spring Boot 2.6, you must eliminate all circular dependencies in your application. See [circular_dependency_elimination.md](circular_dependency_elimination.md) for the standalone tool.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Dependency Upgrades](#dependency-upgrades)
3. [Breaking Changes](#breaking-changes)
4. [Circular References Prohibited](#circular-references-prohibited)
5. [PathPattern Based Path Matching](#pathpattern-based-path-matching)
6. [Actuator Changes](#actuator-changes)
7. [Deprecated Code Removal](#deprecated-code-removal)
8. [New Features](#new-features)
9. [Migration Checklist](#migration-checklist)
10. [Troubleshooting](#troubleshooting)
11. [References](#references)

---

## Automation Summary

| Component | Automation Level | Complexity | Manual Review |
|-----------|-----------------|------------|---------------|
| **Circular Dependencies** | Full Automation | HIGH | After fix |
| **PathPattern Migration** | Detection + Guided | MEDIUM | YES |
| **POM Version Update** | Full Automation | LOW | NO |
| **Actuator Env InfoContributor** | Full Automation | LOW | NO |
| **Deprecated Code Detection** | Detection Only | MEDIUM | YES |
| **Embedded Mongo Version** | Detection + Guided | LOW | YES |

---

## Executive Summary

### Key Changes at a Glance

| Category | Major Changes |
|----------|---------------|
| **Spring Framework** | 5.3.x → 5.3.13+ |
| **Spring Security** | 5.5.x → 5.6.x |
| **Spring Data** | 2021.0 → 2021.1 |
| **Spring Kafka** | 2.7.x → 2.8.x (Kafka 3.0) |
| **Circular References** | **PROHIBITED BY DEFAULT** |
| **Path Matching** | AntPathMatcher → PathPatternParser |
| **Actuator Env Info** | Disabled by default |
| **Deprecation Removal** | Code deprecated in 2.4 removed |

### Migration Complexity

- **Critical (Must Address First)**: Circular dependencies (see separate guide)
- **Critical (Breaking)**: PathPattern matching if using custom path patterns
- **High Priority**: Deprecated code removal, actuator configuration
- **Medium Priority**: Embedded Mongo version, WebFlux session properties
- **Low Priority**: New feature adoption

---

## Dependency Upgrades

### 2.1 Update Spring Boot Version

**pom.xml:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.6.15</version> <!-- Latest 2.6.x release -->
    <relativePath/>
</parent>
```

### 2.2 Major Dependency Version Changes

| Dependency | Spring Boot 2.5 | Spring Boot 2.6 | Notes |
|-----------|-----------------|-----------------|-------|
| Spring Framework | 5.3.x | 5.3.13+ | Performance improvements |
| Spring Security | 5.5.x | 5.6.x | OAuth2 enhancements |
| Spring Data | 2021.0 | 2021.1 | Bug fixes |
| Spring HATEOAS | 1.3.x | 1.4.x | New features |
| Spring Kafka | 2.7.x | **2.8.x** | **Kafka 3.0 upgrade** |
| Spring AMQP | 2.3.x | 2.4.x | Improvements |
| Spring Session | 2021.0 | 2021.1 | Bug fixes |
| Apache Kafka | 2.8.x | **3.0.x** | **Breaking changes** |
| Micrometer | 1.7.x | 1.8.x | New metrics |

### 2.3 Dependency Management Removals

The following dependencies are no longer managed by Spring Boot:
- `com.nimbusds:oauth2-oidc-sdk`
- `com.nimbusds:nimbus-jose-jwt`
- `org.webjars:hal-browser`

**If using these, add explicit version:**
```xml
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.25.6</version> <!-- Or rely on Spring Security's version -->
</dependency>
```

---

## Breaking Changes

### 3.1 Summary of Breaking Changes

| Change | Impact | Migration Effort |
|--------|--------|------------------|
| Circular references prohibited | App won't start | HIGH (use separate tool) |
| PathPattern default | Path matching may break | MEDIUM |
| Actuator env info disabled | /info won't show env | LOW |
| Deprecated code removed | Compilation errors | MEDIUM |
| Embedded Mongo version required | Tests may fail | LOW |

---

## Circular References Prohibited

> [!CAUTION]
> This is the most impactful change in Spring Boot 2.6. **Resolve this BEFORE upgrading.**

### What Changed

- Circular dependencies between beans are now **prohibited by default**
- Applications with cycles will fail to start with `BeanCurrentlyInCreationException`

### Migration Strategy

**Step 1: Run Circular Dependency Tool (BEFORE upgrading)**

See [circular_dependency_elimination.md](circular_dependency_elimination.md) for detailed instructions.

```bash
java -jar antikythera-examples.jar cycle-detector \
  --config cycle-detector.yml \
  --dry-run
```

**Step 2: Fix detected cycles**

```bash
java -jar antikythera-examples.jar cycle-detector \
  --config cycle-detector.yml
```

**Step 3: Verify and commit changes**

**Step 4: THEN upgrade to Spring Boot 2.6**

### Temporary Workaround (NOT RECOMMENDED)

If you must upgrade immediately and fix cycles later:

```yaml
spring:
  main:
    allow-circular-references: true  # Re-enables old behavior
```

> [!WARNING]
> This is a **temporary** workaround. Plan to eliminate cycles and remove this property.

---

## PathPattern Based Path Matching

### What Changed

Spring MVC now uses `PathPatternParser` instead of `AntPathMatcher` by default.

### Key Differences

| Aspect | AntPathMatcher (2.5) | PathPatternParser (2.6) |
|--------|---------------------|------------------------|
| Leading slash | Optional | **Required** |
| Pattern syntax | Flexible | Stricter |
| Performance | Slower | Faster |
| Actuator endpoints | AntPathMatcher | **PathPattern (not configurable)** |

### Common Issues

**Issue 1: Missing leading slash**
```java
// BEFORE (worked in 2.5)
.mvcMatchers("hello").permitAll()

// AFTER (required in 2.6)
.mvcMatchers("/hello").permitAll()
```

**Issue 2: Springfox compatibility**

Springfox uses path patterns incompatible with `PathPatternParser`. Either:
1. Migrate to SpringDoc OpenAPI (recommended)
2. Revert to AntPathMatcher

### Revert to AntPathMatcher

If you have extensive custom path patterns:

```yaml
spring:
  mvc:
    pathmatch:
      matching-strategy: ant-path-matcher
```

### Automated Detection

```java
// Pattern to detect in SecurityConfig
// Search for: mvcMatchers, antMatchers without leading /
.mvcMatchers("path")  // ← Missing leading /
.antMatchers("path")  // ← Missing leading /
```

**Transformation:**
```java
// Add leading slash
.mvcMatchers("/path")
.antMatchers("/path")
```

### Actuator Endpoint Path Matching

Actuator endpoints **always** use `PathPattern` matching (not configurable). This may affect:
- Custom actuator endpoint paths
- Security configurations for actuator endpoints

---

## Actuator Changes

### 5.1 Env InfoContributor Disabled

**What Changed:**
The `/info` endpoint no longer shows environment properties by default.

**Before (2.5):** Environment info shown automatically
**After (2.6):** Must explicitly enable

**Re-enable:**
```yaml
management:
  info:
    env:
      enabled: true
```

### 5.2 Automated Migration

```yaml
# Add to application.yml if /info endpoint is used
management:
  info:
    env:
      enabled: true  # AUTO-ADDED: Explicitly enable env info (disabled by default in 2.6)
```

---

## Deprecated Code Removal

### 6.1 What Was Removed

All classes, methods, and properties deprecated in **Spring Boot 2.4** have been removed.

### 6.2 Detection Strategy

**Step 1: Compile with deprecation warnings on Spring Boot 2.5**

```bash
mvn clean compile -Dmaven.compiler.showDeprecation=true
```

**Step 2: Fix all deprecation warnings BEFORE upgrading**

### 6.3 Common Deprecations Removed

| Deprecated (2.4) | Replacement (2.6) |
|-----------------|-------------------|
| Various metrics exporters | Updated configuration names |
| Older JDBC pool properties | HikariCP-specific properties |
| Legacy session properties | Updated session configuration |

---

## New Features

### 7.1 SameSite Cookie Attribute

Spring Boot 2.6 adds support for the `SameSite` cookie attribute:

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: strict  # or 'lax', 'none'
```

### 7.2 Docker Image Building Enhancements

- Support for ARM architectures (Apple Silicon)
- Cache volume configuration
- Network configuration for builds

### 7.3 Health Group Membership Validation

Invalid health group configurations now fail fast at startup.

### 7.4 WebFlux Session Properties

```yaml
# Deprecated (still works)
spring:
  webflux:
    session:
      timeout: 30m

# New location (recommended)
server:
  reactive:
    session:
      timeout: 30m
```

---

## Migration Checklist

### Pre-Migration (On Spring Boot 2.5)

- [ ] **Run circular dependency tool** (see separate guide)
- [ ] Fix all circular dependencies
- [ ] Compile with `-Xlint:deprecation` and fix warnings
- [ ] Document current `/info` endpoint usage
- [ ] Check for Springfox usage (incompatible with PathPattern)
- [ ] Note any custom path patterns in security config

### POM Updates

- [ ] Update Spring Boot parent to 2.6.x
- [ ] Add explicit versions for removed dependency management
- [ ] Set `spring.mongodb.embedded.version` if using embedded Mongo

### Configuration Updates

- [ ] Add `management.info.env.enabled=true` if `/info` needed
- [ ] Add `spring.mvc.pathmatch.matching-strategy=ant-path-matcher` if needed
- [ ] Migrate `spring.webflux.session.*` to `server.reactive.session.*`

### Code Updates

- [ ] Add leading slashes to all path patterns in security config
- [ ] Replace deprecated APIs with new ones
- [ ] Consider migrating from Springfox to SpringDoc

### Verification

- [ ] Application starts without `BeanCurrentlyInCreationException`
- [ ] All endpoints accessible (test path matching)
- [ ] `/info` endpoint returns expected data
- [ ] All tests pass
- [ ] No deprecation warnings

---

## Troubleshooting

### BeanCurrentlyInCreationException on Startup

**Cause:** Circular dependencies exist.

**Solution:** Run the circular dependency elimination tool BEFORE upgrading.

```bash
# Revert to 2.5, run tool, then upgrade again
java -jar antikythera-examples.jar cycle-detector --config cycle-detector.yml
```

### 404 Errors for Previously Working Endpoints

**Cause:** PathPattern matching is stricter.

**Solution 1:** Add leading slashes to paths:
```java
.mvcMatchers("/api/users")  // Not "api/users"
```

**Solution 2:** Revert to AntPathMatcher:
```yaml
spring.mvc.pathmatch.matching-strategy: ant-path-matcher
```

### Springfox Swagger UI Not Working

**Cause:** Springfox is incompatible with PathPatternParser.

**Solution 1 (Recommended):** Migrate to SpringDoc:
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.6.14</version>
</dependency>
```

**Solution 2:** Revert to AntPathMatcher (see above).

### /info Endpoint Returns Empty

**Cause:** Env info contributor disabled by default.

**Solution:**
```yaml
management:
  info:
    env:
      enabled: true
```

### Embedded Mongo Tests Failing

**Cause:** Version property now required.

**Solution:**
```yaml
spring:
  mongodb:
    embedded:
      version: 4.0.21  # Match your production version
```

---

## References

- [Spring Boot 2.6 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.6-Release-Notes)
- [Circular References Prohibited](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.6-Release-Notes#circular-references-prohibited-by-default)
- [PathPattern Matching](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc-ann-requestmapping-uri-templates)
- [Migrating from Springfox to SpringDoc](https://springdoc.org/#migrating-from-springfox)
