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
    private static Set<String> modifiedFiles = new java.util.HashSet<>();
    private static Set<String> writtenFiles = new java.util.HashSet<>();

    // Profiling accumulators for writeFile breakdown
    private static long totalLppTime = 0;
    private static long totalDiskWriteTime = 0;
    private static int lppCallCount = 0;

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

    /**
     * Record to hold method rename information for batched processing.
     */
    record MethodRename(
            String oldMethodName,
            String newMethodName,
            QueryAnalysisResult result,
            OptimizationIssue issue
    ) {}

    @Override
    void analyzeRepository(TypeWrapper typeWrapper)
            throws IOException, ReflectiveOperationException, InterruptedException {
        super.analyzeRepository(typeWrapper);

        OptimizationStatsLogger.updateQueriesAnalyzed(results.size());

        repositoryFileModified = false;

        // Collect all method renames first for batched processing
        List<MethodRename> methodRenames = new ArrayList<>();

        for (QueryAnalysisResult result : results) {
            OptimizationIssue issue = result.getOptimizationIssue();
            if (issue != null && issue.optimizedQuery() != null) {
                String originalName = issue.query().getMethodName();
                String newName = issue.optimizedQuery().getMethodName();
                String aiExplanation = issue.aiExplanation();

                boolean methodNameChanged = !originalName.equals(newName)
                        && (aiExplanation == null || !aiExplanation.startsWith("N/A"));

                if (methodNameChanged) {
                    methodRenames.add(new MethodRename(originalName, newName, result, issue));
                }
            }
        }

        // Process all results (update annotations, etc.)
        for (QueryAnalysisResult result : results) {
            actOnAnalysisResult(result, methodRenames);
        }

        // Batch update all method call signatures in dependent classes (single pass)
        if (!methodRenames.isEmpty()) {
            String repositoryClassName = typeWrapper.getFullyQualifiedName();
            logger.info("Repository has {} methods with signature changes - processing in single batch",
                    methodRenames.size());

            long startTime = System.currentTimeMillis();
            updateMethodCallSignaturesBatched(methodRenames, repositoryClassName);
            long elapsed = System.currentTimeMillis() - startTime;

            if (elapsed > 1000) {
                logger.info("Batched signature update took {}ms for {} methods", elapsed, methodRenames.size());
            }

            OptimizationStatsLogger.updateMethodSignaturesChanged(methodRenames.size());
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

    /**
     * Process a single analysis result - update annotations and method signatures in the repository file.
     * Note: Method call signature updates in dependent classes are handled separately via batched processing.
     *
     * @param result the analysis result to process
     * @param methodRenames the list of all method renames for this repository (used to check if this result has a rename)
     */
    void actOnAnalysisResult(QueryAnalysisResult result, List<MethodRename> methodRenames) {
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

                // Check if this result has a method rename in our batched list
                String originalMethodName = issue.query().getMethodName();
                boolean methodNameChanged = methodRenames.stream()
                        .anyMatch(r -> r.oldMethodName().equals(originalMethodName));

                // Apply method name change to the repository interface itself
                if (methodNameChanged) {
                    logger.info("Changing method name from {} to {}", originalMethodName, optimizedQuery.getMethodName());

                    MethodDeclaration method = issue.query().getMethodDeclaration().asMethodDeclaration();
                    Callable newMethod = optimizedQuery.getMethodDeclaration();
                    method.setName(newMethod.getNameAsString());

                    reorderMethodParameters(
                            issue.query().getMethodDeclaration().asMethodDeclaration(),
                            newMethod.asMethodDeclaration());
                }

                // Track if file was modified (annotation always changes and may also have parameter reordering)
                repositoryFileModified = true;
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
                if (!result.isEmpty()) {
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
            if (!result.isEmpty()) {
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

        if (fields == null || fields.isEmpty()) {
            logger.debug("No field dependencies found for {}", fullyQualifiedName);
            return;
        }

        int totalFieldNames = fields.values().stream().mapToInt(Set::size).sum();
        if (fields.size() > 200) {
            logger.warn("Repository {} has {} dependent classes - signature updates may be slow",
                    fullyQualifiedName, fields.size());
        }
        logger.info("Updating method call signatures: {} dependent classes, {} total field references for {}",
                fields.size(), totalFieldNames, fullyQualifiedName);

        long loopStartTime = System.currentTimeMillis();
        int classesProcessed = 0;
        int totalVisits = 0;
        for (Map.Entry<String, Set<String>> entry : fields.entrySet()) {
            String className = entry.getKey();
            Set<String> fieldNames = entry.getValue();

            TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(className);

            if (typeWrapper != null) {
                totalVisits += fieldNames.size();
                updateMethodCallSignature(update, fullyQualifiedName, fieldNames, className);
                classesProcessed++;
                if (classesProcessed % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - loopStartTime;
                    logger.info("Progress: {}/{} classes processed in {}ms ({} AST visits)",
                            classesProcessed, fields.size(), elapsed, totalVisits);
                }
            }
        }
        long totalElapsed = System.currentTimeMillis() - loopStartTime;
        logger.info("Finished: {} classes, {} AST visits in {}ms", classesProcessed, totalVisits, totalElapsed);
    }

    private static void updateMethodCallSignature(QueryAnalysisResult update, String fullyQualifiedName,
            Set<String> fieldNames, String className) {
        boolean classModified = false;
        int totalMethodCallsUpdated = 0;

        // Process all field names for this class
        for (String fieldName : fieldNames) {
            NameChangeVisitor visitor = new NameChangeVisitor(fieldName, fullyQualifiedName);

            long t1 = System.currentTimeMillis();
            CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
            long t2 = System.currentTimeMillis();

            cu.accept(visitor, update);
            long t3 = System.currentTimeMillis();

            long getCuTime = t2 - t1;
            long visitTime = t3 - t2;
            if (getCuTime > 100 || visitTime > 100) {
                logger.debug("Timing for {}: getCU={}ms, visit={}ms", className, getCuTime, visitTime);
            }

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
     * Batched version of updateMethodCallSignatures that uses the method call index
     * to only process classes that actually call the methods being renamed.
     * This is much more efficient than scanning all dependent classes.
     *
     * @param methodRenames list of all method renames to apply
     * @param fullyQualifiedName the repository class name
     */
    public void updateMethodCallSignaturesBatched(List<MethodRename> methodRenames, String fullyQualifiedName) {
        // Collect all classes that need to be updated based on method call index
        // Map: className -> Set<fieldNames> that need processing
        Map<String, Set<String>> classesToProcess = new HashMap<>();

        for (MethodRename rename : methodRenames) {
            Set<Fields.CallerInfo> callers = Fields.getMethodCallers(fullyQualifiedName, rename.oldMethodName());
            for (Fields.CallerInfo caller : callers) {
                classesToProcess
                        .computeIfAbsent(caller.callerClass(), k -> new java.util.HashSet<>())
                        .add(caller.fieldName());
            }
        }

        if (classesToProcess.isEmpty()) {
            logger.debug("No method callers found for {} methods being renamed", methodRenames.size());
            return;
        }

        // Compare with old approach for logging
        Map<String, Set<String>> allFields = Fields.getFieldDependencies(fullyQualifiedName);
        int totalDependentClasses = allFields != null ? allFields.size() : 0;

        logger.info("Method call index optimization: processing {} classes instead of {} ({}% reduction)",
                classesToProcess.size(), totalDependentClasses,
                totalDependentClasses > 0 ? (100 - (classesToProcess.size() * 100 / totalDependentClasses)) : 0);

        long loopStartTime = System.currentTimeMillis();
        long totalAstVisitTime = 0;
        long totalFileWriteTime = 0;
        int classesProcessed = 0;
        int totalMethodCallsUpdated = 0;
        List<String> classesModifiedInBatch = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : classesToProcess.entrySet()) {
            String className = entry.getKey();
            Set<String> fieldNames = entry.getValue();

            TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(className);

            if (typeWrapper == null) {
                throw new IllegalStateException(
                        "Class " + className + " found in method call index but not in resolved types. " +
                        "This may indicate incomplete preprocessing or an external dependency that should be excluded.");
            }

            long astStart = System.currentTimeMillis();
            logger.info("  Visiting class: {} (fields: {})", className, fieldNames);
            int callsUpdated = updateMethodCallSignatureBatched(methodRenames, fieldNames, className);
            long astElapsed = System.currentTimeMillis() - astStart;
            totalAstVisitTime += astElapsed;

            if (callsUpdated > 0) {
                logger.info("  Updated {} calls in {} ({}ms)", callsUpdated, className, astElapsed);
                classesModifiedInBatch.add(className);
            }
            totalMethodCallsUpdated += callsUpdated;
            classesProcessed++;

            if (classesProcessed % 100 == 0) {
                long elapsed = System.currentTimeMillis() - loopStartTime;
                logger.info("Progress: {}/{} classes processed in {}ms",
                        classesProcessed, classesToProcess.size(), elapsed);
            }
        }

        // Write modified files to disk immediately after batch processing
        // This ensures changes are persisted before checkpoint is saved
        int filesWritten = 0;
        for (String className : classesModifiedInBatch) {
            try {
                long writeStart = System.currentTimeMillis();
                if (writeFile(className)) {
                    writtenFiles.add(className);
                    filesWritten++;
                }
                totalFileWriteTime += System.currentTimeMillis() - writeStart;
            } catch (IOException e) {
                logger.error("Failed to write file for {}: {}", className, e.getMessage());
            }
        }

        long totalElapsed = System.currentTimeMillis() - loopStartTime;
        logger.info("Batched update finished: {} classes, {} method calls updated, {} files written in {}ms",
                classesProcessed, totalMethodCallsUpdated, filesWritten, totalElapsed);
        logger.info("  Profiling breakdown: AST visiting={}ms, File writing={}ms, Other={}ms",
                totalAstVisitTime, totalFileWriteTime, totalElapsed - totalAstVisitTime - totalFileWriteTime);
        if (lppCallCount > 0) {
            logger.info("  File writing breakdown: LexicalPreservingPrinter={}ms ({}calls, {}ms/call avg), Disk I/O={}ms",
                    totalLppTime, lppCallCount, totalLppTime / lppCallCount, totalDiskWriteTime);
            // Reset for next batch
            totalLppTime = 0;
            totalDiskWriteTime = 0;
            lppCallCount = 0;
        }

        OptimizationStatsLogger.updateMethodCallsChanged(totalMethodCallsUpdated);
    }

    /**
     * Updates method call signatures in a single class for all method renames in one AST visit.
     *
     * @param methodRenames list of all method renames to apply
     * @param fieldNames set of field names that reference the repository in this class
     * @param className the class to update
     * @return number of method calls updated
     */
    private int updateMethodCallSignatureBatched(List<MethodRename> methodRenames,
            Set<String> fieldNames, String className) {
        long getCuStart = System.currentTimeMillis();
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
        long getCuTime = System.currentTimeMillis() - getCuStart;
        logger.info("    Got CompilationUnit in {}ms, starting visitor...", getCuTime);

        // Create a batched visitor that handles all renames and all field names
        BatchedNameChangeVisitor visitor = new BatchedNameChangeVisitor(fieldNames, methodRenames);
        long acceptStart = System.currentTimeMillis();
        cu.accept(visitor, null);
        long acceptTime = System.currentTimeMillis() - acceptStart;

        visitor.logDiagnostics();

        if (visitor.modified && modifiedFiles.add(className)) {
            OptimizationStatsLogger.updateDependentClassesChanged(1);
        }

        return visitor.methodCallsUpdated;
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

        long lppStart = System.currentTimeMillis();
        try {
            content = LexicalPreservingPrinter.print(cu);
        } catch (Exception e) {
            // LexicalPreservingPrinter can still fail on complex AST modifications (e.g.,
            // parameter reordering)
            logger.warn("LexicalPreservingPrinter failed for {}: {}. Using default printer (may lose formatting).",
                    fullyQualifiedName, e.getMessage());
            content = cu.toString();
        }
        totalLppTime += System.currentTimeMillis() - lppStart;
        lppCallCount++;

        File f = new File(fullPath);

        long diskStart = System.currentTimeMillis();
        boolean result = false;
        if (f.exists()) {
            result = writeFile(f, content);
        } else {
            File t = new File(fullPath.replace("src/main", "src/test"));
            if (t.exists()) {
                result = writeFile(t, content);
            }
        }
        totalDiskWriteTime += System.currentTimeMillis() - diskStart;

        return result;
    }

    private static boolean writeFile(File f, String content) throws FileNotFoundException {
        PrintWriter writer = new PrintWriter(f);
        writer.print(content); // Use the content variable we already computed
        writer.close();
        return true;
    }

    /**
     * Reorders method call arguments based on the optimization issue's column order
     * changes.
     * This ensures that when parameter order changes (e.g., findByEmailAndStatus ->
     * findByStatusAndEmail),
     * the method call arguments are also reordered to match.
     */
    static void reorderMethodArguments(MethodCallExpr mce, OptimizationIssue issue) {
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
    }


    /**
     * Batched visitor that handles multiple method renames in a single AST pass.
     * This is much more efficient than visiting the AST multiple times for each rename.
     */
    static class BatchedNameChangeVisitor extends ModifierVisitor<Void> {
        private static final Logger visitorLogger = LoggerFactory.getLogger(BatchedNameChangeVisitor.class);
        private final Set<String> fieldNames;
        // Map from oldMethodName -> list of renames (to handle overloaded methods)
        private final Map<String, List<MethodRename>> renameMap;
        boolean modified;
        int methodCallsUpdated = 0;
        // Diagnostic counters
        int totalVisits = 0;
        long totalVisitTime = 0;

        BatchedNameChangeVisitor(Set<String> fieldNames, List<MethodRename> methodRenames) {
            this.fieldNames = fieldNames;

            // Build a map for quick lookup by old method name
            // Use a list to handle overloaded methods with the same name but different arities
            this.renameMap = new HashMap<>();
            for (MethodRename rename : methodRenames) {
                renameMap.computeIfAbsent(rename.oldMethodName(), k -> new ArrayList<>()).add(rename);
            }
        }

        void logDiagnostics() {
            visitorLogger.info("  Visitor diagnostics: {} MethodCallExpr visits, total time {}ms, avg {}μs/visit",
                    totalVisits, totalVisitTime, totalVisits > 0 ? (totalVisitTime * 1000 / totalVisits) : 0);
        }

        @Override
        public MethodCallExpr visit(MethodCallExpr mce, Void arg) {
            long visitStart = System.currentTimeMillis();
            totalVisits++;
            super.visit(mce, arg);
            Optional<Expression> scope = mce.getScope();

            if (scope.isEmpty()) {
                totalVisitTime += System.currentTimeMillis() - visitStart;
                return mce;
            }

            // Check if this is a call on one of our repository fields
            String matchedFieldName = null;
            for (String fieldName : fieldNames) {
                if (isFieldMatch(scope.get(), fieldName)) {
                    matchedFieldName = fieldName;
                    break;
                }
                // Also check Mockito verify pattern
                if (scope.get() instanceof MethodCallExpr verifyCall &&
                        "verify".equals(verifyCall.getNameAsString()) && !verifyCall.getArguments().isEmpty()) {
                    if (isFieldMatch(verifyCall.getArgument(0), fieldName)) {
                        matchedFieldName = fieldName;
                        break;
                    }
                }
            }

            if (matchedFieldName == null) {
                totalVisitTime += System.currentTimeMillis() - visitStart;
                return mce;
            }

            // Check if this method call matches any of our renames
            String currentMethodName = mce.getNameAsString();
            List<MethodRename> candidates = renameMap.get(currentMethodName);

            if (candidates != null) {
                // Find the matching rename by arity (number of arguments)
                int callArity = mce.getArguments().size();
                MethodRename matchingRename = findMatchingRenameByArity(candidates, callArity);

                if (matchingRename != null) {
                    // Verify arity matches BEFORE renaming to avoid creating broken code
                    MethodDeclaration newMethod = matchingRename.issue().optimizedQuery()
                            .getMethodDeclaration().asMethodDeclaration();

                    if (callArity == newMethod.getParameters().size()) {
                        mce.setName(matchingRename.newMethodName());
                        reorderMethodArguments(mce, matchingRename.issue());
                        modified = true;
                        methodCallsUpdated++;
                    }
                    // If arity doesn't match, skip this call - don't rename it
                }
            }

            totalVisitTime += System.currentTimeMillis() - visitStart;
            return mce;
        }

        @Override
        public MethodReferenceExpr visit(MethodReferenceExpr mre, Void arg) {
            super.visit(mre, arg);
            Expression scope = mre.getScope();

            // Check if this is a reference on one of our repository fields
            String matchedFieldName = null;
            for (String fieldName : fieldNames) {
                if (isFieldMatch(scope, fieldName)) {
                    matchedFieldName = fieldName;
                    break;
                }
                // Also check type expression (static method reference style)
                if (scope.isTypeExpr() && scope.asTypeExpr().getType().asString().equals(fieldName)) {
                    matchedFieldName = fieldName;
                    break;
                }
            }

            if (matchedFieldName == null) {
                return mre;
            }

            // Check if this method reference matches any of our renames
            // For method references, we can't determine arity at the call site,
            // so only rename if there's exactly one candidate (no overloads)
            String currentMethodName = mre.getIdentifier();
            List<MethodRename> candidates = renameMap.get(currentMethodName);

            if (candidates != null && candidates.size() == 1) {
                // Safe to rename - only one candidate, no ambiguity
                mre.setIdentifier(candidates.get(0).newMethodName());
                modified = true;
                methodCallsUpdated++;
            }
            // If multiple candidates (overloaded methods), skip - can't determine which one

            return mre;
        }

        /**
         * Finds a matching rename from candidates based on call arity.
         * Returns null if no match found or if multiple matches exist (ambiguous).
         */
        private MethodRename findMatchingRenameByArity(List<MethodRename> candidates, int callArity) {
            MethodRename match = null;
            for (MethodRename candidate : candidates) {
                MethodDeclaration oldMethod = candidate.issue().query()
                        .getMethodDeclaration().asMethodDeclaration();
                if (oldMethod.getParameters().size() == callArity) {
                    if (match != null) {
                        // Multiple matches with same arity - ambiguous, return null
                        return null;
                    }
                    match = candidate;
                }
            }
            return match;
        }

        private boolean isFieldMatch(Expression expr, String fieldName) {
            if (expr instanceof NameExpr ne) {
                return ne.getNameAsString().equals(fieldName);
            }
            if (expr instanceof FieldAccessExpr fae) {
                return fae.getNameAsString().equals(fieldName) &&
                        fae.getScope().isThisExpr();
            }
            return false;
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

        // Read configuration from generator.yml (target_class)
        configureFromSettings();

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
            // Skip files that were already written during batch processing
            if (!writtenFiles.contains(className)) {
                writeFile(className);
                writtenFiles.add(className);
            }
        }
    }
}
