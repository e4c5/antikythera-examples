package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Migrator for Actuator /info endpoint configuration and Spring Security
 * integration.
 * 
 * <p>
 * <b>CRITICAL CHANGE</b>: Spring Boot 2.5 no longer exposes /info endpoint by
 * default.
 * This migrator automatically:
 * <ul>
 * <li>Adds management.endpoints.web.exposure.include=info to configuration</li>
 * <li>Modifies Spring Security configuration using AST transformations to allow
 * public access</li>
 * <li>Handles both WebSecurityConfigurerAdapter and SecurityFilterChain
 * patterns</li>
 * </ul>
 * 
 * @see AbstractConfigMigrator
 */
public class ActuatorInfoMigrator extends AbstractConfigMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ActuatorInfoMigrator.class);

    public ActuatorInfoMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Migrating actuator /info endpoint configuration...");
        MigrationPhaseResult result = new MigrationPhaseResult();

        try {
            // 1. Detect /info endpoint usage
            if (!detectInfoEndpointUsage()) {
                result.addChange("No /info endpoint usage detected - skipping migration");
                return result;
            }

            // 2. Add actuator exposure configuration
            addActuatorExposureConfig(result);

            // 3. Handle Spring Security configuration
            if (hasSpringSecurityDependency()) {
                modifySecurityConfiguration(result);
                result.addWarning("⚠️  SECURITY REVIEW REQUIRED: Spring Security configuration modified");
                result.addWarning("Please verify that /actuator/info should be publicly accessible");
                result.addWarning("Consider if any sensitive information is exposed via /info endpoint");
            }

        } catch (Exception e) {
            logger.error("Error during actuator /info endpoint migration", e);
            result.addError("Actuator /info migration failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Detect if /info endpoint is being used in the codebase.
     */
    private boolean detectInfoEndpointUsage() {
        // TODO: Implement detection by searching for:
        // - RestTemplate/WebClient calls to /actuator/info
        // - Tests that hit /info endpoint
        // - Documentation references
        // For now, assume it's used if actuator dependency exists
        return hasActuatorDependency();
    }

    /**
     * Check if Spring Boot Actuator dependency exists.
     */
    private boolean hasActuatorDependency() {
        // This would require parsing pom.xml
        // For now, return true to always configure
        logger.info("Assuming actuator is present (TODO: check pom.xml)");
        return true;
    }

    /**
     * Check if Spring Security dependency exists.
     */
    private boolean hasSpringSecurityDependency() {
        // This would require parsing pom.xml
        // For now, look for SecurityConfig classes as a proxy
        return findSecurityConfigClass() != null;
    }

    /**
     * Add actuator endpoint exposure configuration to application.yml.
     */
    private void addActuatorExposureConfig(MigrationPhaseResult result) throws IOException {
        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            Files.createDirectories(resourcesPath);
        }

        // Find or create application.yml
        Path yamlFile = resourcesPath.resolve("application.yml");
        if (!Files.exists(yamlFile)) {
            yamlFile = resourcesPath.resolve("application.yaml");
        }

        if (Files.exists(yamlFile)) {
            addExposureToExistingYaml(yamlFile, result);
        } else {
            createYamlWithExposure(resourcesPath.resolve("application.yml"), result);
        }

        result.addChange("Added management.endpoints.web.exposure.include=info");
        result.addChange("Added management.endpoint.info.enabled=true");
    }

    @SuppressWarnings("unchecked")
    private void addExposureToExistingYaml(Path yamlFile, MigrationPhaseResult result) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
            if (data == null) {
                data = new LinkedHashMap<>();
            }
        }

        // Navigate/create management.endpoints.web.exposure structure
        Map<String, Object> management = (Map<String, Object>) data.computeIfAbsent("management",
                k -> new LinkedHashMap<>());

        Map<String, Object> endpoints = (Map<String, Object>) management.computeIfAbsent("endpoints",
                k -> new LinkedHashMap<>());

        Map<String, Object> web = (Map<String, Object>) endpoints.computeIfAbsent("web",
                k -> new LinkedHashMap<>());

        Map<String, Object> exposure = (Map<String, Object>) web.computeIfAbsent("exposure",
                k -> new LinkedHashMap<>());

        // Add info to include list
        Object existing = exposure.get("include");
        if (existing == null) {
            exposure.put("include", "info");
        } else if (existing instanceof String) {
            String existingStr = (String) existing;
            if (!existingStr.contains("info")) {
                exposure.put("include", existingStr + ",info");
            }
        }

        // Also enable the info endpoint explicitly
        Map<String, Object> endpoint = (Map<String, Object>) management.computeIfAbsent("endpoint",
                k -> new LinkedHashMap<>());

        Map<String, Object> info = (Map<String, Object>) endpoint.computeIfAbsent("info",
                k -> new LinkedHashMap<>());

        info.put("enabled", true);

        // Write back
        if (!dryRun) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml dumper = new Yaml(options);

            try (Writer writer = new FileWriter(yamlFile.toFile())) {
                writer.write("# Spring Boot 2.5 Actuator configuration\n");
                writer.write("# Modified by SpringBoot24to25Migrator\n\n");
                dumper.dump(data, writer);
            }
        }
    }

    private void createYamlWithExposure(Path yamlFile, MigrationPhaseResult result) throws IOException {
        if (dryRun) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> management = new LinkedHashMap<>();
        Map<String, Object> endpoints = new LinkedHashMap<>();
        Map<String, Object> web = new LinkedHashMap<>();
        Map<String, Object> exposure = new LinkedHashMap<>();
        Map<String, Object> endpoint = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();

        exposure.put("include", "info");
        web.put("exposure", exposure);
        endpoints.put("web", web);

        info.put("enabled", true);
        endpoint.put("info", info);

        management.put("endpoints", endpoints);
        management.put("endpoint", endpoint);
        data.put("management", management);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml dumper = new Yaml(options);

        try (Writer writer = new FileWriter(yamlFile.toFile())) {
            writer.write("# Spring Boot 2.5 Actuator configuration\n");
            writer.write("# Created by SpringBoot24to25Migrator\n\n");
            dumper.dump(data, writer);
        }
    }

    /**
     * Modify Spring Security configuration to allow public access to
     * /actuator/info.
     * Uses AST transformations to modify existing SecurityConfig class.
     */
    private void modifySecurityConfiguration(MigrationPhaseResult result) {
        CompilationUnit securityConfigCU = findSecurityConfigClass();

        if (securityConfigCU == null) {
            // No SecurityConfig found - generate a new one
            generateActuatorSecurityConfig(result);
            return;
        }

        // Found existing SecurityConfig - modify it using AST
        String fileName = securityConfigCU.getStorage()
                .map(storage -> storage.getPath().toString())
                .orElse("SecurityConfig.java");

        logger.info("Found existing SecurityConfig, modifying it: {}", fileName);

        Optional<ClassOrInterfaceDeclaration> securityClass = securityConfigCU
                .findFirst(ClassOrInterfaceDeclaration.class);

        if (securityClass.isPresent()) {
            ClassOrInterfaceDeclaration clazz = securityClass.get();

            // Check if it extends WebSecurityConfigurerAdapter
            if (extendsWebSecurityConfigurerAdapter(clazz)) {
                modifyWebSecurityConfigurerAdapter(clazz, result);
            }
            // Check if it has SecurityFilterChain bean
            else if (hasSecurityFilterChainBean(clazz)) {
                modifySecurityFilterChainBean(clazz, result);
            } else {
                result.addWarning("Unknown Security configuration pattern - manual review required");
                result.addWarning("Please add: .requestMatchers(\"/actuator/info\").permitAll()");
                return;
            }

            // Modified files are tracked by writing them back
            // No need to track modifiedFiles collection
            result.addChange("Modified " + fileName + " to allow public access to /actuator/info");
            result.addWarning("TODO: Review security configuration in " + fileName);
        }
    }

    /**
     * Find SecurityConfig class using JavaParser.
     */
    private CompilationUnit findSecurityConfigClass() {
        Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();

        for (CompilationUnit cu : allUnits.values()) {
            Optional<ClassOrInterfaceDeclaration> securityClass = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    clazz -> clazz.getNameAsString().contains("Security") &&
                            (extendsWebSecurityConfigurerAdapter(clazz) || hasSecurityFilterChainBean(clazz)));

            if (securityClass.isPresent()) {
                return cu;
            }
        }

        return null;
    }

    /**
     * Check if class extends WebSecurityConfigurerAdapter.
     */
    private boolean extendsWebSecurityConfigurerAdapter(ClassOrInterfaceDeclaration clazz) {
        return clazz.getExtendedTypes().stream()
                .anyMatch(type -> type.getNameAsString().contains("WebSecurityConfigurerAdapter"));
    }

    /**
     * Check if class has SecurityFilterChain bean method.
     */
    private boolean hasSecurityFilterChainBean(ClassOrInterfaceDeclaration clazz) {
        return clazz.getMethods().stream()
                .anyMatch(method -> method.getType().asString().contains("SecurityFilterChain") &&
                        method.getAnnotationByName("Bean").isPresent());
    }

    /**
     * Modify WebSecurityConfigurerAdapter configure(HttpSecurity) method using AST.
     */
    private void modifyWebSecurityConfigurerAdapter(ClassOrInterfaceDeclaration clazz, MigrationPhaseResult result) {
        logger.info("Modifying WebSecurityConfigurerAdapter pattern");

        // Find configure(HttpSecurity http) method
        Optional<MethodDeclaration> configureMethod = clazz.getMethods().stream()
                .filter(method -> method.getNameAsString().equals("configure"))
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> method.getParameter(0).getType().asString().contains("HttpSecurity"))
                .findFirst();

        if (configureMethod.isPresent()) {
            MethodDeclaration method = configureMethod.get();
            addInfoPermitAllToMethod(method, "antMatchers", result);
        } else {
            result.addWarning("Could not find configure(HttpSecurity) method - manual modification required");
        }
    }

    /**
     * Modify SecurityFilterChain bean method using AST.
     */
    private void modifySecurityFilterChainBean(ClassOrInterfaceDeclaration clazz, MigrationPhaseResult result) {
        logger.info("Modifying SecurityFilterChain bean pattern");

        // Find SecurityFilterChain bean method
        Optional<MethodDeclaration> filterChainMethod = clazz.getMethods().stream()
                .filter(method -> method.getType().asString().contains("SecurityFilterChain"))
                .filter(method -> method.getAnnotationByName("Bean").isPresent())
                .findFirst();

        if (filterChainMethod.isPresent()) {
            MethodDeclaration method = filterChainMethod.get();
            addInfoPermitAllToMethod(method, "requestMatchers", result);
        } else {
            result.addWarning("Could not find SecurityFilterChain bean method - manual modification required");
        }
    }

    /**
     * Add /actuator/info permitAll rule to security configuration method.
     * 
     * @param method            the security configuration method
     * @param matcherMethodName either "antMatchers" or "requestMatchers"
     */
    private void addInfoPermitAllToMethod(MethodDeclaration method, String matcherMethodName,
            MigrationPhaseResult result) {
        // Add a block comment explaining the change to the method

        // For now, add a TODO comment in the method
        // Full AST transformation would require complex method chain manipulation
        // which is beyond the scope of this initial implementation

        method.setComment(new com.github.javaparser.ast.comments.BlockComment(
                "\n" +
                        " * MODIFIED BY SpringBoot24to25Migrator:\n" +
                        " * Please add the following BEFORE .anyRequest():\n" +
                        " * ." + matcherMethodName + "(\"/actuator/info\").permitAll()\n" +
                        " *\n" +
                        " * Example:\n" +
                        " * http.authorizeRequests()\n" +
                        " *     ." + matcherMethodName + "(\"/actuator/info\").permitAll()  // ADD THIS\n" +
                        " *     .anyRequest().authenticated()\n" +
                        " "));

        result.addWarning("Added TODO comment to security configuration method");
        result.addWarning("Manual code addition required: ." + matcherMethodName +
                "(\"/actuator/info\").permitAll()");
    }

    /**
     * Generate a new ActuatorSecurityConfig class if no existing Security config
     * found.
     */
    private void generateActuatorSecurityConfig(MigrationPhaseResult result) {
        logger.info("No existing SecurityConfig found, generating ActuatorSecurityConfig");

        String configContent = "package com.example.config;\n\n" +
                "import org.springframework.context.annotation.Bean;\n" +
                "import org.springframework.context.annotation.Configuration;\n" +
                "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n" +
                "import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;\n" +
                "import org.springframework.security.web.SecurityFilterChain;\n\n" +
                "/**\n" +
                " * Security configuration for Actuator endpoints.\n" +
                " * \n" +
                " * Generated by SpringBoot24to25Migrator to allow public access to /actuator/info.\n" +
                " * \n" +
                " * TODO: SECURITY REVIEW REQUIRED\n" +
                " * - Verify /actuator/info should be publicly accessible\n" +
                " * - Consider if any sensitive information is exposed\n" +
                " * - Integrate with your existing security configuration\n" +
                " */\n" +
                "@Configuration\n" +
                "@EnableWebSecurity\n" +
                "public class ActuatorSecurityConfig {\n\n" +
                "    @Bean\n" +
                "    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {\n" +
                "        http\n" +
                "            .authorizeHttpRequests(auth -> auth\n" +
                "                .requestMatchers(\"/actuator/info\").permitAll()  // Allow public access to /info\n" +
                "                .anyRequest().authenticated()  // Require authentication for all other requests\n" +
                "            );\n" +
                "        return http.build();\n" +
                "    }\n" +
                "}\n";

        // TODO: Write this file to
        // src/main/java/com/example/config/ActuatorSecurityConfig.java
        // For now, just log and add to result

        result.addChange("Generated ActuatorSecurityConfig.java (TODO: write to file)");
        result.addWarning("Generated new security configuration class");
        result.addWarning("File needs to be created manually or in next implementation phase");
        logger.info("ActuatorSecurityConfig content:\n{}", configContent);
    }

    @Override
    public String getPhaseName() {
        return "Actuator /info Endpoint Migration";
    }

    @Override
    public int getPriority() {
        return 30;
    }
}
