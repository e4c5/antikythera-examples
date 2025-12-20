package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Abstract base class for Spring Boot version migrations using Template Method
 * pattern.
 * 
 * <p>
 * Defines the standard migration flow that all Spring Boot version migrators
 * follow:
 * <ol>
 * <li>Initialize components</li>
 * <li>Migrate POM dependencies</li>
 * <li>Migrate configuration properties</li>
 * <li>Execute version-specific code migrations</li>
 * <li>Write modified files</li>
 * <li>Validate migration</li>
 * </ol>
 * 
 * <p>
 * Subclasses implement version-specific migration logic via hook methods:
 * <ul>
 * <li>{@link #initializeComponents()} - Create version-specific migrators</li>
 * <li>{@link #migratePom()} - POM migration logic</li>
 * <li>{@link #migrateProperties()} - Property file migration logic</li>
 * <li>{@link #executeVersionSpecificMigrations()} - Version-specific migrations
 * (Kafka, Cassandra, etc.)</li>
 * <li>{@link #validate()} - Post-migration validation</li>
 * <li>{@link #getSourceVersion()} - Source Spring Boot version (e.g.,
 * "2.2")</li>
 * <li>{@link #getTargetVersion()} - Target Spring Boot version (e.g.,
 * "2.3")</li>
 * </ul>
 * 
 * <p>
 * Example subclass:
 * 
 * <pre>
 * {
 *     &#64;code
 *     public class SpringBoot22to23Migrator extends AbstractSpringBootMigrator {
 *         &#64;Override
 *         protected String getSourceVersion() {
 *             return "2.2";
 *         }
 * 
 *         &#64;Override
 *         protected String getTargetVersion() {
 *             return "2.3";
 *         }
 * 
 *         &#64;Override
 *         protected void initializeComponents() throws IOException {
 *             this.pomMigrator = new PomMigrator22to23(dryRun);
 *             this.propertyMigrator = new PropertyMigrator22to23(dryRun);
 *             // ... other version-specific migrators
 *         }
 * 
 *         @Override
 *         protected void executeVersionSpecificMigrations() {
 *             result.addPhase("Validation Starter", validationDetector.migrate());
 *             // ... other version-specific phases
 *         }
 *     }
 * }
 * </pre>
 * 
 * @see MigrationPhase
 * @see MigrationResult
 * @see MigrationPhaseResult
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public abstract class AbstractSpringBootMigrator {
    private static final Logger logger = LoggerFactory.getLogger(AbstractSpringBootMigrator.class);

    protected final boolean dryRun;
    protected final MigrationResult result;
    protected final Set<String> modifiedFiles;
    protected MigrationValidator validator;

    /**
     * Constructor for abstract migrator.
     * 
     * @param dryRun if true, no files will be modified (preview mode)
     */
    protected AbstractSpringBootMigrator(boolean dryRun) {
        this.dryRun = dryRun;
        this.result = new MigrationResult();
        this.modifiedFiles = new HashSet<>();
    }

    /**
     * Template method that defines the migration flow.
     * 
     * <p>
     * This method should NOT be overridden. Subclasses customize behavior via hook
     * methods.
     * 
     * @return Migration result with details of all changes
     * @throws Exception if migration fails
     */
    public final MigrationResult migrateAll() throws Exception {
        logger.info("Starting Spring Boot {} → {} migration (dry-run: {})",
                getSourceVersion(), getTargetVersion(), dryRun);

        // Initialize components
        logger.info("Initializing migration components...");
        initializeComponents();

        // Phase 1: POM Migration (always first)
        logger.info("Phase 1: Migrating POM dependencies...");
        MigrationPhaseResult pomResult = migratePom();
        result.addPhase("POM Migration", pomResult);

        if (pomResult.hasCriticalErrors()) {
            logger.error("Critical errors in POM migration. Stopping migration.");
            return result;
        }

        // Phase 2: Property Files
        logger.info("Phase 2: Migrating property files...");
        MigrationPhaseResult propertyResult = migrateProperties();
        result.addPhase("Property Migration", propertyResult);

        // Phase 3: Version-Specific Code Migrations
        logger.info("Phase 3: Executing version-specific migrations...");
        executeVersionSpecificMigrations();

        // Phase 4: Write modified files to disk
        if (!dryRun && !modifiedFiles.isEmpty()) {
            logger.info("Phase 4: Writing {} modified files to disk...", modifiedFiles.size());
            writeModifiedFiles();
        } else if (dryRun && !modifiedFiles.isEmpty()) {
            logger.info("Dry-run mode: {} files would be modified", modifiedFiles.size());
        }

        // Phase 5: Validation
        if (!dryRun) {
            logger.info("Phase 5: Validating migration...");
            MigrationPhaseResult validationResult = validate();
            result.addPhase("Validation", validationResult);
        }

        logger.info("Migration completed. Status: {}", result.isSuccessful() ? "SUCCESS" : "FAILED");
        return result;
    }

    /**
     * Write all modified compilation units to disk using LexicalPreservingPrinter.
     * 
     * <p>
     * This method is final to ensure consistent file writing across all migrators.
     * It attempts to use LexicalPreservingPrinter for minimal formatting changes,
     * falling back to standard toString() if lexical preservation fails.
     * 
     * @throws IOException if file writing fails
     */
    protected final void writeModifiedFiles() throws IOException {
        for (String className : modifiedFiles) {
            CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
            if (cu == null) {
                logger.warn("Could not find CompilationUnit for {}", className);
                continue;
            }

            String relativePath = AbstractCompiler.classToPath(className);
            Path fullPath = Path.of(Settings.getBasePath(), "src/main/java", relativePath);

            String content;
            try {
                content = LexicalPreservingPrinter.print(cu);
            } catch (Exception e) {
                logger.warn("LexicalPreservingPrinter failed for {}, using default printer", className);
                content = cu.toString();
            }

            Files.writeString(fullPath, content);
            logger.info("Wrote modified file: {}", fullPath);
        }
    }

    /**
     * Print a summary of the migration results to standard output.
     * 
     * <p>
     * This method is final to ensure consistent reporting across all migrators.
     */
    protected final void printSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("Spring Boot %s → %s Migration %s%n",
                getSourceVersion(), getTargetVersion(), dryRun ? "(DRY RUN)" : "");
        System.out.println("=".repeat(80));
        System.out.println(result.getSummary());
    }

    // ==================== Hook Methods - Subclasses Must Implement
    // ====================

    /**
     * Initialize all migration components.
     * 
     * <p>
     * Subclasses should:
     * <ul>
     * <li>Load configuration via {@link Settings#loadConfigMap()}</li>
     * <li>Enable lexical preservation via
     * {@link AbstractCompiler#setEnableLexicalPreservation(boolean)}</li>
     * <li>Pre-process source files via {@link AbstractCompiler#preProcess()}</li>
     * <li>Create all version-specific migrators</li>
     * <li>Initialize the validator</li>
     * </ul>
     * 
     * @throws IOException if initialization fails
     */
    protected abstract void initializeComponents() throws IOException;

    /**
     * Execute POM migration for this Spring Boot version.
     * 
     * <p>
     * Typically delegates to a version-specific PomMigrator implementation.
     * 
     * @return result of POM migration phase
     */
    protected abstract MigrationPhaseResult migratePom();

    /**
     * Execute property file migration for this Spring Boot version.
     * 
     * <p>
     * Typically delegates to a version-specific PropertyMigrator implementation.
     * 
     * @return result of property migration phase
     */
    protected abstract MigrationPhaseResult migrateProperties();

    /**
     * Execute version-specific code migrations.
     * 
     * <p>
     * Subclasses should call
     * {@link MigrationResult#addPhase(String, MigrationPhaseResult)}
     * for each migration phase executed. For example:
     * 
     * <pre>{@code
     * result.addPhase("Validation Starter Detection", validationDetector.migrate());
     * result.addPhase("Cassandra Driver v4", cassandraMigrator.migrate());
     * }</pre>
     * 
     * <p>
     * Also track modified classes via {@link #modifiedFiles}:
     * 
     * <pre>{@code
     * MigrationPhaseResult cassandraResult = cassandraMigrator.migrate();
     * modifiedFiles.addAll(cassandraResult.getModifiedClasses());
     * result.addPhase("Cassandra Driver v4", cassandraResult);
     * }</pre>
     */
    protected abstract void executeVersionSpecificMigrations() throws Exception;

    /**
     * Validate the migration after completion.
     * 
     * <p>
     * Typically delegates to {@link MigrationValidator}.
     * 
     * @return result of validation phase
     */
    protected abstract MigrationPhaseResult validate();

    /**
     * Get the source Spring Boot version.
     * 
     * @return source version (e.g., "2.1", "2.2", "2.3")
     */
    protected abstract String getSourceVersion();

    /**
     * Get the target Spring Boot version.
     * 
     * @return target version (e.g., "2.2", "2.3", "2.4")
     */
    protected abstract String getTargetVersion();

    // ==================== Accessors ====================

    /**
     * Get the migration result.
     * 
     * @return migration result
     */
    public final MigrationResult getResult() {
        return result;
    }

    /**
     * Check if this is a dry-run migration.
     * 
     * @return true if dry-run mode
     */
    public final boolean isDryRun() {
        return dryRun;
    }
}
