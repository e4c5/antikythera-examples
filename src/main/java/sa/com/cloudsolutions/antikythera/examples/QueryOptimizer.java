package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
    
    // Track previous index count to calculate delta per repository
    private int previousIndexCount = 0;
    
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
        // Create stats tracker for this repository
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats(fullyQualifiedName);
        
        // Clear results from previous repository analysis to get accurate count for this repository
        int previousResultsCount = results.size();
        
        super.analyzeRepository(fullyQualifiedName, typeWrapper);
        
        // Count queries analyzed for THIS repository only (new results added by super.analyzeRepository)
        int currentRepositoryQueries = results.size() - previousResultsCount;
        stats.setQueriesAnalyzed(currentRepositoryQueries);
        
        List<QueryOptimizationResult> updates = new ArrayList<>();
        try {
            LexicalPreservingPrinter.setup(AntikytheraRunTime.getCompilationUnit(fullyQualifiedName));
        } catch (Exception e) {
            // pass this
        }

        // Track changes made to repository methods - only process results from this repository
        List<QueryOptimizationResult> currentRepositoryResults = results.subList(previousResultsCount, results.size());
        
        for (QueryOptimizationResult result : currentRepositoryResults) {
            if (!result.isAlreadyOptimized()) {
                if(!result.getOptimizationIssues().isEmpty()) {
                    OptimizationIssue issue = result.getOptimizationIssues().getFirst();
                    if (issue.optimizedQuery() != null) {
                        // Actually update the annotation
                        updateAnnotationValue(issue.query().getMethodDeclaration().asMethodDeclaration(),
                                "Query", issue.optimizedQuery().getStatement().toString());
                        
                        // Track @Query annotation changes (only count when we actually change it)
                        stats.incrementQueryAnnotationsChanged();
                        
                        // Check if method name changed (indicating signature should change)
                        boolean methodNameChanged = !issue.query().getMethodName().equals(issue.optimizedQuery().getMethodName());
                        
                        // Only reorder parameters if the method name has changed
                        // (If name hasn't changed, we just swap placeholders in the query, keeping same parameter order)
                        boolean parametersReordered = false;
                        if (methodNameChanged) {
                            parametersReordered = reorderMethodParameters(
                                issue.query().getMethodDeclaration().asMethodDeclaration(),
                                issue.currentColumnOrder(),
                                issue.recommendedColumnOrder()
                            );
                        }
                        
                        // Track method signature changes
                        if (methodNameChanged || parametersReordered) {
                            stats.incrementMethodSignaturesChanged();
                        }
                    }
                }
                updates.add(result);
            }
        }
        
        if (!updates.isEmpty()) {
            writeFile(fullyQualifiedName);
        }
        
        // Apply signature updates and track dependent class changes
        MethodCallUpdateStats callStats = applySignatureUpdatesToUsagesWithStats(updates, fullyQualifiedName);
        stats.setMethodCallsUpdated(callStats.methodCallsUpdated);
        stats.setDependentClassesModified(callStats.dependentClassesModified);
        
        // Track Liquibase indexes generated for this repository only (delta from previous count)
        int currentIndexCount = suggestedNewIndexes.size();
        int indexesGeneratedForThisRepository = currentIndexCount - previousIndexCount;
        stats.setLiquibaseIndexesGenerated(indexesGeneratedForThisRepository);
        previousIndexCount = currentIndexCount;
        
        // Log statistics to CSV file
        try {
            OptimizationStatsLogger.logStats(stats);
        } catch (IOException e) {
            System.err.println("Failed to log optimization stats: " + e.getMessage());
        }
    }

    /**
     * Updates the "value" of a JavaParser AnnotationExpr on a method,
     * preserving the original annotation's indentation and formatting.
     * <p>
     * This handles both single-member (@Query("...")) and normal
     * (@Query(value = "...")) annotation styles.
     *
     * @param method          The method node containing the annotation.
     * @param annotationName  The name of the annotation to find (e.g., "Query").
     * @param newStringValue  The new string to set as the annotation's value.
     * @return true if the annotation was found and successfully replaced,
     * false otherwise.
     */
    public void updateAnnotationValue(MethodDeclaration method,
                                         String annotationName,
                                         String newStringValue) {

        // 1. Find the annotation on the method
        Optional<AnnotationExpr> oldAnnotationOpt = method.getAnnotationByName(annotationName);
        if (oldAnnotationOpt.isPresent()) {


            AnnotationExpr oldAnnotation = oldAnnotationOpt.get();

            // 2. Create the new value expression
            StringLiteralExpr newValueExpr = new StringLiteralExpr(newStringValue);

            // 3. Clone the old annotation to preserve its formatting
            AnnotationExpr newAnnotation = oldAnnotation.clone();

            // 4. Modify the clone's value based on its type
            boolean updateSucceeded = false;
            if (newAnnotation.isSingleMemberAnnotationExpr()) {

                // --- Case 1: @Query("...") ---
                newAnnotation.asSingleMemberAnnotationExpr().setMemberValue(newValueExpr);
                updateSucceeded = true;

            } else if (newAnnotation.isNormalAnnotationExpr()) {

                // --- Case 2: @Query(value = "...") ---
                NormalAnnotationExpr normal = newAnnotation.asNormalAnnotationExpr();

                // Find the pair named "value" and update it
                Optional<MemberValuePair> valuePair = normal.getPairs().stream()
                        .filter(p -> p.getName().asString().equals("value"))
                        .findFirst();

                if (valuePair.isPresent()) {
                    valuePair.get().setValue(newValueExpr);
                    updateSucceeded = true;
                }
            }

            // 5. If we successfully modified the clone, replace the original
            if (updateSucceeded) {
                oldAnnotation.replace(newAnnotation);
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
    private boolean reorderMethodParameters(MethodDeclaration method, 
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
            // Get current parameters
            var currentParams = new ArrayList<>(method.getParameters());
            var reorderedParams = new ArrayList<com.github.javaparser.ast.body.Parameter>();
            
            // Reorder parameters based on column order mapping
            for (String recommendedColumn : recommendedColumnOrder) {
                int currentIndex = currentColumnOrder.indexOf(recommendedColumn);
                if (currentIndex >= 0 && currentIndex < currentParams.size()) {
                    reorderedParams.add(currentParams.get(currentIndex).clone());
                }
            }
            
            // Only apply if we successfully mapped all parameters
            if (reorderedParams.size() == currentParams.size()) {
                method.getParameters().clear();
                method.getParameters().addAll(reorderedParams);
                return true;
            }
        } catch (Exception e) {
            // If reordering fails, log and continue without modification
            QueryOptimizationChecker.logger.warn("Failed to reorder method parameters for {}: {}", method.getNameAsString(), e.getMessage());
        }
        
        return false;
    }

    /**
     * Statistics for method call updates across dependent classes.
     */
    public static class MethodCallUpdateStats {
        public int methodCallsUpdated = 0;
        public int dependentClassesModified = 0;
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
     * @return statistics about method calls updated and classes modified
     */
    public MethodCallUpdateStats applySignatureUpdatesToUsagesWithStats(List<QueryOptimizationResult> updates, String fullyQualifiedName) throws FileNotFoundException {
        MethodCallUpdateStats stats = new MethodCallUpdateStats();
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
                        writeFile(className);
                        stats.dependentClassesModified++;
                        stats.methodCallsUpdated += visitor.methodCallsUpdated;
                    }
                }
            }
        }
        
        return stats;
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
            String content = cu.toString();

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

    static class NameChangeVisitor extends ModifierVisitor<List<QueryOptimizationResult>> {
        String fielName;
        boolean modified;
        int methodCallsUpdated = 0;
        
        NameChangeVisitor(String fieldName) {
            this.fielName = fieldName;
        }

        @Override
        public MethodCallExpr visit(MethodCallExpr mce, List<QueryOptimizationResult> updates) {
            super.visit(mce, updates);
            Optional<Expression> scope = mce.getScope();
            if (scope.isPresent() && scope.get() instanceof NameExpr fe && fe.getNameAsString().equals(fielName) && !updates.isEmpty()) {
                // Loop through ALL updates to find matching method
                for (QueryOptimizationResult update : updates) {
                    // Check if this call matches the current update's method name
                    if (update.getMethodName().equals(mce.getNameAsString()) && !update.getOptimizationIssues().isEmpty()) {
                        OptimizationIssue issue = update.getOptimizationIssues().getFirst();
                        if (issue.optimizedQuery() != null) {
                            // Update method name
                            mce.setName(issue.optimizedQuery().getMethodName());
                            
                            // Reorder arguments based on column order changes
                            reorderMethodArguments(mce, issue);
                            
                            modified = true;
                            methodCallsUpdated++;
                            break; // Found the match, no need to check other updates
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
         */
        private void reorderMethodArguments(MethodCallExpr mce, OptimizationIssue issue) {
            List<String> currentOrder = issue.currentColumnOrder();
            List<String> recommendedOrder = issue.recommendedColumnOrder();
            
            // Only reorder if we have both orders and they're different
            if (currentOrder == null || recommendedOrder == null || 
                currentOrder.equals(recommendedOrder) || 
                currentOrder.size() != recommendedOrder.size()) {
                return;
            }
            
            // Only reorder if argument count matches parameter count
            if (mce.getArguments().size() != currentOrder.size()) {
                return;
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
            }
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
        System.out.println("📊 Detailed statistics logged to: query-optimization-stats.csv");
        
        // Exit with non-zero if at least 1 high and at least 10 medium priority recommendations
        if (high >= 1 && medium >= 10) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }
}
