package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;

/**
 * Main orchestrator for Spring Boot 2.2 to 2.3 migration.
 * 
 * <p>
 * Coordinates all migration phases including:
 * <ul>
 * <li>POM dependency updates</li>
 * <li>Property file transformations</li>
 * <li>Validation starter detection and addition</li>
 * <li>H2 console configuration</li>
 * <li>Cassandra driver v4 migration (detection + guide)</li>
 * <li>Elasticsearch REST client migration (detection + guide)</li>
 * <li>Spring Cloud version compatibility</li>
 * <li>Validation and reporting</li>
 * </ul>
 * 
 * <p>
 * Key changes in Spring Boot 2.3:
 * <ul>
 * <li><b>CRITICAL</b>: Validation starter no longer included by default</li>
 * <li>H2 console requires explicit datasource naming</li>
 * <li>Cassandra driver upgraded to v4 (breaking changes)</li>
 * <li>Elasticsearch TransportClient deprecated</li>
 * </ul>
 * 
 * @see AbstractSpringBootMigrator
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot22to23Migrator extends AbstractSpringBootMigrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot22to23Migrator.class);

    // Migration components - version specific
    private PomMigrator22to23 pomMigrator;
    private PropertyMigrator22to23 propertyMigrator;
    private ValidationStarterDetector validationDetector;
    private H2ConfigurationMigrator h2Migrator;
    private CassandraCodeMigrator cassandraMigrator;
    private ElasticsearchCodeMigrator elasticsearchMigrator;
    private SpringCloudVersionMigrator springCloudMigrator;

    // Optional feature flags (disabled by default)
    private final boolean enableCloudNativeFeatures;

    /**
     * Constructor with default settings (no optional features).
     * 
     * @param dryRun if true, no files will be modified
     */
    public SpringBoot22to23Migrator(boolean dryRun) {
        this(dryRun, false);
    }

    /**
     * Constructor with optional feature flags.
     * 
     * @param dryRun                    if true, no files will be modified
     * @param enableCloudNativeFeatures if true, enables optional cloud-native
     *                                  features
     */
    public SpringBoot22to23Migrator(boolean dryRun, boolean enableCloudNativeFeatures) {
        super(dryRun);
        this.enableCloudNativeFeatures = enableCloudNativeFeatures;
    }

    @Override
    protected void initializeComponents() throws IOException {
        logger.info("Initializing Spring Boot 2.2 to 2.3 migration components...");

        // Load configuration and pre-process source files
        Settings.loadConfigMap();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();

        // Initialize version-specific migrators
        this.pomMigrator = new PomMigrator22to23(dryRun);
        this.propertyMigrator = new PropertyMigrator22to23(dryRun);

        // Initialize critical migrators for Spring Boot 2.3
        this.validationDetector = new ValidationStarterDetector(dryRun);
        this.h2Migrator = new H2ConfigurationMigrator(dryRun);

        // Initialize data layer migrators (detection + guide generation)
        this.cassandraMigrator = new CassandraCodeMigrator(dryRun);
        this.elasticsearchMigrator = new ElasticsearchCodeMigrator(dryRun);
        this.springCloudMigrator = new SpringCloudVersionMigrator(dryRun);

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
        // Phase 3: Critical - Validation Starter (HIGHEST PRIORITY for Spring Boot 2.3)
        logger.info("Phase 3: Detecting validation usage and adding starter if needed...");

        MigrationPhaseResult validationResult = validationDetector.migrate();
        result.addPhase("Validation Starter Detection", validationResult);

        // Phase 4: H2 Console Configuration
        logger.info("Phase 4: Checking H2 console configuration...");

        MigrationPhaseResult h2Result = h2Migrator.migrate();
        result.addPhase("H2 Console Configuration", h2Result);

        // Phase 5: Spring Cloud Version Migration
        logger.info("Phase 5: Validating Spring Cloud compatibility...");

        MigrationPhaseResult cloudResult = springCloudMigrator.migrate();
        result.addPhase("Spring Cloud Version Migration", cloudResult);

        // Phase 6: Data Layer Migrations (Detection + Manual Review)
        logger.info("Phase 6: Detecting data layer breaking changes...");

        MigrationPhaseResult cassandraResult = cassandraMigrator.migrate();
        modifiedFiles.addAll(cassandraResult.getModifiedClasses());
        result.addPhase("Cassandra Driver v4 Migration", cassandraResult);

        MigrationPhaseResult esResult = elasticsearchMigrator.migrate();
        modifiedFiles.addAll(esResult.getModifiedClasses());
        result.addPhase("Elasticsearch REST Client Migration", esResult);

        // Phase 7: Optional Cloud Native Features
        if (enableCloudNativeFeatures) {
            logger.info("Phase 7: Applying optional cloud-native enhancements...");
            // TODO: Implement optional features (Layered JARs, Graceful Shutdown, Health
            // Probes)
        }
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
        return "2.2";
    }

    @Override
    protected String getTargetVersion() {
        return "2.3";
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
     * java com.raditha.spring.SpringBoot22to23Migrator [--dry-run] 
     *      [--project-path &lt;path&gt;] [--enable-cloud-native]
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        boolean enableCloudNative = false;
        String projectPath = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--enable-cloud-native":
                    enableCloudNative = true;
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
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(dryRun, enableCloudNative);
        MigrationResult result = migrator.migrateAll();

        // Print detailed report
        migrator.printReport();

        // Exit with appropriate code
        System.exit(result.isSuccessful() ? 0 : 1);
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java com.raditha.spring.SpringBoot22to23Migrator [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run               Run migration without making changes");
        System.out.println("  --project-path <path>   Path to Spring Boot project (default: current directory)");
        System.out.println("  --enable-cloud-native   Enable optional cloud-native features (optional)");
        System.out.println("  --help, -h              Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println(
                "  java com.raditha.spring.SpringBoot22to23Migrator --dry-run --project-path /path/to/project");
        System.out.println(
                "  java com.raditha.spring.SpringBoot22to23Migrator --enable-cloud-native");
        System.exit(1);
    }
}
