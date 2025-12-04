package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("java:S106")
public class TestFixer {
    private static boolean dryRun = false;

    public static void main(String[] args) throws IOException {
        for (String arg : args) {
            if (arg.equals("--dry-run")) {
                dryRun = true;
                System.out.println("Running in DRY RUN mode. No changes will be made.");
            }
        }

        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        for (var entry : AntikytheraRunTime.getResolvedCompilationUnits().entrySet()) {
            boolean modified = processCu(entry.getKey(), entry.getValue());
            if (new TestRefactorer().refactor(entry.getValue())) {
                modified = true;
            }

            if (modified && !dryRun) {
                saveCompilationUnit(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean processCu(String classname, CompilationUnit cu) {
        boolean modified = false;
        LexicalPreservingPrinter.setup(cu);

        List<MethodDeclaration> toRemove = new ArrayList<>();

        for (TypeDeclaration<?> decl : cu.getTypes()) {
            processClass(classname, decl, toRemove);
        }

        if (!toRemove.isEmpty()) {
            if (dryRun) {
                System.out.println("Would remove " + toRemove.size() + " tests from " + classname);
            } else {
                for (MethodDeclaration method : toRemove) {
                    method.remove();
                }
                modified = true;
            }
        }
        return modified;
    }

    private static void processClass(String classname, TypeDeclaration<?> decl, List<MethodDeclaration> toRemove) {
        if (decl instanceof ClassOrInterfaceDeclaration) {
            for (MethodDeclaration method : decl.getMethods()) {
                if (method.getAnnotationByName("Test").isPresent() &&
                        !hasAssertion(method, decl.asClassOrInterfaceDeclaration())) {
                    System.out.println(
                            "Found test without assertions: " + classname + "#" + method.getNameAsString());
                    toRemove.add(method);
                }
            }
        }
    }

    private static void saveCompilationUnit(String classname, CompilationUnit cu) throws IOException {
        String relativePath = AbstractCompiler.classToPath(classname);
        File file = new File(Settings.getBasePath(), relativePath);

        // Let's try standard locations
        File mainFile = new File(Settings.getBasePath() + "/src/main/java/" + relativePath);
        File testFile = new File(Settings.getBasePath() + "/src/test/java/" + relativePath);

        if (testFile.exists()) {
            file = testFile;
        } else if (mainFile.exists()) {
            file = mainFile;
        }

        if (file.exists()) {
            try (PrintWriter writer = new PrintWriter(file)) {
                writer.print(LexicalPreservingPrinter.print(cu));
            }
            System.out.println("Saved changes to " + file.getAbsolutePath());
        } else {
            System.err.println("Could not find file to save: " + relativePath);
        }
    }

    private static boolean hasAssertion(MethodDeclaration method, ClassOrInterfaceDeclaration classDecl) {
        AssertionVisitor visitor = new AssertionVisitor(classDecl);
        method.accept(visitor, null);
        return visitor.foundAssertion;
    }

    private static class AssertionVisitor extends VoidVisitorAdapter<Void> {
        boolean foundAssertion = false;
        private final ClassOrInterfaceDeclaration classDecl;
        private final Set<String> visitedMethods = new HashSet<>();

        public AssertionVisitor(ClassOrInterfaceDeclaration classDecl) {
            this.classDecl = classDecl;
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            if (foundAssertion)
                return;

            String name = n.getNameAsString();
            if (name.toLowerCase().startsWith("assert") || name.equals("verify")) {
                foundAssertion = true;
                return;
            }

            // Check for helper methods
            // Simple resolution: check if method exists in current class
            // We need to handle recursion

            // Try to resolve the method to find its declaration
            // This is complex with just JavaParser without a full solver attached to the
            // node sometimes
            // But we can try to find a method in the current class with the same name
            // This is a heuristic

            if (n.getScope().isEmpty() || n.getScope().get().isThisExpr()) {
                Optional<MethodDeclaration> helper = classDecl.getMethodsByName(name).stream()
                        .filter(m -> m.getParameters().size() == n.getArguments().size()) // Simple param count
                                                                                          // check
                        .findFirst();

                if (helper.isPresent()) {
                    String helperSig = helper.get().getSignature().asString();
                    if (!visitedMethods.contains(helperSig)) {
                        visitedMethods.add(helperSig);
                        helper.get().accept(this, null);
                    }
                }
            }
            super.visit(n, arg);
        }
    }
}
