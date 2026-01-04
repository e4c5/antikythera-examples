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
 * Main orchestrator for Spring Boot 2.3 to 2.4 migration.
 * 
 * <p>
 * Coordinates all migration phases including:
 * <ul>
 * <li>POM dependency updates</li>
 * <li>Property file transformations</li>
 * <li>Configuration file processing (profile syntax migration for YAML and
 * properties)</li>
 * <li>Data.sql initialization ordering fixes</li>
 * <li>Neo4j property namespace changes and OGM detection</li>
 * <li>Logback property restructuring</li>
 * <li>Elasticsearch RestClient detection</li>
 * <li>Hazelcast 4.x upgrade detection</li>
 * <li>HTTP trace configuration detection (cookie exclusion behavior)</li>
 * <li>R2DBC code detection (moved to Spring Framework 5.3)</li>
 * <li>Validation and reporting</li>
 * </ul>
 * 
 * <p>
 * Key changes in Spring Boot 2.4:
 * <ul>
 * <li><b>CRITICAL</b>: Configuration file processing overhauled - profile
 * syntax changed</li>
 * <li><b>CRITICAL</b>: data.sql processing timing changed (requires
 * defer-datasource-initialization)</li>
 * <li>Neo4j property namespace changed from spring.data.neo4j.* to
 * spring.neo4j.*</li>
 * <li>Logback properties moved to logging.logback.rollingpolicy.*
 * namespace</li>
 * <li>Elasticsearch RestClient auto-configuration removed</li>
 * <li>Hazelcast upgraded to 4.x (breaking API changes)</li>
 * <li>HTTP traces exclude cookies by default</li>
 * <li>R2DBC infrastructure moved to Spring Framework 5.3</li>
 * </ul>
 * 
 * @see AbstractSpringBootMigrator
 */
@Command(name = "spring-boot-23to24-migrator", mixinStandardHelpOptions = true,
        version = "Spring Boot 2.3 → 2.4 Migrator v1.0", description = "Migrates Spring Boot 2.3 applications to 2.4")
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot23to24Migrator extends AbstractSpringBootMigrator implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot23to24Migrator.class);

    // Migration components - version specific
    private PomMigrator23to24 pomMigrator;
    private PropertyMigrator23to24 propertyMigrator;

    // Critical migrators (Priority 1)
    private ConfigurationProcessingMigrator configMigrator;
    private DataSqlMigrator dataSqlMigrator;

    // High priority migrators (Priority 2)
    private Neo4jPropertyMigrator neo4jMigrator;
    private LogbackPropertyMigrator logbackMigrator;

    // Code detection migrators (Priority 3)
    private ElasticsearchCodeMigrator23to24 elasticsearchMigrator;
    private HazelcastCodeMigrator hazelcastMigrator;

    // Additional detection migrators (Priority 4)
    private HttpTracesConfigMigrator httpTracesMigrator;
    private R2dbcCodeMigrator r2dbcMigrator;

    // CLI Options
    @Option(names = { "--dry-run" }, description = "Run migration without making changes")
    private boolean cliDryRun = false;

    @Option(names = {
            "--project-path" }, description = "Path to Spring Boot project (default: current directory)", paramLabel = "<path>")
    private String projectPath;

    /**
     * Default constructor for Picocli.
     */
    public SpringBoot23to24Migrator() {
        super(false); // Will be set by CLI
    }

    /**
     * Constructor with default settings.
     * For programmatic/testing use.
     * 
     * @param dryRun if true, no files will be modified
     */
    public SpringBoot23to24Migrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    protected void initializeComponents() throws IOException {
        logger.info("Initializing Spring Boot 2.3 to 2.4 migration components...");

        // Load configuration and pre-process source files
        Settings.loadConfigMap();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();

        // Initialize version-specific migrators
        this.pomMigrator = new PomMigrator23to24(dryRun);
        this.propertyMigrator = new PropertyMigrator23to24(dryRun);

        // Initialize critical migrators for Spring Boot 2.4
        this.configMigrator = new ConfigurationProcessingMigrator(dryRun);
        this.dataSqlMigrator = new DataSqlMigrator(dryRun);

        // Initialize high priority migrators
        this.neo4jMigrator = new Neo4jPropertyMigrator(dryRun);
        this.logbackMigrator = new LogbackPropertyMigrator(dryRun);

        // Initialize code detection migrators
        this.elasticsearchMigrator = new ElasticsearchCodeMigrator23to24(dryRun);
        this.hazelcastMigrator = new HazelcastCodeMigrator(dryRun);

        // Initialize additional detection migrators
        this.httpTracesMigrator = new HttpTracesConfigMigrator(dryRun);
        this.r2dbcMigrator = new R2dbcCodeMigrator(dryRun);

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
        // Phase 3: Configuration Processing (CRITICAL - Spring Boot 2.4 changed profile
        // syntax)
        logger.info("Phase 3: Migrating configuration file processing...");
        result.addPhase("Configuration Processing", configMigrator.migrate());

        // Phase 4: Data.sql Processing (CRITICAL - timing changed in Spring Boot 2.4)
        logger.info("Phase 4: Checking data.sql configuration...");
        result.addPhase("Data.sql Processing", dataSqlMigrator.migrate());

        // Phase 5: Neo4j Property Migration (HIGH - property namespace changed)
        logger.info("Phase 5: Migrating Neo4j properties...");
        result.addPhase("Neo4j Properties", neo4jMigrator.migrate());

        // Phase 6: Logback Property Migration (MEDIUM - property restructuring)
        logger.info("Phase 6: Migrating Logback properties...");
        result.addPhase("Logback Properties", logbackMigrator.migrate());

        // Phase 7: Elasticsearch Code Detection (auto-configuration removed)
        logger.info("Phase 7: Detecting Elasticsearch RestClient usage...");
        result.addPhase("Elasticsearch Detection", elasticsearchMigrator.migrate());

        // Phase 8: Hazelcast Detection (version upgrade to 4.x)
        logger.info("Phase 8: Detecting Hazelcast usage...");
        result.addPhase("Hazelcast Detection", hazelcastMigrator.migrate());

        // Phase 9: HTTP Traces Configuration Detection (cookie exclusion behavior
        // change)
        logger.info("Phase 9: Detecting HTTP trace configuration...");
        result.addPhase("HTTP Traces Detection", httpTracesMigrator.migrate());

        // Phase 10: R2DBC Code Detection (moved to Spring Framework 5.3)
        logger.info("Phase 10: Detecting R2DBC usage...");
        result.addPhase("R2DBC Detection", r2dbcMigrator.migrate());
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
        return "2.3";
    }

    @Override
    protected String getTargetVersion() {
        return "2.4";
    }

    /**
     * Picocli call method - executes the migration.
     * 
     * @return exit code (0 for success, 1 for failure)
     */
    @Override
    public Integer call() throws Exception {
        // Create migrator with CLI flags
        SpringBoot23to24Migrator migrator = new SpringBoot23to24Migrator(cliDryRun);

        // Set project path if provided
        if (projectPath != null) {
            Settings.setProperty(Settings.BASE_PATH, projectPath);
            logger.info("Using project path: {}", projectPath);
        }

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
        int exitCode = new CommandLine(new SpringBoot23to24Migrator()).execute(args);
        System.exit(exitCode);
    }
}
