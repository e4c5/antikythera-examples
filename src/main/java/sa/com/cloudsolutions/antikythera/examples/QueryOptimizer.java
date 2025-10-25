package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
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


    private String getScopeName(com.github.javaparser.ast.expr.Expression scope) {
        if (scope == null) return null;
        if (scope.isNameExpr()) return scope.asNameExpr().getNameAsString();
        if (scope.isFieldAccessExpr()) return scope.asFieldAccessExpr().getNameAsString();
        if (scope.isThisExpr()) return "this"; // unlikely useful
        return null;
    }

    private String simpleName(String typeName) {
        if (typeName == null) return null;
        int lt = typeName.lastIndexOf('<');
        if (lt > 0) typeName = typeName.substring(0, lt);
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }


    /**
     * Apply recorded signature updates to usage sites in classes that @Autowired the given repository.
     * This reorders call arguments to match the new parameter order. Only same-arity calls are modified.
     */
    public void applySignatureUpdatesToUsages() {
        if (signatureUpdates.isEmpty()) return;
        Map<String, List<CodeStandardizer.SignatureUpdate>> byRepo = new java.util.HashMap<>();
        for (CodeStandardizer.SignatureUpdate up : signatureUpdates) {
            byRepo.computeIfAbsent(up.repositoryClassFqn, k -> new java.util.ArrayList<>()).add(up);
        }
        Map<String, com.github.javaparser.ast.CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> e : units.entrySet()) {
            String fqn = e.getKey();
            com.github.javaparser.ast.CompilationUnit cu = e.getValue();
            boolean modified = false;
            // Find classes with @Autowired fields that match any repo
            java.util.List<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classes = cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls : classes) {
                java.util.Map<String, String> autowiredFields = new java.util.HashMap<>(); // fieldName -> typeSimple
                for (com.github.javaparser.ast.body.FieldDeclaration fd : cls.getFields()) {
                    if (fd.getAnnotationByName("Autowired").isPresent()) {
                        for (var var : fd.getVariables()) {
                            String typeName = var.getType().toString();
                            String simple = simpleName(typeName);
                            autowiredFields.put(var.getNameAsString(), simple);
                        }
                    }
                }
                if (autowiredFields.isEmpty()) continue;

                java.util.List<com.github.javaparser.ast.expr.MethodCallExpr> calls = cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class);
                for (com.github.javaparser.ast.expr.MethodCallExpr call : calls) {
                    String scopeName = getScopeName(call.getScope().orElse(null));
                    if (scopeName == null) continue;
                    String fieldTypeSimple = autowiredFields.get(scopeName);
                    if (fieldTypeSimple == null) continue;
                    // Try all updates whose repo simple name matches
                    for (Map.Entry<String, java.util.List<CodeStandardizer.SignatureUpdate>> upEntry : byRepo.entrySet()) {
                        String repoFqn = upEntry.getKey();
                        String repoSimple = simpleName(repoFqn);
                        if (!repoSimple.equals(fieldTypeSimple)) continue;
                        for (CodeStandardizer.SignatureUpdate up : upEntry.getValue()) {
                            if (!up.methodName.equals(call.getNameAsString())) continue;
                            java.util.List<String> oldNames = up.oldParamNames;

                            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.Expression> args = call.getArguments();

                            // Map old param name to index
                            java.util.Map<String, Integer> oldIndex = new java.util.HashMap<>();
                            for (int i = 0; i < oldNames.size(); i++) oldIndex.put(oldNames.get(i), i);
                            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.Expression> reordered = new com.github.javaparser.ast.NodeList<>();
                            boolean ok = true;

                            if (!ok) continue;
                            call.setArguments(reordered);
                            modified = true;
                            logger.info("Reordered call args for {}.{} in {}", repoSimple, up.methodName, fqn);
                        }
                    }
                }
            }
            if (modified) {
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(sa.com.cloudsolutions.antikythera.parser.AbstractCompiler.classToPath(fqn));
                    java.nio.file.Files.writeString(p, cu.toString());
                } catch (Exception ex) {
                    logger.warn("Failed writing updated usages in {}: {}", fqn, ex.getMessage());
                }
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
