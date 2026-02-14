# Antikythera Examples

This module provides a suite of advanced utilities and tools built on top of the **Antikythera** framework. It serves as both a showcase of the framework's capabilities and a production-ready toolkit for Java/Spring Boot code analysis, optimization, and migration.

## Table of Contents

- [General Configuration](#general-configuration)
- [Development Setup](#development-setup)
- [Maven Parent POM Converter](#maven-parent-pom-converter)
- [Query Optimizer](#query-optimizer)
- [JPA Repository Analyzer](#jpa-repository-analyzer)
- [Spring Boot Migration Tools](#spring-boot-migration-tools)
    - [2.1 to 2.2 Migrator](#spring-boot-2122-migrator)
    - [2.2 to 2.3 Migrator](#spring-boot-2223-migrator)
    - [2.3 to 2.4 Migrator](#spring-boot-2324-migrator)
    - [2.4 to 2.5 Migrator](#spring-boot-2425-migrator)
    - [Spring Boot Version Detector](#spring-boot-version-detector)
    - [Circular Dependency Tool](#circular-dependency-tool)
- [Code Quality & Analysis Tools](#code-quality--analysis-tools)
    - [AnnotationFinder](#annotationfinder)
    - [TestFixer](#testfixer)
    - [Logger Analysis](#logger-analysis)
    - [Liquibase Tools](#liquibase-tools)
        - [Liquibase Indexes](#liquibase-indexes)
        - [Liquibase Validator](#liquibase-validator)
        - [Liquibase Validation MCP Server](#liquibase-validation-mcp-server)
    - [HardDelete Detection](#harddelete-detection)
    - [UsageFinder](#usagefinder)
- [Knowledge Graph](#knowledge-graph)

---

## General Configuration

Many tools in this project utilize a shared configuration format (typically `generator.yml`) to define project paths, AI service credentials, and other environment specifics.

### Common Configuration Structure (`generator.yml`)

```yaml
variables:
  projects_folder: ${HOME}/your-projects
  m2_folder: ${HOME}/.m2/repository

base_path: ${projects_folder}/your-project/

ai_service:
  provider: "gemini"
  model: "gemini-2.5-flash-lite-preview-09-2025"
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 90
  max_retries: 2
  track_usage: true
  cost_per_1k_tokens: 0.00015
```

**Key Requirements:**
- **`base_path`**: Must point to the root of the project you are analyzing/modifying.
- **`GEMINI_API_KEY`**: Required for AI-powered features (Query Optimizer, Migrators).
- **Environment Variables**: You can use `${VAR}` syntax to reference environment variables.

---

## Development Setup

If you want to extend these examples or debug the underlying Antikythera framework:

### Build & Test
```bash
mvn clean compile
mvn test
```

### IDE Setup (IntelliJ IDEA)
You can link the local `antikythera` source code to this project for seamless debugging:
1.  **Module Approach (Recommended)**: Import the `antikythera` project as a module in the same IntelliJ window. IntelliJ will automatically use module dependencies.
2.  **Attach Sources**: In "Project Structure > Libraries", find `sa.com.cloudsolutions:antikythera` and click "Attach Sources...", pointing to your local `antikythera` source root.

---

## Maven Parent POM Converter

A tool that converts Maven POMs with parent inheritance into completely standalone POMs by expanding all inherited configuration (`<properties>`, `<dependencies>`, `<plugins>`, `<profiles>`).

### Quick Start
```bash
mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI"
# Run inside your project directory
```

### Features
- ✅ Resolves parent POMs from local repository or relativePath
- ✅ Supports multi-level parent hierarchies
- ✅ Merges all inherited configurations
- ✅ Automatic timestamped backups

📖 **[User Guide](docs/MAVEN_PARENT_CONVERTER_GUIDE.md)**

---

## Query Optimizer

A comprehensive system for analyzing and optimizing Spring Data JPA repositories. It uses AI to optimize SQL/JPQL queries and an automated refactoring engine to apply those changes.

### Quick Start

**Analysis Only:**
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker"
```

**Optimization (Apply Changes):**
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"
```

### Features
- **AI-Powered Optimization**: Uses LLMs to rewrite inefficient queries based on schema and cardinality.
- **Liquibase Generation**: Automatically generates changesets for missing indexes.
- **Safe Refactoring**: Updates `@Query` annotations and dependent method calls while preserving formatting.

### Specific Configuration
Ensure your `generator.yml` is set up with valid AI service credentials and points to your project's `base_path`.

---

## JPA Repository Analyzer

Bulk exports all JPA repository queries to CSV for auditing and analysis.

### Quick Start
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.JPARepositoryAnalyzer" \
  -Dexec.args="-b /path/to/project -o repository_queries.csv"
```

### Features
- Extracts all `@Query` annotations from JPA repositories
- Exports query metadata to CSV format
- Supports custom output file paths
- Analyzes both explicit queries and derived query methods

---

## Spring Boot Migration Tools

Automated tools to upgrade Spring Boot applications across major/minor versions.

### Spring Boot 2.1→2.2 Migrator
Upgrades Spring Boot 2.1 apps to 2.2.
```bash
java -cp target/classes com.raditha.spring.SpringBoot21to22Migrator --project-path /path/to/project
```
📖 **[Documentation](docs/spring_boot_2.1_to_2.2_migration.md)**

### Spring Boot 2.2→2.3 Migrator
Upgrades Spring Boot 2.2 apps to 2.3, handling validation changes, H2 console config, and more.
```bash
java -cp target/classes com.raditha.spring.SpringBoot22to23Migrator --project-path /path/to/project
```
📖 **[Quick Start](docs/spring_boot_2.2_to_2.3_quickstart.md)**

### Spring Boot 2.3→2.4 Migrator
Upgrades Spring Boot 2.3 apps to 2.4.
```bash
java -cp target/classes com.raditha.spring.SpringBoot23to24Migrator --project-path /path/to/project
```

### Spring Boot 2.4→2.5 Migrator
Upgrades Spring Boot 2.4 apps to 2.5.
```bash
java -cp target/classes com.raditha.spring.SpringBoot24to25Migrator --project-path /path/to/project
```

### Spring Boot Version Detector
Detects the Spring Boot version from a POM file.
```bash
java -cp target/classes sa.com.cloudsolutions.antikythera.examples.SpringBootVersionDetector /path/to/pom.xml
```
Outputs the detected Spring Boot version to stdout.

### Circular Dependency Tool
Detects and resolves circular bean dependencies using strategies like `@Lazy`, setter injection, and **Method Extraction** for `@PostConstruct` cycles.

**Quick Start:**
```bash
mvn exec:java -Dexec.mainClass="com.raditha.spring.cycle.CircularDependencyTool" \
  -Dexec.args="--config your-config.yml --strategy auto"
```
📖 **[Documentation](docs/circular_dependency_tool.md)**

---

## Code Quality & Analysis Tools

### AnnotationFinder
Finds all classes and methods annotated with a specific annotation.

**Quick Start:**
```bash
# Detailed output (default) - includes full method signatures
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.AnnotationFinder" \
  -Dexec.args="Service"

# Simple output - method name only, duplicates eliminated
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.AnnotationFinder" \
  -Dexec.args="Service --simple"
```

**Features:**
- Searches entire codebase for specified annotation
- Handles both simple names (e.g., `Service`) and fully qualified names (e.g., `org.springframework.stereotype.Service`)
- Two output modes: detailed (default) and simple
- Supports any annotation name (e.g., `@Service`, `@Test`, `@Entity`, `@Query`)

**Output Format:**
- **Class annotations**: `com.example.MyService` (same in both modes)
- **Method annotations (detailed mode)**: `com.example.MyClass#print()` or `com.example.MyClass#print(String s)`
- **Method annotations (simple mode)**: `com.example.MyClass#print` (method name only, no duplicates for overloaded methods)

**Options:**
- `--simple` or `-s`: Enable simple output mode (method name only, eliminates duplicates)

### TestFixer
Identifies and fixes bad testing patterns (missing assertions, framework migration).
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer" -Dexec.args="--dry-run"
```
📖 **[Documentation](docs/test_fixer.md)**

### Logger Analysis
Analyzes and standardizes logger usage across the codebase, ensuring consistent logging patterns.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.Logger"
```
Processes all classes in the project and standardizes logger field declarations and usage.

### Liquibase Tools

#Audits database indexes defined in Liquibase changelogs.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" -Dexec.args="path/to/db.changelog-master.xml"
```
📖 **[Documentation](docs/liquibase_indexes.md)**

#### Liquibase Validator
Validates Liquibase changelog XML files for syntax errors and structural issues.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidator" \
  -Dexec.args="path/to/changelog.xml"
```
Outputs validation results in JSON format with exit code 0 for valid files, 1 for invalid.

#### Liquibase Validation MCP Server
MCP (Model Context Protocol) server for Liquibase validation, enabling integration with AI agents and tools.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer"
```
Starts an MCP server that provides Liquibase validation capabilities via the MCP protocol.

### HardDelete Detection
Finds hard delete operations in repositories to enforce soft-delete policies.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete"
```
📖 **[Documentation](docs/hard_delete.md)**

### UsageFinder
Analyzes collection usage (List, Set, Map) in non-entity classes to detect potential memory issues or N+1 problems.
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder"
```
📖 **[Documentation](docs/usage_finder.md)**

---

## Knowledge Graph

### KnowledgeGraphCLI
Generates knowledge graphs from Java codebases, visualizing relationships between classes, methods, and dependencies.
```bash
mvn exec:java -Dexec.mainClass="com.raditha.graph.KnowledgeGraphCLI" \
  -Dexec.args="--base-path=/path/to/project --config=src/main/resources/graph.yml"
```

### Features
- Builds comprehensive dependency graphs
- Configurable analysis scope via YAML configuration
- Visualizes code relationships and dependencies
