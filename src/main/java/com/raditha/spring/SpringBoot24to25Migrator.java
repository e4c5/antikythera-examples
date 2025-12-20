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
 * <li>SQL script property transformations (spring.datasource.* → spring.sql.init.*)</li>
 * <li>Actuator /info endpoint exposure and security configuration</li>
 * <li>Cassandra throttling configuration (defaults removed in 2.5)</li>
 * <li>Groovy 3.x and Spock 2.0+ upgrades</li>
 * <li>Deprecated code detection (code deprecated in 2.3)</li>
 * <li>Validation and reporting</li>
 * </ul>
 * 
 * <p>
 * Key changes in Spring Boot 2.5:
 * <ul>
 * <li><b>CRITICAL</b>: SQL script initialization redesigned - properties moved from spring.datasource.* to spring.sql.init.*</li>
 * <li><b>CRITICAL</b>: /info actuator endpoint no longer exposed by default, requires auth with Spring Security</li>
 * <li><b>HIGH</b>: Cassandra throttling default values removed - explicit config required</li>
 * <li><b>HIGH</b>: Groovy upgraded to 3.x - requires Spock 2.0+ for tests</li>
 * <li>Code deprecated in Spring Boot 2.3 removed</li>
 * <li>Default EL implementation changed from Glassfish to Tomcat</li>
 * <li>Error view message attribute now removed (not blanked)</li>
 * <li>Java 16 and 17 support added</li>
 * </ul>
 * 
 * @see AbstractSpringBootMigrator
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot24to25Migrator extends AbstractSpringBootMigrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot24to25Migrator.class);

    // Migration components - version specific
    private PomMigrator24to25 pomMigrator;
    private PropertyMigrator24to25 propertyMigrator;
    
    // Critical migrators (Priority 1)
    private SqlScriptPropertiesMigrator sqlScriptMigrator;
    private ActuatorInfoMigrator actuatorMigrator;
    
    // High priority migrators (Priority 2)
    private CassandraThrottlingMigrator cassandraMigrator;
    private GroovySpockMigrator groovyMigrator;
    
    // Detection migrators (Priority 3)
    private DeprecatedCodeDetector24to25 deprecatedCodeDetector;

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
        this.propertyMigrator = new PropertyMigrator24to25(dryRun);

        // Initialize critical migrators for Spring Boot 2.5
        this.sqlScriptMigrator = new SqlScriptPropertiesMigrator(dryRun);
        this.actuatorMigrator = new ActuatorInfoMigrator(dryRun);

        // Initialize high priority migrators
        this.cassandraMigrator = new CassandraThrottlingMigrator(dryRun);
        this.groovyMigrator = new GroovySpockMigrator(dryRun);

        // Initialize detection migrators
        this.deprecatedCodeDetector = new DeprecatedCodeDetector24to25(dryRun);

        // Initialize validator
        this.validator = new MigrationValidator(dryRun);
    }

    @Override
    protected MigrationPhaseResult migratePom() throws Exception {
        return pomMigrator.migrate();
    }

    @Override
    protected MigrationPhaseResult migrateProperties() {
        return propertyMigrator.migrate();
    }

    @Override
    protected void executeVersionSpecificMigrations() throws Exception {
        // Phase 3: SQL Script Properties (CRITICAL - Spring Boot 2.5 changed initialization properties)
        logger.info("Phase 3: Migrating SQL script initialization properties...");
        result.addPhase("SQL Script Properties", sqlScriptMigrator.migrate());

        // Phase 4: Actuator /info Endpoint (CRITICAL - no longer exposed by default)
        logger.info("Phase 4: Configuring /info actuator endpoint...");
        result.addPhase("Actuator /info Endpoint", actuatorMigrator.migrate());

        // Phase 5: Cassandra Throttling (HIGH - defaults removed)
        logger.info("Phase 5: Configuring Cassandra throttling...");
        result.addPhase("Cassandra Throttling", cassandraMigrator.migrate());

        // Phase 6: Groovy/Spock Upgrade (HIGH - Groovy 3.x requires Spock 2.0+)
        logger.info("Phase 6: Upgrading Groovy and Spock versions...");
        result.addPhase("Groovy/Spock Upgrade", groovyMigrator.migrate());

        // Phase 7: Deprecated Code Detection (code removed from Spring Boot 2.3)
        logger.info("Phase 7: Detecting deprecated code...");
        result.addPhase("Deprecated Code Detection", deprecatedCodeDetector.migrate());
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
