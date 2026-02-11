package com.raditha.graph;

import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Command Line Interface for generating the Knowledge Graph.
 * <p>
 * Usage: java com.raditha.graph.KnowledgeGraphCLI <path-to-project-src> [path-to-graph.yml]
 * </p>
 */
@SuppressWarnings("java:S106")
public class KnowledgeGraphCLI {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphCLI.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java com.raditha.graph.KnowledgeGraphCLI <path-to-project-src> [path-to-graph.yml]");
            System.exit(1);
        }

        String projectPath = args[0];
        String configPath = args.length > 1 ? args[1] : "src/main/resources/graph.yml";

        try {
            new KnowledgeGraphCLI().run(projectPath, configPath);
        } catch (Exception e) {
            logger.error("Analysis failed", e);
            System.exit(1);
        }
    }

    public void run(String projectPath, String configPath) throws IOException {
        logger.info("Initializing Knowledge Graph Builder...");
        logger.info("Target Project: {}", projectPath);
        logger.info("Configuration: {}", configPath);

        // 1. Load Configuration
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configPath);
        }
        Settings.loadConfigMap(configFile);

        // 2. Override base_path with the provided CLI argument
        // This ensures AbstractCompiler looks at the correct source root
        updateBasePath(projectPath);

        // 3. Initialize Parser via MavenHelper to resolve dependencies
        AbstractCompiler.reset();
        try {
            MavenHelper mavenHelper = new MavenHelper();
            mavenHelper.readPomFile();
            mavenHelper.buildJarPaths();
        } catch (Exception e) {
            logger.warn("Could not load Maven dependencies (pom.xml not found or invalid). Proceeding with limited resolution.", e);
        }

        logger.info("Pre-processing project sources via Antikythera...");
        AbstractCompiler.preProcess();

        // 4. Collect resolved compilation units from runtime
        List<CompilationUnit> units = collectCompilationUnits();
        logger.info("Found {} compilation units to analyze.", units.size());

        if (units.isEmpty()) {
            logger.warn("No compilation units found. Check the project path and structure.");
            return;
        }

        // 5. Build Graph
        Neo4jGraphStore store = Neo4jGraphStore.fromSettings(configFile);
        KnowledgeGraphBuilder builder = new KnowledgeGraphBuilder(store);
        builder.build(units);
    }

    private void updateBasePath(String projectPath) {
        Settings.setProperty("base_path", projectPath);
    }

    private List<CompilationUnit> collectCompilationUnits() {
        return new java.util.ArrayList<>(AntikytheraRunTime.getResolvedCompilationUnits().values());
    }
}
