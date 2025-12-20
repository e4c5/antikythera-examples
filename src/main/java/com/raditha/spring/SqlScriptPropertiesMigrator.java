package com.raditha.spring;

import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates SQL script initialization properties for Spring Boot 2.5.
 * 
 * <p>
 * Spring Boot 2.5 redesigned SQL script initialization, moving properties from
 * {@code spring.datasource.*} to {@code spring.sql.init.*}:
 * <ul>
 * <li>{@code spring.datasource.initialization-mode} → {@code spring.sql.init.mode}</li>
 * <li>{@code spring.datasource.schema} → {@code spring.sql.init.schema-locations}</li>
 * <li>{@code spring.datasource.data} → {@code spring.sql.init.data-locations}</li>
 * <li>{@code spring.datasource.platform} → {@code spring.sql.init.platform}</li>
 * <li>{@code spring.datasource.continue-on-error} → {@code spring.sql.init.continue-on-error}</li>
 * <li>{@code spring.datasource.separator} → {@code spring.sql.init.separator}</li>
 * <li>{@code spring.datasource.sql-script-encoding} → {@code spring.sql.init.encoding}</li>
 * </ul>
 * 
 * <p>
 * Additionally, if JPA/Hibernate DDL generation is detected and data.sql is
 * present, the migrator adds:
 * {@code spring.jpa.defer-datasource-initialization=true} to ensure SQL scripts
 * run after Hibernate schema creation.
 * 
 * @see MigrationPhase
 */
public class SqlScriptPropertiesMigrator extends AbstractConfigMigrator {

    // Property mapping from old to new names
    private static final Map<String, String> PROPERTY_MAPPINGS = Map.of(
        "initialization-mode", "mode",
        "schema", "schema-locations",
        "data", "data-locations",
        "platform", "platform",
        "continue-on-error", "continue-on-error",
        "separator", "separator",
        "sql-script-encoding", "encoding"
    );

    public SqlScriptPropertiesMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Path basePath = Paths.get(Settings.getBasePath());
        Path resourcesPath = basePath.resolve("src/main/resources");

        if (!Files.exists(resourcesPath)) {
            result.addChange("No resources directory found");
            return result;
        }

        List<Path> yamlFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.yml", "*.yaml");
        List<Path> propFiles = PropertyFileUtils.findPropertyFiles(resourcesPath, "*.properties");

        boolean foundSqlProperties = false;
        boolean hasJpaDdl = false;
        boolean hasDataSql = false;

        // Process YAML files
        for (Path yamlFile : yamlFiles) {
            MigrationInfo info = migrateYamlFile(yamlFile, result);
            if (info.foundSqlProperties) {
                foundSqlProperties = true;
            }
            if (info.hasJpaDdl) {
                hasJpaDdl = true;
            }
            if (info.hasDataSql) {
                hasDataSql = true;
            }
        }

        // Process properties files
        for (Path propFile : propFiles) {
            MigrationInfo info = migratePropertiesFile(propFile, result);
            if (info.foundSqlProperties) {
                foundSqlProperties = true;
            }
            if (info.hasJpaDdl) {
                hasJpaDdl = true;
            }
            if (info.hasDataSql) {
                hasDataSql = true;
            }
        }

        // Check if data.sql exists in resources
        if (Files.exists(resourcesPath.resolve("data.sql"))) {
            hasDataSql = true;
        }

        // Add defer-datasource-initialization if needed
        if (hasJpaDdl && hasDataSql) {
            addDeferDataSourceInitialization(yamlFiles, propFiles, result);
        }

        if (!foundSqlProperties && !hasJpaDdl) {
            result.addChange("No SQL script initialization properties detected");
        }

        return result;
    }

    /**
     * Migrate SQL script properties in a YAML file.
     */
    private MigrationInfo migrateYamlFile(Path yamlFile, MigrationPhaseResult result) throws IOException {
        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }

        if (data == null) {
            return new MigrationInfo();
        }

        MigrationInfo info = transformYamlData(data, result, yamlFile.getFileName().toString());

        if (info.modified && !dryRun) {
            try (OutputStream output = Files.newOutputStream(yamlFile)) {
                yaml.dump(data, new OutputStreamWriter(output));
            }
        }

        return info;
    }

    /**
     * Transform SQL script properties in YAML data.
     */
    @SuppressWarnings("unchecked")
    private MigrationInfo transformYamlData(Map<String, Object> data, MigrationPhaseResult result, String fileName) {
        MigrationInfo info = new MigrationInfo();

        if (!data.containsKey("spring")) {
            return info;
        }

        Map<String, Object> spring = (Map<String, Object>) data.get("spring");

        // Check for JPA DDL auto configuration
        if (spring.containsKey("jpa")) {
            Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
            if (jpa != null && jpa.containsKey("hibernate")) {
                Map<String, Object> hibernate = (Map<String, Object>) jpa.get("hibernate");
                if (hibernate != null && hibernate.containsKey("ddl-auto")) {
                    String ddlAuto = (String) hibernate.get("ddl-auto");
                    if (ddlAuto != null && !ddlAuto.equals("none") && !ddlAuto.equals("validate")) {
                        info.hasJpaDdl = true;
                    }
                }
            }
        }

        // Check for spring.datasource section
        if (spring.containsKey("datasource")) {
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            if (datasource == null) {
                return info;
            }

            // Check if any SQL script properties exist
            Map<String, Object> sqlInitProps = new LinkedHashMap<>();
            
            for (Map.Entry<String, String> mapping : PROPERTY_MAPPINGS.entrySet()) {
                String oldKey = mapping.getKey();
                String newKey = mapping.getValue();
                
                if (datasource.containsKey(oldKey)) {
                    Object value = datasource.remove(oldKey);
                    sqlInitProps.put(newKey, value);
                    info.foundSqlProperties = true;
                    info.modified = true;
                    
                    if ("data-locations".equals(newKey) || "data".equals(oldKey)) {
                        info.hasDataSql = true;
                    }
                    
                    result.addChange(String.format("[%s] Migrated spring.datasource.%s → spring.sql.init.%s",
                            fileName, oldKey, newKey));
                }
            }

            // If we found SQL init properties, create the new structure
            if (!sqlInitProps.isEmpty()) {
                // Create spring.sql.init section
                if (!spring.containsKey("sql")) {
                    spring.put("sql", new LinkedHashMap<>());
                }
                
                Map<String, Object> sql = (Map<String, Object>) spring.get("sql");
                if (sql == null) {
                    sql = new LinkedHashMap<>();
                    spring.put("sql", sql);
                }
                
                sql.put("init", sqlInitProps);
            }
        }

        return info;
    }

    /**
     * Migrate SQL script properties in a .properties file.
     */
    private MigrationInfo migratePropertiesFile(Path propFile, MigrationPhaseResult result) throws IOException {
        MigrationInfo info = new MigrationInfo();
        Properties props = new Properties();

        try (InputStream input = Files.newInputStream(propFile)) {
            props.load(input);
        }

        Properties newProps = new Properties();
        
        for (String propName : props.stringPropertyNames()) {
            String propValue = props.getProperty(propName);
            
            // Check for JPA DDL auto
            if ("spring.jpa.hibernate.ddl-auto".equals(propName)) {
                String ddlAuto = propValue;
                if (ddlAuto != null && !ddlAuto.equals("none") && !ddlAuto.equals("validate")) {
                    info.hasJpaDdl = true;
                }
                newProps.setProperty(propName, propValue);
            }
            // Transform SQL script properties
            else if (propName.startsWith("spring.datasource.")) {
                String subKey = propName.substring("spring.datasource.".length());
                
                if (PROPERTY_MAPPINGS.containsKey(subKey)) {
                    String newKey = PROPERTY_MAPPINGS.get(subKey);
                    String newPropName = "spring.sql.init." + newKey;
                    newProps.setProperty(newPropName, propValue);
                    info.foundSqlProperties = true;
                    info.modified = true;
                    
                    if ("data-locations".equals(newKey) || "data".equals(subKey)) {
                        info.hasDataSql = true;
                    }
                    
                    result.addChange(String.format("[%s] Migrated %s → %s",
                            propFile.getFileName(), propName, newPropName));
                } else {
                    newProps.setProperty(propName, propValue);
                }
            } else {
                newProps.setProperty(propName, propValue);
            }
        }

        if (info.modified && !dryRun) {
            try (OutputStream output = Files.newOutputStream(propFile)) {
                newProps.store(output, "Migrated by SqlScriptPropertiesMigrator for Spring Boot 2.5");
            }
        }

        return info;
    }

    /**
     * Add defer-datasource-initialization property if JPA DDL and data.sql are both present.
     */
    @SuppressWarnings("unchecked")
    private void addDeferDataSourceInitialization(List<Path> yamlFiles, List<Path> propFiles, 
                                                   MigrationPhaseResult result) throws IOException {
        // Prefer YAML files, fall back to properties
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

            if (!data.containsKey("spring")) {
                data.put("spring", new LinkedHashMap<>());
            }

            Map<String, Object> spring = (Map<String, Object>) data.get("spring");
            if (!spring.containsKey("jpa")) {
                spring.put("jpa", new LinkedHashMap<>());
            }

            Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
            if (!jpa.containsKey("defer-datasource-initialization")) {
                jpa.put("defer-datasource-initialization", true);
                
                if (!dryRun) {
                    try (OutputStream output = Files.newOutputStream(mainYaml)) {
                        yaml.dump(data, new OutputStreamWriter(output));
                    }
                }
                
                result.addChange(String.format("[%s] Added spring.jpa.defer-datasource-initialization=true " +
                        "(SQL scripts will run AFTER Hibernate schema creation)", mainYaml.getFileName()));
                result.addWarning("IMPORTANT: Verify SQL script execution order after migration");
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

            if (!props.containsKey("spring.jpa.defer-datasource-initialization")) {
                props.setProperty("spring.jpa.defer-datasource-initialization", "true");
                
                if (!dryRun) {
                    try (OutputStream output = Files.newOutputStream(mainProp)) {
                        props.store(output, "Updated by SqlScriptPropertiesMigrator for Spring Boot 2.5");
                    }
                }
                
                result.addChange(String.format("[%s] Added spring.jpa.defer-datasource-initialization=true " +
                        "(SQL scripts will run AFTER Hibernate schema creation)", mainProp.getFileName()));
                result.addWarning("IMPORTANT: Verify SQL script execution order after migration");
            }
        }
    }

    @Override
    public String getPhaseName() {
        return "SQL Script Properties Migration";
    }

    @Override
    public int getPriority() {
        return 30; // High priority - critical for SQL script functionality
    }

    /**
     * Helper class to track migration information.
     */
    private static class MigrationInfo {
        boolean modified = false;
        boolean foundSqlProperties = false;
        boolean hasJpaDdl = false;
        boolean hasDataSql = false;
    }
}
