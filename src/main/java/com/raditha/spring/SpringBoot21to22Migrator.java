package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;

/**
 * Main orchestrator for Spring Boot 2.1 to 2.2 migration.
 * 
 * <p>
 * Coordinates all migration phases including:
 * <ul>
 * <li>POM dependency updates</li>
 * <li>Property file transformations</li>
 * <li>Code migrations (Kafka, Redis, Hibernate, Jedis)</li>
 * <li>Configuration optimizations (JMX, ConfigurationPropertiesScan)</li>
 * <li>Validation and reporting</li>
 * </ul>
 * 
 * <p>
 * Most migrations are fully automated. Some migrations (Hibernate
 * AttributeConverter
 * generation, Redis set operations, Jedis configuration) may require manual
 * review
 * and completion after initial code generation.
 * 
 * @see AbstractSpringBootMigrator
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot21to22Migrator extends AbstractSpringBootMigrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot21to22Migrator.class);

    // Migration components - version specific
    private PomMigrator21to22 pomMigrator;
    private PropertyMigrator21to22 propertyMigrator;
    private KafkaCodeMigrator kafkaMigrator;
    private RedisCodeMigrator redisMigrator;
    private HibernateCodeMigrator hibernateMigrator;
    private JedisConnectionMigrator jedisMigrator;
    private JmxConfigDetector jmxDetector;
    private ActuatorConfigDetector actuatorDetector;
    private ConfigPropertiesScanMigrator configPropsMigrator;
    private LazyInitializationConfigurer lazyInitConfigurer;
    private JakartaEEPrepMigrator jakartaPrepMigrator;

    // Optional feature flags (disabled by default)
    private final boolean enableLazyInit;
    private final boolean enableJakartaPrep;

    /**
     * Constructor with default settings (no optional features).
     * 
     * @param dryRun if true, no files will be modified
     */
    public SpringBoot21to22Migrator(boolean dryRun) {
        this(dryRun, false, false);
    }

    /**
     * Constructor with optional feature flags.
     * 
     * @param dryRun            if true, no files will be modified
     * @param enableLazyInit    if true, adds lazy initialization to test profiles
     * @param enableJakartaPrep if true, adds Jakarta EE migration prep comments
     */
    public SpringBoot21to22Migrator(boolean dryRun, boolean enableLazyInit, boolean enableJakartaPrep) {
        super(dryRun);
        this.enableLazyInit = enableLazyInit;
        this.enableJakartaPrep = enableJakartaPrep;
    }

    @Override
    protected void initializeComponents() throws IOException {
        logger.info("Initializing Spring Boot 2.1 to 2.2 migration components...");

        // Load configuration and pre-process source files
        Settings.loadConfigMap();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();

        // Initialize version-specific migrators (using new extracted classes)
        this.pomMigrator = new PomMigrator21to22(dryRun);
        this.propertyMigrator = new PropertyMigrator21to22(dryRun);

        // Initialize code migrators (existing implementations)
        this.kafkaMigrator = new KafkaCodeMigrator(dryRun);
        this.redisMigrator = new RedisCodeMigrator(dryRun);
        this.hibernateMigrator = new HibernateCodeMigrator(dryRun);
        this.jedisMigrator = new JedisConnectionMigrator(dryRun);

        // Initialize configuration optimizers
        this.jmxDetector = new JmxConfigDetector(dryRun);
        this.actuatorDetector = new ActuatorConfigDetector(dryRun);
        this.configPropsMigrator = new ConfigPropertiesScanMigrator(dryRun);

        // Initialize optional feature migrators
        this.lazyInitConfigurer = new LazyInitializationConfigurer(dryRun, enableLazyInit);
        this.jakartaPrepMigrator = new JakartaEEPrepMigrator(dryRun, enableJakartaPrep);

        // Initialize validator
        this.validator = new MigrationValidator(dryRun);
    }

    @Override
    protected MigrationPhaseResult migratePom() {
        return pomMigrator.migrate();
    }

    @Override
    protected MigrationPhaseResult migrateProperties() {
        return propertyMigrator.migrate();
    }

    @Override
    protected void executeVersionSpecificMigrations() throws IOException {
        // Phase 3: Code Migrations
        logger.info("Phase 3: Migrating code (Kafka, Redis, Hibernate)...");

        MigrationPhaseResult kafkaResult = kafkaMigrator.migrate();
        modifiedFiles.addAll(kafkaResult.getModifiedClasses());
        result.addPhase("Kafka Migration", kafkaResult);

        MigrationPhaseResult redisResult = redisMigrator.migrate();
        modifiedFiles.addAll(redisResult.getModifiedClasses());
        result.addPhase("Redis Migration", redisResult);

        MigrationPhaseResult hibernateResult;
        try {
            hibernateResult = hibernateMigrator.migrate();
            modifiedFiles.addAll(hibernateResult.getModifiedClasses());
            result.addPhase("Hibernate Migration", hibernateResult);
        } catch (Exception e) {
            hibernateResult = new MigrationPhaseResult();
            hibernateResult.addError("Hibernate migration failed: " + e.getMessage());
            result.addPhase("Hibernate Migration", hibernateResult);
            logger.error("Hibernate migration failed", e);
        }

        MigrationPhaseResult jedisResult = jedisMigrator.migrate();
        modifiedFiles.addAll(jedisResult.getModifiedClasses());
        result.addPhase("Jedis Configuration Migration", jedisResult);

        // Phase 4: Configuration Optimizations
        logger.info("Phase 4: Applying configuration optimizations...");

        MigrationPhaseResult jmxResult = jmxDetector.migrate();
        result.addPhase("JMX Detection", jmxResult);

        MigrationPhaseResult configPropsResult = configPropsMigrator.migrate();
        modifiedFiles.addAll(configPropsResult.getModifiedClasses());
        result.addPhase("ConfigurationPropertiesScan", configPropsResult);

        MigrationPhaseResult actuatorResult = actuatorDetector.migrate();
        result.addPhase("Actuator Configuration Detection", actuatorResult);

        // Phase 5: Optional Enhancements
        if (enableLazyInit || enableJakartaPrep) {
            logger.info("Phase 5: Applying optional enhancements...");

            if (enableLazyInit) {
                MigrationPhaseResult lazyInitResult = lazyInitConfigurer.migrate();
                result.addPhase("Lazy Initialization Configuration", lazyInitResult);
            }

            if (enableJakartaPrep) {
                MigrationPhaseResult jakartaResult = jakartaPrepMigrator.migrate();
                modifiedFiles.addAll(jakartaResult.getModifiedClasses());
                result.addPhase("Jakarta EE Preparatory Comments", jakartaResult);
            }
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
        return "2.1";
    }

    @Override
    protected String getTargetVersion() {
        return "2.2";
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
     * java com.raditha.spring.SpringBoot21to22Migrator [--dry-run] 
     *      [--project-path &lt;path&gt;] [--enable-lazy-init] [--enable-jakarta-prep]
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        boolean enableLazyInit = false;
        boolean enableJakartaPrep = false;
        String projectPath = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--enable-lazy-init":
                    enableLazyInit = true;
                    break;
                case "--enable-jakarta-prep":
                    enableJakartaPrep = true;
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
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(dryRun, enableLazyInit, enableJakartaPrep);
        MigrationResult result = migrator.migrateAll();

        // Print detailed report
        migrator.printReport();

        // Exit with appropriate code
        System.exit(result.isSuccessful() ? 0 : 1);
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java com.raditha.spring.SpringBoot21to22Migrator [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run              Run migration without making changes");
        System.out.println("  --project-path <path>  Path to Spring Boot project (default: current directory)");
        System.out.println("  --enable-lazy-init     Add lazy initialization to test profiles (optional)");
        System.out.println("  --enable-jakarta-prep  Add Jakarta EE migration prep comments (optional)");
        System.out.println("  --help, -h             Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println(
                "  java com.raditha.spring.SpringBoot21to22Migrator --dry-run --project-path /path/to/project");
        System.out.println(
                "  java com.raditha.spring.SpringBoot21to22Migrator --enable-lazy-init --enable-jakarta-prep");
        System.exit(1);
    }
}
