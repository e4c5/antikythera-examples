package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
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
 * Migrates Neo4j properties and detects Neo4j OGM usage for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 changed Neo4j property namespace and removed Neo4j OGM
 * support:
 * <ul>
 * <li>{@code spring.data.neo4j.*} → {@code spring.neo4j.*}</li>
 * <li>{@code spring.data.neo4j.username/password} →
 * {@code spring.neo4j.authentication.username/password}</li>
 * <li>Neo4j OGM removed - must migrate to Neo4j SDN-RX</li>
 * </ul>
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Automatically migrates property names in YAML and properties files</li>
 * <li>Detects Neo4j OGM usage in code and flags for manual review</li>
 * <li>Provides migration guidance for OGM → SDN-RX transition</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class Neo4jPropertyMigrator extends AbstractConfigMigrator {

    public Neo4jPropertyMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        // Phase 1: Migrate property files
        migratePropertyFiles(result);

        // Phase 2: Detect Neo4j OGM usage in code
        detectNeo4jOGM(result);

        return result;
    }

    /**
     * Migrate Neo4j properties in YAML and properties files.
     */
    private void migratePropertyFiles(MigrationPhaseResult result) throws Exception {
        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            result.addChange("No resources directory found");
            return;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        boolean foundNeo4jProperties = false;

        // Process YAML files
        for (Path yamlFile : yamlFiles) {
            if (migrateYamlFile(yamlFile, result)) {
                foundNeo4jProperties = true;
            }
        }

        // Process properties files
        for (Path propFile : propFiles) {
            if (migratePropertiesFile(propFile, result)) {
                foundNeo4jProperties = true;
            }
        }

        if (!foundNeo4jProperties) {
            result.addChange("No Neo4j properties detected");
        }
    }

    /**
     * Transform Neo4j properties in YAML data.
     */
    @SuppressWarnings("unchecked")
    protected boolean transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        boolean modified = false;

        if (!data.containsKey("spring")) {
            return false;
        }

        Map<String, Object> spring = (Map<String, Object>) data.get("spring");

        // Check for spring.data.neo4j section
        if (spring != null && spring.containsKey("data")) {
            Map<String, Object> dataSection = (Map<String, Object>) spring.get("data");

            if (dataSection != null && dataSection.containsKey("neo4j")) {
                Map<String, Object> oldNeo4j = (Map<String, Object>) dataSection.remove("neo4j");

                // Extract properties
                String uri = (String) oldNeo4j.get("uri");
                String username = (String) oldNeo4j.get("username");
                String password = (String) oldNeo4j.get("password");

                // Create new spring.neo4j section
                if (!spring.containsKey("neo4j")) {
                    spring.put("neo4j", new LinkedHashMap<>());
                }
                Map<String, Object> newNeo4j = (Map<String, Object>) spring.get("neo4j");

                // Migrate URI directly
                if (uri != null) {
                    newNeo4j.put("uri", uri);
                }

                // Create authentication subsection if credentials present
                if (username != null || password != null) {
                    Map<String, Object> authentication = new LinkedHashMap<>();
                    if (username != null)
                        authentication.put("username", username);
                    if (password != null)
                        authentication.put("password", password);
                    newNeo4j.put("authentication", authentication);
                }

                result.addChange(String.format("%s: Migrated spring.data.neo4j.* → spring.neo4j.*", fileName));
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Migrate Neo4j properties in a properties file.
     */
    private boolean migratePropertiesFile(Path propFile, MigrationPhaseResult result) throws IOException {
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(propFile)) {
            props.load(input);
        }

        boolean modified = false;

        // Property key transformations
        Map<String, String> propertyMigrations = Map.of(
                "spring.data.neo4j.uri", "spring.neo4j.uri",
                "spring.data.neo4j.username", "spring.neo4j.authentication.username",
                "spring.data.neo4j.password", "spring.neo4j.authentication.password");

        for (Map.Entry<String, String> migration : propertyMigrations.entrySet()) {
            String oldKey = migration.getKey();
            String newKey = migration.getValue();

            if (props.containsKey(oldKey)) {
                String value = props.getProperty(oldKey);
                props.remove(oldKey);
                props.setProperty(newKey, value);

                result.addChange(String.format("%s: %s → %s",
                        propFile.getFileName(), oldKey, newKey));
                modified = true;
            }
        }

        if (modified && !dryRun) {
            try (OutputStream output = Files.newOutputStream(propFile)) {
                props.store(output, "Neo4j property migration for Spring Boot 2.4");
            }
        }

        return modified;

    }

    /**
     * Detect Neo4j OGM usage in code.
     */
    private void detectNeo4jOGM(MigrationPhaseResult result) {
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithNeo4jOGM = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for Neo4j OGM imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                // Old Neo4j OGM packages
                if (importName.startsWith("org.neo4j.ogm") ||
                        importName.startsWith(
                                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration")) {
                    filesWithNeo4jOGM.add(className);
                    break;
                }
            }
        }

        if (!filesWithNeo4jOGM.isEmpty()) {
            result.setRequiresManualReview(true);
            result.addWarning(
                    String.format("Neo4j OGM detected in %d files - requires manual migration to Neo4j SDN-RX",
                            filesWithNeo4jOGM.size()));

            for (String className : filesWithNeo4jOGM) {
                result.addChange("Neo4j OGM usage in: " + className);
            }

            // Add migration guidance
            StringBuilder guide = new StringBuilder();
            guide.append("\n=== NEO4J OGM → SDN-RX MIGRATION GUIDE ===\n\n");
            guide.append("Spring Boot 2.4 removed Neo4j OGM support. You must migrate to Neo4j SDN-RX:\n\n");
            guide.append("1. UPDATE DEPENDENCIES:\n");
            guide.append("   Remove: org.neo4j.ogm:*\n");
            guide.append("   Use: org.springframework.boot:spring-boot-starter-data-neo4j (auto-includes SDN-RX)\n\n");
            guide.append("2. UPDATE IMPORTS:\n");
            guide.append("   OLD: org.neo4j.ogm.*\n");
            guide.append("   NEW: org.springframework.data.neo4j.repository.ReactiveNeo4jRepository\n\n");
            guide.append("3. REPOSITORIES:\n");
            guide.append("   OLD: extends Neo4jRepository\n");
            guide.append("   NEW: extends ReactiveNeo4jRepository for reactive, Neo4jRepository for imperative\n\n");
            guide.append("FILES REQUIRING CHANGES:\n");
            for (String className : filesWithNeo4jOGM) {
                guide.append("  - ").append(className).append("\n");
            }
            guide.append("\nREFERENCE: https://neo4j.com/docs/spring-data-neo4j/current/\n");

            result.addChange(guide.toString());
        }
    }

    @Override
    public String getPhaseName() {
        return "Neo4j Property Migration";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
