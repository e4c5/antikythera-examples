package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.ast.NodeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.QueryType;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("java:S106")
public class QueryOptimizer extends QueryOptimizationChecker {
    private static final Logger logger = LoggerFactory.getLogger(QueryOptimizer.class);
    private boolean repositoryFileModified;
    private static final Set<String> modifiedFiles = new java.util.HashSet<>();

    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for
     * comprehensive query analysis.
     *
     * @param liquibaseXmlPath path to the Liquibase XML file for database metadata
     * @throws Exception if initialization fails
     */
    public QueryOptimizer(File liquibaseXmlPath) throws Exception {
        super(liquibaseXmlPath);
        Fields.buildDependencies();
        EntityMappingResolver.build();
    }

    @Override
    void analyzeRepository(TypeWrapper typeWrapper)
            throws IOException, ReflectiveOperationException, InterruptedException {
        if (!typeWrapper.getFullyQualifiedName().endsWith(".AdmissionRequestRepository")) {
            return;
        }
        super.analyzeRepository(typeWrapper);

        OptimizationStatsLogger.updateQueriesAnalyzed(results.size());

        List<QueryAnalysisResult> updates = new ArrayList<>();
        repositoryFileModified = false;

        for (QueryAnalysisResult result : results) {
            actOnAnalysisResult(result, updates);
        }

        if (repositoryFileModified) {
            boolean fileWasWritten = writeFile(typeWrapper.getFullyQualifiedName(),
                    this.repositoryParser.getCompilationUnit());
            if (!fileWasWritten) {
                repositoryFileModified = false;
            }
        }
    }

    void actOnAnalysisResult(QueryAnalysisResult result, List<QueryAnalysisResult> updates) {
        OptimizationIssue issue = result.getOptimizationIssue();
        if (issue != null) {
            RepositoryQuery optimizedQuery = issue.optimizedQuery();
            if (optimizedQuery != null) {
                String queryValue;
                if (QueryType.HQL.equals(optimizedQuery.getQueryType())) {
                    queryValue = optimizedQuery.getOriginalQuery();
                } else {
                    queryValue = optimizedQuery.getStatement().toString();
                }

                // Convert to text block if the query contains newlines
                updateAnnotationValueWithTextBlockSupport(
                        issue.query().getMethodDeclaration().asMethodDeclaration(), queryValue);

                // Check if method name changed (indicating signature should change)
                boolean methodNameChanged = !issue.query().getMethodName().equals(optimizedQuery.getMethodName());

                // Apply method name change if recommended
                if (methodNameChanged) {
                    updateMethodCallSignatures(result, issue.query().getRepositoryClassName());

                    MethodDeclaration method = issue.query().getMethodDeclaration().asMethodDeclaration();
                    Callable newMethod = optimizedQuery.getMethodDeclaration();
                    method.setName(newMethod.getNameAsString());
                    logger.info("Changed method name from {} to {}",
                            issue.query().getMethodName(), newMethod.getNameAsString());

                    reorderMethodParameters(
                            issue.query().getMethodDeclaration().asMethodDeclaration(),
                            newMethod.asMethodDeclaration());
                }
                // Track if file was modified (annotation always changes and may also have
                // parameter reordering)
                repositoryFileModified = true;

                // Track method signature changes
                if (methodNameChanged) {
                    OptimizationStatsLogger.updateMethodSignaturesChanged(1);
                }
                updates.add(result);
            }
        }
    }

    /**
     * Updates the annotation value with proper text block support.
     * If the query contains literal \n characters or actual newlines, it uses
     * TextBlockLiteralExpr.
     * Otherwise, it uses StringLiteralExpr.
     *
     * @param method         the method declaration containing the annotation
     * @param newStringValue the new query value
     */
    private void updateAnnotationValueWithTextBlockSupport(MethodDeclaration method, String newStringValue) {
        // Check if the string contains literal \n or actual newlines
        boolean isMultiline = newStringValue != null &&
                (newStringValue.contains("\\n") || newStringValue.contains("\n"));

        if (isMultiline) {
            // Convert literal \n to actual newlines
            String processedValue = "    " + newStringValue.replace("\\n", "\n        ");
            updateAnnotationValue(method, "Query", processedValue, true);
        } else {
            updateAnnotationValue(method, "Query", newStringValue, false);
        }
    }

    /**
     * Updates the "value" of a JavaParser AnnotationExpr on a method,
     * preserving the original annotation's indentation and formatting.
     * <p>
     * This handles both single-member (@Query("...")) and normal
     * (@Query(value = "...")) annotation styles.
     * 
     * IMPORTANT: Modifies the annotation IN PLACE to ensure
     * LexicalPreservingPrinter tracks changes.
     *
     * @param method         The method node containing the annotation.
     * @param annotationName The name of the annotation to find (e.g., "Query").
     * @param newStringValue The new string to set as the annotation's value.
     * @param useTextBlock   Whether to use TextBlockLiteralExpr (true) or
     *                       StringLiteralExpr (false)
     */
    public void updateAnnotationValue(MethodDeclaration method,
            String annotationName,
            String newStringValue,
            boolean useTextBlock) {

        // 1. Find the annotation on the method
        Optional<AnnotationExpr> oldAnnotationOpt = method.getAnnotationByName(annotationName);
        if (oldAnnotationOpt.isPresent()) {
            AnnotationExpr annotation = oldAnnotationOpt.get();

            // 2. Create the new value expression (text block or regular string)
            Expression newValueExpr;
            if (useTextBlock) {
                newValueExpr = new TextBlockLiteralExpr(newStringValue);
            } else {
                newValueExpr = new StringLiteralExpr(newStringValue);
            }

            // 3. Modify IN PLACE (not replace) so LexicalPreservingPrinter tracks the
            // change
            if (annotation.isSingleMemberAnnotationExpr()) {
                // --- Case 1: @Query("...") ---
                annotation.asSingleMemberAnnotationExpr().setMemberValue(newValueExpr);
            } else if (annotation.isNormalAnnotationExpr()) {
                // --- Case 2: @Query(value = "...") ---
                NormalAnnotationExpr normal = annotation.asNormalAnnotationExpr();

                // Find the pair named "value" and update it IN PLACE
                Optional<MemberValuePair> valuePair = normal.getPairs().stream()
                        .filter(p -> p.getName().asString().equals("value"))
                        .findFirst();

                valuePair.ifPresent(memberValuePair ->
                    memberValuePair.setValue(newValueExpr)
                );
            }
            OptimizationStatsLogger.updateQueryAnnotationsChanged(1);
        }
    }

    /**
     * Reorders method parameters to match the recommended column order.
     * This ensures the method signature matches the optimized WHERE clause column
     * order.
     *
     * @param method                 the method declaration to modify
     * @param newMethod              the updated method signature
     */
    void reorderMethodParameters(MethodDeclaration method,MethodDeclaration newMethod) {
        NodeList<Parameter> params = method.getParameters();
        for (int i = 0 ; i < params.size() ; i++ ) {
            params.set(i, newMethod.getParameter(i).clone());
        }
    }

    /**
     * Apply recorded signature updates to usage sites and return statistics about
     * changes made.
     * This reorders call arguments to match the new parameter order. Only
     * same-arity calls are modified.
     * 
     * @param update             the optimization results with signature changes
     * @param fullyQualifiedName the repository class name
     */
    public void updateMethodCallSignatures(QueryAnalysisResult update, String fullyQualifiedName) {
        Map<String, Set<String>> fields = Fields.getFieldDependencies(fullyQualifiedName);

        if (fields == null) {
            return;
        }

        for (Map.Entry<String, Set<String>> entry : fields.entrySet()) {
            String className = entry.getKey();
            Set<String> fieldNames = entry.getValue();

            TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(className);

            if (typeWrapper != null) {
                boolean classModified = false;
                int totalMethodCallsUpdated = 0;

                // Process all field names for this class
                for (String fieldName : fieldNames) {
                    NameChangeVisitor visitor = new NameChangeVisitor(fieldName, fullyQualifiedName);
                    // Visit the entire CompilationUnit to ensure modifications apply to the CU
                    // instance
                    CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                    cu.accept(visitor, update);

                    if (visitor.modified) {
                        classModified = true;
                        totalMethodCallsUpdated += visitor.methodCallsUpdated;
                    }
                }

                // Write file once if any field was modified
                if (classModified) {
                    modifiedFiles.add(className);
                    OptimizationStatsLogger.updateMethodCallsChanged(totalMethodCallsUpdated);
                }
            }
        }
    }

    /**
     * Writes the modified compilation unit to disk using FileOperationsManager.
     * 
     * Attempts to use LexicalPreservingPrinter for whitespace preservation, but
     * falls back to cu.toString() if:
     * 1. LexicalPreservingPrinter throws an exception
     * 2. LexicalPreservingPrinter returns unchanged content (indicating AST mods weren't tracked)
     * 
     * The fallback uses JavaParser's default pretty printer which produces
     * consistent formatting.
     * 
     * @return true if the file was actually written (content changed), false if
     *         skipped (no changes)
     */
    static boolean writeFile(String fullyQualifiedName) throws IOException {
        return writeFile(fullyQualifiedName, AntikytheraRunTime.getCompilationUnit(fullyQualifiedName));
    }

    static boolean writeFile(String fullyQualifiedName, CompilationUnit cu) throws IOException {
        String relativePath = AbstractCompiler.classToPath(fullyQualifiedName);
        String fullPath = Settings.getBasePath() + "/src/main/java/" + relativePath;

        // Use LexicalPreservingPrinter for whitespace preservation
        // Since we modify annotations IN PLACE, LexicalPreservingPrinter should track
        // changes properly
        String content;

        try {
            content = LexicalPreservingPrinter.print(cu);
        } catch (Exception e) {
            // LexicalPreservingPrinter can still fail on complex AST modifications (e.g.,
            // parameter reordering)
            logger.warn("LexicalPreservingPrinter failed for {}: {}. Using default printer (may lose formatting).",
                    fullyQualifiedName, e.getMessage());
            content = cu.toString();
        }

        File f = new File(fullPath);

        if (f.exists()) {
            return writeFile(f, content);
        }
        else {
            File t = new File(fullPath.replace("src/main","src/test"));
            if (t.exists()) {
                return writeFile(t, content);
            }
        }

        return false;
    }

    private static boolean writeFile(File f, String content) throws FileNotFoundException {
        PrintWriter writer = new PrintWriter(f);
        writer.print(content); // Use the content variable we already computed
        writer.close();
        return true;
    }

    static class NameChangeVisitor extends ModifierVisitor<QueryAnalysisResult> {
        String fieldName;
        String repositoryFqn;
        boolean modified;
        int methodCallsUpdated = 0;

        NameChangeVisitor(String fieldName, String repositoryFqn) {
            this.fieldName = fieldName;
            this.repositoryFqn = repositoryFqn;
        }

        @Override
        public MethodCallExpr visit(MethodCallExpr mce, QueryAnalysisResult update) {
            super.visit(mce, update);
            Optional<Expression> scope = mce.getScope();
            OptimizationIssue issue = update.getOptimizationIssue();
            if (issue == null || issue.optimizedQuery() == null) {
                return mce;
            }

            if (scope.isPresent() && scope.get() instanceof NameExpr fe && fe.getNameAsString().equals(fieldName)) {
                // Check if this call matches the current update's method name

                if (update.getMethodName().equals(mce.getNameAsString())) {
                    String originalMethodName = mce.getNameAsString();
                    String newMethodName = issue.optimizedQuery().getMethodName();

                    // Only count as an update if something actually changes
                    boolean methodNameChanged = !originalMethodName.equals(newMethodName);

                    // Update method name if it changed
                    if (methodNameChanged) {
                        mce.setName(newMethodName);
                        reorderMethodArguments(mce, issue);
                    }

                    // Only mark as modified and increment counter if actual changes were made
                    if (methodNameChanged) {
                        modified = true;
                        methodCallsUpdated++;
                    }

                }
            }
            return mce;
        }

        /**
         * Reorders method call arguments based on the optimization issue's column order
         * changes.
         * This ensures that when parameter order changes (e.g., findByEmailAndStatus ->
         * findByStatusAndEmail),
         * the method call arguments are also reordered to match.
         * 
         */
        void reorderMethodArguments(MethodCallExpr mce, OptimizationIssue issue) {
            MethodDeclaration oldMethod = issue.query().getMethodDeclaration().asMethodDeclaration();
            MethodDeclaration newMethod = issue.optimizedQuery().getMethodDeclaration().asMethodDeclaration();
            Map<Integer, Integer> map = new HashMap<>();
            for (int i = 0 ; i < oldMethod.getParameters().size() ; i++) {
                for (int j = 0 ; j < newMethod.getParameters().size() ; j++) {
                    if (oldMethod.getParameter(i).toString().equals(newMethod.getParameter(j).toString())) {
                        map.put(j, i);
                    }
                }
            }

            NodeList<Expression> args = mce.getArguments();
            NodeList<Expression> newArgs = new NodeList<>();
            for (int i = 0 ; i < args.size() ; i++) {
                newArgs.add(args.get(map.get(i)));
            }

            mce.getArguments().clear();
            mce.setArguments(newArgs);
        }
    }

    /**
     * Checks if a command-line flag is present.
     * 
     * @param args command-line arguments
     * @param flag the flag to check for
     * @return true if the flag is present
     */
    static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        long s = System.currentTimeMillis();
        Settings.loadConfigMap();

        // Enable lexical preservation BEFORE preProcess to preserve whitespace in all
        // parsed files
        AbstractCompiler.setEnableLexicalPreservation(true);

        AbstractCompiler.preProcess();

        // Parse command-line flags
        boolean quietMode = hasFlag(args, "--quiet") || hasFlag(args, "-q");
        QueryOptimizationChecker.setQuietMode(quietMode);

        if (!quietMode) {
            System.out.println("Time to preprocess   " + (System.currentTimeMillis() - s) + "ms");
        }

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizer checker = new QueryOptimizer(getLiquibasePath());
        if (!quietMode) {
            System.out.println("Time to build field map   " + (System.currentTimeMillis() - s) + "ms");
        }
        checker.analyze();

        // Generate Liquibase file with suggested changes and include in master
        checker.generateLiquibaseChangesFile();

        OptimizationStatsLogger.initialize("");
        OptimizationStatsLogger.printSummary(System.out);
        OptimizationStatsLogger.updateDependentClassesChanged(modifiedFiles.size());
        updateFiles();

        if (!quietMode) {
            System.out.println("\nTime taken " + (System.currentTimeMillis() - s) + " ms.");
        }
        System.out.println("📊 Detailed statistics logged to: query-optimization-stats.csv");

        // Explicitly exit to ensure JVM shuts down (HttpClient may have non-daemon
        // threads)
        System.exit(0);
    }

    private static void updateFiles() throws IOException {
        for (String className : modifiedFiles) {
            writeFile(className);
        }
    }
}
