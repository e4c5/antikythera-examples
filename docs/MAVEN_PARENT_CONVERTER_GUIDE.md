# Maven Parent POM Converter - User Guide

## Overview

The Maven Parent POM Converter is a tool that converts Maven POMs with parent inheritance into completely standalone POMs. All inherited configuration (properties, dependencies, plugins, profiles) is explicitly expanded into the child POM, and the `<parent>` element is removed.

## Use Cases

- **Simplify dependency management** - Make all versions explicit
- **Eliminate parent dependency** - Remove coupling to corporate parent POMs
- **Improve POM transparency** - See all inherited configuration in one place
- **Facilitate migration** - Prepare for moving projects between organizations
- **Debugging** - Understand what's inherited from parents

## Quick Start

### Basic Usage

Flatten the `pom.xml` in the current directory:

```bash
cd /path/to/your/project
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI"
```

This will:
1. Resolve the parent POM chain
2. Merge all inherited configuration
3. Create a backup (`pom.xml.backup-YYYYMMDD-HHMMSS`)
4. Write the flattened POM

### Dry Run Mode

Preview changes without modifying files:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
  -Dexec.args="--dry-run"
```

**Output example:**
```
=== Flattening Summary ===
Properties: 1 → 202 (+201 from parent)
Dependencies: 2 total, 2 versions added from dependencyManagement
Plugins: 0 → 15 (+15 from parent)
Profiles: 0 → 3 (+3 from parent)

Dry run - no changes made
```

## Command-Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--pom <path>` | Path to POM file | `./pom.xml` |
| `--dry-run` | Show changes without modifying files | false |
| `--no-backup` | Skip creating backup file | false |
| `--skip-profiles` | Exclude parent profiles from flattening | false |
| `--help` | Show help message | - |
| `--version` | Show version information | - |

## Examples

### Example 1: Flatten Specific POM

```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
  -Dexec.args="--pom ../my-module/pom.xml"
```

### Example 2: Skip Profiles

If you don't want parent profiles merged:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
  -Dexec.args="--skip-profiles"
```

### Example 3: No Backup

If you're certain and don't need a backup:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
  -Dexec.args="--no-backup"
```

### Example 4: Combined Options

```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
  -Dexec.args="--pom my-module/pom.xml --skip-profiles --no-backup"
```

## What Gets Flattened

### Properties
All properties from the parent chain are merged into the child POM:
```xml
<!-- Before: Child inherits implicitly -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>2.7.14</version>
</parent>

<!-- After: All properties explicit -->
<properties>
  <java.version>11</java.version>
  <spring-framework.version>5.3.29</spring-framework.version>
  <hibernate.version>5.6.15.Final</hibernate.version>
  <!-- ... 200+ more properties -->
</properties>
```

### Dependencies
Versions from `<dependencyManagement>` are added explicitly:
```xml
<!-- Before: Version inferred -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- After: Version explicit -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <version>2.7.14</version>
</dependency>
```

### DependencyManagement
All parent `<dependencyManagement>` sections are merged.

### Plugins & PluginManagement
Plugin configurations from parents are flattened into the child.

### Profiles
Profiles from parents are merged (unless `--skip-profiles` is used).

## Understanding the Output

### Flattening Summary

```
=== Flattening Summary ===
Properties: 1 → 202 (+201 from parent)
Dependencies: 2 total, 2 versions added from dependencyManagement
Plugins: 0 → 15 (+15 from parent)
Profiles: 0 → 3 (+3 from parent)

Backup created: pom.xml.backup-20251221-125336

✓ POM successfully flattened: pom.xml
```

**Explanation:**
- **Properties**: Child had 1, now has 202 (201 inherited from parents)
- **Dependencies**: 2 dependencies got explicit versions from parent's dependencyManagement
- **Plugins**: 15 plugins inherited from parent's pluginManagement
- **Profiles**: 3 profiles inherited from parent

## Important Notes

### Property Expressions Preserved

Dependency versions may contain property expressions like `${junit.version}`. These are preserved (not resolved), which is correct Maven behavior. The properties are also included in the flattened POM, so the expressions remain valid.

```xml
<properties>
  <junit.version>5.9.3</junit.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version> <!-- Expression preserved -->
  </dependency>
</dependencies>
```

### Comments Not Preserved

Due to limitations of the Maven Model API, comments in the POM will be lost during transformation. Ensure any important documentation is captured elsewhere.

### Builds Should Be Identical

The flattened POM should produce identical build outputs as the original. The tool doesn't change functionality, only makes inherited configuration explicit.

### Parent Resolution

The tool resolves parents from:
1. **Relative path** (if specified in `<parent><relativePath>`)
2. **Local Maven repository** (`~/.m2/repository`) as fallback

Ensure parent POMs are available in your local repository before running the tool.

## Troubleshooting

### Parent Not Found

**Error:**
```
Error: Failed to resolve parent POM
  org.example:parent-pom:1.0.0
  Unable to resolve parent POM from relativePath or local repository
```

**Solution:**
```bash
# Download parent to local repository
mvn dependency:get -Dartifact=org.example:parent-pom:1.0.0:pom
```

### Version Mismatch Warning

**Warning:**
```
WARN  Parent version mismatch: declared=1.0.0, found=1.0.1
```

**Explanation:** The POM at `relativePath` has a different version than declared in `<parent>`. The tool uses the declared version for resolution.

## Programmatic Usage

For integrating into other Maven tools:

```java
// Resolve parent chain
ParentPomResolver resolver = new ParentPomResolver();
Parent parent = childModel.getParent();
List<Model> parentChain = resolver.resolveParentChain(parent, pomPath);

// Flatten inheritance
InheritanceFlattener flattener = new InheritanceFlattener();
Model flattened = flattener.flattenInheritance(childModel, parentChain);

// Write flattened POM
ModelWriter writer = new DefaultModelWriter();
try (FileWriter fw = new FileWriter("flattened-pom.xml")) {
    writer.write(fw, Collections.emptyMap(), flattened);
}
```

## Support

For issues or questions, refer to the project documentation or create an issue in the project repository.
