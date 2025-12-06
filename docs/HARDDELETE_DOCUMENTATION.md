# HardDelete - Hard Delete Detection Tool

## Overview

HardDelete is a static analysis tool that scans Java code to identify hard delete operations in JPA repositories. It distinguishes between hard deletes (permanent removal) and soft deletes (logical deletion via status flags), helping teams detect potential data loss risks and enforce soft delete policies.

## What is a Hard Delete?

A **hard delete** permanently removes data from the database using SQL `DELETE` statements. In contrast, a **soft delete** marks records as deleted without physically removing them, typically using an `UPDATE` statement to set a flag like `deleted = true` or `status = 'INACTIVE'`.

### Why Detect Hard Deletes?

- **Data Recovery**: Soft deletes allow data recovery
- **Audit Trails**: Maintain complete history for compliance
- **Referential Integrity**: Avoid cascading delete issues
- **Analytics**: Preserve historical data for reporting

## Features

- **Standard JPA Method Detection**: Identifies calls to `delete`, `deleteById`, `deleteAll`, etc.
- **Derived Query Detection**: Detects `deleteBy*` and `removeBy*` methods
- **Soft Delete Recognition**: Distinguishes soft deletes by analyzing `@Query` annotations
- **Context-Aware**: Reports the class and method where hard deletes occur
- **CSV Output**: Easy-to-parse output format

## Usage

### Basic Usage

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete"
```

### Output to File

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" \
  > hard-deletes-report.csv
```

### Filter Specific Packages

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" | \
  grep "com.example.order"
```

## Output Format

CSV format: `ClassName,MethodName,MethodCallExpression`

```csv
com.example.service.UserService,deleteExpiredUsers,userRepository.deleteById(userId)
com.example.service.OrderService,cleanupOldOrders,orderRepository.deleteByCreatedAtBefore(cutoffDate)
com.example.service.ProductService,removeProduct,productRepository.delete(product)
```

### Fields

1. **ClassName**: Fully qualified name of the class containing the hard delete
2. **MethodName**: Name of the method where the delete occurs
3. **MethodCallExpression**: The actual delete method call

## Detection Logic

### 1. Standard JPA Repository Methods

The tool detects these standard methods:

- `delete(entity)`
- `deleteById(id)`
- `deleteAll()`
- `deleteAll(entities)`
- `deleteAllById(ids)`
- `deleteInBatch(entities)`
- `deleteAllInBatch()`
- `deleteAllByIdInBatch(ids)`

### 2. Derived Query Methods

Methods following these patterns:

- `deleteBy*` (e.g., `deleteByStatus`, `deleteByCreatedAtBefore`)
- `removeBy*` (e.g., `removeByUserId`)

### 3. Soft Delete Exemption

A method is **NOT** flagged as a hard delete if the repository has a custom `@Query` annotation with an `UPDATE` statement:

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // This is a SOFT DELETE (not flagged)
    @Query("UPDATE User u SET u.deleted = true WHERE u.id = :id")
    @Modifying
    void deleteById(@Param("id") Long id);
    
    // This is a HARD DELETE (flagged)
    @Query("DELETE FROM User u WHERE u.id = :id")
    @Modifying
    void hardDeleteById(@Param("id") Long id);
}
```

## Examples

### Example 1: Hard Delete in Service

**Code:**
```java
@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;
    
    public void removeOrder(Long orderId) {
        orderRepository.deleteById(orderId);  // Hard delete detected
    }
}
```

**Output:**
```csv
com.example.service.OrderService,removeOrder,orderRepository.deleteById(orderId)
```

### Example 2: Soft Delete (Not Flagged)

**Repository:**
```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("UPDATE User u SET u.deleted = true, u.deletedAt = :deletedAt WHERE u.id = :id")
    @Modifying
    void deleteById(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);
}
```

**Service:**
```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    public void deleteUser(Long userId) {
        userRepository.deleteById(userId, LocalDateTime.now());  // NOT flagged (soft delete)
    }
}
```

**Output:**
```
(no output - this is a soft delete)
```

### Example 3: Derived Query Hard Delete

**Repository:**
```java
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
```

**Service:**
```java
@Service
public class SessionCleanupService {
    @Autowired
    private SessionRepository sessionRepository;
    
    public void cleanupExpiredSessions() {
        sessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());  // Hard delete detected
    }
}
```

**Output:**
```csv
com.example.service.SessionCleanupService,cleanupExpiredSessions,sessionRepository.deleteByExpiresAtBefore(LocalDateTime.now())
```

## Use Cases

### 1. Code Audit

Identify all hard deletes in the codebase:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" \
  > audit-report.csv
```

### 2. Pre-Commit Hook

Add to CI/CD to prevent new hard deletes:

```bash
#!/bin/bash
HARD_DELETES=$(mvn -q exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" | wc -l)

if [ $HARD_DELETES -gt 0 ]; then
    echo "❌ Hard deletes detected!"
    mvn -q exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete"
    exit 1
fi
```

### 3. Migration Planning

Identify hard deletes that need conversion to soft deletes:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" | \
  awk -F',' '{print $1}' | sort -u
```

### 4. Documentation

Generate documentation of deletion behavior:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.HardDelete" | \
  awk -F',' '{print "- " $2 " in " $1}' > deletion-inventory.md
```

## Configuration

Ensure `generator.yml` is configured:

```yaml
variables:
  projects_folder: ${HOME}/your-projects

base_path: ${projects_folder}/your-project/
```

## Converting Hard Deletes to Soft Deletes

### Step 1: Add Soft Delete Fields

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;
    
    private String email;
    
    // Add soft delete fields
    private Boolean deleted = false;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    // getters and setters
}
```

### Step 2: Override Delete Methods

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    @Query("UPDATE User u SET u.deleted = true, u.deletedAt = :deletedAt WHERE u.id = :id")
    @Modifying
    @Override
    void deleteById(@Param("id") Long id);
    
    default void deleteById(Long id) {
        deleteById(id, LocalDateTime.now());
    }
    
    // Add custom finder to exclude deleted
    @Query("SELECT u FROM User u WHERE u.deleted = false")
    List<User> findAllActive();
}
```

### Step 3: Add Soft Delete Filter

```java
@Entity
@Table(name = "users")
@Where(clause = "deleted = false")
@SQLDelete(sql = "UPDATE users SET deleted = true, deleted_at = NOW() WHERE id = ?")
public class User {
    // ...
}
```

## Limitations

- **Custom SQL**: Only analyzes `@Query` annotations, not native SQL in services
- **Cascading Deletes**: Doesn't analyze JPA cascade configurations
- **Indirect Deletes**: Doesn't track deletes through EntityManager
- **Bulk Operations**: May not detect all bulk delete scenarios

## Best Practices

1. **Establish Policy**: Define when hard deletes are acceptable (e.g., test data, temporary records)
2. **Document Exceptions**: Maintain a whitelist of approved hard deletes
3. **Regular Audits**: Run HardDelete tool periodically
4. **Code Reviews**: Include hard delete check in PR reviews
5. **Education**: Train team on soft delete patterns

## Related Tools

- **TestFixer**: Can refactor tests to use soft delete patterns
- **QueryOptimizationChecker**: Analyzes query patterns

## See Also

- [Testing Best Practices](sdet_reference_guide.md) - Test patterns for soft deletes
- [Query Optimization](../README.md) - Optimizing soft delete queries
