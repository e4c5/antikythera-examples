package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

/**
 * Detects code deprecated in Spring Boot 2.3 that was removed in 2.5.
 * 
 * <p>
 * Spring Boot 2.5 removed all code deprecated in version 2.3. This detector
 * scans for common deprecated patterns that may cause compilation errors.
 * 
 * <p>
 * Common removals include:
 * <ul>
 * <li>Deprecated configuration properties</li>
 * <li>Deprecated auto-configuration classes</li>
 * <li>Deprecated metrics exporters</li>
 * <li>Deprecated actuator endpoints</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class DeprecatedCodeDetector24to25 extends AbstractConfigMigrator {
    private static final Logger logger = LoggerFactory.getLogger(DeprecatedCodeDetector24to25.class);

    public DeprecatedCodeDetector24to25(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        // Scan all compilation units for deprecated code patterns
        for (CompilationUnit cu : AntikytheraRunTime.getAllCompilationUnits()) {
            String sourceCode = cu.toString();
            String fileName = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");

            // Check for deprecated actuator classes
            if (sourceCode.contains("org.springframework.boot.actuate.autoconfigure.metrics.export")) {
                result.addWarning(String.format("[%s] Found potential deprecated metrics exporter auto-configuration", fileName));
                result.addWarning("Some metrics auto-configuration classes from Spring Boot 2.3 were removed");
            }

            // Check for deprecated ConfigurationPropertiesBinding
            if (sourceCode.contains("@ConfigurationPropertiesBinding")) {
                result.addWarning(String.format("[%s] Found @ConfigurationPropertiesBinding annotation", fileName));
                result.addWarning("@ConfigurationPropertiesBinding was deprecated in 2.3 - review usage");
            }

            // Check for deprecated DataSourceInitializer
            if (sourceCode.contains("DataSourceInitializer")) {
                result.addWarning(String.format("[%s] Found DataSourceInitializer usage", fileName));
                result.addWarning("DataSourceInitializer API changed - use spring.sql.init.* properties instead");
            }

            // Check for deprecated WebMvcConfigurer methods
            if (sourceCode.contains("@EnableWebMvc") || sourceCode.contains("WebMvcConfigurer")) {
                if (sourceCode.contains("addResourceHandlers") || sourceCode.contains("configurePathMatch")) {
                    logger.info("Found WebMvcConfigurer in {} - review for deprecated methods", fileName);
                }
            }
        }

        if (!result.hasWarnings()) {
            result.addChange("No deprecated code patterns detected");
            result.addChange("Compile the project to verify: mvn clean compile");
        } else {
            result.addWarning("Deprecated code detected - review and update as needed");
            result.addWarning("Run 'mvn clean compile' to identify compilation errors");
        }

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Deprecated Code Detection (2.3→2.5)";
    }

    @Override
    public int getPriority() {
        return 50; // Lower priority - detection only
    }
}
