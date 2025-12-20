package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;

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
 * <li>Java 17 support added (first version to support Java 17 LTS)</li>
 * </ul>
 * 
 * @see AbstractSpringBootMigrator
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot24to25Migrator extends AbstractSpringBootMigrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot24to25Migrator.class);

    // Migration components - version specific
    private PomMigrator24to25 pomMigrator;
    private SqlScriptPropertiesMigrator sqlScriptMigrator;
    private ActuatorInfoMigrator actuatorMigrator;
    private CassandraThrottlingMigrator cassandraMigrator;
    private GroovySpockMigrator groovyMigrator;
    private DeprecatedCodeFixer deprecatedCodeFixer;

    /**
     * Constructor with default settings.
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

        // Initialize code fixer
        this.deprecatedCodeFixer = new DeprecatedCodeFixer(dryRun);

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
     * Main method for command-line execution.
     * 
     * <p>
     * Usage:
     * 
     * <pre>
     * java com.raditha.spring.SpringBoot24to25Migrator [--dry-run] 
     *      [--project-path &lt;path&gt;]
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        String projectPath = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--project-path":
                    if (i + 1 < args.length) {
                        projectPath = args[++i];
                    } else {
                        System.err.println("Error: --project-path requires a path argument");
                        printUsageAndExit();
                    }
                    break;
                case "--help":
                case "-h":
                    printUsageAndExit();
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsageAndExit();
            }
        }

        // Set project path if provided
        if (projectPath != null) {
            Settings.setProperty(Settings.BASE_PATH, projectPath);
            logger.info("Using project path: {}", projectPath);
        }

        // Run migration
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(dryRun);
        MigrationResult result = migrator.migrateAll();

        // Print detailed report
        migrator.printReport();

        // Exit with appropriate code
        System.exit(result.isSuccessful() ? 0 : 1);
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java com.raditha.spring.SpringBoot24to25Migrator [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run               Run migration without making changes");
        System.out.println("  --project-path <path>   Path to Spring Boot project (default: current directory)");
        System.out.println("  --help, -h              Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println(
                "  java com.raditha.spring.SpringBoot24to25Migrator --dry-run --project-path /path/to/project");
        System.exit(1);
    }
}
