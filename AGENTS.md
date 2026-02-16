# Antikythera Examples - Agent Guide

Utilities and examples for the Antikythera framework, focused on JPA repository analysis, query optimization, and test refactoring.

## Main Executables

- **QueryOptimizationChecker**: Analyzes JPA repositories for query optimization opportunities (read-only mode)
- **QueryOptimizer**: Extends Checker to automatically rewrite `@Query` annotations, reorder parameters, and update call sites
- **HardDelete**: Detects hard delete operations in repository usage patterns
- **UsageFinder**: Finds and analyzes collection usage patterns across the codebase
- **JPARepositoryAnalyzer**: Exports all repository queries to CSV for analysis and reporting
- **Logger**: Adds or updates SLF4J logger fields in Java classes
- **TestFixer**: Refactors tests, converts embedded resources, and optionally migrates JUnit 4→5

## Setup

Before running tests or using the tools, you must set up the testbeds. This downloads the necessary repositories (like Spring PetClinic and Spring Boot Cycles) to the `testbeds/` directory.

```bash
./setup-testbeds.sh
```

## Build & Development Commands

### Build and Compile
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

Run a single test class:
```bash
mvn test -Dtest=QueryAnalysisEngineTest
```

Run a specific test method:
```bash
mvn test -Dtest=QueryAnalysisEngineTest#testEngineInitialization
```

### Package
```bash
mvn package
```

## Running the Tools

**Query Optimization Checker** - Analyzes repositories without modifying code:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker" \
  -Dexec.args="--low-cardinality=col1,col2 --high-cardinality=col3,col4"
```

**Query Optimizer** - Analyzes and automatically modifies code:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--quiet"
```

**Hard Delete Finder** - Detects hard delete operations:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete"
```

**Usage Finder** - Finds collection usage patterns:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder"
```

**JPA Repository Analyzer** - Exports repository queries to CSV:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.JPARepositoryAnalyzer" \
  -Dexec.args="-b /path/to/project -o output.csv"
```

**Logger** - Adds/updates SLF4J logger fields:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.Logger"
```

**Test Fixer** - Refactors tests and migrates JUnit 4→5:
```bash
mvn exec:java -Dexec.mainClass="com.raditha.cleanunit.TestFixer" \
  -Dexec.args="--junit4to5"
```

## Architecture Overview

### Query Optimization Pipeline

1. **Repository Discovery**: Scans for JPA repository interfaces extending `JpaRepository`
2. **Query Extraction**: Parses `@Query` annotations and derived query methods via `RepositoryParser`
3. **Metadata Loading**: Loads Liquibase XML to classify column cardinality (HIGH/MEDIUM/LOW) via `CardinalityAnalyzer`
4. **Query Analysis**: Extracts WHERE clauses with JSQLParser, analyzes ordering via `QueryAnalysisEngine`
5. **AI Optimization**: Batches queries with context to Gemini API via `GeminiAIService` for recommendations
6. **Code Modification**: Updates `@Query` annotations, reorders parameters, updates call sites (QueryOptimizer only)
7. **Liquibase Generation**: Creates index changesets via `LiquibaseGenerator`

### Key Components

**CardinalityAnalyzer**: Classifies columns as HIGH (PK/unique), MEDIUM (indexed), or LOW (boolean/enum/flags)

**QueryAnalysisEngine**: Rule-based optimization detection:
- Rule 1: MEDIUM cardinality first column must have supporting index
- Rule 2: LOW cardinality first with HIGH alternative → HIGH severity
- Rule 3: LOW cardinality first with MEDIUM alternative → MEDIUM severity
- Rule 4: Non-PK HIGH before PK → suggest reorder

**GeminiAIService**: AI integration with batch processing, token tracking, and retry logic

**Fields**: Dependency analysis utility mapping `@Autowired` fields to containing classes for call site updates

## QueryOptimizationChecker and QueryOptimizer Deep Dive

### QueryOptimizationChecker: Analysis-Only Mode

**LLM-first approach** that analyzes queries without modifying code.

**Workflow**:
1. **Initialization**: Loads Liquibase XML, initializes analyzers and AI service
2. **Repository Discovery**: Scans for JPA repositories (currently processes first match only)
3. **Raw Query Collection**: Extracts queries via `RepositoryParser.buildQueries()`
4. **LLM Analysis**: Batches queries with cardinality info, sends to Gemini, tracks token usage
5. **Post-LLM Index Check**: Verifies indexes exist for recommendations via `CardinalityAnalyzer`
6. **Reporting**: Prints optimization details, generates Liquibase changesets, exits with code 1 if recommendations exist

**Outputs**: Analysis reports, Liquibase CREATE/DROP INDEX changesets, token usage statistics

### QueryOptimizer: Analysis + Code Modification

**Extends QueryOptimizationChecker** with automatic code rewriting.

**Additional Steps**:
1. **Annotation Updates**: Rewrites `@Query` annotations with optimized SQL using AST manipulation
2. **Parameter Reordering**: Matches parameter order to optimized WHERE clause column order
3. **Call Site Propagation**: Uses `Fields.buildDependencies()` to find and update all usage sites
4. **Safe Writing**: Only writes if content changed, uses `LexicalPreservingPrinter` for formatting

### Comparison: Checker vs Optimizer

| Feature | QueryOptimizationChecker | QueryOptimizer |
|---------|-------------------------|----------------|
| Analyzes queries | ✓ | ✓ |
| Generates reports | ✓ | ✓ |
| AI recommendations | ✓ | ✓ |
| Liquibase generation | ✓ | ✓ |
| Modifies `@Query` | ✗ | ✓ |
| Renames methods | ✗ | ✓ |
| Updates call sites | ✗ | ✓ |
| Dependencies map | ✗ | ✓ |

### When to Use Each

**Use QueryOptimizationChecker when**:
- You want to review recommendations before applying
- Running in CI/CD for quality gates
- Generating reports for developers
- Creating Liquibase changesets only

**Use QueryOptimizer when**:
- You trust the AI recommendations
- Performing bulk optimization across repositories
- You want automated refactoring
- You need both code changes AND index changes

### Common Pitfalls

1. **Repository Limit**: Currently processes first JPA repository only
2. **Liquibase Path**: Must be at `<base_path>/src/main/resources/db/changelog/db.changelog-master.xml`
3. **API Key Required**: `GEMINI_API_KEY` or `OPENAI_API_KEY` environment variable required (depending on provider)
4. **Call Site Updates**: Only updates direct method calls on repository fields, not indirect invocations

## Configuration

**Primary Config**: `src/main/resources/generator.yml`

### Gemini Configuration

```yaml
base_path: ${projects_folder}/BM/csi-bm-approval-java-service/

ai_service:
  provider: "gemini"
  model: "gemini-3-flash-preview"  # or "gemini-2.5-flash-lite-preview-09-2025"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 90
  max_retries: 2
  track_usage: true
```

**Required**: `GEMINI_API_KEY` environment variable

### OpenAI Configuration

```yaml
base_path: ${projects_folder}/BM/csi-bm-approval-java-service/

ai_service:
  provider: "openai"
  model: "gpt-4o"  # or "gpt-4o-mini" (recommended budget option)
  api_key: "${OPENAI_API_KEY}"
  api_endpoint: "https://api.openai.com/v1/chat/completions"  # Optional
  timeout_seconds: 90
  max_retries: 2
  track_usage: true
```

**Required**: `OPENAI_API_KEY` environment variable

**Supported Providers**: `gemini` (default) or `openai`

**Liquibase**: Must be at `<project>/src/main/resources/db/changelog/db.changelog-master.xml`

## Development Patterns

**Debug Antikythera Library**: Open antikythera as module in IntelliJ (File > New > Module from Existing Sources)

**Add Analysis Rule**: Update `QueryAnalysisEngine.analyzeQuery()`, add test, update AI prompt if needed

**Extend Cardinality**: Modify `CardinalityAnalyzer.analyzeColumnCardinality()`, add tests

## Dependencies

- JDK 21, Maven 3.8+
- Antikythera 0.1.2 (main framework)
- JavaParser 3.26.2 (AST parsing/modification)
- JSQLParser 5.3 (SQL parsing)
- ANTLR4 Runtime 4.13.1 (HQL parsing)
- Gemini API or OpenAI API (AI optimization)
- JUnit 5 + Mockito (testing)

## Output Artifacts

**Analysis Report**: Console output with:
- Query-by-query optimization recommendations
- AI explanations for each recommendation
- Token usage and cost estimates
- Consolidated index suggestions

**Liquibase Changeset**: Generated XML file with CREATE INDEX and DROP INDEX statements

**Modified Source Files**: When using `QueryOptimizer`, Java files are updated in place with:
- Rewritten `@Query` annotation values
- Reordered method parameters (if column order changes)
- Updated call sites in dependent classes with reordered arguments


