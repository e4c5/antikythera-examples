package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.Evaluator;
import sa.com.cloudsolutions.antikythera.evaluator.EvaluatorFactory;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("java:S106")
public class QueryOptimizer extends QueryOptimizationChecker{
    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for comprehensive query analysis.
     *
     * @param liquibaseXmlPath path to the Liquibase XML file for database metadata
     * @throws Exception if initialization fails
     */
    public QueryOptimizer(File liquibaseXmlPath) throws Exception {
        super(liquibaseXmlPath);
    }

    @Override
    protected void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws IOException, ReflectiveOperationException {
        if (!fullyQualifiedName.endsWith("HospitalNursingClinicRepository")) {
            return;
        }
        super.analyzeRepository(fullyQualifiedName, typeWrapper);
        CodeStandardizer standardizer = new CodeStandardizer();
        boolean modified = false;
        for (QueryOptimizationResult result : results) {
            if (!result.isOptimized()) {
                Optional<CodeStandardizer.SignatureUpdate> bada =  standardizer.standardize(result);
                if (bada.isPresent()) {
                    modified = true;
                }
            }
        }
        if (modified) {
            String fullPath = Settings.getBasePath() + "src/main/java/" + AbstractCompiler.classToPath(fullyQualifiedName);
            File f = new File(fullPath);

            if (f.exists()) {
                PrintWriter writer = new PrintWriter(f);

                writer.print(LexicalPreservingPrinter.print(AntikytheraRunTime.getCompilationUnit(fullyQualifiedName)));
                writer.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        long s = System.currentTimeMillis();
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizer checker = new QueryOptimizer(getLiquibasePath());
        checker.analyze();
        // Apply any method signature updates to usages in @Autowired consumers
        checker.applySignatureUpdatesToUsages();

        // Print consolidated index actions at end of analysis
        checker.printConsolidatedIndexActions();
        // Generate Liquibase file with suggested changes and include in master
        checker.generateLiquibaseChangesFile();

        // Print execution summary
        int queries = checker.getTotalQueriesAnalyzed();
        int high = checker.getTotalHighPriorityRecommendations();
        int medium = checker.getTotalMediumPriorityRecommendations();
        int createCount = checker.getTotalIndexCreateRecommendations();
        int dropCount = checker.getTotalIndexDropRecommendations();
        System.out.printf(
                "%nSUMMARY: Analyzed %d quer%s. Recommendations given: %d high priorit%s, %d medium priorit%s. Index actions: %d creation%s, %d drop%s.",
                queries,
                queries == 1 ? "y" : "ies",
                high,
                high == 1 ? "y" : "ies",
                medium,
                medium == 1 ? "y" : "ies",
                createCount,
                createCount == 1 ? "" : "s",
                dropCount,
                dropCount == 1 ? "" : "s"
        );

        System.out.println("Time taken " + (System.currentTimeMillis() - s) + " ms.");
        // Exit with non-zero if at least 1 high and at least 10 medium priority recommendations
        if (high >= 1 && medium >= 10) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }
}
