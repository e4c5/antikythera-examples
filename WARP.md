# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

This repository contains examples and utilities for the Antikythera framework, focused on JPA repository query analysis and optimization. The main functionality includes:

- Static analysis of Spring Data JPA repositories
- Query optimization recommendations using AI (Gemini)
- Database cardinality analysis using Liquibase metadata
- Automated detection of hard deletes in repository usage

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

### Main Executables

**Query Optimizer** - Analyzes repositories and suggests query optimizations:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--low-cardinality=col1,col2 --high-cardinality=col3,col4"
```

**Hard Delete Finder** - Detects hard delete operations in code:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete"
```

**Repository Processor** - Batch processes multiple repositories:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.RepoProcessor" \
  -Dexec.args="/path/to/repos/root"
```

**Usage Finder** - Finds collection usage patterns:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder"
```

## Architecture

### Core Analysis Flow

The query optimization pipeline follows this flow:

1. **Repository Discovery** (`QueryOptimizationChecker`)
   - Scans compiled code for JPA repository interfaces
   - Identifies repositories extending `JpaRepository`

2. **Query Extraction** (`RepositoryParser` from antikythera library)
   - Parses `@Query` annotations (HQL and native SQL)
   - Analyzes derived query methods (e.g., `findByUsername`)
   - Extracts method parameters and their types

3. **Database Metadata Loading** (`CardinalityAnalyzer`)
   - Loads Liquibase XML for table/column/index information
   - Classifies columns by cardinality: HIGH (primary keys, unique), MEDIUM (indexed), LOW (boolean/enum)
   - Supports user-defined cardinality overrides via CLI

4. **Query Analysis** (`QueryAnalysisEngine`)
   - Extracts WHERE clause conditions using JSQLParser
   - Analyzes condition ordering based on cardinality
   - Identifies optimization opportunities (e.g., low cardinality column appearing first)

5. **AI-Enhanced Optimization** (`GeminiAIService` + `QueryBatchProcessor`)
   - Batches queries per repository for efficient AI processing
   - Sends queries with cardinality context to Gemini API
   - Receives optimization recommendations and rewritten queries
   - Tracks token usage and costs

6. **Code Modification** (`QueryOptimizer`)
   - Updates `@Query` annotation values with optimized SQL
   - Renames methods if parameter order changes
   - Updates call sites across dependent classes using `Fields` dependency map
   - Uses JavaParser's `LexicalPreservingPrinter` to maintain formatting

7. **Liquibase Generation** (`LiquibaseChangesWriter`)
   - Generates Liquibase changeset XML for suggested indexes
   - Consolidates duplicate index suggestions across queries

### Key Components

**CardinalityAnalyzer**: Column cardinality classification engine
- Primary keys and unique constraints → HIGH
- Boolean/enum columns → LOW
- Naming heuristics: `is_*`, `has_*`, `*_flag`, `*_enabled` → LOW
- Everything else → MEDIUM (needs index analysis)

**QueryAnalysisEngine**: Core optimization rule engine
- Rule 1: MEDIUM cardinality first column must have supporting index
- Rule 2: LOW cardinality first with HIGH alternative → HIGH severity
- Rule 3: LOW cardinality first with MEDIUM alternative → MEDIUM severity
- Rule 4: Non-primary-key HIGH before primary key → suggest reorder

**GeminiAIService**: AI service integration
- Configurable model (flash-lite, flash, pro)
- Batch processing (default 40 queries per request)
- Token usage tracking and cost estimation
- Retry logic with exponential backoff

**Fields**: Static dependency analysis utility
- Maps `@Autowired` repository fields to their containing classes
- Enables automatic update propagation when method signatures change

## QueryOptimizationChecker and QueryOptimizer Deep Dive

### QueryOptimizationChecker: Analysis-Only Mode

`QueryOptimizationChecker` is the base class that performs comprehensive query analysis without modifying code. It follows an **LLM-first approach**:

#### Initialization (Constructor)
1. Loads Liquibase XML to build index map via `Indexes.load()`
2. Initializes `CardinalityAnalyzer` with database metadata
3. Creates `QueryAnalysisEngine` for programmatic analysis
4. Configures `GeminiAIService` with settings from `generator.yml`
5. Sets up `QueryBatchProcessor` for batching queries

#### Analysis Workflow (`analyze()` method)

**Step 1: Repository Discovery**
- Scans all resolved types from `AntikytheraRunTime.getResolvedTypes()`
- Filters for JPA repositories by checking for `JpaRepository` in extended types
- Currently processes **first matching repository only** (has `break` statement at line 92)

**Step 2: Raw Query Collection (`analyzeRepository()`)**
- Uses `RepositoryParser` to compile and process repository types
- Calls `repositoryParser.buildQueries()` to extract all queries
- Collects queries without any programmatic analysis ("raw")
- Increments `totalQueriesAnalyzed` counter

**Step 3: LLM-First Analysis (`sendRawQueriesToLLM()`)**
- Creates `QueryBatch` with raw queries and WHERE clause column cardinality
- Uses `QueryAnalysisEngine` to extract actual columns from WHERE clauses
- Adds cardinality information for each column to the batch
- Sends entire batch to `GeminiAIService.analyzeQueryBatch()`
- Tracks token usage and costs (`TokenUsage` object)
- **Key insight**: LLM receives queries BEFORE programmatic analysis

**Step 4: Post-LLM Index Analysis (`analyzeLLMRecommendations()`)**
- Takes LLM recommendations and performs programmatic index checks
- For each recommended column order, checks if optimal index exists
- Uses `CardinalityAnalyzer.hasIndexWithLeadingColumn()` to verify indexes
- Creates `QueryOptimizationResult` with:
  - WHERE conditions extracted by `QueryAnalysisEngine`
  - LLM-enhanced optimization issues
  - Required index suggestions (de-duplicated)

**Step 5: Reporting (`reportOptimizationResults()`)**
- Sorts issues by severity: HIGH → MEDIUM → LOW
- Updates global counters: `totalHighPriorityRecommendations`, `totalMediumPriorityRecommendations`
- Prints detailed reports with:
  - Severity icons (🔴 HIGH, 🟡 MEDIUM, 🟢 LOW)
  - Current vs recommended column order
  - Cardinality information for each column
  - AI explanations from LLM
  - Index existence status (✓ EXISTS, ⚠ MISSING)

#### Consolidated Reporting

**Index Suggestions (`printConsolidatedIndexActions()`)**:
- De-duplicates index recommendations across all queries using `LinkedHashSet<String>`
- Key format: `table|column` (lowercase)
- Generates Liquibase changesets with:
  - `CREATE INDEX CONCURRENTLY` for PostgreSQL
  - `CREATE INDEX ... ONLINE` for Oracle
  - Preconditions to avoid duplicate index creation
  - Rollback scripts

**Index Drop Suggestions**:
- Scans existing indexes from `CardinalityAnalyzer.getIndexMap()`
- Identifies indexes with LOW cardinality leading columns
- Recommends dropping these inefficient indexes
- Generates Liquibase `DROP INDEX` changesets

**Exit Code Logic**:
- Exits with code 1 if: `high >= 1 AND medium >= 10`
- Useful for CI/CD pipeline integration

### QueryOptimizer: Analysis + Code Modification

`QueryOptimizer` extends `QueryOptimizationChecker` and adds automatic code rewriting capabilities.

#### Additional Initialization
- Calls `Fields.buildDependencies()` to map repository usage across codebase
- Builds dependency graph: repository → classes that @Autowire it → field names

#### Extended Analysis Workflow

**After parent analysis** (`analyzeRepository()` override):

1. **Setup Lexical Preservation**
   - Calls `LexicalPreservingPrinter.setup()` on the CompilationUnit
   - Preserves original formatting, indentation, and comments

2. **Annotation Updates (`updateAnnotationValue()`)**
   - For each non-optimized query with an optimized version:
   - Locates `@Query` annotation on method
   - Handles both styles:
     - Single-member: `@Query("SELECT ...")`
     - Normal: `@Query(value = "SELECT ...")`
   - Clones annotation to preserve formatting
   - Replaces query string with optimized SQL
   - Uses AST node replacement, not string manipulation

3. **Method Name Updates**
   - If parameter order changes, method must be renamed
   - Example: `findByNameAndId(name, id)` → `findByIdAndName(id, name)`
   - Prevents breaking changes at call sites

4. **Propagate Changes to Usage Sites (`applySignatureUpdatesToUsages()`)**
   - Uses `Fields.getFieldDependencies()` to find all classes using this repository
   - For each dependent class:
     - Creates `NameChangeVisitor` with the repository field name
     - Visits all method calls on that field
     - Updates method names if signature changed
   - Example flow:
     ```
     // Repository:
     interface UserRepo {
       findByActiveAndId(active, id) → findByIdAndActive(id, active)
     }
     
     // Service class:
     @Autowired UserRepo userRepo;
     userRepo.findByActiveAndId(...) → userRepo.findByIdAndActive(...)
     ```

5. **File Writing (`writeFile()`)**
   - Only writes if content actually changed (prevents timestamp churn)
   - Compares generated content with original file
   - Uses UTF-8 encoding
   - Writes both repository and dependent classes

#### Safety Mechanisms

1. **CompilationUnit Null Check**: Skips writing if no parsed AST available
2. **Content Equality Check**: Prevents writing identical content (avoids whitespace-only changes)
3. **Visitor Pattern**: `NameChangeVisitor` safely traverses AST to find call sites
4. **Modified Flag**: Tracks whether visitor actually changed anything

#### Comparison: Checker vs Optimizer

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
- Performing bulk optimization across many repositories
- You want automated refactoring with the `RepoProcessor`
- You need both code changes AND index changes

### Common Pitfalls

1. **Repository Limit**: Current implementation only processes first JPA repository found (line 92 has `break`)
2. **Liquibase Path**: Must be at `<base_path>/src/main/resources/db/changelog/db.changelog-master.xml`
3. **GEMINI_API_KEY**: Must be set in environment or `generator.yml`
4. **Fields Dependency**: QueryOptimizer requires `Fields.buildDependencies()` which scans entire codebase
5. **Call Site Updates**: Only updates direct method calls on repository fields, not indirect invocations

## Configuration

### Primary Config: `src/main/resources/generator.yml`

```yaml
variables:
  projects_folder: ${HOME}/csi/repos
  m2_folder: ${HOME}/.m2/repository

base_path: ${projects_folder}/BM/csi-bm-approval-java-service/

ai_service:
  provider: "gemini"
  model: "gemini-2.5-flash-lite-preview-09-2025"
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 90
  max_retries: 2
  queries_per_request: 40
  track_usage: true
  cost_per_1k_tokens: 0.00015
```

**Required Environment Variables:**
- `GEMINI_API_KEY`: API key for Gemini AI service

### Liquibase Database Metadata

The analyzer requires a Liquibase master changelog XML file containing:
- Table definitions with columns
- Index definitions (primary keys, unique constraints, indexes)
- Column types for accurate cardinality detection

Typical path: `<project>/src/main/resources/db/changelog/db.changelog-master.xml`

## Development Patterns

### Working with Antikythera Library

This project depends on `sa.com.cloudsolutions:antikythera:0.1.1`. To debug Antikythera code:

**Option A (Recommended)**: Open as module in IntelliJ
1. File > New > Module from Existing Sources…
2. Select the antikythera project's `pom.xml`
3. Maven tool window > Reload All Maven Projects
4. Set breakpoints and use Step Into (F7) to enter Antikythera code

**Option B**: Attach sources to library JAR
1. File > Project Structure > Libraries
2. Locate `sa.com.cloudsolutions:antikythera:0.1.1`
3. Click "Attach Sources…" and select Antikythera source directory

### Adding New Analysis Rules

To add a new query optimization rule:

1. Update `QueryAnalysisEngine.analyzeConditionOrdering()` with new detection logic
2. Create `OptimizationIssue` with appropriate severity (HIGH/MEDIUM)
3. Add corresponding test in `QueryAnalysisEngineTest` or `QueryOrderingTest`
4. If using AI, update prompt in `src/main/resources/ai-prompts/query-optimization-system-prompt.txt`

### Adding New Cardinality Classifications

To extend cardinality detection:

1. Modify `CardinalityAnalyzer.analyzeColumnCardinality()` for new heuristics
2. Add tests in `CardinalityAnalyzerTest`
3. Document in AI system prompt if relevant for LLM analysis

### Testing Strategy

- **Unit tests**: Test individual components (CardinalityAnalyzer, QueryAnalysisEngine)
- **Integration tests**: Test end-to-end analysis on sample repositories
- Use JUnit 5 (`@Test`) and Mockito for mocking
- Test files mirror source structure under `src/test/java/`

## Dependencies

**Core Libraries:**
- JDK 21 (required)
- Maven 3.8+
- Antikythera 0.1.1 (main framework)
- JavaParser 3.26.2 (AST parsing and modification)
- JSQLParser 5.3 (SQL parsing)
- SLF4J 2.0.13 (logging)

**AI Integration:**
- Gemini API (via REST)
- Jackson for JSON (implied via Antikythera)

**Test Libraries:**
- JUnit Jupiter 5.9.3
- Mockito 5.11.0

## Output Artifacts

**Analysis Report**: Console output with:
- Query-by-query optimization recommendations
- Severity levels (HIGH, MEDIUM)
- Token usage and cost estimates
- Consolidated index suggestions

**Liquibase Changeset**: Generated XML file with CREATE INDEX statements for suggested indexes

**Modified Source Files**: When using `QueryOptimizer`, Java files are updated in place with:
- Rewritten `@Query` annotation values
- Renamed methods (if parameter order changes)
- Updated call sites in dependent classes
