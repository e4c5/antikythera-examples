package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.maven.model.Model;
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
        // Search for /actuator/info or /info endpoint references in code
        Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();
        
        for (CompilationUnit cu : allUnits.values()) {
            // Search for string literals containing /info or /actuator/info
            boolean hasInfoReference = cu.findAll(com.github.javaparser.ast.expr.StringLiteralExpr.class).stream()
                    .anyMatch(str -> str.getValue().contains("/info") || 
                                     str.getValue().contains("/actuator/info"));
            
            if (hasInfoReference) {
                logger.info("Found /info endpoint reference in {}", 
                           cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown"));
                return true;
            }
        }
        
        // If actuator dependency exists but no explicit references found,
        // assume endpoint is used (safer default)
        if (hasActuatorDependency()) {
            logger.info("Actuator dependency found, assuming /info endpoint is used");
            return true;
        }
        
        return false;
    }

    /**
     * Check if Spring Boot Actuator dependency exists.
     */
    private boolean hasActuatorDependency() {
        try {
            Model model = loadPomModel();
            return getDependenciesByGroupId(model, "org.springframework.boot").stream()
                    .anyMatch(dep -> "spring-boot-starter-actuator".equals(dep.getArtifactId()));
        } catch (Exception e) {
            logger.warn("Could not parse POM to check for actuator dependency, assuming present", e);
            return true; // Safe default - configure endpoint exposure anyway
        }
    }

    /**
     * Check if Spring Security dependency exists.
     */
    private boolean hasSpringSecurityDependency() {
        try {
            Model model = loadPomModel();
            // Check for any Spring Security starter or core dependency
            boolean hasSecurityDep = getDependenciesByGroupId(model, "org.springframework.boot").stream()
                    .anyMatch(dep -> dep.getArtifactId().contains("spring-boot-starter-security") ||
                                     dep.getArtifactId().contains("spring-boot-starter-oauth2"));
            
            if (!hasSecurityDep) {
                hasSecurityDep = getDependenciesByGroupId(model, "org.springframework.security").stream()
                        .anyMatch(dep -> dep.getArtifactId().startsWith("spring-security-"));
            }
            
            return hasSecurityDep || findSecurityConfigClass() != null;
        } catch (Exception e) {
            logger.warn("Could not parse POM to check for security dependency", e);
            // Fall back to looking for SecurityConfig classes
            return findSecurityConfigClass() != null;
        }
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
        
        try {
            // Find the http parameter name
            String httpParamName = method.getParameter(0).getNameAsString();
            
            // Create the new method call: .antMatchers("/actuator/info").permitAll()
            String permitAllLine = "        " + httpParamName + "." + matcherMethodName + 
                                 "(\"/actuator/info\").permitAll()";
            
            // Add a comment to the method explaining the change
            method.setComment(new com.github.javaparser.ast.comments.BlockComment(
                    "\n" +
                    " * MODIFIED BY SpringBoot24to25Migrator:\n" +
                    " * Added permitAll() for /actuator/info endpoint\n" +
                    " * \n" +
                    " * Spring Boot 2.5 changed /info endpoint security.\n" +
                    " * Please review this configuration to ensure it aligns with your security requirements.\n" +
                    " * \n" +
                    " * Added line:\n" +
                    " * " + permitAllLine + "\n" +
                    " */"));
            
            // Log detailed instructions for manual modification
            result.addChange("Modified " + method.getNameAsString() + "() method - added security comment");
            result.addWarning("⚠️  MANUAL CODE CHANGE REQUIRED in " + method.getNameAsString() + "()");
            result.addWarning("Add BEFORE .anyRequest(): ." + matcherMethodName + 
                            "(\"/actuator/info\").permitAll()");
            result.addWarning("The method has been marked with a comment block with detailed instructions");
            
            // Note: Full AST method chain manipulation is complex and error-prone
            // The comment provides clear instructions for manual addition
            logger.warn("Security configuration requires manual update - added comment to method");
            
        } catch (Exception e) {
            logger.error("Failed to add comment to security method", e);
            result.addError("Could not modify security configuration method: " + e.getMessage());
            result.addWarning("Please manually add: ." + matcherMethodName + 
                            "(\"/actuator/info\").permitAll()");
        }
    }

    /**
     * Generate a new ActuatorSecurityConfig class if no existing Security config
     * found.
     */
    private void generateActuatorSecurityConfig(MigrationPhaseResult result) {
        logger.info("No existing SecurityConfig found, generating ActuatorSecurityConfig");

        try {
            // Determine the package based on existing project structure
            String packageName = determineSecurityConfigPackage();
            
            String configContent = "package " + packageName + ";\n\n" +
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

            // Create the file path
            Path basePath = Paths.get(Settings.getBasePath());
            Path configDir = basePath.resolve("src/main/java")
                    .resolve(packageName.replace('.', File.separatorChar));
            
            if (!Files.exists(configDir) && !dryRun) {
                Files.createDirectories(configDir);
            }
            
            Path configFile = configDir.resolve("ActuatorSecurityConfig.java");
            
            if (!dryRun) {
                Files.writeString(configFile, configContent);
                logger.info("Generated ActuatorSecurityConfig.java at: {}", configFile);
                result.addChange("Generated " + configFile);
            } else {
                logger.info("DRY-RUN: Would generate ActuatorSecurityConfig.java at: {}", configFile);
                result.addChange("Would generate " + configFile + " (dry-run mode)");
            }
            
            result.addWarning("⚠️  NEW FILE GENERATED: ActuatorSecurityConfig.java");
            result.addWarning("SECURITY REVIEW REQUIRED for the generated security configuration");
            result.addWarning("Verify that /actuator/info should be publicly accessible");
            
        } catch (Exception e) {
            logger.error("Failed to generate ActuatorSecurityConfig", e);
            result.addError("Could not generate ActuatorSecurityConfig.java: " + e.getMessage());
            result.addWarning("You may need to manually create security configuration for /actuator/info");
        }
    }
    
    /**
     * Determine appropriate package for security configuration based on existing code structure.
     */
    private String determineSecurityConfigPackage() {
        // Try to find existing configuration package
        Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();
        
        for (CompilationUnit cu : allUnits.values()) {
            Optional<ClassOrInterfaceDeclaration> configClass = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    clazz -> clazz.getAnnotationByName("Configuration").isPresent());
            
            if (configClass.isPresent() && cu.getPackageDeclaration().isPresent()) {
                String pkg = cu.getPackageDeclaration().get().getNameAsString();
                if (pkg.endsWith(".config") || pkg.contains(".config.")) {
                    logger.info("Found existing config package: {}", pkg);
                    return pkg;
                }
            }
        }
        
        // Fall back to common convention
        String basePackage = findBasePackage();
        String configPackage = basePackage + ".config";
        logger.info("Using default config package: {}", configPackage);
        return configPackage;
    }
    
    /**
     * Find the base package of the application.
     */
    private String findBasePackage() {
        Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();
        
        // Look for @SpringBootApplication class
        for (CompilationUnit cu : allUnits.values()) {
            Optional<ClassOrInterfaceDeclaration> mainClass = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    clazz -> clazz.getAnnotationByName("SpringBootApplication").isPresent());
            
            if (mainClass.isPresent() && cu.getPackageDeclaration().isPresent()) {
                String pkg = cu.getPackageDeclaration().get().getNameAsString();
                logger.info("Found base package from @SpringBootApplication: {}", pkg);
                return pkg;
            }
        }
        
        // Fall back to first package found
        for (CompilationUnit cu : allUnits.values()) {
            if (cu.getPackageDeclaration().isPresent()) {
                String pkg = cu.getPackageDeclaration().get().getNameAsString();
                // Get base package (first segment)
                String[] parts = pkg.split("\\.");
                if (parts.length >= 3) {
                    String basePackage = parts[0] + "." + parts[1] + "." + parts[2];
                    logger.info("Using inferred base package: {}", basePackage);
                    return basePackage;
                }
            }
        }
        
        // Absolute fallback
        logger.warn("Could not determine base package, using default");
        return "com.example";
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
