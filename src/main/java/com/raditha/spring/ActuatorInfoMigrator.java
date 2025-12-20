package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Configures the /info actuator endpoint for Spring Boot 2.5.
 * 
 * <p>
 * Spring Boot 2.5 changes to /info endpoint:
 * <ul>
 * <li>/info endpoint is NO LONGER exposed by default</li>
 * <li>Requires authentication when Spring Security is present</li>
 * <li>Must be explicitly exposed via management.endpoints.web.exposure.include</li>
 * </ul>
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects /info endpoint usage in code, tests, and monitoring scripts</li>
 * <li>Adds actuator exposure configuration if endpoint is used</li>
 * <li>Detects Spring Security and generates configuration guidance</li>
 * <li>Flags for manual review when security configuration is involved</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class ActuatorInfoMigrator extends AbstractConfigMigrator {

    public ActuatorInfoMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path basePath = Paths.get(Settings.getBasePath());

        // Step 1: Detect /info endpoint usage
        boolean infoEndpointUsed = detectInfoEndpointUsage(basePath, result);

        if (!infoEndpointUsed) {
            result.addChange("No /info endpoint usage detected - no changes needed");
            return result;
        }

        // Step 2: Check for Spring Security
        boolean hasSpringSecurity = hasSpringSecurityDependency(basePath, result);

        // Step 3: Add actuator exposure configuration
        addActuatorExposureConfig(basePath, result);

        // Step 4: Generate security configuration guidance if needed
        if (hasSpringSecurity) {
            generateSecurityConfigGuidance(result);
            result.setManualReviewRequired(true);
        }

        return result;
    }

    /**
     * Detect if /info actuator endpoint is used in the codebase.
     */
    private boolean detectInfoEndpointUsage(Path basePath, MigrationPhaseResult result) {
        boolean found = false;

        // Search for /actuator/info references in source code
        for (CompilationUnit cu : AntikytheraRunTime.getAllCompilationUnits()) {
            String sourceCode = cu.toString();
            if (sourceCode.contains("/actuator/info") || sourceCode.contains("/info")) {
                found = true;
                result.addChange(String.format("Found /info endpoint reference in %s",
                        cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown")));
            }
        }

        return found;
    }

    /**
     * Check if Spring Security dependency is present.
     */
    private boolean hasSpringSecurityDependency(Path basePath, MigrationPhaseResult result) {
        Path pomPath = basePath.resolve("pom.xml");
        
        if (!Files.exists(pomPath)) {
            return false;
        }

        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model;
            try (InputStream input = Files.newInputStream(pomPath)) {
                model = reader.read(input);
            }

            boolean hasSecurity = model.getDependencies().stream()
                    .anyMatch(dep -> "org.springframework.boot".equals(dep.getGroupId()) &&
                            (dep.getArtifactId().contains("security") || 
                             dep.getArtifactId().contains("oauth2")));

            if (hasSecurity) {
                result.addChange("Spring Security detected - /info endpoint will require authentication");
            }

            return hasSecurity;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Add actuator endpoint exposure configuration.
     */
    @SuppressWarnings("unchecked")
    private void addActuatorExposureConfig(Path basePath, MigrationPhaseResult result) throws IOException {
        Path resourcesPath = basePath.resolve("src/main/resources");
        
        if (!Files.exists(resourcesPath)) {
            result.addWarning("No resources directory found - cannot add actuator configuration");
            return;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        // Prefer YAML files
        if (!yamlFiles.isEmpty()) {
            Path mainYaml = yamlFiles.stream()
                    .filter(p -> p.getFileName().toString().equals("application.yml") ||
                                 p.getFileName().toString().equals("application.yaml"))
                    .findFirst()
                    .orElse(yamlFiles.get(0));
            
            Yaml yaml = YamlUtils.createYaml();
            Map<String, Object> data;

            try (InputStream input = Files.newInputStream(mainYaml)) {
                data = yaml.load(input);
            }

            if (data == null) {
                data = new LinkedHashMap<>();
            }

            boolean modified = false;

            // Create management.endpoints.web.exposure.include structure
            if (!data.containsKey("management")) {
                data.put("management", new LinkedHashMap<>());
            }

            Map<String, Object> management = (Map<String, Object>) data.get("management");
            if (!management.containsKey("endpoints")) {
                management.put("endpoints", new LinkedHashMap<>());
            }

            Map<String, Object> endpoints = (Map<String, Object>) management.get("endpoints");
            if (!endpoints.containsKey("web")) {
                endpoints.put("web", new LinkedHashMap<>());
            }

            Map<String, Object> web = (Map<String, Object>) endpoints.get("web");
            if (!web.containsKey("exposure")) {
                web.put("exposure", new LinkedHashMap<>());
            }

            Map<String, Object> exposure = (Map<String, Object>) web.get("exposure");
            
            // Add or update include property
            if (!exposure.containsKey("include")) {
                exposure.put("include", "info");
                modified = true;
                result.addChange(String.format("[%s] Added management.endpoints.web.exposure.include=info",
                        mainYaml.getFileName()));
            } else {
                String existing = exposure.get("include").toString();
                if (!existing.contains("info")) {
                    exposure.put("include", existing + ",info");
                    modified = true;
                    result.addChange(String.format("[%s] Added 'info' to management.endpoints.web.exposure.include",
                            mainYaml.getFileName()));
                }
            }

            // Enable info endpoint
            if (!management.containsKey("endpoint")) {
                management.put("endpoint", new LinkedHashMap<>());
            }

            Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
            if (!endpoint.containsKey("info")) {
                endpoint.put("info", new LinkedHashMap<>());
            }

            Map<String, Object> info = (Map<String, Object>) endpoint.get("info");
            if (!info.containsKey("enabled")) {
                info.put("enabled", true);
                modified = true;
                result.addChange(String.format("[%s] Added management.endpoint.info.enabled=true",
                        mainYaml.getFileName()));
            }

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(mainYaml)) {
                    yaml.dump(data, new OutputStreamWriter(output));
                }
            }
        } else if (!propFiles.isEmpty()) {
            Path mainProp = propFiles.stream()
                    .filter(p -> p.getFileName().toString().equals("application.properties"))
                    .findFirst()
                    .orElse(propFiles.get(0));
            
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(mainProp)) {
                props.load(input);
            }

            boolean modified = false;

            if (!props.containsKey("management.endpoints.web.exposure.include")) {
                props.setProperty("management.endpoints.web.exposure.include", "info");
                modified = true;
                result.addChange(String.format("[%s] Added management.endpoints.web.exposure.include=info",
                        mainProp.getFileName()));
            } else {
                String existing = props.getProperty("management.endpoints.web.exposure.include");
                if (!existing.contains("info")) {
                    props.setProperty("management.endpoints.web.exposure.include", existing + ",info");
                    modified = true;
                    result.addChange(String.format("[%s] Added 'info' to management.endpoints.web.exposure.include",
                            mainProp.getFileName()));
                }
            }

            if (!props.containsKey("management.endpoint.info.enabled")) {
                props.setProperty("management.endpoint.info.enabled", "true");
                modified = true;
                result.addChange(String.format("[%s] Added management.endpoint.info.enabled=true",
                        mainProp.getFileName()));
            }

            if (modified && !dryRun) {
                try (OutputStream output = Files.newOutputStream(mainProp)) {
                    props.store(output, "Updated by ActuatorInfoMigrator for Spring Boot 2.5");
                }
            }
        }
    }

    /**
     * Generate guidance for Spring Security configuration.
     */
    private void generateSecurityConfigGuidance(MigrationPhaseResult result) {
        result.addWarning("SECURITY REVIEW REQUIRED:");
        result.addWarning("Spring Security is detected - /info endpoint now requires authentication by default");
        result.addWarning("");
        result.addWarning("To allow public access to /info endpoint, add to your Security configuration:");
        result.addWarning("");
        result.addWarning("  @Bean");
        result.addWarning("  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {");
        result.addWarning("      http");
        result.addWarning("          .authorizeHttpRequests(auth -> auth");
        result.addWarning("              .requestMatchers(\"/actuator/info\").permitAll()  // Allow public access");
        result.addWarning("              .anyRequest().authenticated()");
        result.addWarning("          )");
        result.addWarning("          .httpBasic(withDefaults());");
        result.addWarning("      return http.build();");
        result.addWarning("  }");
        result.addWarning("");
        result.addWarning("OR if using WebSecurityConfigurerAdapter (deprecated):");
        result.addWarning("");
        result.addWarning("  @Override");
        result.addWarning("  protected void configure(HttpSecurity http) throws Exception {");
        result.addWarning("      http");
        result.addWarning("          .authorizeRequests()");
        result.addWarning("              .antMatchers(\"/actuator/info\").permitAll()");
        result.addWarning("              .anyRequest().authenticated()");
        result.addWarning("          .and()");
        result.addWarning("          .httpBasic();");
        result.addWarning("  }");
    }

    @Override
    public String getPhaseName() {
        return "Actuator /info Endpoint Configuration";
    }

    @Override
    public int getPriority() {
        return 30; // High priority - affects endpoint availability
    }
}
