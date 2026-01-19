# Indexes - Liquibase Index Analysis Tool

## Overview

The Indexes tool parses Liquibase XML changelog files to extract and display all database indexes, primary keys, and unique constraints. It provides a comprehensive view of 
the database index structure by processing the master changelog and all included files.

## Features

- **Comprehensive Index Parsing**: Analyzes all types of indexes:
  - Primary keys (PK)
  - Unique constraints
  - Unique indexes
  - Regular indexes
- **Include Resolution**: Follows `<include>` and `<includeAll>` directives
- **Drop Tracking**: Applies `dropIndex` and `dropConstraint` operations
- **SQL Statement Parsing**: Extracts indexes from raw SQL statements
- **Vendor-Specific Support**: Handles `CONCURRENTLY`, `ONLINE`, `IF NOT EXISTS` clauses
- **Order Preservation**: Maintains column order in composite indexes
- **Index Name Length Validation**: Automatically limits index names to 60 characters for database portability
- **Smart Truncation**: Uses deterministic hashing when truncating long index names for uniqueness

## Usage

### Basic Usage

```bash
java -cp target/antikythera-examples-*.jar \
  sa.com.cloudsolutions.liquibase.Indexes \
  /path/to/db/changelog/db.changelog-master.xml
```

### With Maven Exec

```bash
mvn exec:java \
  -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="/path/to/db/changelog/db.changelog-master.xml"
```

### Typical Project Setup

```bash
mvn exec:java \
  -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="src/main/resources/db/changelog/db.changelog-master.xml"
```

## Output Format

The tool displays indexes grouped by table, with indentation showing the index type:

```
users
  PK: PRIMARY_KEY;<unnamed>;[id]
  UNIQUE: UNIQUE_CONSTRAINT;uk_users_email;[email]
  INDEX: INDEX;idx_users_status;[status]
  INDEX: INDEX;idx_users_created_status;[created_at,status]

orders
  PK: PRIMARY_KEY;pk_orders;[id]
  UNIQUE: UNIQUE_INDEX;idx_orders_number;[order_number]
  INDEX: INDEX;idx_orders_user_status;[user_id,status]
  INDEX: INDEX;idx_orders_created;[created_at]
```

Each line follows the format: `TYPE;NAME;[COLUMNS]`

- **TYPE**: PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, or INDEX
- **NAME**: Index or constraint name (or `<unnamed>` if not specified)
- **COLUMNS**: Comma-separated list of columns in order

## Supported Liquibase Elements

### Change Types Parsed

1. **`<createIndex>`**
   ```xml
   <createIndex indexName="idx_users_email" tableName="users">
       <column name="email"/>
   </createIndex>
   ```

2. **`<addUniqueConstraint>`**
   ```xml
   <addUniqueConstraint constraintName="uk_users_email" 
                        tableName="users" 
                        columnNames="email"/>
   ```

3. **`<addPrimaryKey>`**
   ```xml
   <addPrimaryKey constraintName="pk_users" 
                  tableName="users" 
                  columnNames="id"/>
   ```

4. **`<createTable>` with inline constraints**
   ```xml
   <createTable tableName="users">
       <column name="id" type="bigint">
           <constraints primaryKey="true" primaryKeyName="pk_users"/>
       </column>
       <column name="email" type="varchar(255)">
           <constraints unique="true" uniqueConstraintName="uk_users_email"/>
       </column>
   </createTable>
   ```

5. **`<sql>` with CREATE INDEX statements**
   ```xml
   <sql>
       CREATE INDEX CONCURRENTLY idx_users_status ON users(status);
   </sql>
   ```

6. **`<dropIndex>`**
   ```xml
   <dropIndex indexName="idx_users_old" tableName="users"/>
   ```

### Include Directives

The tool recursively processes:

```xml
<include file="db/changelog/001-initial-schema.xml"/>
<includeAll path="db/changelog/migrations/"/>
```

## Use Cases

### 1. Index Audit

Review all indexes in your database schema:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="src/main/resources/db/changelog/db.changelog-master.xml" \
  > indexes-audit.txt
```

### 2. Verify Query Optimizer Suggestions

Compare QueryOptimizationChecker recommendations against existing indexes:

```bash
# Run QueryOptimizationChecker
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker"

# Check current indexes
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="src/main/resources/db/changelog/db.changelog-master.xml"
```

### 3. Migration Planning

Before creating new indexes, check what exists:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="src/main/resources/db/changelog/db.changelog-master.xml" | grep "orders"
```

### 4. Find Composite Indexes

Identify multi-column indexes for a specific table:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.liquibase.Indexes" \
  -Dexec.args="src/main/resources/db/changelog/db.changelog-master.xml" | \
  grep "INDEX.*,.*]"
```

## Examples

### Example 1: Simple Index Information

**Input (Liquibase XML):**
```xml
<createIndex indexName="idx_users_email" tableName="users">
    <column name="email"/>
</createIndex>
<createIndex indexName="idx_users_status_created" tableName="users">
    <column name="status"/>
    <column name="created_at"/>
</createIndex>
```

**Output:**
```
users
  INDEX: INDEX;idx_users_email;[email]
  INDEX: INDEX;idx_users_status_created;[status,created_at]
```

### Example 2: After Drop Operations

**Input:**
```xml
<createIndex indexName="idx_users_old" tableName="users">
    <column name="old_field"/>
</createIndex>

<dropIndex indexName="idx_users_old" tableName="users"/>

<createIndex indexName="idx_users_new" tableName="users">
    <column name="new_field"/>
</createIndex>
```

**Output:**
```
users
  INDEX: INDEX;idx_users_new;[new_field]
```

(The dropped index is not shown)

### Example 3: PostgreSQL CONCURRENTLY

**Input:**
```xml
<sql>
    CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_status 
    ON orders(status);
</sql>
```

**Output:**
```
orders
  INDEX: INDEX;idx_orders_status;[status]
```

## Error Handling

The tool handles common issues gracefully:

1. **Missing File**: 
   ```
   Error: File not found: /path/to/file.xml
   ```

2. **Include Not Found**:
   ```
   Warning: included file not found: db/changelog/missing.xml
   ```

3. **Malformed SQL**: Silently skips unparseable SQL statements

## Limitations

- **Functional Indexes**: Expression-based indexes (e.g., `LOWER(email)`) are ignored
- **Partial Indexes**: `WHERE` clauses in indexes are not captured
- **Schema Prefixes**: Schema names are stripped from table names
- **External Indexes**: Only includes indexes defined in Liquibase changelogs
- **Index Name Length**: Index names are automatically limited to 60 characters for database portability
  - Long names are truncated and appended with a 7-digit hash for uniqueness
  - Format: `idx_<truncated_base>_<hash>` (total 60 chars max)

## Index Name Generation

The `LiquibaseGenerator` utility automatically generates index names following these rules:

### Standard Naming Convention
- **Format**: `idx_<table>_<column1>_<column2>_...`
- **Case**: All lowercase
- **Sanitization**: Special characters replaced with underscores

### Length Limit (60 Characters)
To ensure compatibility across all major databases, index names are limited to 60 characters:

| Database | Technical Limit | Antikythera Limit |
|----------|----------------|-------------------|
| PostgreSQL | 63 characters | 60 characters |
| Oracle (older) | 30 characters | 60 characters* |
| Oracle (12.2+) | 128 characters | 60 characters |
| MySQL | 64 characters | 60 characters |
| SQL Server | 128 characters | 60 characters |

*Note: For Oracle databases with 30-character limits, additional configuration may be required.

### Truncation with Hash Suffix
When an index name exceeds 60 characters:
1. The base name is truncated to 52 characters
2. An underscore separator is added
3. A 7-digit deterministic hash is appended

**Example**:
```
Original:  idx_very_long_table_name_column_one_column_two_column_three_column_four
Truncated: idx_very_long_table_name_column_one_column_two_c_1234567
Length:    60 characters
```

The hash ensures:
- **Uniqueness**: Different column combinations produce different hashes
- **Determinism**: Same inputs always produce the same hash
- **Collision Avoidance**: 10 million possible hash values (0000000-9999999)

## Integration with QueryOptimizationChecker

The Indexes class is used internally by:
- **QueryOptimizationChecker**: To identify missing indexes
- **CardinalityAnalyzer**: To determine column cardinality based on index presence
- **LiquibaseGenerator**: To generate index changesets with proper naming and preconditions

### Index Generation Features

When `QueryOptimizationChecker` generates index recommendations, it uses `LiquibaseGenerator` which provides:

1. **Index Name Validation**: Ensures all index names are ≤60 characters
2. **Multi-Column Index Limits**: Configurable maximum columns per index (default: 4)
3. **Liquibase Preconditions**: Uses built-in `<indexExists>` instead of custom SQL
4. **Multi-Dialect Support**: Generates SQL for PostgreSQL, Oracle, MySQL, H2

### Configuration

Index generation behavior can be configured in `generator.yml`:

```yaml
query_optimizer:
  # Maximum number of columns in a multi-column index (default: 4)
  max_index_columns: 4
  
  # Database dialects to generate Liquibase changesets for
  supported_dialects:
    - postgresql
    - oracle
  
  # Path to the master Liquibase changelog file
  liquibase_master_file: src/main/resources/db/changelog/db.changelog-master.xml
```

**See Also**:
- [Maximum Index Columns Configuration](max_index_columns_configuration.md)
- [Index Name Length Validation](index_name_length_validation.md)

## API Usage

The tool can also be used programmatically:

```java
File liquibaseXml = new File("src/main/resources/db/changelog/db.changelog-master.xml");
Map<String, Set<IndexInfo>> indexes = Indexes.load(liquibaseXml);

for (Map.Entry<String, Set<IndexInfo>> entry : indexes.entrySet()) {
    String table = entry.getKey();
    Set<IndexInfo> tableIndexes = entry.getValue();
    
    System.out.println("Table: " + table);
    for (IndexInfo index : tableIndexes) {
        System.out.println("  " + index.type() + ": " + index.name() + 
                         " on " + index.columns());
    }
}
```

## Recent Improvements

### Index Name Length Validation (v0.1.2.9+)
All generated index names are now automatically limited to **60 characters** for maximum database portability:
- Prevents issues with databases that have shorter identifier limits
- Uses deterministic hashing for truncated names to ensure uniqueness
- No manual intervention required - works transparently

**Example**:
```java
// Long table and column names
String indexName = generator.generateIndexName(
    "customer_transaction_history", 
    List.of("user_id", "transaction_date", "payment_method", "status")
);
// Result: idx_customer_transaction_history_user_id_trans_1234567 (60 chars)
```

### Configurable Multi-Column Index Limits (v0.1.2.9+)
You can now configure the maximum number of columns in multi-column indexes:
- **Default**: 4 columns (recommended for OLTP)
- **Range**: 1-16 columns
- **Configuration**: `query_optimizer.max_index_columns` in `generator.yml`

This prevents overly complex indexes that can hurt write performance.

### Liquibase Built-in Preconditions (v0.1.2.9+)
Index changesets now use Liquibase's native `<indexExists>` precondition:
```xml
<preConditions onFail="MARK_RAN">
    <not>
        <indexExists tableName="users" columnNames="email"/>
    </not>
</preConditions>
```

**Benefits**:
- Simpler, more maintainable code (~150 lines removed)
- Database-agnostic (Liquibase handles dialect differences)
- Standard approach following Liquibase best practices

## See Also

- [QueryOptimizationChecker](../README.md#query-analysis-read-only) - Uses Indexes for analysis
- [QueryOptimizer](../README.md#query-optimization-modifies-code) - Generates new index suggestions
- [LiquibaseGenerator](../README.md#supporting-components) - Creates Liquibase changesets
- [Maximum Index Columns Configuration](max_index_columns_configuration.md) - Configure multi-column index limits
- [Index Name Length Validation](index_name_length_validation.md) - Details on 60-character limit
