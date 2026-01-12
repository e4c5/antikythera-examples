package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Map;

@CommandLine.Command(name = "JPARepositoryAnalyzer", mixinStandardHelpOptions = true, version = "1.0", description = "Analyzes JPA repositories and exports queries to a CSV file.")
public class JPARepositoryAnalyzer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(JPARepositoryAnalyzer.class);

    @CommandLine.Option(names = { "-b", "--base-path" }, description = "Base path of the project to analyze")
    private String basePath;

    @CommandLine.Option(names = { "-o",
            "--output" }, description = "Output CSV file path", defaultValue = "repository_queries.csv")
    private String outputFile = "repository_queries.csv";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JPARepositoryAnalyzer()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            Settings.loadConfigMap();
            if (basePath != null) {
                Settings.setProperty(Settings.BASE_PATH, basePath);
            } else {
                logger.info("Base path argument not provided. Defaulting to settings: {}", Settings.getBasePath());
            }
            AbstractCompiler.preProcess(); // This will use the updated base path from Settings
            analyzeAndExport();
        } catch (IOException e) {
            logger.error("Error during execution", e);
        }
    }

    public void analyzeAndExport() throws IOException {
        java.io.File file = new java.io.File(outputFile);
        boolean fileExists = file.exists() && file.length() > 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            if (!fileExists) {
                writer.println("Fully Qualified Class Name,Method Name,Query");
            }

            Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();
            for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
                TypeWrapper typeWrapper = entry.getValue();

                if (BaseRepositoryParser.isJpaRepository(typeWrapper)) {
                    processRepository(typeWrapper, writer);
                }
            }
            logger.info("Analysis complete. Results written to {}", outputFile);
        }
    }

    private void processRepository(TypeWrapper typeWrapper, PrintWriter writer) throws IOException {
        String fullyQualifiedName = typeWrapper.getFullyQualifiedName();
        RepositoryParser parser = new RepositoryParser();
        parser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        parser.processTypes();

        if (parser.getEntity() != null && parser.getEntity().getFullyQualifiedName() != null) {
            parser.buildQueries();
            Collection<RepositoryQuery> queries = parser.getAllQueries();

            for (RepositoryQuery query : queries) {
                writeQueryToCsv(query, writer);
            }
        }
    }

    private void writeQueryToCsv(RepositoryQuery query, PrintWriter writer) {
        String className = query.getRepositoryClassName();
        if (className == null) {
            className = query.getClassname(); // Fallback
        }

        String methodName = query.getMethodName();
        String sql = query.getStatement() != null ? query.getStatement().toString() : "";

        // Escape logic: replace newlines with tabs
        String escapedSql = sql.replace("\n", "\t").replace("\r", "");

        // CSV escaping for fields containing commas or quotes
        writer.printf("%s,%s,%s%n",
                escapeCsvField(className),
                escapeCsvField(methodName),
                escapeCsvField(escapedSql));
    }

    private String escapeCsvField(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuotes = field.contains(",") || field.contains("\"") || field.contains("\n");
        if (needsQuotes) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
