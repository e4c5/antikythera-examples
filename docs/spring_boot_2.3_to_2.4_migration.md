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

#### Automated Configuration Processing Migration

> [!NOTE]
> This migration requires YAML structure analysis and transformation using Antikythera's property file manipulation patterns.

**Detection Patterns - AST-Based Approach**

**Pattern 1: Detect Legacy Profile Syntax in YAML Files**
```java
// Following pattern from H2ConfigurationMigrator and AbstractPropertyFileMigrator
Path basePath = Paths.get(Settings.getBasePath());
List<Path> yamlFiles = findPropertyFiles(basePath, "*.yml", "*.yaml");

for (Path yamlFile : yamlFiles) {
    Yaml yaml = YamlUtils.createYaml();
    Map<String, Object> data;
    
    try (InputStream input = Files.newInputStream(yamlFile)) {
        data = yaml.load(input);
    }
    
    if (hasLegacyProfileSyntax(data)) {
        filesToMigrate.add(yamlFile);
    }
}

@SuppressWarnings("unchecked")
private boolean hasLegacyProfileSyntax(Map<String, Object> data) {
    // Check for "spring.profiles" key (deprecated in Spring Boot 2.4)
    if (data.containsKey("spring")) {
        Map<String, Object> spring = (Map<String, Object>) data.get("spring");
        if (spring.containsKey("profiles") && spring.get("profiles") instanceof String) {
            return true; // Found legacy syntax
        }
    }
    return false;
}
```

**Pattern 2: Detect Multi-Document YAML Complexity**
```java
// Check for complex multi-document YAML files requiring manual review
Yaml yaml = new Yaml();
Iterable<Object> documents = yaml.loadAll(inputStream);

int documentCount = 0;
boolean hasProfileDocuments = false;

for (Object doc : documents) {
    documentCount++;
    if (doc instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> docMap = (Map<String, Object>) doc;
        if (hasProfileConfiguration(docMap)) {
            hasProfileDocuments = true;
        }
    }
}

if (documentCount > 1 && hasProfileDocuments) {
    // Complex multi-document file - flag for manual review
    result.setRequiresManualReview(true);
    result.addWarning("Multi-document YAML with profiles requires manual review");
}
```

**Automated Transformation Strategy**

**Complexity**: MEDIUM - YAML structure manipulation

**Step 1: Transform Legacy Profile Syntax**
```java
// Following AbstractPropertyFileMigrator pattern for YAML transformation
@SuppressWarnings("unchecked")
private boolean transformYamlData(Map<String, Object> data, 
                                  MigrationPhaseResult result,
                                  String fileName) {
    boolean modified = false;
    
    if (data.containsKey("spring")) {
        Map<String, Object> spring = (Map<String, Object>) data.get("spring");
        
        // Check for old "spring.profiles" syntax
        if (spring.containsKey("profiles") && spring.get("profiles") instanceof String) {
            String profileName = (String) spring.remove("profiles");
            
            // Create new structure: spring.config.activate.on-profile
            if (!spring.containsKey("config")) {
                spring.put("config", new LinkedHashMap<>());
            }
            Map<String, Object> config = (Map<String, Object>) spring.get("config");
            
            if (!config.containsKey("activate")) {
                config.put("activate", new LinkedHashMap<>());
            }
            Map<String, Object> activate = (Map<String, Object>) config.get("activate");
            activate.put("on-profile", profileName);
            
            result.addChange(fileName + ": spring.profiles → spring.config.activate.on-profile");
            modified = true;
        }
    }
    
    return modified;
}
```

**Step 2: Add Legacy Processing Flag (Temporary for Complex Cases)**
```java
// Add temporary workaround flag for complex configurations
private void addLegacyProcessingFlag(Path yamlFile, MigrationPhaseResult result) {
    try {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;
        
        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }
        
        if (data == null) {
            data = new LinkedHashMap<>();
        }
        
        if (!data.containsKey("spring")) {
            data.put("spring", new LinkedHashMap<>());
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> spring = (Map<String, Object>) data.get("spring");
        
        if (!spring.containsKey("config")) {
            spring.put("config", new LinkedHashMap<>());
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) spring.get("config");
        config.put("use-legacy-processing", true);  // Temporary workaround
        
        if (!dryRun) {
            try (OutputStream output = Files.newOutputStream(yamlFile)) {
                yaml.dump(data, new OutputStreamWriter(output));
            }
        }
        
        result.addWarning("Added spring.config.use-legacy-processing=true - requires manual review");
        result.setRequiresManualReview(true);
        
    } catch (Exception e) {
        result.addError("Failed to add legacy processing flag: " + e.getMessage());
    }
}
```

**Validation Strategy**:
- Application starts successfully with all profiles
- Property values resolve correctly in different profiles
- No configuration processing warnings in logs
- Integration tests pass with active profiles: default, dev, prod
- Multi-profile configurations work as expected

**Risk Level**: MEDIUM - Configuration errors can cause runtime failures

**Automation Confidence**: 70% (syntax replacement automated, complex multi-document cases require manual review)

**Recommendation**: 
- Fully automate simple profile syntax replacement
- Flag complex multi-document configurations for manual review
- Add migration report entry listing files requiring attention
- Temporarily add legacy processing flag for complex cases

**Automation Output Example**:
```
[INFO] Configuration File Processing Migration
[INFO] Found 3 YAML files with legacy profile syntax
[INFO] 
[INFO] Transformed Files:
[INFO]   ✓ application.yml - spring.profiles → spring.config.activate.on-profile
[INFO]   ✓ application-dev.yml - 2 profile sections transformed
[WARNING] application-complex.yml - Multi-document file flagged for manual review
[INFO] 
[INFO] Added spring.config.use-legacy-processing=true to 1 file(s)
[SUCCESS] Profile syntax migration completed: 2/3 files automated, 1 requires review
```


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

> [!NOTE]
> This profile syntax change is automatically handled by the Configuration File Processing migration above.


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

#### Automated Neo4j Property Migration

> [!NOTE]
> This migration combines AST-based code detection (CassandraCodeMigrator pattern) with property file transformation (AbstractPropertyFileMigrator pattern).

**Detection Patterns - AST-Based Approach**

**Pattern 1: Detect Old Neo4j Properties in YAML**
```java
// Following H2ConfigurationMigrator pattern
@SuppressWarnings("unchecked")
private Map<String, Boolean> detectNeo4jProperties(Path yamlFile) {
    boolean hasOldProperties = false;
    boolean hasNewProperties = false;
    
    try {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;
        
        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }
        
        if (data != null && data.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");
            
            // Check for old spring.data.neo4j.* properties
            if (spring.containsKey("data")) {
                Map<String, Object> dataSection = (Map<String, Object>) spring.get("data");
                if (dataSection.containsKey("neo4j")) {
                    hasOldProperties = true;
                }
            }
            
            // Check for new spring.neo4j.* properties
            if (spring.containsKey("neo4j")) {
                hasNewProperties = true;
            }
        }
    } catch (Exception e) {
        logger.warn("Error detecting Neo4j properties in {}: {}", yamlFile, e.getMessage());
    }
    
    return Map.of("hasOld", hasOldProperties, "hasNew", hasNewProperties);
}
```

**Pattern 2: Detect Neo4j OGM Code Usage (JavaParser AST)**
```java
// Following CassandraCodeMigrator pattern for code detection
Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
List<String> filesWithNeo4jOGM = new ArrayList<>();

for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
    String className = entry.getKey();
    CompilationUnit cu = entry.getValue();
    
    if (cu == null) continue;
    
    // Check for Neo4j OGM imports
    for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
        String importName = imp.getNameAsString();
        
        // Old Neo4j OGM packages
        if (importName.startsWith("org.neo4j.ogm") ||
            importName.startsWith("org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration")) {
            filesWithNeo4jOGM.add(className);
            break;
        }
    }
}

if (!filesWithNeo4jOGM.isEmpty()) {
    result.setRequiresManualReview(true);
    result.addWarning("Neo4j OGM detected - requires manual migration to Neo4j SDN-RX");
    for (String className : filesWithNeo4jOGM) {
        result.addChange("Neo4j OGM usage in: " + className);
    }
}
```

**Automated Transformation Strategy**

**Complexity**: MEDIUM - Property restructuring with namespace change

**Step 1: YAML Property Migration**
```java
@SuppressWarnings("unchecked")
private boolean migrateNeo4jYamlProperties(Map<String, Object> data,
                                            MigrationPhaseResult result,
                                            String fileName) {
    boolean modified = false;
    
    if (!data.containsKey("spring")) {
        return false;
    }
    
    Map<String, Object> spring = (Map<String, Object>) data.get("spring");
    
    // Check for spring.data.neo4j section
    if (spring.containsKey("data")) {
        Map<String, Object> dataSection = (Map<String, Object>) spring.get("data");
        
        if (dataSection.containsKey("neo4j")) {
            Map<String, Object> oldNeo4j = (Map<String, Object>) dataSection.remove("neo4j");
            
            // Extract properties
            String uri = (String) oldNeo4j.get("uri");
            String username = (String) oldNeo4j.get("username");
            String password = (String) oldNeo4j.get("password");
            
            // Create new spring.neo4j section
            if (!spring.containsKey("neo4j")) {
                spring.put("neo4j", new LinkedHashMap<>());
            }
            Map<String, Object> newNeo4j = (Map<String, Object>) spring.get("neo4j");
            
            // Migrate URI directly
            if (uri != null) {
                newNeo4j.put("uri", uri);
            }
            
            // Create authentication subsection if credentials present
            if (username != null || password != null) {
                Map<String, Object> authentication = new LinkedHashMap<>();
                if (username != null) authentication.put("username", username);
                if (password != null) authentication.put("password", password);
                newNeo4j.put("authentication", authentication);
            }
            
            result.addChange(fileName + ": Migrated spring.data.neo4j.* → spring.neo4j.*");
            modified = true;
        }
    }
    
    return modified;
}
```

**Step 2: Properties File Migration**
```java
private boolean migrateNeo4jPropertiesFile(Properties props,
                                            MigrationPhaseResult result,
                                            String fileName) {
    boolean modified = false;
    
    // Property key transformations
    Map<String, String> propertyMigrations = Map.of(
        "spring.data.neo4j.uri", "spring.neo4j.uri",
        "spring.data.neo4j.username", "spring.neo4j.authentication.username",
        "spring.data.neo4j.password", "spring.neo4j.authentication.password"
    );
    
    for (Map.Entry<String, String> migration : propertyMigrations.entrySet()) {
        String oldKey = migration.getKey();
        String newKey = migration.getValue();
        
        if (props.containsKey(oldKey)) {
            String value = props.getProperty(oldKey);
            props.remove(oldKey);
            props.setProperty(newKey, value);
            
            result.addChange(fileName + ": " + oldKey + " → " + newKey);
            modified = true;
        }
    }
    
    return modified;
}
```

**Validation Strategy**:
- Neo4j connection successful with new properties
- Application starts without Neo4j property warnings
- Database operations work correctly
- Integration tests with Neo4j testcontainer pass
- Repository queries execute successfully

**Risk Level**: MEDIUM - Property changes affect runtime connectivity

**Automation Confidence**: 90% (property migration fully automated, OGM code requires manual migration)

**Recommendation**:
- Fully automate property transformation for YAML and .properties files
- Generate comprehensive migration guide for Neo4j OGM code (similar to CassandraCodeMigrator)
- Flag all files with OGM imports for manual review
- Add migration report section listing affected files

**Automation Output Example**:
```
[INFO] Neo4j Property Migration
[INFO] Found old Neo4j properties in 2 files
[INFO] 
[INFO] Property Files Migrated:
[INFO]   ✓ application.yml - spring.data.neo4j.* → spring.neo4j.*
[INFO]   ✓ application-prod.properties - 3 properties migrated
[INFO] 
[WARNING] Neo4j OGM Code Detected:
[WARN]   ⚠ UserGraphRepository.java - Uses org.neo4j.ogm.*
[WARN]   ⚠ GraphConfiguration.java - Uses Neo4jDataAutoConfiguration
[INFO] 
[ACTION REQUIRED] Manual migration to Neo4j SDN-RX required for OGM usage
[INFO] See: https://neo4j.com/docs/spring-data-neo4j/current/
[SUCCESS] Property migration completed: 2/2 files automated
[MANUAL REVIEW] 2 files flagged for OGM → SDN-RX migration
```


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


#### Automated data.sql Processing Migration

> [!NOTE]
> This migration uses file system traversal and property file analysis to detect the risky combination of data.sql with Hibernate DDL auto.

**Detection Patterns - File and Property Analysis**

**Pattern 1: Detect data.sql Files**
```java
// Find data.sql in resources directory
Path basePath = Paths.get(Settings.getBasePath());
Path resourcesPath = basePath.resolve("src/main/resources");

List<Path> sqlScripts = new ArrayList<>();

if (Files.exists(resourcesPath)) {
    try (Stream<Path> paths = Files.walk(resourcesPath)) {
        paths.filter(Files::isRegularFile)
             .filter(path -> path.getFileName().toString().equals("data.sql") ||
                           path.getFileName().toString().equals("schema.sql"))
             .forEach(sqlScripts::add);
    }
}

boolean hasDataSql = sqlScripts.stream()
    .anyMatch(path -> path.getFileName().toString().equals("data.sql"));
```

**Pattern 2: Detect Hibernate DDL Auto Configuration**
```java
// Check YAML files for JPA Hibernate DDL auto configuration
@SuppressWarnings("unchecked")
private boolean hasHibernateDdlAuto(Path yamlFile) {
    try {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;
        
        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }
        
        if (data != null && data.containsKey("spring")) {
            Map<String, Object> spring = (Map<String, Object>) data.get("spring");
            
            if (spring.containsKey("jpa")) {
                Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
                
                if (jpa.containsKey("hibernate")) {
                    Map<String, Object> hibernate = (Map<String, Object>) jpa.get("hibernate");
                    
                    if (hibernate.containsKey("ddl-auto")) {
                        String ddlAuto = hibernate.get("ddl-auto").toString();
                        // Only create, update, create-drop are risky
                        return !ddlAuto.equals("none") && !ddlAuto.equals("validate");
                    }
                }
            }
        }
    } catch (Exception e) {
        logger.debug("Error checking Hibernate DDL auto in {}", yamlFile, e);
    }
    
    return false;
}
```

**Automated Transformation Strategy**

**Complexity**: LOW - Simple property addition

**Step 1: Add defer-datasource-initialization Property to YAML**
```java
@SuppressWarnings("unchecked")
private boolean addDeferDatasourceInit(Path yamlFile, MigrationPhaseResult result) {
    try {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;
        
        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }
        
        if (data == null) {
            data = new LinkedHashMap<>();
        }
        
        // Navigate to spring.jpa section
        if (!data.containsKey("spring")) {
            data.put("spring", new LinkedHashMap<>());
        }
        Map<String, Object> spring = (Map<String, Object>) data.get("spring");
        
        if (!spring.containsKey("jpa")) {
            spring.put("jpa", new LinkedHashMap<>());
        }
        Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
        
        // Check if already set
        if (jpa.containsKey("defer-datasource-initialization")) {
            result.addChange("defer-datasource-initialization already configured");
            return false;
        }
        
        // Add property
        jpa.put("defer-datasource-initialization", true);
        
        if (!dryRun) {
            try (OutputStream output = Files.newOutputStream(yamlFile)) {
                yaml.dump(data, new OutputStreamWriter(output));
            }
        }
        
        result.addChange(yamlFile.getFileName() + ": Added spring.jpa.defer-datasource-initialization=true");
        return true;
        
    } catch (Exception e) {
        result.addError("Failed to add defer-datasource-initialization: " + e.getMessage());
        return false;
    }
}
```

**Step 2: Add to Properties File**
```java
private boolean addDeferDatasourceInitProperties(Path propFile, MigrationPhaseResult result) {
    try {
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(propFile)) {
            props.load(input);
        }
        
        if (props.containsKey("spring.jpa.defer-datasource-initialization")) {
            result.addChange("defer-datasource-initialization already configured");
            return false;
        }
        
        props.setProperty("spring.jpa.defer-datasource-initialization", "true");
        
        if (!dryRun) {
            try (OutputStream output = Files.newOutputStream(propFile)) {
                props.store(output, "Spring Boot 2.4 - data.sql compatibility");
            }
        }
        
        result.addChange(propFile.getFileName() + ": Added spring.jpa.defer-datasource-initialization=true");
        return true;
        
    } catch (Exception e) {
        result.addError("Failed to add defer-datasource-initialization: " + e.getMessage());
        return false;
    }
}
```

**Validation Strategy**:
- Application starts successfully
- data.sql executes without table not found errors
- Hibernate-created tables exist when data.sql runs
- Database initialization completes successfully
- Integration tests with H2/testcontainers pass

**Risk Level**: LOW - Property addition only, no code changes

**Automation Confidence**: 100% (deterministic, safe transformation)

**Recommendation**:
- Fully automated when data.sql + Hibernate DDL AUTO detected
- Add migration report entry explaining the change and reasoning

**Automation Output Example**:
```
[INFO] SQL Script Initialization Migration
[INFO] Found data.sql with Hibernate DDL AUTO configuration
[WARN] Risky combination detected: data.sql may execute before Hibernate schema creation
[INFO] 
[ACTION] Adding spring.jpa.defer-datasource-initialization=true
[INFO]   ✓ application.yml - Property added
[INFO] 
[SUCCESS] data.sql will now execute AFTER Hibernate schema creation
[NOTE] Verify data.sql references Hibernate-created tables correctly
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


#### Automated Logback Property Migration

> [!NOTE]
> This migration follows the AbstractPropertyFileMigrator pattern for property transformation.

**Detection and Transformation Patterns**

**Property Mapping Table**:
```java
// Following AbstractPropertyFileMigrator pattern
private static final Map<String, PropertyMapping> LOGBACK_MAPPINGS = Map.of(
    "logging.pattern.rolling-file-name",
        new PropertyMapping("logging.logback.rollingpolicy.file-name-pattern", TransformationType.NEST),
    "logging.file.max-size",
        new PropertyMapping("logging.logback.rollingpolicy.max-file-size", TransformationType.NEST),
    "logging.file.max-history",
        new PropertyMapping("logging.logback.rollingpolicy.max-history", TransformationType.NEST),
    "logging.file.total-size-cap",
        new PropertyMapping("logging.logback.rollingpolicy.total-size-cap", TransformationType.NEST),
    "logging.file.clean-history-on-start",
        new PropertyMapping("logging.logback.rollingpolicy.clean-history-on-start", TransformationType.NEST)
);
```

**Automated Transformation Strategy**

**Complexity**: LOW - Direct property renaming following established patterns

**Step 1: YAML File Migration**
```java
@SuppressWarnings("unchecked")
@Override
protected boolean transformYamlData(Map<String, Object> data,
                                    MigrationPhaseResult result,
                                    String fileName) {
    boolean modified = false;
    
    if (!data.containsKey("logging")) {
        return false;
    }
    
    Map<String, Object> logging = (Map<String, Object>) data.get("logging");
    
    // Migrate logging.file.* properties to logging.logback.rollingpolicy.*
    if (logging.containsKey("file") && logging.get("file") instanceof Map) {
        Map<String, Object> file = (Map<String, Object>) logging.get("file");
        
        Map<String, Object> propertiesToMigrate = new HashMap<>();
        
        // Extract properties that need migration
        for (String key : List.of("max-size", "max-history", "total-size-cap", "clean-history-on-start")) {
            if (file.containsKey(key)) {
                propertiesToMigrate.put(key, file.get(key));
                file.remove(key);
            }
        }
        
        if (!propertiesToMigrate.isEmpty()) {
            // Create logback.rollingpolicy structure
            if (!logging.containsKey("logback")) {
                logging.put("logback", new LinkedHashMap<>());
            }
            Map<String, Object> logback = (Map<String, Object>) logging.get("logback");
            
            if (!logback.containsKey("rollingpolicy")) {
                logback.put("rollingpolicy", new LinkedHashMap<>());
            }
            Map<String, Object> rollingpolicy = (Map<String, Object>) logback.get("rollingpolicy");
            
            // Move properties
            for (Map.Entry<String, Object> entry : propertiesToMigrate.entrySet()) {
                rollingpolicy.put(entry.getKey(), entry.getValue());
                result.addChange(fileName + ": logging.file." + entry.getKey() + 
                               " → logging.logback.rollingpolicy." + entry.getKey());
                modified = true;
            }
        }
    }
    
    // Migrate logging.pattern.rolling-file-name
    if (logging.containsKey("pattern") && logging.get("pattern") instanceof Map) {
        Map<String, Object> pattern = (Map<String, Object>) logging.get("pattern");
        
        if (pattern.containsKey("rolling-file-name")) {
            Object value = pattern.remove("rolling-file-name");
            
            if (!logging.containsKey("logback")) {
                logging.put("logback", new LinkedHashMap<>());
            }
            Map<String, Object> logback = (Map<String, Object>) logging.get("logback");
            
            if (!logback.containsKey("rollingpolicy")) {
                logback.put("rollingpolicy", new LinkedHashMap<>());
            }
            Map<String, Object> rollingpolicy = (Map<String, Object>) logback.get("rollingpolicy");
            
            rollingpolicy.put("file-name-pattern", value);
            result.addChange(fileName + ": logging.pattern.rolling-file-name → logging.logback.rollingpolicy.file-name-pattern");
            modified = true;
        }
    }
    
    return modified;
}
```

**Step 2: Properties File Migration**
```java
@Override
protected boolean transformProperties(Properties props,
                                     MigrationPhaseResult result,
                                     String fileName) {
    boolean modified = false;
    
    for (Map.Entry<String, PropertyMapping> entry : LOGBACK_MAPPINGS.entrySet()) {
        String oldKey = entry.getKey();
        PropertyMapping mapping = entry.getValue();
        
        if (props.containsKey(oldKey)) {
            String value = props.getProperty(oldKey);
            props.remove(oldKey);
            props.setProperty(mapping.newKey, value);
            
            result.addChange(fileName + ": " + oldKey + " → " + mapping.newKey);
            modified = true;
        }
    }
    
    return modified;
}
```

**Validation Strategy**:
- Application starts without deprecated property warnings
- Log files roll correctly with configured size/history limits
- Logback configuration loads successfully
- No errors in log output related to rolling policy
- Verify rolling policy behavior in development environment

**Risk Level**: NONE - Property renames have deprecated aliases in Spring Boot 2.4

**Automation Confidence**: 100% (safe, deterministic, follows established patterns)

**Recommendation**:
- Fully automated following AbstractPropertyFileMigrator pattern
- Add spring-boot-properties-migrator temporarily for verification
- Remove properties migrator after successful migration

**Automation Output Example**:
```
[INFO] Logback Property Migration
[INFO] Found 2 files with deprecated Logback properties
[INFO] 
[INFO] Migrated Files:
[INFO]   ✓ application.yml - 4 properties migrated
[INFO]     logging.file.max-size → logging.logback.rollingpolicy.max-file-size
[INFO]     logging.file.max-history → logging.logback.rollingpolicy.max-history
[INFO]     logging.file.total-size-cap → logging.logback.rollingpolicy.total-size-cap
[INFO]     logging.pattern.rolling-file-name → logging.logback.rollingpolicy.file-name-pattern
[INFO]   ✓ application-prod.properties - 3 properties migrated
[INFO] 
[SUCCESS] Logback property migration completed: 2/2 files automated
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

#### Automated Elasticsearch RestClient Migration

> [!NOTE]
> This migration uses AST-based code detection following the CassandraCodeMigrator pattern.

**Detection Patterns - AST-Based Approach**

**Following CassandraCodeMigrator pattern for code detection:**

```java
// Use JavaParser AST to detect RestClient usage
Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
List<String> filesWithLowLevelRestClient = new ArrayList<>();
List<String> filesWithHighLevelClient = new ArrayList<>();

for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
    String className = entry.getKey();
    CompilationUnit cu = entry.getValue();
    
    if (cu == null) continue;
    
    boolean hasLowLevel = false;
    boolean hasHighLevel = false;
    
    // Check imports
    for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
        String importName = imp.getNameAsString();
        
        if ("org.elasticsearch.client.RestClient".equals(importName)) {
            hasLowLevel = true;
        }
        if ("org.elasticsearch.client.RestHighLevelClient".equals(importName)) {
            hasHighLevel = true;
        }
    }
    
    // Check for @Autowired fields of type RestClient
    if (hasLowLevel) {
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            String fieldType = field.getCommonType().asString();
            if ("RestClient".equals(fieldType)) {
                filesWithLowLevelRestClient.add(className);
                break;
            }
        }
    }
    
    if (hasHighLevel) {
        filesWithHighLevelClient.add(className);
    }
}
```

**Automated Transformation Strategy**

**Complexity**: LOW - Detection and flagging only, no code generation

When low-level RestClient usage is detected:
- Flag all affected files for manual review
- Set `result.setRequiresManualReview(true)`
- Add warning about auto-configuration removal
- List affected files in migration report
- Reference migration guide section above for manual steps

**Validation Strategy**:
- Application compiles successfully
- Elasticsearch connection works with new configuration
- All operations that used RestClient still function
- Integration tests with Elasticsearch testcontainer pass
- Search/index operations execute successfully

**Risk Level**: MEDIUM - Requires manual review of configuration

**Automation Confidence**: 100% for detection, 0% for transformation (manual migration required)

**Recommendation**:
- Automate detection only
- Flag all affected files for manual review with clear guidance
- Reference the manual configuration examples already in this guide
- Do not attempt automated code generation

**Automation Output Example**:
```
[WARNING] Elasticsearch RestClient Auto-Configuration Removed
[INFO] Found 3 classes using low-level RestClient:
[WARN]   ⚠ SearchService.java
[WARN]   ⚠ IndexManager.java  
[WARN]   ⚠ DocumentProcessor.java
[INFO] 
[ACTION REQUIRED] Manual configuration needed - see above for options
[TIP] Recommended: Use RestHighLevelClient or add manual @Bean
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
