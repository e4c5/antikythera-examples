package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

/**
 * Main orchestrator for Spring Boot 2.1 to 2.2 migration.
 * 
 * Coordinates all migration phases including:
 * - POM dependency updates
 * - Property file transformations
 * - Code migrations (Kafka, Redis, Hibernate)
 * - Configuration optimizations (JMX, ConfigurationPropertiesScan)
 * - Validation and reporting
 * 
 * All migrations are fully automated with no manual work required.
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot21to22Migrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot21to22Migrator.class);

    private final boolean dryRun;
    private final MigrationResult result;

    // Migration components
    private SpringBootPomMigrator pomMigrator;
    private PropertyFileMigrator propertyMigrator;
    private KafkaCodeMigrator kafkaMigrator;
    private RedisCodeMigrator redisMigrator;
    private HibernateCodeMigrator hibernateMigrator;
    private JmxConfigDetector jmxDetector;
    private ConfigPropertiesScanMigrator configPropsMigrator;
    private MigrationValidator validator;

    public SpringBoot21to22Migrator(boolean dryRun) {
        this.dryRun = dryRun;
        this.result = new MigrationResult();
        initializeComponents();
    }

    /**
     * Initialize all migration components.
     */
    private void initializeComponents() {
        logger.info("Initializing Spring Boot 2.1 to 2.2 migration components...");

        this.pomMigrator = new SpringBootPomMigrator(dryRun);
        this.propertyMigrator = new PropertyFileMigrator(dryRun);
        this.kafkaMigrator = new KafkaCodeMigrator(dryRun);
        this.redisMigrator = new RedisCodeMigrator(dryRun);
        this.hibernateMigrator = new HibernateCodeMigrator(dryRun);
        this.jmxDetector = new JmxConfigDetector(dryRun);
        this.configPropsMigrator = new ConfigPropertiesScanMigrator(dryRun);
        this.validator = new MigrationValidator(dryRun);
    }

    /**
     * Execute the complete migration process.
     * 
     * @return Migration result with details of all changes
     */
    public MigrationResult migrateAll() throws Exception {
        logger.info("Starting Spring Boot 2.1 to 2.2 migration (dry-run: {})", dryRun);

        // Phase 1: POM Migration
        logger.info("Phase 1: Migrating POM dependencies...");
        MigrationPhaseResult pomResult = pomMigrator.migrate();
        result.addPhase("POM Migration", pomResult);

        if (pomResult.hasCriticalErrors()) {
            logger.error("Critical errors in POM migration. Stopping migration.");
            return result;
        }

        // Phase 2: Property Files
        logger.info("Phase 2: Migrating property files...");
        MigrationPhaseResult propertyResult = propertyMigrator.migrate();
        result.addPhase("Property Migration", propertyResult);

        // Phase 3: Code Migrations
        logger.info("Phase 3: Migrating code (Kafka, Redis, Hibernate)...");
        MigrationPhaseResult kafkaResult = kafkaMigrator.migrate();
        result.addPhase("Kafka Migration", kafkaResult);

        MigrationPhaseResult redisResult = redisMigrator.migrate();
        result.addPhase("Redis Migration", redisResult);

        MigrationPhaseResult hibernateResult = hibernateMigrator.migrate();
        result.addPhase("Hibernate Migration", hibernateResult);

        // Phase 4: Configuration Optimizations
        logger.info("Phase 4: Applying configuration optimizations...");
        MigrationPhaseResult jmxResult = jmxDetector.migrate();
        result.addPhase("JMX Detection", jmxResult);

        MigrationPhaseResult configPropsResult = configPropsMigrator.migrate();
        result.addPhase("ConfigurationPropertiesScan", configPropsResult);

        // Phase 5: Validation
        if (!dryRun) {
            logger.info("Phase 5: Validating migration...");
            MigrationPhaseResult validationResult = validator.validate();
            result.addPhase("Validation", validationResult);
        }

        logger.info("Migration completed successfully!");
        return result;
    }

    /**
     * Get the migration result.
     */
    public MigrationResult getResult() {
        return result;
    }

    /**
     * Print a summary of the migration results.
     */
    public void printSummary() {
        System.out.println(result.generateReport());
    }

    /**
     * Main method for command-line execution.
     * 
     * Usage: java com.raditha.spring.SpringBoot21to22Migrator [--dry-run]
     * [--project-path <path>]
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
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(dryRun);
        MigrationResult result = migrator.migrateAll();

        // Print results
        System.out.println("\n" + "=".repeat(80));
        System.out.println("Spring Boot 2.1 → 2.2 Migration " + (dryRun ? "(DRY RUN)" : ""));
        System.out.println("=".repeat(80));
        System.out.println(result.getSummary());

        // Exit with appropriate code
        System.exit(result.isSuccessful() ? 0 : 1);

    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java com.raditha.spring.SpringBoot21to22Migrator [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run              Run migration without making changes");
        System.out.println("  --project-path <path>  Path to Spring Boot project (default: current directory)");
        System.out.println("  --help, -h             Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println(
                "  java com.raditha.spring.SpringBoot21to22Migrator --dry-run --project-path /path/to/project");
        System.exit(1);
    }
}
