# SpringBootVersionDetector

A utility tool to detect the Spring Boot version used in a Maven project by analyzing its `pom.xml` file.

## Overview

`SpringBootVersionDetector` scans a Maven `pom.xml` file to determine which version of Spring Boot is being used. It checks multiple sources in the following priority order:

1. **Parent POM** - `spring-boot-starter-parent` declaration
2. **Dependencies** - Spring Boot dependencies with explicit versions
3. **Properties** - Version properties (e.g., `spring-boot.version`)
4. **Dependency Management** - Spring Boot in `dependencyManagement` section

## Features

- ✅ Detects Spring Boot version from parent declarations
- ✅ Detects version from dependencies
- ✅ Detects version from Maven properties
- ✅ Resolves property references (e.g., `${spring-boot.version}`)
- ✅ Handles nested property resolution
- ✅ Command-line interface for scripting
- ✅ Programmatic API for integration

## Usage

### Command Line

```bash
# Using Maven exec
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.parser.SpringBootVersionDetector" \
  -Dexec.args="/path/to/pom.xml"

# Direct Java execution
java -cp target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout) \
  sa.com.cloudsolutions.antikythera.parser.SpringBootVersionDetector \
  /path/to/pom.xml
```

**Output:**
- Prints the Spring Boot version to stdout (e.g., `3.3.4`)
- Exits with code 0 on success
- Exits with code 1 if version not found or error occurs

### Programmatic API

```java
import sa.com.cloudsolutions.antikythera.parser.SpringBootVersionDetector;
import java.nio.file.Path;

// Using a specific pom.xml path
Path pomPath = Path.of("/path/to/project/pom.xml");
SpringBootVersionDetector detector = new SpringBootVersionDetector(pomPath);
String version = detector.detectSpringBootVersion();

if (version != null) {
    System.out.println("Spring Boot version: " + version);
} else {
    System.out.println("No Spring Boot detected");
}
```

```java
// Using default base path from Settings
SpringBootVersionDetector detector = new SpringBootVersionDetector();
String version = detector.detectSpringBootVersion();
```

## Examples

### Example 1: Parent POM Declaration

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
</parent>
```
**Detected version:** `3.3.4`

### Example 2: Dependency with Version

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
        <version>2.7.18</version>
    </dependency>
</dependencies>
```
**Detected version:** `2.7.18`

### Example 3: Property Reference

```xml
<properties>
    <spring-boot.version>3.2.1</spring-boot.version>
</properties>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>${spring-boot.version}</version>
</parent>
```
**Detected version:** `3.2.1`

### Example 4: Nested Properties

```xml
<properties>
    <base.version>3.2.5</base.version>
    <spring-boot.version>${base.version}</spring-boot.version>
</properties>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>${spring-boot.version}</version>
</parent>
```
**Detected version:** `3.2.5`

## Integration with MavenHelper

The detector leverages the existing `MavenHelper` class to:
- Parse `pom.xml` files with proper encoding handling (UTF-8 BOM support)
- Resolve Maven models and properties
- Handle complex POM structures

## Error Handling

The detector gracefully handles:
- Missing `pom.xml` files
- Invalid XML syntax
- Unresolved property references
- Non-Spring Boot projects (returns `null`)

## Testing

Run the test suite:

```bash
mvn test -Dtest=SpringBootVersionDetectorTest
```

The test suite includes:
- ✅ Parent POM detection
- ✅ Dependency detection
- ✅ Property detection
- ✅ Property reference resolution
- ✅ Nested property resolution
- ✅ Dependency management detection
- ✅ Non-Spring Boot project handling
- ✅ Real-world project testing

## Dependencies

- Apache Maven Model (`org.apache.maven:maven-model`)
- SLF4J (`org.slf4j:slf4j-api`)
- MavenHelper (internal Antikythera class)

## Use Cases

1. **CI/CD Pipelines**: Detect Spring Boot version for compatibility checks
2. **Migration Tools**: Identify projects needing Spring Boot upgrades
3. **Dependency Analysis**: Audit Spring Boot versions across multiple projects
4. **Version Enforcement**: Ensure projects use approved Spring Boot versions
5. **Build Automation**: Conditionally apply build steps based on Spring Boot version

## Return Values

- Returns version string (e.g., `"3.3.4"`, `"2.7.18"`) if Spring Boot is detected
- Returns `null` if no Spring Boot version is found
- Throws `IOException` if file cannot be read
- Throws `XmlPullParserException` if XML is invalid

## Logging

The detector logs informational messages via SLF4J:

```
INFO  - Spring Boot version detected from parent: 3.3.4
INFO  - Spring Boot version detected from dependencies: 2.7.18
INFO  - Spring Boot version detected from properties: 3.2.1
INFO  - No Spring Boot version found in pom.xml
WARN  - Unable to resolve property: some.undefined.property
```

## License

Part of the Antikythera project. See project LICENSE for details.
