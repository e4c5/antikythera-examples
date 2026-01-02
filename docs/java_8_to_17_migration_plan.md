# Java 8 to Java 17 Migration Plan for Spring Boot Applications

## Executive Summary

Migrating from Java 8 to Java 17 is a **multi-step process** that requires upgrading Spring Boot first, as **Spring Boot 2.1.3 does not support Java 17**. This guide focuses on **breaking changes** that will cause compilation or runtime failures, not optional syntactic improvements.

> [!CAUTION]
> **Critical Prerequisite**: Spring Boot 2.5+ is required for Java 17 support. You cannot simply change `java.version` to 17 with Spring Boot 2.1.3.

---

## Table of Contents

1. [Java Version Support Matrix](#java-version-support-matrix)
2. [Migration Strategy](#migration-strategy)
3. [Phase 1: Spring Boot Upgrade](#phase-1-spring-boot-upgrade)
4. [Phase 2: Java 17 Breaking Changes](#phase-2-java-17-breaking-changes)
5. [Phase 3: Dependency Updates](#phase-3-dependency-updates)
6. [Phase 4: Code Fixes](#phase-4-code-fixes)
7. [Testing & Validation](#testing--validation)
8. [Automation Opportunities](#automation-opportunities)
9. [Troubleshooting](#troubleshooting)

---

## Java Version Support Matrix

### Spring Boot Version Requirements

| Spring Boot Version | Java 8 | Java 11 | Java 17 | Notes |
|---------------------|--------|---------|---------|-------|
| 2.1.x | ✅ | ✅ | ❌ | Current version |
| 2.2.x | ✅ | ✅ | ❌ | |
| 2.3.x | ✅ | ✅ | ❌ | |
| 2.4.x | ✅ | ✅ | ❌ | |
| **2.5.x** | ✅ | ✅ | **✅** | Minimum for Java 17 |
| 2.6.x | ❌ | ✅ | ✅ | Java 8 dropped |
| 2.7.x | ❌ | ✅ | ✅ | |
| 3.0.x | ❌ | ❌ | ✅ | Jakarta EE, requires Java 17+ |

> [!IMPORTANT]
> **Recommended Target**: Spring Boot **2.7.x** with Java 17
> - Maintains `javax.*` namespace (no Jakarta EE migration needed yet)
> - Full Java 17 support
> - Long-term support until November 2023

---

## Migration Strategy

### Phased Approach (Recommended)

```
Current State: Spring Boot 2.1.3 + Java 8
                    ↓
Phase 1: Spring Boot 2.1.3 → 2.5.15 (Java 8)
                    ↓
Phase 2: Spring Boot 2.5.15 → 2.7.18 (Java 8)
                    ↓
Phase 3: Java 8 → Java 11 (Spring Boot 2.7.18)
                    ↓
Phase 4: Java 11 → Java 17 (Spring Boot 2.7.18)
                    ↓
Target State: Spring Boot 2.7.18 + Java 17
```

**Why this approach?**
- Isolates Spring Boot changes from Java changes
- Allows testing at each stage
- Reduces risk by breaking into manageable steps
- Java 11 is an intermediate LTS version (validation checkpoint)

### Alternative: Direct Jump (Higher Risk)

```
Current: Spring Boot 2.1.3 + Java 8
              ↓ (Big Bang)
Target: Spring Boot 2.7.18 + Java 17
```

**Only recommended if**:
- Small codebase (< 50k LOC)
- Comprehensive test coverage (> 80%)
- Dedicated migration sprint available
- Aggressive timeline

---

## Phase 1: Spring Boot Upgrade

### Step 1.1: Upgrade to Spring Boot 2.5.15

**Why 2.5.15?**
- First version with Java 17 support
- Still supports Java 8 (allows testing before Java upgrade)
- Introduces breaking changes that must be addressed

**Key Changes in 2.1 → 2.5**:
- Validation starter removal (2.3)
- Cassandra driver v4 (2.3)
- Elasticsearch 7.6+ (2.3)
- SQL script handling changes (2.5)
- Actuator endpoint changes (2.3+)

**Refer to existing migration guides**:
- [Spring Boot 2.1 to 2.2 Migration](spring_boot_2.1_to_2.2_migration.md)
- [Spring Boot 2.2 to 2.3 Migration](spring_boot_2.2_to_2.3_migration.md)
- Spring Boot 2.3 to 2.4 (create if needed)
- Spring Boot 2.4 to 2.5 (create if needed)

### Step 1.2: Upgrade to Spring Boot 2.7.18 (Recommended)

**Why 2.7.18?**
- Maintained `javax.*` namespace (no Jakarta migration)
- Production-ready for Java 17
- Extended support
- Final 2.x release series

**pom.xml update**:
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>  <!-- Latest 2.7.x -->
    <relativePath/>
</parent>
```

---

## Phase 2: Java 17 Breaking Changes

### Critical Breaking Changes

#### 1. Removed Java EE Modules (Java 9+)

**Issue**: Java EE modules moved to separate dependencies

**Affected APIs**:
- **JAXB** (`javax.xml.bind.*`)
- **JAX-WS** (`javax.xml.ws.*`)
- **JAXR** (`javax.xml.registry.*`)
- **JAF** (`javax.activation.*`)
- **Common Annotations** (`javax.annotation.*`)

**Symptoms**:
```
java.lang.ClassNotFoundException: javax.xml.bind.JAXBException
java.lang.NoClassDefFoundError: javax/annotation/Generated
```

**Solution**: Add explicit dependencies

```xml
<!-- JAXB (if using XML marshalling) -->
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.1</version>
</dependency>

<!-- Common Annotations (if using @Generated, @PostConstruct, @PreDestroy) -->
<dependency>
    <groupId>javax.annotation</groupId>
    <artifactId>javax.annotation-api</artifactId>
    <version>1.3.2</version>
</dependency>
```

**Detection**:
```bash
# Scan for JAXB usage
grep -r "import javax.xml.bind" src/

# Scan for annotations
grep -r "@Generated\|@PostConstruct\|@PreDestroy" src/
```

#### 2. Removed Deprecated APIs

**Java 9 Removals**:
- `sun.misc.BASE64Encoder/Decoder` → Use `java.util.Base64`

**Before**:
```java
import sun.misc.BASE64Encoder;
import sun.misc.BASE64Decoder;

BASE64Encoder encoder = new BASE64Encoder();
String encoded = encoder.encode(data);
```

**After**:
```java
import java.util.Base64;

String encoded = Base64.getEncoder().encodeToString(data);
byte[] decoded = Base64.getDecoder().decode(encoded);
```

#### 3. Strong Encapsulation (Java 16+)

**Issue**: Internal JDK APIs are no longer accessible

**Commonly affected**:
- `sun.misc.Unsafe`
- `com.sun.management.*`
- Internal reflection APIs

**Symptoms**:
```
InaccessibleObjectException: Unable to make field private accessible
```

**Solutions**:

**Option 1: Add JVM flags** (temporary workaround)
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            --add-opens java.base/java.lang=ALL-UNNAMED
            --add-opens java.base/java.util=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```

**Option 2: Update libraries** that use internal APIs (preferred)

#### 4. Reflection API Changes

**Issue**: Stricter reflection access controls

**Code that may break**:
```java
// May fail in Java 17
Field field = MyClass.class.getDeclaredField("privateField");
field.setAccessible(true);  // May throw InaccessibleObjectException
Object value = field.get(instance);
```

**Solutions**:
1. **Use public APIs** instead of reflection
2. **Add module opens** if reflection is unavoidable
3. **Update libraries** (Hibernate, Jackson, etc. have Java 17 compatible versions)

#### 5. SecurityManager Deprecation (Java 17)

**Issue**: `SecurityManager` deprecated for removal

```java
// Deprecated in Java 17
System.setSecurityManager(new SecurityManager());
```

**Impact**: Low for most Spring Boot apps (rarely used)

**Action**: Remove SecurityManager usage if present

---

## Phase 3: Dependency Updates

### Critical Dependency Version Requirements

Based on the application's dependencies, the following upgrades are **required** for Java 17:

#### Core Dependencies

| Dependency | Java 8 Version | Java 17 Compatible | Notes |
|------------|----------------|-------------------|-------|
| **Lombok** | 1.18.20 | **1.18.24+** | CRITICAL - earlier versions fail on Java 17 |
| **Hibernate** | 5.4.x | **5.6.x+** | Hibernate 5.4 has Java 17 issues |
| **MapStruct** | 1.6.3 | **1.5.0+** | Compatible (already OK) |
| **Mockito** | 4.6.1 | **4.0.0+** | Compatible (already OK) |
| **ByteBuddy** | 1.14.2 | **1.12.10+** | Compatible (already OK) |
| **PostgreSQL JDBC** | Managed by Boot | **42.3.0+** | Upgrade via Spring Boot BOM |

#### Critical: Lombok Upgrade

> [!CAUTION]
> **Lombok must be upgraded to 1.18.24+** for Java 17. Earlier versions will cause compilation failures.

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.30</version>  <!-- Java 17 compatible -->
    <scope>provided</scope>
</dependency>
```

**Verification**:
```bash
# After upgrade, clean and rebuild
mvn clean install

# Lombok should generate methods without errors
```

#### Critical: Hibernate Upgrade

**Spring Boot 2.7 uses Hibernate 5.6.x** (Java 17 compatible), but verify:

```xml
<!-- Managed by Spring Boot 2.7, but verify in dependency tree -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
    <!-- Includes Hibernate 5.6.15.Final -->
</dependency>
```

**Check Hibernate version**:
```bash
mvn dependency:tree | grep hibernate-core
```

Expected output:
```
[INFO] +- org.hibernate:hibernate-core:jar:5.6.15.Final:compile
```

#### Third-Party Libraries

**Springfox** (if used):
- Springfox 2.x: ❌ Not compatible with Java 17
- **Solution**: Migrate to SpringDoc OpenAPI

```xml
<!-- Remove Springfox -->
<!--<dependency>
    <groupId>io.springfox</groupId>
    <artifactId>springfox-swagger-ui</artifactId>
    <version>2.7.0</version>
</dependency>-->

<!-- Add SpringDoc -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.7.0</version>
</dependency>
```

**Guava** (if used for reflection):
- Guava 19.0: ❌ Has Java 17 reflection issues
- **Solution**: Upgrade to Guava 31.0+

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>31.1-jre</version>  <!-- Java 17 compatible -->
</dependency>
```

---

## Phase 4: Code Fixes

### Automated Detection Patterns

#### Pattern 1: BASE64 Usage

**Detection**:
```bash
grep -rn "sun.misc.BASE64" src/
```

**Automated replacement**:
```java
// Find and replace:
import sun.misc.BASE64Encoder;
import sun.misc.BASE64Decoder;

BASE64Encoder encoder = new BASE64Encoder();
String encoded = encoder.encode(bytes);

// With:
import java.util.Base64;

String encoded = Base64.getEncoder().encodeToString(bytes);
```

#### Pattern 2: JAXB Annotations

**Detection**:
```bash
grep -rn "@XmlRootElement\|@XmlElement\|JAXBContext" src/
```

**Action**: Verify JAXB dependencies added (see Phase 2.1)

#### Pattern 3: @PostConstruct / @PreDestroy

**Detection**:
```bash
grep -rn "@PostConstruct\|@PreDestroy" src/
```

**Action**: Add `javax.annotation-api` dependency

#### Pattern 4: Unsafe Usage

**Detection**:
```bash
grep -rn "sun.misc.Unsafe" src/
```

**Action**:
- Check if library dependency (update library)
- If custom code, refactor to use safe APIs

### Manual Code Review Areas

#### 1. JVM Version Checks

**Bad**:
```java
String javaVersion = System.getProperty("java.version");
if (javaVersion.startsWith("1.8")) {
    // Java 8 specific code
}
```

**Good**:
```java
int javaVersion = Runtime.version().feature();
if (javaVersion >= 11) {
    // Code for Java 11+
}
```

#### 2. Date/Time Parsing

**Stricter parsing in Java 17**:
```java
// May fail in Java 17 if format doesn't match exactly
DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
LocalDate.parse("2024-1-1", formatter);  // Fails (use "2024-01-01")
```

**Solution**: Use `DateTimeFormatterBuilder` with lenient parsing if needed

#### 3. Charset Handling

**Default charset changes**:
```java
// Risky - default charset may differ
String str = new String(bytes);

// Safe - explicit charset
String str = new String(bytes, StandardCharsets.UTF_8);
```

---

## Testing & Validation

### Phase-by-Phase Testing

#### After Spring Boot Upgrade (Java 8)

```bash
# Build with Java 8
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk
mvn clean install

# Run all tests
mvn test

# Run integration tests
mvn verify
```

#### After Java 11 Upgrade

```bash
# Build with Java 11
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk
mvn clean install -DskipTests

# Run tests (may reveal issues)
mvn test

# Check for warnings
mvn test 2>&1 | grep -i "WARNING\|illegal"
```

#### After Java 17 Upgrade

```bash
# Update pom.xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>

# Build with Java 17
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
mvn clean install

# Look for specific errors
mvn clean compile 2>&1 | grep -E "ClassNotFoundException|NoClassDefFoundError|InaccessibleObjectException"
```

### Validation Checklist

- [ ] Application starts successfully
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Database connectivity works (Hibernate with Java 17)
- [ ] Kafka publishing/consuming works
- [ ] Redis caching works
- [ ] REST endpoints respond correctly
- [ ] WebSocket connections work
- [ ] Scheduled tasks execute
- [ ] JMX metrics available (if enabled)
- [ ] Logging configuration works (Log4j2)
- [ ] No `IllegalAccessError` or `InaccessibleObjectException` in logs
- [ ] Performance benchmarks comparable to Java 8

---

## Automation Opportunities

### Automated Migration Tool

Similar to Spring Boot migrators, create a **Java 17 Migration Tool**:

```java
public class Java17Migrator {
    
    /**
     * Detect and fix BASE64 usage
     */
    public void migrateBase64() {
        // Find: import sun.misc.BASE64Encoder
        // Replace: import java.util.Base64
        
        // Find: new BASE64Encoder().encode(data)
        // Replace: Base64.getEncoder().encodeToString(data)
    }
    
    /**
     * Detect missing JAXB dependencies
     */
    public void detectJaxbUsage() {
        // Scan for @XmlRootElement, JAXBContext
        // Check if javax.xml.bind dependency present
        // Report: "Add JAXB dependencies for Java 17"
    }
    
    /**
     * Detect Lombok version
     */
    public void validateLombokVersion() {
        // Check pom.xml for lombok version
        // If < 1.18.24, flag as critical upgrade
    }
    
    /**
     * Add --add-opens flags if needed
     */
    public void generateJvmFlags() {
        // Detect reflection usage
        // Generate suggested --add-opens flags
        // Add to maven-surefire-plugin configuration
    }
}
```

### Detection Patterns (generator.yml)

```yaml
java-17-migration:
  detect-removed-apis:
    - pattern: "sun.misc.BASE64"
      replacement: "java.util.Base64"
      severity: "CRITICAL"
    
    - pattern: "javax.xml.bind"
      action: "ADD_DEPENDENCY"
      dependency: "javax.xml.bind:jaxb-api:2.3.1"
      severity: "CRITICAL"
    
    - pattern: "@PostConstruct|@PreDestroy"
      action: "ADD_DEPENDENCY"
      dependency: "javax.annotation:javax.annotation-api:1.3.2"
      severity: "HIGH"
  
  validate-dependencies:
    lombok:
      minimum-version: "1.18.24"
      severity: "CRITICAL"
    
    guava:
      minimum-version: "31.0"
      severity: "HIGH"
    
    hibernate:
      minimum-version: "5.6.0"
      severity: "CRITICAL"
```

---

## Troubleshooting

### Common Issues

#### Issue 1: `ClassNotFoundException: javax.xml.bind.JAXBException`

**Cause**: JAXB removed from Java 11+

**Solution**:
```xml
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.1</version>
</dependency>
```

#### Issue 2: Lombok compilation failures

**Symptom**:
```
error: cannot find symbol
  symbol:   method builder()
  location: class MyClass
```

**Cause**: Lombok version incompatible with Java 17

**Solution**: Upgrade to Lombok 1.18.24+

#### Issue 3: `InaccessibleObjectException`

**Symptom**:
```
java.lang.reflect.InaccessibleObjectException: Unable to make field private java.lang.String accessible
```

**Cause**: Strong encapsulation in Java 16+

**Temporary Fix** (for testing):
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            --add-opens java.base/java.lang=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```

**Permanent Fix**: Update the library causing the issue

#### Issue 4: Hibernate `java.lang.IllegalArgumentException`

**Symptom**:
```
java.lang.IllegalArgumentException: Can not set java.lang.String field to java.lang.Object
```

**Cause**: Hibernate 5.4 reflection issues with Java 17

**Solution**: Spring Boot 2.7 uses Hibernate 5.6 (compatible)

#### Issue 5: Performance Regression

**Symptom**: Application slower on Java 17

**Investigation**:
1. Check GC logs (G1GC is default in Java 17)
2. Compare heap settings
3. Check for increased reflection usage warnings

**JVM Tuning** (if needed):
```bash
# Java 8 tuning may need adjustment for Java 17
-XX:+UseG1GC
-XX:MaxRAMPercentage=75.0
-XX:InitialRAMPercentage=50.0
```

---

## Summary Migration Checklist

### Prerequisites
- [ ] Review and understand all breaking changes
- [ ] Create feature branch for migration
- [ ] Ensure comprehensive test coverage
- [ ] Set up Java 11 and Java 17 environments

### Phase 1: Spring Boot Upgrade (on Java 8)
- [ ] Upgrade Spring Boot 2.1 → 2.2
- [ ] Upgrade Spring Boot 2.2 → 2.3
- [ ] Upgrade Spring Boot 2.3 → 2.5
- [ ] Upgrade Spring Boot 2.5 → 2.7
- [ ] Verify all tests pass on Java 8

### Phase 2: Java 11 (Checkpoint)
- [ ] Update `java.version` to 11 in pom.xml
- [ ] Build with Java 11
- [ ] Fix any Java 11 specific issues
- [ ] Run full test suite
- [ ] Perform smoke testing

### Phase 3: Java 17 Upgrade
- [ ] Update `java.version` to 17 in pom.xml
- [ ] Upgrade Lombok to 1.18.24+
- [ ] Add JAXB dependencies (if needed)
- [ ] Add javax.annotation-api (if needed)
- [ ] Upgrade Guava (if used)
- [ ] Update Springfox to SpringDoc (if needed)

### Phase 4: Code Fixes
- [ ] Replace `sun.misc.BASE64` usage
- [ ] Fix reflection access issues
- [ ] Update JVM version checks
- [ ] Review deprecated API usage

### Phase 5: Testing
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Performance testing
- [ ] Load testing (if applicable)
- [ ] Security scanning

### Phase 6: Deployment
- [ ] Update CI/CD pipelines for Java 17
- [ ] Update Dockerfile (if using containers)
- [ ] Deploy to test environment
- [ ] Deploy to staging
- [ ] Monitor for issues
- [ ] Deploy to production

---

## Estimated Timeline

| Phase | Estimated Duration | Risk Level |
|-------|-------------------|------------|
| Spring Boot 2.1 → 2.7 | 2-4 weeks | HIGH |
| Java 8 → 11 | 1 week | MEDIUM |
| Java 11 → 17 | 1-2 weeks | MEDIUM |
| Testing & Validation | 1-2 weeks | - |
| **Total** | **5-9 weeks** | - |

**Factors affecting timeline**:
- Application size and complexity
- Test coverage quality
- Number of custom internal libraries
- Team familiarity with newer Java versions
- Availability of staging/test environments

---

## Conclusion

Migrating from Java 8 to Java 17 for a Spring Boot 2.1.3 application is a **significant undertaking** that requires:

1. **Spring Boot upgrade first** (2.1 → 2.7)
2. **Incremental Java version upgrades** (8 → 11 → 17)
3. **Critical dependency updates** (Lombok, Hibernate, third-party libraries)
4. **Code fixes** for removed APIs and stricter encapsulation

**Success Factors**:
- Comprehensive test coverage
- Phased approach with validation at each step
- Dedicated team and timeline
- Stakeholder buy-in for the effort required

**Benefits of Java 17**:
- Latest LTS with long-term support
- Performance improvements (30-40% faster than Java 8)
- Modern language features (records, sealed classes, pattern matching)
- Security updates
- Better container awareness
- Foundation for future Spring Boot 3.x migration
