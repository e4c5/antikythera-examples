package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Main orchestrator for Spring Boot 2.4 to 2.5 migration.
 * 
 * <p>
 * Coordinates all migration phases including:
 * <ul>
 * <li>POM dependency updates</li>
 * <li>SQL script property transformations (spring.datasource.* →
 * spring.sql.init.*)</li>
 * <li>Actuator /info endpoint configuration and Spring Security
 * integration</li>
 * <li>Cassandra throttling configuration</li>
 * <li>Groovy 3.x and Spock 2.0 upgrades</li>
 * <li>Deprecated code detection and fixing (code removed from Spring Boot
 * 2.5)</li>
 * <li>Error message attribute usage detection</li>
 * <li>Validation and reporting</li>
 * </ul>
 * 
 * <p>
 * Key changes in Spring Boot 2.5:
 * <ul>
 * <li><b>CRITICAL</b>: SQL script initialization properties moved to
 * spring.sql.init.* namespace</li>
 * <li><b>CRITICAL</b>: /info actuator endpoint no longer exposed by
 * default</li>
 * <li>Cassandra throttling defaults removed - must configure explicitly</li>
 * <li>Groovy upgraded to 3.x (requires Spock 2.0+)</li>
 * <li>Code deprecated in Spring Boot 2.3 has been removed</li>
 * <li>Error message attribute is removed (not blanked) from error
 * responses</li>
 * <li>Java 17 support added (first version to support Java 17 LTS)</li>
 * </ul>
 * 
 * @see AbstractSpringBootMigrator
 */
@Command(name = "spring-boot-24to25-migrator", mixinStandardHelpOptions = true,
        version = "Spring Boot 2.4 → 2.5 Migrator v1.0", description = "Migrates Spring Boot 2.4 applications to 2.5")
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot24to25Migrator extends AbstractSpringBootMigrator implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot24to25Migrator.class);

    // Migration components - version specific
    private PomMigrator24to25 pomMigrator;
    private SqlScriptPropertiesMigrator sqlScriptMigrator;
    private ActuatorInfoMigrator actuatorMigrator;
    private CassandraThrottlingMigrator cassandraMigrator;
    private GroovySpockMigrator groovyMigrator;
    private DeprecatedCodeFixer deprecatedCodeFixer;
    private ErrorMessageAttributeDetector errorMessageDetector;

    // CLI Options
    @Option(names = { "--dry-run" }, description = "Run migration without making changes")
    private boolean cliDryRun = false;

    @Option(names = {
            "--project-path" }, description = "Path to Spring Boot project (default: current directory)", paramLabel = "<path>")
    private String projectPath;

    /**
     * Default constructor for Picocli.
     */
    public SpringBoot24to25Migrator() {
        super(false); // Will be set by CLI
    }

    /**
     * Constructor with default settings.
     * For programmatic/testing use.
     * 
     * @param dryRun if true, no files will be modified
     */
    public SpringBoot24to25Migrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    protected void initializeComponents() throws IOException {
        logger.info("Initializing Spring Boot 2.4 to 2.5 migration components...");

        // Load configuration and pre-process source files
        Settings.loadConfigMap();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();

        // Initialize version-specific migrators
        this.pomMigrator = new PomMigrator24to25(dryRun);
        this.sqlScriptMigrator = new SqlScriptPropertiesMigrator(dryRun);

        // Initialize configuration migrators
        this.actuatorMigrator = new ActuatorInfoMigrator(dryRun);
        this.cassandraMigrator = new CassandraThrottlingMigrator(dryRun);
        this.groovyMigrator = new GroovySpockMigrator(dryRun);

        // Initialize code fixer and detectors
        this.deprecatedCodeFixer = new DeprecatedCodeFixer(dryRun);
        this.errorMessageDetector = new ErrorMessageAttributeDetector(dryRun);

        // Initialize validator
        this.validator = new MigrationValidator(dryRun);
    }

    @Override
    protected MigrationPhaseResult migratePom() throws Exception {
        return pomMigrator.migrate();
    }

    @Override
    protected MigrationPhaseResult migrateProperties() {
        return sqlScriptMigrator.migrate();
    }

    @Override
    protected void executeVersionSpecificMigrations() throws Exception {
        // Phase 3: Configuration Migrations (CRITICAL)
        logger.info("Phase 3: Migrating actuator and security configuration...");
        result.addPhase("Actuator /info Endpoint", actuatorMigrator.migrate());

        // Phase 4: Dependency-Specific Migrations
        logger.info("Phase 4: Migrating Cassandra throttling configuration...");
        result.addPhase("Cassandra Throttling", cassandraMigrator.migrate());

        logger.info("Phase 5: Upgrading Groovy and Spock versions...");
        MigrationPhaseResult groovyResult = groovyMigrator.migrate();
        modifiedFiles.addAll(groovyResult.getModifiedClasses());
        result.addPhase("Groovy/Spock Upgrade", groovyResult);

        // Phase 6: Code Fixes
        logger.info("Phase 6: Fixing deprecated code...");
        MigrationPhaseResult deprecatedResult = deprecatedCodeFixer.migrate();
        modifiedFiles.addAll(deprecatedResult.getModifiedClasses());
        result.addPhase("Deprecated Code Fixes", deprecatedResult);

        // Phase 7: Detection (Error message attribute)
        logger.info("Phase 7: Detecting error message attribute usage...");
        MigrationPhaseResult errorMessageResult = errorMessageDetector.migrate();
        result.addPhase("Error Message Attribute Detection", errorMessageResult);
    }

    @Override
    protected MigrationPhaseResult validate() {
        try {
            return validator.migrate();
        } catch (IOException | InterruptedException e) {
            MigrationPhaseResult result = new MigrationPhaseResult();
            result.addError("Validation failed: " + e.getMessage());
            logger.error("Validation failed", e);
            return result;
        }
    }

    @Override
    protected String getSourceVersion() {
        return "2.4";
    }

    @Override
    protected String getTargetVersion() {
        return "2.5";
    }

    /**
     * Print a summary of the migration results.
     * Delegates to base class implementation.
     */
    public void printReport() {
        printSummary();
    }

    /**
     * Picocli call method - executes the migration.
     * Includes special config loading logic for this migrator.
     * 
     * @return exit code (0 for success, 1 for failure)
     */
    @Override
    public Integer call() throws Exception {
        // Load configuration first (required before setting properties)
        // This is specific to the 24to25 migrator
        try {
            Settings.loadConfigMap(new java.io.File("src/test/resources/generator.yml"));
        } catch (Exception e) {
            // If config file not found, just initialize Settings props
            Settings.loadConfigMap();
        }

        // Set project path if provided
        if (projectPath != null) {
            Settings.setProperty(Settings.BASE_PATH, projectPath);
            logger.info("Using project path: {}", projectPath);
        }

        // Create migrator with CLI flags
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(cliDryRun);

        // Run migration
        MigrationResult result = migrator.migrateAll();

        // Print detailed report
        migrator.printReport();

        return result.isSuccessful() ? 0 : 1;
    }

    /**
     * Main method for command-line execution.
     * 
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpringBoot24to25Migrator()).execute(args);
        System.exit(exitCode);
    }
}
