package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.ImportDeclaration;
import org.yaml.snakeyaml.Yaml;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Detects JMX usage in the codebase and automatically enables JMX
 * configuration.
 * 
 * Detection patterns:
 * - @ManagedResource annotation
 * - javax.management.* imports
 * - Spring Kafka usage (often uses JMX for metrics)
 */
public class JmxConfigDetector extends AbstractConfigMigrator {


    public JmxConfigDetector(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Detect JMX usage and enable configuration if needed.
     */
    public MigrationPhaseResult migrate() throws IOException {
        MigrationPhaseResult result = new MigrationPhaseResult();
        boolean needsJmx = detectJmxUsage(result);

        if (needsJmx) {
            enableJmxConfiguration(result);
        } else {
            result.addChange("No JMX usage detected - no configuration needed");
        }

        return result;
    }

    /**
     * Detect if JMX is being used in the codebase.
     */
    private boolean detectJmxUsage(MigrationPhaseResult result) {

        // Iterate over all loaded compilation units
        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            // Check for @ManagedResource annotation
            List<AnnotationExpr> annotations = cu.findAll(AnnotationExpr.class);
            for (AnnotationExpr annotation : annotations) {
                if (annotation.getNameAsString().equals("ManagedResource")) {
                    result.addChange("Detected JMX usage: @ManagedResource in " + className);
                    return true;
                }
            }

            // Check for javax.management imports
            List<ImportDeclaration> imports = cu.findAll(ImportDeclaration.class);
            for (ImportDeclaration imp : imports) {
                if (imp.getNameAsString().startsWith("javax.management")) {
                    result.addChange("Detected JMX usage: javax.management import in " + className);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Enable JMX in application.yml.
     */
    @SuppressWarnings("unchecked")
    private void enableJmxConfiguration(MigrationPhaseResult result) throws IOException {
        Path yamlFile = findApplicationYaml();

        if (yamlFile == null) {
            result.addWarning("Could not find application.yml - JMX not auto-enabled");
            return;
        }

        Yaml yaml = YamlUtils.createYaml();
        Map<String, Object> data;

        try (InputStream input = Files.newInputStream(yamlFile)) {
            data = yaml.load(input);
        }

        if (data == null) {
            data = new HashMap<>();
        }

        // Add spring.jmx.enabled=true
        Map<String, Object> spring = (Map<String, Object>) data.computeIfAbsent("spring", k -> new HashMap<>());
        Map<String, Object> jmx = (Map<String, Object>) spring.computeIfAbsent("jmx", k -> new HashMap<>());

        if (!jmx.containsKey("enabled")) {
            jmx.put("enabled", true);

            if (!dryRun) {
                try (Writer writer = Files.newBufferedWriter(yamlFile)) {
                    yaml.dump(data, writer);
                }
                result.addChange("Added spring.jmx.enabled=true to " + yamlFile.getFileName());
            } else {
                result.addChange("Would add spring.jmx.enabled=true to " + yamlFile.getFileName());
            }
        } else {
            result.addChange("spring.jmx.enabled already configured");
        }
    }

    @Override
    public String getPhaseName() {
        return "JMX Detection";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
