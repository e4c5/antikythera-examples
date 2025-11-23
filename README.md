# antikythera-examples

This project contains examples and tools for the Antikythera test generation framework, focusing on JPA repository query analysis and optimization.

## Architecture Overview

The project provides a comprehensive query optimization system that analyzes Spring Data JPA repositories, uses AI to suggest optimizations, and can automatically apply those optimizations to your codebase.

### Query Analysis Components

- **QueryOptimizationExtractor**: Core utility for extracting SQL conditions from queries
  - `extractWhereConditions()`: Extracts conditions from WHERE clauses only (excluding JOIN ON conditions)
  - `extractJoinConditions()`: Extracts conditions from JOIN ON clauses only (new facility)
  - `extractAllConditions()`: Convenience method to extract both WHERE and JOIN conditions separately
- **WhereClauseCollector**: Visitor pattern implementation that separates WHERE and JOIN ON conditions
- **JoinCondition**: Model representing JOIN ON conditions with left/right table and column details
- **WhereCondition**: Model representing WHERE clause conditions

### Core Query Optimization Tools

- **QueryOptimizationChecker**: Analyzes JPA repository queries for optimization opportunities (read-only analysis)
- **QueryOptimizer**: Extends QueryOptimizationChecker to apply query optimizations and update code automatically
- **QueryAnalysisEngine**: Core optimization rule engine for analyzing WHERE clause conditions
- **CardinalityAnalyzer**: Classifies column cardinality (HIGH/MEDIUM/LOW) based on database metadata
- **GeminiAIService**: AI service integration for intelligent query optimization recommendations
- **Fields**: Static dependency analysis utility for tracking repository usage across the codebase

### Supporting Components

- **QueryOptimizationResult**: Aggregates analysis results including WHERE conditions and optimization issues
- **OptimizationStatsLogger**: Tracks and logs detailed statistics about code modifications
- **LiquibaseGenerator**: Generates Liquibase changesets for index creation and drops
- **HardDelete**: Detects hard delete operations in repository methods
- **UsageFinder**: Finds collection usage patterns in entity classes

## Development Setup

Using existing local sources of sa.com.cloudsolutions:antikythera in this project

If you already have the antikythera sources on your machine, you can debug and step into them from this examples project without downloading any sources. Pick the approach that best fits your workflow.

Option A (recommended): Open antikythera as a module in the same IntelliJ window
- File > New > Module from Existing Sources…
- Select the antikythera project's pom.xml on your disk.
- Confirm to import it as a Maven project. IntelliJ will add it as another module alongside this examples module.
- In the Maven tool window, click "Reload All Maven Projects" to ensure dependencies are resolved.
- Result: IntelliJ uses the module output instead of the binary JAR. You can build both projects together and step directly into antikythera code during debugging.

Option B: Attach your local source folder to the library JAR
- Open File > Project Structure… > Libraries.
- Locate the antikythera library (sa.com.cloudsolutions:antikythera:0.1.2) that this project depends on.
- Click "Attach Sources…" and select the local source root directory of the antikythera project.
- Apply and close. IntelliJ will show the library with attached sources, enabling navigation and Step Into during debugging.

Option C (alternative workflow): Work with both projects in one Maven workspace
- Simply open both projects in the same IntelliJ window using the Maven view (no parent/aggregator change needed).
- Or, if you have a parent project that lists both modules, open the parent so IntelliJ imports both modules automatically.

Verification
- Set breakpoints in the examples project where it calls into antikythera APIs.
- Start a Debug session.
- Use Step Into (F7) when execution enters antikythera classes. The editor should open the source files from your local checkout.

Troubleshooting
- Maven reimport: Use the Maven tool window > Reload All Maven Projects after changes.
- SDK/language level: Ensure Project SDK is JDK 21 and language level matches (we set maven-compiler-plugin <release>21</release> for consistency).
- Caches: If IntelliJ still doesn’t link sources, try File > Invalidate Caches / Restart.

## Build & Test

### Build
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

## Usage Examples

### Query Condition Extraction

The project provides facilities to extract WHERE and JOIN conditions separately from SQL queries:

```java
// Parse a SQL query
Statement statement = CCJSqlParserUtil.parse(
    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = ?"
);

// Extract only WHERE conditions (excluding JOIN ON)
List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
// Returns: [WhereCondition(tableName=o, columnName=status, operator==, position=0)]

// Extract only JOIN ON conditions (excluding WHERE)
List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);
// Returns: [JoinCondition(leftTable=o, leftColumn=customer_id, rightTable=c, rightColumn=id, operator==)]

// Extract both separately in one call
QueryOptimizationExtractor.ConditionExtractionResult result = 
    QueryOptimizationExtractor.extractAllConditions(statement);
List<WhereCondition> whereList = result.getWhereConditions();
List<JoinCondition> joinList = result.getJoinConditions();
```
### Running Query Optimization Tools

#### Query Analysis (Read-only)

Analyzes queries and provides recommendations without modifying code:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker"
```

With cardinality overrides:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker" \
  -Dexec.args="--low-cardinality=is_active,is_deleted --high-cardinality=email"
```

#### Query Optimization (Modifies code)

Analyzes queries and automatically applies optimizations to your code:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"
```

Quiet mode (only shows changes made):
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--quiet"
```

## Configuration

The query optimization system requires configuration in `generator.yml`:

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

**Required:**
- Set `base_path` to your project root
- Set `GEMINI_API_KEY` environment variable
- Ensure Liquibase changelog exists at `<base_path>/src/main/resources/db/changelog/db.changelog-master.xml`

## Key Features

### AI-Powered Query Optimization

The system uses an LLM-first approach:
1. Collects all repository queries
2. Sends queries to Gemini AI with cardinality context
3. Receives optimization recommendations and rewritten queries
4. Performs programmatic index analysis
5. Generates Liquibase changesets for required indexes

### Automatic Code Modification (QueryOptimizer)

When using `QueryOptimizer`, the system automatically:
- Updates `@Query` annotations with optimized SQL
- Reorders method parameters to match optimized query conditions
- Updates all method call sites in dependent classes
- Uses JavaParser's LexicalPreservingPrinter to maintain code formatting
- Tracks detailed statistics about all modifications

### Cardinality Analysis

The system classifies columns into three cardinality levels:
- **HIGH**: Primary keys, unique constraints
- **MEDIUM**: Indexed columns
- **LOW**: Boolean/enum columns, columns with naming patterns like `is_*`, `has_*`, `*_flag`

Notes
- No repository changes are required for any of the development approaches above. The pom already declares dependencies explicitly so IntelliJ resolves them reliably. 
