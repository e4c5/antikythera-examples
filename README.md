# antikythera-examples

This project contains examples and tools for the Antikythera test generation framework, including query optimization tools, code analysis utilities, and consolidated infrastructure components.

## Architecture Overview

The project has been refactored to eliminate code duplication and improve maintainability through consolidated utility components:

### Utility Components

- **FileOperationsManager**: Centralized file I/O operations with UTF-8 encoding and atomic operations
- **GitOperationsManager**: Git repository operations with retry logic and error handling  
- **LiquibaseGenerator**: Liquibase changeset generation with multi-database support
- **RepositoryAnalyzer**: JPA repository analysis and query method extraction

### Main Tools

- **QueryOptimizationChecker**: Analyzes JPA repository queries for optimization opportunities
- **QueryOptimizer**: Applies query optimizations and updates code automatically
- **RepoProcessor**: Batch processes multiple repositories for analysis
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
- Locate the antikythera library (sa.com.cloudsolutions:antikythera:0.1.1) that this project depends on.
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

## Usage Examples

### Using Utility Components

#### FileOperationsManager
```java
// Read file content
String content = FileOperationsManager.readFileContent(Paths.get("example.txt"));

// Write file with atomic operation
FileOperationsManager.atomicWriteFileContent(Paths.get("output.txt"), content);

// Append to file
FileOperationsManager.appendToFile(Paths.get("log.txt"), "New log entry\n");
```

#### GitOperationsManager
```java
// Find and checkout development branch
String branch = GitOperationsManager.findAndCheckoutBranch(repoPath, "develop", "main");

// Check repository status
String status = GitOperationsManager.getRepositoryStatus(repoPath);

// Pull latest changes
GitOperationsManager.pullLatest(repoPath);
```

#### LiquibaseGenerator
```java
// Create single-column index
String changeset = generator.createIndexChangeset("users", "email");

// Create multi-column index
String multiIndex = generator.createMultiColumnIndexChangeset("orders", 
    List.of("customer_id", "order_date"));

// Write to Liquibase file
LiquibaseGenerator.WriteResult result = generator.writeChangesetToFile(
    masterFile, changeset);
```

#### RepositoryAnalyzer
```java
// Check if TypeWrapper is JPA repository
boolean isRepo = RepositoryAnalyzer.isJpaRepository(typeWrapper);

// Extract repository methods
List<RepositoryMethod> methods = RepositoryAnalyzer.extractRepositoryMethods(repository);

// Analyze repository metadata
RepositoryMetadata metadata = RepositoryAnalyzer.analyzeRepository(fqn, typeWrapper);
```

### Running Query Optimization Tools

#### Query Analysis (Read-only)
```bash
java -cp target/classes sa.com.cloudsolutions.antikythera.examples.QueryOptimizationChecker
```

#### Query Optimization (Modifies code)
```bash
java -cp target/classes sa.com.cloudsolutions.antikythera.examples.QueryOptimizer
```

#### Batch Repository Processing
```bash
java -cp target/classes sa.com.cloudsolutions.antikythera.examples.RepoProcessor /path/to/projects
```

## Migration Guide

The refactoring consolidated similar functionality into utility components. If you have custom code that used the old patterns:

1. **File Operations**: Replace direct `Files.readString()` calls with `FileOperationsManager.readFileContent()`
2. **Git Operations**: Replace custom git command execution with `GitOperationsManager` methods
3. **Liquibase Generation**: Use `LiquibaseGenerator` instead of custom XML building
4. **Repository Analysis**: Use `RepositoryAnalyzer.isJpaRepository()` for consistent detection

Notes
- No repository changes are required for any of the approaches above. The pom already declares dependencies explicitly so IntelliJ resolves them reliably. 
