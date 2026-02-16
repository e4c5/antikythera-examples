package com.raditha.graph;

import com.github.javaparser.ast.CompilationUnit;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Command Line Interface for generating the Knowledge Graph.
 * <p>
 * Usage:
 * java com.raditha.graph.KnowledgeGraphCLI [--config=<path-to-graph.yml>] [--base-path=<path-to-project-src>]
 * <br>
 * Backward-compatible positional usage:
 * java com.raditha.graph.KnowledgeGraphCLI <path-to-project-src> [path-to-graph.yml]
 * </p>
 */
@SuppressWarnings("java:S106")
public class KnowledgeGraphCLI {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphCLI.class);

    private static final String DEFAULT_CONFIG_PATH = "src/main/resources/graph.yml";
    private static final String CONFIG_OPTION = "--config=";
    private static final String BASE_PATH_OPTION = "--base-path=";
    private static final String PROJECT_PATH_OPTION = "--project-path=";

    public static void main(String[] args) {
        try {
            CliOptions options = parseArgs(args);
            new KnowledgeGraphCLI().run(options.basePath(), options.configPath());
        } catch (Exception e) {
            logger.error("Analysis failed", e);
            System.exit(1);
        }
    }

    static CliOptions parseArgs(String[] args) throws IOException {
        List<String> positionalArgs = Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--"))
                .toList();

        String configPath = findOptionValue(args, CONFIG_OPTION)
                .orElseGet(() -> positionalArgs.size() > 1 ? positionalArgs.get(1) : DEFAULT_CONFIG_PATH);
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        // Load first so base_path can be sourced from graph.yml (same settings-driven pattern as other tools).
        Settings.loadConfigMap(configFile);

        String optionBasePath = findOptionValue(args, BASE_PATH_OPTION)
                .or(() -> findOptionValue(args, PROJECT_PATH_OPTION))
                .orElse(null);

        String positionalBasePath = positionalArgs.isEmpty() ? null : positionalArgs.getFirst();

        String basePath = optionBasePath != null ? optionBasePath : positionalBasePath;
        if (basePath == null) {
            basePath = Settings.getProperty(Settings.BASE_PATH, String.class).orElse(null);
        }

        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("""
                    Missing project source path.
                    Provide one of:
                    - --base-path=<path-to-project-src> (or --project-path=...)
                    - positional arg: <path-to-project-src>
                    - base_path in graph.yml
                    """.stripIndent());
        }

        return new CliOptions(basePath, configPath);
    }

    private static Optional<String> findOptionValue(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    public void run(String projectPath, String configPath) throws IOException, SQLException, XmlPullParserException {
        logger.info("Initializing Knowledge Graph Builder...");
        logger.info("Target Project: {}", projectPath);
        logger.info("Configuration: {}", configPath);

        // 1. Ensure Configuration is loaded from selected file
        File configFile = new File(configPath);
        Settings.loadConfigMap(configFile);

        // 2. Override base_path with the provided CLI argument
        // This ensures AbstractCompiler looks at the correct source root
        updateBasePath(projectPath);

        // 3. Initialize Parser via MavenHelper to resolve dependencies
        AbstractCompiler.reset();

        MavenHelper mavenHelper = new MavenHelper();
        mavenHelper.readPomFile();
        mavenHelper.buildJarPaths();

        new AbstractCompiler();
        AbstractCompiler.preProcess();

        // 4. Collect resolved compilation units from runtime
        List<CompilationUnit> units = collectCompilationUnits();
        logger.info("Found {} compilation units to analyze.", units.size());

        if (units.isEmpty()) {
            logger.warn("No compilation units found. Check the project path and structure.");
            return;
        }

        // 5. Build Graph (Settings already loaded above, use the no-arg variant
        //    so the base_path override from step 2 is preserved)
        GraphStore store = GraphStoreFactory.createGraphStore();
        KnowledgeGraphBuilder builder = new KnowledgeGraphBuilder(store);
        builder.build(units);
    }

    private void updateBasePath(String projectPath) {
        Settings.setProperty("base_path", projectPath);
    }

    private List<CompilationUnit> collectCompilationUnits() {
        return new java.util.ArrayList<>(AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    record CliOptions(String basePath, String configPath) {
    }
}
