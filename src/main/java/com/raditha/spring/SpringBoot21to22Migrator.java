package com.raditha.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

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
@Command(name = "spring-boot-21to22-migrator", mixinStandardHelpOptions = true,
        version = "Spring Boot 2.1 → 2.2 Migrator v1.0", description = "Migrates Spring Boot 2.1 applications to 2.2")
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot21to22Migrator extends AbstractSpringBootMigrator implements Callable<Integer> {
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

    // CLI Options
    @Option(names = { "--dry-run" }, description = "Run migration without making changes")
    private boolean cliDryRun = false;

    @Option(names = {
            "--project-path" }, description = "Path to Spring Boot project (default: current directory)", paramLabel = "<path>")
    private String projectPath;

    @Option(names = { "--enable-lazy-init" }, description = "Add lazy initialization to test profiles (optional)")
    private boolean enableLazyInit = false;

    @Option(names = { "--enable-jakarta-prep" }, description = "Add Jakarta EE migration prep comments (optional)")
    private boolean enableJakartaPrep = false;

    /**
     * Default constructor for Picocli.
     */
    public SpringBoot21to22Migrator() {
        super(false); // Will be set by CLI
    }

    /**
     * Constructor with default settings (no optional features).
     * For programmatic/testing use.
     * 
     * @param dryRun if true, no files will be modified
     */
    public SpringBoot21to22Migrator(boolean dryRun) {
        this(dryRun, false, false);
    }

    /**
     * Constructor with optional feature flags.
     * For programmatic/testing use.
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
        Settings.loadConfigMap(new File("src/main/resources/migrator.yml"));
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
    protected MigrationPhaseResult migratePom() throws Exception {
        return pomMigrator.migrate();
    }

    @Override
    protected MigrationPhaseResult migrateProperties() {
        return propertyMigrator.migrate();
    }

    @Override
    protected void executeVersionSpecificMigrations() throws Exception {
        // Phase 3: Code Migrations
        logger.info("Phase 3: Migrating code (Kafka, Redis, Hibernate)...");

        MigrationPhaseResult kafkaResult = kafkaMigrator.migrate();
        modifiedFiles.addAll(kafkaResult.getModifiedClasses());
        result.addPhase("Kafka Migration", kafkaResult);

        MigrationPhaseResult redisResult = redisMigrator.migrate();
        modifiedFiles.addAll(redisResult.getModifiedClasses());
        result.addPhase("Redis Migration", redisResult);

        MigrationPhaseResult hibernateResult = hibernateMigrator.migrate();
        modifiedFiles.addAll(hibernateResult.getModifiedClasses());

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
     * Picocli call method - executes the migration.
     * 
     * @return exit code (0 for success, 1 for failure)
     */
    @Override
    public Integer call() throws Exception {
        return executeMigration(cliDryRun);
    }

    /**
     * Helper method to execute the migration with the specified flags.
     * 
     * @param dryRun whether to run in dry-run mode
     * @return exit code (0 for success, 1 for failure)
     * @throws Exception if migration fails
     */
    private Integer executeMigration(boolean dryRun) throws Exception {
        // Create new instance with CLI flags
        SpringBoot21to22Migrator migrator = new SpringBoot21to22Migrator(dryRun, enableLazyInit, enableJakartaPrep);

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
        int exitCode = new CommandLine(new SpringBoot21to22Migrator()).execute(args);
        System.exit(exitCode);
    }
}
