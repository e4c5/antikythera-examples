# QueryOptimizer - Automated JPA Query Optimization Tool

## Overview

QueryOptimizer is an intelligent static analysis tool that automatically optimizes JPA repository queries by analyzing column cardinality, reordering WHERE clause conditions, and generating database indexes. It uses AI (Gemini or OpenAI) to provide intelligent recommendations for query restructuring and automatically applies code changes across your codebase.

## What Does QueryOptimizer Do?

QueryOptimizer performs three main optimizations:

1. **Query Restructuring**: Reorders WHERE clause columns in `@Query` annotations based on column cardinality (moving high-cardinality filters to the front)
2. **Method Signature Updates**: Renames repository methods and reorders parameters to match optimized query structure
3. **Call Site Propagation**: Automatically updates all method calls across dependent classes (Services, Controllers, Tests)
4. **Schema Enhancement**: Generates Liquibase changesets with new index recommendations

## Features

- **AI-Powered Analysis**: Uses Gemini or OpenAI to analyze queries and provide optimization recommendations
- **Automatic Code Modification**: Updates `@Query` annotations, method signatures, and call sites
- **Cardinality Analysis**: Classifies columns as HIGH (PK/unique), MEDIUM (indexed), or LOW (boolean/enum/flags)
- **Batch Processing**: Efficiently processes multiple queries and updates in batches
- **Checkpoint Support**: Can resume from checkpoints for large codebases
- **Liquibase Integration**: Automatically generates index changesets
- **Token Usage Tracking**: Monitors AI API usage and costs

## Usage

### Basic Usage

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"
```

### With Command-Line Options

```bash
# Quiet mode (less verbose output)
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--quiet"

# Fresh start (clear checkpoint)
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--fresh"

# With cardinality overrides
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--low-cardinality=status,type,category --high-cardinality=user_id,order_id"
```

### Command-Line Flags

- `--quiet` or `-q`: Reduces verbose output
- `--fresh` or `-f`: Clears checkpoint and starts fresh
- `--low-cardinality=<col1,col2,...>`: Override column cardinality classification
- `--high-cardinality=<col1,col2,...>`: Override column cardinality classification

## Configuration

### Primary Configuration File

The tool reads configuration from `src/main/resources/generator.yml`:

```yaml
variables:
  projects_folder: ${HOME}/csi/repos

base_path: ${projects_folder}/BM/csi-bm-approval-java-service/

# AI service configuration
ai_service:
  provider: "gemini"  # or "openai"
  model: "gemini-3-flash-preview"  # Model name (varies by provider)
  api_key: "${GEMINI_API_KEY}"  # or "${OPENAI_API_KEY}" for OpenAI
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"  # Optional, defaults provided
  timeout_seconds: 120
  max_retries: 2
  queries_per_request: 40
  track_usage: true
  cost_per_1k_tokens: 0.00015  # Optional, for cost tracking
  max_tokens_per_request: 32000
  enable_request_compression: false

# Query optimizer specific configuration
query_optimizer:
  # Optional: Analyze only this specific repository
  # target_class: "com.example.UserRepository"
  
  # Database dialects for Liquibase changesets
  supported_dialects:
    - postgresql
    - oracle
  
  # Path to master Liquibase changelog
  liquibase_master_file: ${base_path}/src/main/resources/db/changelog/db.changelog-master.xml

database:
  query_conversion:
    enabled: true
    skip_processed: true
  log_file: query-optimization-stats.csv
```

### AI Provider Configuration

#### Gemini Configuration

```yaml
ai_service:
  provider: "gemini"
  model: "gemini-3-flash-preview"  # or "gemini-2.5-flash-lite-preview-09-2025"
  api_key: "${GEMINI_API_KEY}"  # Required: Set GEMINI_API_KEY environment variable
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"  # Optional
  timeout_seconds: 120
  max_retries: 2
  queries_per_request: 40
  track_usage: true
```

**Required Environment Variable:**
```bash
export GEMINI_API_KEY="your-gemini-api-key"
```

#### OpenAI Configuration

```yaml
ai_service:
  provider: "openai"
  model: "gpt-4o"  # or "gpt-4o-mini" (recommended budget option)
  api_key: "${OPENAI_API_KEY}"  # Required: Set OPENAI_API_KEY environment variable
  api_endpoint: "https://api.openai.com/v1/chat/completions"  # Optional, defaults to this
  timeout_seconds: 90
  max_retries: 2
  queries_per_request: 40
  track_usage: true
```

**Required Environment Variable:**
```bash
export OPENAI_API_KEY="your-openai-api-key"
```

**Optional Environment Variable:**
```bash
export OPENAI_API_ENDPOINT="https://api.openai.com/v1/chat/completions"  # For custom endpoints
```

### Supported Models

#### Gemini Models
- `gemini-3-flash-preview` (recommended)
- `gemini-2.5-flash-lite-preview-09-2025`
- `gemini-pro`

#### OpenAI Models
- `gpt-4o` (flagship model, higher cost)
- `gpt-4o-mini` (recommended budget option)
- `gpt-4-turbo` (legacy)
- `gpt-4` (legacy)

### Liquibase Configuration

The tool requires a Liquibase master changelog file at:
```
<base_path>/src/main/resources/db/changelog/db.changelog-master.xml
```

The tool will automatically append new index changesets to this file.

## How It Works

### 1. Repository Discovery

Scans for JPA repository interfaces extending `JpaRepository`:

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.email = :email")
    List<User> findByStatusAndEmail(@Param("status") String status, @Param("email") String email);
}
```

### 2. Query Analysis

- Extracts queries from `@Query` annotations and derived query methods
- Loads Liquibase XML to classify column cardinality
- Analyzes WHERE clause ordering using JSQLParser
- Applies rule-based optimization detection

### 3. AI Optimization

- Batches queries with cardinality context
- Sends to AI service (Gemini or OpenAI) for recommendations
- Receives optimized query structures and explanations
- Tracks token usage and costs

### 4. Code Modification

- Updates `@Query` annotation values with optimized SQL
- Renames methods if parameter order changes (e.g., `findByStatusAndEmail` → `findByEmailAndStatus`)
- Reorders method parameters to match optimized WHERE clause
- Updates all call sites in dependent classes

### 5. Index Generation

- Generates Liquibase changesets for recommended indexes
- Appends to master changelog file
- Supports multiple database dialects

## Example Transformations

### Example 1: Query Reordering

**Before:**
```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.email = :email")
    List<User> findByStatusAndEmail(@Param("status") String status, @Param("email") String email);
}
```

**After:**
```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("""
        SELECT u FROM User u WHERE u.email = :email AND u.status = :status
        """)
    List<User> findByEmailAndStatus(@Param("email") String email, @Param("status") String status);
}
```

**Service Call Updated:**
```java
// Before
userRepository.findByStatusAndEmail("ACTIVE", "user@example.com");

// After
userRepository.findByEmailAndStatus("user@example.com", "ACTIVE");
```

### Example 2: Index Generation

The tool generates Liquibase changesets:

```xml
<changeSet id="query-optimizer-index-001" author="query-optimizer">
    <createIndex tableName="users" indexName="idx_users_email_status">
        <column name="email"/>
        <column name="status"/>
    </createIndex>
</changeSet>
```

## Cardinality Classification

Columns are automatically classified based on database metadata:

- **HIGH Cardinality**: Primary keys, unique constraints, indexed columns
- **MEDIUM Cardinality**: Columns with indexes but not unique
- **LOW Cardinality**: Boolean fields, enums, flags (e.g., `status`, `is_active`, `deleted`)

### Override Cardinality

You can override cardinality classification via command-line:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--low-cardinality=tenant_id,hospital_id --high-cardinality=transaction_id"
```

## Output Artifacts

### 1. Modified Source Files

- Repository interfaces with updated `@Query` annotations
- Method signatures with reordered parameters
- Dependent classes with updated method calls

### 2. Liquibase Changesets

Generated XML file with CREATE INDEX statements appended to master changelog.

### 3. Statistics Report

Console output and CSV file (`query-optimization-stats.csv`) with:
- Queries analyzed
- Optimizations applied
- Method signatures changed
- Call sites updated
- Token usage and costs

### 4. Console Output

```
--- Query Optimization Summary ---
Repositories analyzed: 5
Queries optimized: 12
Method signatures changed: 8
Dependent classes modified: 23
Method calls updated: 45

--- Final AI Token Usage Report ---
Total input tokens: 45,230
Total output tokens: 12,450
Total cost: $0.15
-----------------------------------
```

## Checkpoint and Resume

For large codebases, the tool supports checkpoint/resume:

- **Checkpoint**: Automatically saves progress after processing each repository
- **Resume**: Automatically resumes from last checkpoint on restart
- **Fresh Start**: Use `--fresh` flag to clear checkpoint and start over

## Comparison: QueryOptimizationChecker vs QueryOptimizer

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

**Use QueryOptimizationChecker when:**
- You want to review recommendations before applying
- Running in CI/CD for quality gates
- Generating reports for developers
- Creating Liquibase changesets only

**Use QueryOptimizer when:**
- You trust the AI recommendations
- Performing bulk optimization across repositories
- You want automated refactoring
- You need both code changes AND index changes

## Common Pitfalls

1. **Repository Limit**: Currently processes first JPA repository only (may be updated in future versions)
2. **Liquibase Path**: Must be at `<base_path>/src/main/resources/db/changelog/db.changelog-master.xml`
3. **API Key Required**: Must set `GEMINI_API_KEY` or `OPENAI_API_KEY` environment variable
4. **Call Site Updates**: Only updates direct method calls on repository fields, not indirect invocations
5. **Method References**: Method references (e.g., `repo::methodName`) are updated, but only if there's exactly one candidate (no overloads)

## Best Practices

### 1. Review Before Committing

Always review the generated changes before committing:

```bash
# Run QueryOptimizer
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"

# Review the diff
git diff

# Test the changes
mvn test
```

### 2. Use Checkpoints for Large Codebases

For large projects, let the tool checkpoint automatically:

```bash
# First run - will checkpoint after each repository
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"

# If interrupted, restart - will resume from checkpoint
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer"
```

### 3. Start with QueryOptimizationChecker

For initial analysis, use the read-only checker:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker"
```

Review the recommendations, then run QueryOptimizer if satisfied.

### 4. Configure Cardinality Overrides

For domain-specific columns, use cardinality overrides:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizer" \
  -Dexec.args="--low-cardinality=tenant_id,hospital_id,is_active"
```

### 5. Monitor Token Usage

Track AI API costs by enabling usage tracking:

```yaml
ai_service:
  track_usage: true
  cost_per_1k_tokens: 0.00015  # Adjust based on your model
```

## Troubleshooting

### API Key Not Found

**Error:**
```
AI service API key is required. Set GEMINI_API_KEY environment variable...
```

**Solution:**
```bash
export GEMINI_API_KEY="your-api-key"
# or
export OPENAI_API_KEY="your-api-key"
```

### Liquibase File Not Found

**Error:**
```
Liquibase master file not found at: ...
```

**Solution:**
Ensure the path in `generator.yml` is correct:
```yaml
query_optimizer:
  liquibase_master_file: ${base_path}/src/main/resources/db/changelog/db.changelog-master.xml
```

### Method Call Updates Skipped

**Issue:** Some method calls aren't updated.

**Possible Causes:**
- Method is called indirectly (not on a field)
- Method has overloads and arity can't be determined
- Method reference with ambiguous overloads

**Solution:** Manually review and update remaining call sites.

## Related Tools

- **QueryOptimizationChecker**: Read-only analysis without code modification
- **JPARepositoryAnalyzer**: Exports all repository queries to CSV
- **HardDelete**: Detects hard delete operations
- **UsageFinder**: Analyzes collection usage patterns

## See Also

- [Query Optimization Review Prompt](query-optimization-review-prompt.md) - AI prompt for reviewing QueryOptimizer changes
- [Liquibase Indexes](liquibase_indexes.md) - Liquibase index management
- [AGENTS.md](../AGENTS.md) - Complete agent documentation

