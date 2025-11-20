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
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.QueryType;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        EntityMappingResolver.build();
    }

    @Override
    protected void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws IOException, ReflectiveOperationException, InterruptedException {
        System.out.println(fullyQualifiedName);
        super.analyzeRepository(fullyQualifiedName, typeWrapper);

        OptimizationStatsLogger.updateQueriesAnalyzed(results.size());
        
        List<QueryOptimizationResult> updates = new ArrayList<>();
        boolean repositoryFileModified = false;

        for (QueryOptimizationResult result : results) {
            OptimizationIssue issue = result.getOptimizationIssue();
            if (issue != null) {
                RepositoryQuery optimizedQuery = issue.optimizedQuery();
                if (optimizedQuery != null) {
                    String queryValue;
                    if (QueryType.HQL.equals(optimizedQuery.getQueryType())) {
                        queryValue = optimizedQuery.getOriginalQuery();
                    }
                    else {
                        queryValue = optimizedQuery.getStatement().toString();
                    }

                    // Convert to text block if the query contains newlines
                    updateAnnotationValueWithTextBlockSupport(
                            issue.query().getMethodDeclaration().asMethodDeclaration(),
                            "Query", queryValue);

                    OptimizationStatsLogger.updateQueryAnnotationsChanged(1);

                    // Check if method name changed (indicating signature should change)
                    boolean methodNameChanged = !issue.query().getMethodName().equals(optimizedQuery.getMethodName());

                    // Reorder parameters if column order changed (regardless of method name change)
                    boolean parametersReordered = reorderMethodParameters(
                        issue.query().getMethodDeclaration().asMethodDeclaration(),
                        issue.currentColumnOrder(),
                        issue.recommendedColumnOrder()
                    );

                    // Track if file was modified (annotation always changes, and may also have parameter reordering)
                    repositoryFileModified = true;

                    // Track method signature changes
                    if (methodNameChanged || parametersReordered) {
                        OptimizationStatsLogger.updateMethodSignaturesChanged(1);
                    }
                    updates.add(result);
                }
            }
        }
        
        if (repositoryFileModified) {
            boolean fileWasWritten = writeFile(fullyQualifiedName);
            if (!fileWasWritten) {
                // File wasn't actually written (no content changes), so reset the modification flag
                repositoryFileModified = false;
            }
        }

        updateStats(fullyQualifiedName, updates, repositoryFileModified);
    }

    private void updateStats(String fullyQualifiedName, List<QueryOptimizationResult> updates, boolean repositoryFileModified) throws IOException {
        // Apply signature updates and track dependent class changes
        applySignatureUpdatesToUsagesWithStats(updates, fullyQualifiedName);

        // Track files modified (repository file + dependent classes)
        if (repositoryFileModified) {
            OptimizationStatsLogger.updateDependentClassesChanged(1);
        }
    }

    /**
     * Updates the annotation value with proper text block support.
     * If the query contains literal \n characters or actual newlines, it uses TextBlockLiteralExpr.
     * Otherwise, it uses StringLiteralExpr.
     *
     * @param method the method declaration containing the annotation
     * @param annotationName the name of the annotation to update
     * @param newStringValue the new query value
     */
    private void updateAnnotationValueWithTextBlockSupport(MethodDeclaration method,
                                                          String annotationName,
                                                          String newStringValue) {
        // Check if the string contains literal \n or actual newlines
        boolean isMultiline = newStringValue != null &&
                             (newStringValue.contains("\\n") || newStringValue.contains("\n"));

        if (isMultiline) {
            // Convert literal \n to actual newlines
            String processedValue = "    " + newStringValue.replace("\\n", "\n        ");
            updateAnnotationValue(method, annotationName, processedValue, true);
        } else {
            updateAnnotationValue(method, annotationName, newStringValue, false);
        }
    }

    /**
     * Updates the "value" of a JavaParser AnnotationExpr on a method,
     * preserving the original annotation's indentation and formatting.
     * <p>
     * This handles both single-member (@Query("...")) and normal
     * (@Query(value = "...")) annotation styles.
     * 
     * IMPORTANT: Modifies the annotation IN PLACE to ensure LexicalPreservingPrinter tracks changes.
     *
     * @param method          The method node containing the annotation.
     * @param annotationName  The name of the annotation to find (e.g., "Query").
     * @param newStringValue  The new string to set as the annotation's value.
     * @param useTextBlock    Whether to use TextBlockLiteralExpr (true) or StringLiteralExpr (false)
     */
    public void updateAnnotationValue(MethodDeclaration method,
                                     String annotationName,
                                     String newStringValue,
                                     boolean useTextBlock) {

        System.out.println(annotationName + " -> " + newStringValue);
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

            // 3. Modify IN PLACE (not replace) so LexicalPreservingPrinter tracks the change
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

                valuePair.ifPresent(memberValuePair -> memberValuePair.setValue(newValueExpr));
            }
        }
    }

    /**
     * Reorders method parameters to match the recommended column order.
     * This ensures the method signature matches the optimized WHERE clause column order.
     * 
     * @param method the method declaration to modify
     * @param currentColumnOrder the current column order
     * @param recommendedColumnOrder the recommended column order
     * @return true if parameters were reordered, false otherwise
     */
    boolean reorderMethodParameters(MethodDeclaration method, 
                                           List<String> currentColumnOrder, 
                                           List<String> recommendedColumnOrder) {
        // Validate inputs
        if (method == null || currentColumnOrder == null || recommendedColumnOrder == null) {
            return false;
        }
        
        // Only reorder if orders are different
        if (currentColumnOrder.equals(recommendedColumnOrder)) {
            return false;
        }
        
        // Only reorder if parameter count matches column count
        if (method.getParameters().size() != currentColumnOrder.size()) {
            return false;
        }
        
        // Check if sizes match
        if (currentColumnOrder.size() != recommendedColumnOrder.size()) {
            return false;
        }
        
        try {
            // Get current parameters (save copies before modifying)
            var currentParams = new ArrayList<>(method.getParameters());
            var paramNodeList = method.getParameters();
            
            // Build reordered parameter list
            var reorderedParams = new ArrayList<Parameter>();
            for (String recommendedColumn : recommendedColumnOrder) {
                int currentIndex = currentColumnOrder.indexOf(recommendedColumn);
                if (currentIndex >= 0 && currentIndex < currentParams.size()) {
                    reorderedParams.add(currentParams.get(currentIndex));
                }
            }
            
            // Only apply if we successfully mapped all parameters
            if (reorderedParams.size() == currentParams.size()) {
                // Replace parameters one-by-one using set() for better LexicalPreservingPrinter tracking
                // This is more LPP-friendly than clear()+addAll()
                for (int i = 0; i < reorderedParams.size(); i++) {
                    paramNodeList.set(i, reorderedParams.get(i));
                }
                
                // Note: Parameter reordering may still cause LexicalPreservingPrinter to fall back
                // to default formatting in some cases, but this approach maximizes compatibility
                
                return true;
            }
        } catch (Exception e) {
            // If reordering fails, log and continue without modification
            QueryOptimizationChecker.logger.warn("Failed to reorder method parameters for {}: {}", method.getNameAsString(), e.getMessage());
        }
        
        return false;
    }

    /**
     * Apply recorded signature updates to usage sites in classes that @Autowired the given repository.
     * This reorders call arguments to match the new parameter order. Only same-arity calls are modified.
     */
    public void applySignatureUpdatesToUsages(List<QueryOptimizationResult> updates, String fullyQualifiedName) throws FileNotFoundException {
        applySignatureUpdatesToUsagesWithStats(updates, fullyQualifiedName);
    }
    
    /**
     * Apply recorded signature updates to usage sites and return statistics about changes made.
     * This reorders call arguments to match the new parameter order. Only same-arity calls are modified.
     * 
     * @param updates the optimization results with signature changes
     * @param fullyQualifiedName the repository class name
     */
    public void applySignatureUpdatesToUsagesWithStats(List<QueryOptimizationResult> updates, String fullyQualifiedName) throws FileNotFoundException {
        Map<String, String> fields = Fields.getFieldDependencies(fullyQualifiedName);
        
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String className = entry.getKey();
                String fieldName = entry.getValue();
                TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(className);
                
                if (typeWrapper != null) {
                    NameChangeVisitor visitor = new NameChangeVisitor(fieldName);
                    typeWrapper.getType().accept(visitor, updates);
                    
                    if (visitor.modified) {
                        if (writeFile(className)) {
                            OptimizationStatsLogger.updateDependentClassesChanged(1);
                            OptimizationStatsLogger.updateMethodCallsChanged(visitor.methodCallsUpdated);
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the modified compilation unit to disk using FileOperationsManager.
     * 
     * Attempts to use LexicalPreservingPrinter for whitespace preservation, but falls back to
     * cu.toString() if:
     * 1. LexicalPreservingPrinter throws an exception
     * 2. LexicalPreservingPrinter returns unchanged content (indicating AST mods weren't tracked)
     * 
     * The fallback uses JavaParser's default pretty printer which produces consistent formatting.
     * 
     * @return true if the file was actually written (content changed), false if skipped (no changes)
     */
    static boolean writeFile(String fullyQualifiedName) throws FileNotFoundException {
        String fullPath = Settings.getBasePath() + "src/main/java/" + AbstractCompiler.classToPath(fullyQualifiedName);
        Path filePath = Path.of(fullPath);

        if (Files.exists(filePath)) {
            var cu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
            if (cu == null) {
                // No parsed CompilationUnit available, skip writing to avoid truncating the file
                return false;
            }
            
            String original;
            try {
                original = Files.readString(filePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                original = null; // If reading fails, proceed to write to be safe
            }
            
            // Use LexicalPreservingPrinter for whitespace preservation
            // Since we modify annotations IN PLACE, LexicalPreservingPrinter should track changes properly
            String content;
            try {
                content = LexicalPreservingPrinter.print(cu);
                logger.debug("LexicalPreservingPrinter successfully preserved formatting for {}", fullyQualifiedName);
            } catch (Exception e) {
                // LexicalPreservingPrinter can still fail on complex AST modifications (e.g., parameter reordering)
                logger.warn("LexicalPreservingPrinter failed for {}: {}. Using default printer (may lose formatting).", 
                           fullyQualifiedName, e.getMessage());
                content = cu.toString();
            }

            // If resulting content is identical to original, skip writing
            if (original != null && original.equals(content)) {
                return false;
            }
            
            try {
                File f = new File(fullPath);

                if (f.exists()) {
                    PrintWriter writer = new PrintWriter(f);

                    writer.print(content);  // Use the content variable we already computed
                    writer.close();
                    return true;
                }
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to write file " + fullPath + ": " + e.getMessage());
            }
        }
        return false;
    }

    static class NameChangeVisitor extends ModifierVisitor<List<QueryOptimizationResult>> {
        String fieldName;
        boolean modified;
        int methodCallsUpdated = 0;
        
        NameChangeVisitor(String fieldName) {
            this.fieldName = fieldName;
        }

        @Override
        public MethodCallExpr visit(MethodCallExpr mce, List<QueryOptimizationResult> updates) {
            super.visit(mce, updates);
            Optional<Expression> scope = mce.getScope();
            if (scope.isPresent() && scope.get() instanceof NameExpr fe && fe.getNameAsString().equals(fieldName) && !updates.isEmpty()) {
                // Loop through ALL updates to find matching method
                for (QueryOptimizationResult update : updates) {
                    // Check if this call matches the current update's method name
                    OptimizationIssue issue = update.getOptimizationIssue();
                    if (update.getMethodName().equals(mce.getNameAsString()) && issue != null) {
                        if (issue.optimizedQuery() != null) {
                            String originalMethodName = mce.getNameAsString();
                            String newMethodName = issue.optimizedQuery().getMethodName();
                            
                            // Only count as an update if something actually changes
                            boolean methodNameChanged = !originalMethodName.equals(newMethodName);
                            boolean argumentsReordered = false;
                            
                            // Update method name if it changed
                            if (methodNameChanged) {
                                mce.setName(newMethodName);
                            }
                            
                            // Reorder arguments based on column order changes
                            argumentsReordered = reorderMethodArguments(mce, issue);
                            
                            // Only mark as modified and increment counter if actual changes were made
                            if (methodNameChanged || argumentsReordered) {
                                modified = true;
                                methodCallsUpdated++;
                            }
                        }
                    }
                }
            }
            return mce;
        }
        
        /**
         * Reorders method call arguments based on the optimization issue's column order changes.
         * This ensures that when parameter order changes (e.g., findByEmailAndStatus -> findByStatusAndEmail),
         * the method call arguments are also reordered to match.
         * 
         * @return true if arguments were actually reordered, false otherwise
         */
        private boolean reorderMethodArguments(MethodCallExpr mce, OptimizationIssue issue) {
            List<String> currentOrder = issue.currentColumnOrder();
            List<String> recommendedOrder = issue.recommendedColumnOrder();
            
            // Only reorder if we have both orders and they're different
            if (currentOrder == null || recommendedOrder == null || 
                currentOrder.equals(recommendedOrder) || 
                currentOrder.size() != recommendedOrder.size()) {
                return false;
            }
            
            // Only reorder if argument count matches parameter count
            if (mce.getArguments().size() != currentOrder.size()) {
                return false;
            }
            
            // Create mapping from current position to new position
            List<Expression> currentArgs = new ArrayList<>(mce.getArguments());
            List<Expression> reorderedArgs = new ArrayList<>();
            
            // For each position in the recommended order, find the corresponding argument
            for (String recommendedColumn : recommendedOrder) {
                int currentIndex = currentOrder.indexOf(recommendedColumn);
                if (currentIndex >= 0 && currentIndex < currentArgs.size()) {
                    reorderedArgs.add(currentArgs.get(currentIndex).clone());
                }
            }
            
            // Update the method call with reordered arguments
            if (reorderedArgs.size() == currentArgs.size()) {
                mce.getArguments().clear();
                mce.getArguments().addAll(reorderedArgs);
                return true;
            }
            
            return false;
        }
    }

    /**
     * Checks if a command-line flag is present.
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
        
        // Enable lexical preservation BEFORE preProcess to preserve whitespace in all parsed files
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

        if (!quietMode) {
            System.out.println("\nTime taken " + (System.currentTimeMillis() - s) + " ms.");
        }
        System.out.println("📊 Detailed statistics logged to: query-optimization-stats.csv");

        // Explicitly exit to ensure JVM shuts down (HttpClient may have non-daemon threads)
        System.exit(0);
    }
}
