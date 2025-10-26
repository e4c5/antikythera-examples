package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Fields.buildDependencies();
    }

    @Override
    protected void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws IOException, ReflectiveOperationException, InterruptedException {
        super.analyzeRepository(fullyQualifiedName, typeWrapper);
        List<QueryOptimizationResult> updates = new ArrayList<>();

        for (QueryOptimizationResult result : results) {
            if (!result.isAlreadyOptimized()) {
                updates.add(result);
            }
        }
        if (!updates.isEmpty()) {
            writeFile(fullyQualifiedName);
        }
//        applySignatureUpdatesToUsages(updates, fullyQualifiedName);
    }

    /**
     * Apply recorded signature updates to usage sites in classes that @Autowired the given repository.
     * This reorders call arguments to match the new parameter order. Only same-arity calls are modified.
     */
    public void applySignatureUpdatesToUsages(List<CodeStandardizer.SignatureUpdate> updates, String fullyQualifiedName) throws FileNotFoundException {
        Map<String, String> fields = Fields.getFieldDependencies(fullyQualifiedName);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String className = entry.getKey();
            String fieldName = entry.getValue();
            TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(className);
            NameChangeVisitor visitor = new NameChangeVisitor(fieldName);
            typeWrapper.getType().accept(visitor, updates);
            if (visitor.modified) {
                writeFile(className);
            }
        }
    }

    private static void writeFile(String fullyQualifiedName) throws FileNotFoundException {
        String fullPath = Settings.getBasePath() + "src/main/java/" + AbstractCompiler.classToPath(fullyQualifiedName);
        File f = new File(fullPath);

        if (f.exists()) {
            var cu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
            if (cu == null) {
                // No parsed CompilationUnit available, skip writing to avoid truncating the file
                return;
            }
            String original;
            try {
                original = Files.readString(Path.of(fullPath), StandardCharsets.UTF_8);
            } catch (IOException e) {
                original = null; // If reading fails, proceed to write to be safe
            }
            String content;
            // Try to preserve original formatting/comments; fall back to plain printing if ranges are missing
            try {
                content = LexicalPreservingPrinter.print(cu);
            } catch (RuntimeException ex) {
                // Lexical preservation can fail if some nodes don't have ranges; fall back to standard pretty print
                content = cu.toString();
            }
            // If resulting content is identical to original, skip writing to avoid incidental whitespace changes
            if (original != null && original.equals(content)) {
                return;
            }
            try (PrintWriter writer = new PrintWriter(f, StandardCharsets.UTF_8)) {
                writer.print(content);
            } catch (IOException ioe) {
                try (PrintWriter writer = new PrintWriter(f)) {
                    writer.print(content);
                }
            }
        }
    }

    static class NameChangeVisitor extends ModifierVisitor<List<CodeStandardizer.SignatureUpdate>> {
        String fielName;
        boolean modified;
        NameChangeVisitor(String fieldName) {
            this.fielName = fieldName;
        }

        @Override
        public MethodCallExpr visit(MethodCallExpr mce, List<CodeStandardizer.SignatureUpdate> updates) {
            super.visit(mce, updates);
            Optional<Expression> scope = mce.getScope();
            if (scope.isPresent() && scope.get() instanceof NameExpr fe && fe.getNameAsString().equals(fielName)) {
                for (CodeStandardizer.SignatureUpdate update : updates) {
                    if (update.methodName.equals(mce.getNameAsString())) {
                        mce.setName(update.newMethodName);
                        modified = true;
                    }
                }
            }
            return mce;
        }
    }

    public static void main(String[] args) throws Exception {
        long s = System.currentTimeMillis();
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();
        System.out.println("Time to preprocess   " + (System.currentTimeMillis() - s) + "ms");
        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizer checker = new QueryOptimizer(getLiquibasePath());
        System.out.println("Time to build field map   " + (System.currentTimeMillis() - s) + "ms");
        checker.analyze();

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
