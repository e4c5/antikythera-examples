package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("java:S106")
public class QueryOptimizer extends QueryOptimizationChecker {
    private static final Logger logger = LoggerFactory.getLogger(QueryOptimizer.class);
    private boolean repositoryFileModified;
    private static Set<String> modifiedFiles = new java.util.HashSet<>();

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
        super.analyzeRepository(typeWrapper);

        OptimizationStatsLogger.updateQueriesAnalyzed(results.size());

        repositoryFileModified = false;
        for (QueryAnalysisResult result : results) {
            actOnAnalysisResult(result);
        }

        if (repositoryFileModified && writeFile(typeWrapper.getFullyQualifiedName(),
                this.repositoryParser.getCompilationUnit())) {
            OptimizationStatsLogger.updateRepositoriesModified(1);
        }

        // Save modified files to checkpoint for resume capability
        checkpointManager.setModifiedFiles(modifiedFiles);
    }

    /**
     * Restores modified files from checkpoint on resume.
     * Called during initialization when resuming from a checkpoint.
     */
    private void restoreModifiedFilesFromCheckpoint() {
        Set<String> restored = checkpointManager.getModifiedFiles();
        if (!restored.isEmpty()) {
            modifiedFiles.addAll(restored);
            logger.info("Restored {} modified files from checkpoint", restored.size());
        }
    }

    @Override
    public int analyze() throws IOException, ReflectiveOperationException, InterruptedException {
        // Restore modified files from checkpoint before analysis
        if (checkpointManager.hasCheckpoint()) {
            // Load will be called by super.analyze(), but we need to check first
            // and restore modified files if resuming
            checkpointManager.load();
            restoreModifiedFilesFromCheckpoint();
        }
        return super.analyze();
    }

    void actOnAnalysisResult(QueryAnalysisResult result) {
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
            }
        }
    }

    /**
     * Formats a long query string for use in a Java text block by breaking it into
     * multiple lines at whitespace boundaries near the target column width.
     * The result contains actual newlines followed by proper indentation.
     *
     * @param query       the query string to format
     * @param targetWidth the target column width (e.g., 80)
     * @param indent      the indentation string for continuation lines
     * @return the formatted query with actual newlines at whitespace boundaries
     */
    static String formatQueryForTextBlock(String query, int targetWidth, String indent) {
        if (query == null || query.length() <= targetWidth) {
            return query;
        }

        StringBuilder result = new StringBuilder();
        int currentPos = 0;
        int queryLength = query.length();

        while (currentPos < queryLength) {
            // Calculate remaining length
            int remaining = queryLength - currentPos;

            // If remaining fits in target width, append and finish
            if (remaining <= targetWidth) {
                if (result.length() > 0) {
                    result.append("\n").append(indent);
                }
                result.append(query.substring(currentPos));
                break;
            }

            // Find the best break point (last whitespace before or at target width)
            int searchEnd = Math.min(currentPos + targetWidth, queryLength);
            int breakPoint = -1;

            // Search backwards from target width to find whitespace
            for (int i = searchEnd; i > currentPos; i--) {
                if (Character.isWhitespace(query.charAt(i - 1))) {
                    breakPoint = i;
                    break;
                }
            }

            // If no whitespace found, search forward for the next whitespace
            if (breakPoint == -1) {
                for (int i = searchEnd; i < queryLength; i++) {
                    if (Character.isWhitespace(query.charAt(i))) {
                        breakPoint = i + 1; // Include the whitespace
                        break;
                    }
                }
            }

            // If still no break point found, take the rest
            if (breakPoint == -1) {
                breakPoint = queryLength;
            }

            // Append the segment
            if (result.length() > 0) {
                result.append("\n").append(indent);
            }
            result.append(query.substring(currentPos, breakPoint));
            currentPos = breakPoint;
        }

        return result.toString();
    }

    /**
     * Formats the query value for the @Query annotation using text blocks.
     * Long queries are broken into multiple lines for readability.
     *
     * @param method         the method declaration containing the annotation
     * @param newStringValue the new query value
     */
    private void updateAnnotationValueWithTextBlockSupport(MethodDeclaration method, String newStringValue) {
        // Format long queries for readability using text blocks (break at ~80 chars at
        // whitespace)
        // Use 8 spaces for indentation to align with typical method annotation
        // indentation
        String indent = "        ";
        String formattedQuery = formatQueryForTextBlock(newStringValue, 80, indent);
        // Prepend indent to first line and append newline + indent for closing
        // delimiter alignment
        String textBlockContent = indent + formattedQuery + "\n" + indent;
        updateAnnotationValue(method, "Query", textBlockContent, true);
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

                if (valuePair.isPresent()) {
                    valuePair.get().setValue(newValueExpr);
                } else {
                    normal.addPair("value", newValueExpr);
                }
            }
            OptimizationStatsLogger.updateQueryAnnotationsChanged(1);
        }
    }

    /**
     * Reorders method parameters to match the recommended column order.
     * This ensures the method signature matches the optimized WHERE clause column
     * order.
     *
     * @param method    the method declaration to modify
     * @param newMethod the updated method signature
     */
    void reorderMethodParameters(MethodDeclaration method, MethodDeclaration newMethod) {
        NodeList<Parameter> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
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
                updateMethodCallSignature(update, fullyQualifiedName, fieldNames, className);
            }
        }
    }

    private static void updateMethodCallSignature(QueryAnalysisResult update, String fullyQualifiedName,
            Set<String> fieldNames, String className) {
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
            if (modifiedFiles.add(className)) {
                OptimizationStatsLogger.updateDependentClassesChanged(1);
            }
            OptimizationStatsLogger.updateMethodCallsChanged(totalMethodCallsUpdated);
        }
    }

    /**
     * Writes the modified compilation unit to disk using FileOperationsManager.
     * 
     * Attempts to use LexicalPreservingPrinter for whitespace preservation, but
     * falls back to cu.toString() if:
     * 1. LexicalPreservingPrinter throws an exception
     * 2. LexicalPreservingPrinter returns unchanged content (indicating AST mods
     * weren't tracked)
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
        } else {
            File t = new File(fullPath.replace("src/main", "src/test"));
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

            boolean isMatchingCall = false;

            // Case 1: Regular method call - fieldName.methodName(...) or
            // this.fieldName.methodName(...)
            if (scope.isPresent() && isFieldMatch(scope.get())) {
                isMatchingCall = true;
            }

            // Case 2: Mockito verify call - verify(fieldName).methodName(...)
            if (scope.isPresent() && scope.get() instanceof MethodCallExpr verifyCall &&
                    ("verify".equals(verifyCall.getNameAsString()) && !verifyCall.getArguments().isEmpty())) {
                Expression firstArg = verifyCall.getArgument(0);
                if (isFieldMatch(firstArg)) {
                    isMatchingCall = true;
                }
            }

            if (isMatchingCall && update.getMethodName().equals(mce.getNameAsString())) {
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

            return mce;
        }

        @Override
        public MethodReferenceExpr visit(MethodReferenceExpr mre, QueryAnalysisResult update) {
            super.visit(mre, update);
            Expression scope = mre.getScope();
            OptimizationIssue issue = update.getOptimizationIssue();
            if (issue == null || issue.optimizedQuery() == null) {
                return mre;
            }

            if (isFieldMatch(scope) && update.getMethodName().equals(mre.getIdentifier())) {
                String originalMethodName = mre.getIdentifier();
                String newMethodName = issue.optimizedQuery().getMethodName();

                if (!originalMethodName.equals(newMethodName)) {
                    mre.setIdentifier(newMethodName);
                    modified = true;
                    methodCallsUpdated++;
                }
            } else if (scope.isTypeExpr() && scope.asTypeExpr().getType().asString().equals(fieldName) 
                    && update.getMethodName().equals(mre.getIdentifier())) {
                // Support static method references if fieldName happens to match class name (rare but possible in tests)
                // or if we want to support class name references.
                String originalMethodName = mre.getIdentifier();
                String newMethodName = issue.optimizedQuery().getMethodName();

                if (!originalMethodName.equals(newMethodName)) {
                    mre.setIdentifier(newMethodName);
                    modified = true;
                    methodCallsUpdated++;
                }
            }

            return mre;
        }

        private boolean isFieldMatch(Expression expr) {
            if (expr instanceof NameExpr ne) {
                return ne.getNameAsString().equals(fieldName);
            }
            if (expr instanceof FieldAccessExpr fae) {
                return fae.getNameAsString().equals(fieldName) && 
                       fae.getScope().isThisExpr();
            }
            return false;
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
            for (int i = 0; i < oldMethod.getParameters().size(); i++) {
                for (int j = 0; j < newMethod.getParameters().size(); j++) {
                    if (oldMethod.getParameter(i).toString().equals(newMethod.getParameter(j).toString())) {
                        map.put(j, i);
                    }
                }
            }

            NodeList<Expression> args = mce.getArguments();
            if (args.size() != newMethod.getParameters().size()) {
                return;
            }

            NodeList<Expression> newArgs = new NodeList<>();
            for (int i = 0; i < args.size(); i++) {
                Integer oldIdx = map.get(i);
                if (oldIdx != null && oldIdx < args.size()) {
                    newArgs.add(args.get(oldIdx));
                } else {
                    // Fallback if mapping is missing or invalid
                    return;
                }
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

        // Parse command-line flags
        boolean quietMode = hasFlag(args, "--quiet") || hasFlag(args, "-q");
        QueryOptimizationChecker.setQuietMode(quietMode);

        // Check for --fresh flag to clear checkpoint and start fresh
        boolean freshStart = hasFlag(args, "--fresh") || hasFlag(args, "-f");
        if (freshStart) {
            CheckpointManager tempCheckpoint = new CheckpointManager();
            if (tempCheckpoint.hasCheckpoint()) {
                tempCheckpoint.clear();
                System.out.println("🗑️ Checkpoint cleared - starting fresh");
            }
        }

        // Read configuration from generator.yml (skip_processed, target_class)
        configureFromSettings();

        // CLI flags override YAML settings
        if (hasFlag(args, "--skip-processed") || hasFlag(args, "-s")) {
            QueryOptimizationChecker.setSkipProcessed(true);
        }

        AbstractCompiler.loadDependencies();
        AbstractCompiler.preProcess();

        if (!quietMode) {
            System.out.println("Time to preprocess   " + (System.currentTimeMillis() - s) + "ms");
        }

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizer checker = new QueryOptimizer(getLiquibasePath());
        checker.analyze();

        // Generate Liquibase file with suggested changes and include in master
        checker.generateLiquibaseChangesFile();

        OptimizationStatsLogger.printSummary(System.out);
        updateFiles();

        System.out.println("\n--- Final AI Token Usage Report ---");
        System.out.println(checker.getCumulativeTokenUsage().getFormattedReport());
        System.out.println("-----------------------------------\n");

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
