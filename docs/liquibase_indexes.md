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

## Integration with QueryOptimizationChecker

The Indexes class is used internally by:
- **QueryOptimizationChecker**: To identify missing indexes
- **CardinalityAnalyzer**: To determine column cardinality based on index presence

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

## See Also

- [QueryOptimizationChecker](../README.md#query-analysis-read-only) - Uses Indexes for analysis
- [QueryOptimizer](../README.md#query-optimization-modifies-code) - Generates new index suggestions
- [LiquibaseGenerator](../README.md#supporting-components) - Creates Liquibase changesets
