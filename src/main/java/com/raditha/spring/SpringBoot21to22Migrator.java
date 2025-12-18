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
 * Main orchestrator for Spring Boot 2.1 to 2.2 migration.
 * 
 * Coordinates all migration phases including:
 * - POM dependency updates
 * - Property file transformations
 * - Code migrations (Kafka, Redis, Hibernate, Jedis)
 * - Configuration optimizations (JMX, ConfigurationPropertiesScan)
 * - Validation and reporting
 * 
 * Most migrations are fully automated. Some migrations (Hibernate AttributeConverter
 * generation, Redis set operations, Jedis configuration) may require manual review
 * and completion after initial code generation.
 */
@SuppressWarnings("java:S106") // Allow System.out usage for reporting
public class SpringBoot21to22Migrator {
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot21to22Migrator.class);

    private final boolean dryRun;
    private final MigrationResult result;
    private final Set<String> modifiedFiles = new HashSet<>();

    // Migration components
    private SpringBootPomMigrator pomMigrator;
    private PropertyFileMigrator propertyMigrator;
    private KafkaCodeMigrator kafkaMigrator;
    private RedisCodeMigrator redisMigrator;
    private HibernateCodeMigrator hibernateMigrator;
    private JedisConnectionMigrator jedisMigrator;
    private JmxConfigDetector jmxDetector;
    private ActuatorConfigDetector actuatorDetector;
    private ConfigPropertiesScanMigrator configPropsMigrator;
    private LazyInitializationConfigurer lazyInitConfigurer;
    private JakartaEEPrepMigrator jakartaPrepMigrator;
    private MigrationValidator validator;

    // Optional feature flags (disabled by default)
    private boolean enableLazyInit = false;
    private boolean enableJakartaPrep = false;

    public SpringBoot21to22Migrator(boolean dryRun) throws IOException {
        this(dryRun, false, false);
    }

    /**
     * Constructor with optional feature flags.
     * 
     * @param dryRun if true, no files will be modified
     * @param enableLazyInit if true, adds lazy initialization to test profiles
     * @param enableJakartaPrep if true, adds Jakarta EE migration prep comments
     */
    public SpringBoot21to22Migrator(boolean dryRun, boolean enableLazyInit, boolean enableJakartaPrep) throws IOException {
        this.dryRun = dryRun;
        this.enableLazyInit = enableLazyInit;
        this.enableJakartaPrep = enableJakartaPrep;
        this.result = new MigrationResult();
        initializeComponents();
    }

    /**
     * Initialize all migration components.
     * Pre-processes source files with lexical preservation enabled.
     */
    private void initializeComponents() throws IOException {
        logger.info("Initializing Spring Boot 2.1 to 2.2 migration components...");

        // Load configuration and pre-process source files
        Settings.loadConfigMap();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();

        this.pomMigrator = new SpringBootPomMigrator(dryRun);
        this.propertyMigrator = new PropertyFileMigrator(dryRun);
        this.kafkaMigrator = new KafkaCodeMigrator(dryRun);
        this.redisMigrator = new RedisCodeMigrator(dryRun);
        this.hibernateMigrator = new HibernateCodeMigrator(dryRun);
        this.jedisMigrator = new JedisConnectionMigrator(dryRun);
        this.jmxDetector = new JmxConfigDetector(dryRun);
        this.actuatorDetector = new ActuatorConfigDetector(dryRun);
        this.configPropsMigrator = new ConfigPropertiesScanMigrator(dryRun);
        this.lazyInitConfigurer = new LazyInitializationConfigurer(dryRun, enableLazyInit);
        this.jakartaPrepMigrator = new JakartaEEPrepMigrator(dryRun, enableJakartaPrep);
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
        modifiedFiles.addAll(kafkaResult.getModifiedClasses());
        result.addPhase("Kafka Migration", kafkaResult);

        MigrationPhaseResult redisResult = redisMigrator.migrate();
        modifiedFiles.addAll(redisResult.getModifiedClasses());
        result.addPhase("Redis Migration", redisResult);

        MigrationPhaseResult hibernateResult = hibernateMigrator.migrate();
        modifiedFiles.addAll(hibernateResult.getModifiedClasses());
        result.addPhase("Hibernate Migration", hibernateResult);

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

        // Phase 6: Write modified files to disk
        if (!dryRun && !modifiedFiles.isEmpty()) {
            logger.info("Phase 6: Writing {} modified files to disk...", modifiedFiles.size());
            writeModifiedFiles();
        }

        // Phase 6: Validation
        if (!dryRun) {
            logger.info("Phase 6: Validating migration...");
            MigrationPhaseResult validationResult = validator.migrate();
            result.addPhase("Validation", validationResult);
        }

        logger.info("Migration completed successfully!");
        return result;
    }

    /**
     * Write all modified compilation units to disk using LexicalPreservingPrinter.
     */
    private void writeModifiedFiles() throws IOException {
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
     *        [--project-path <path>] [--enable-lazy-init] [--enable-jakarta-prep]
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
