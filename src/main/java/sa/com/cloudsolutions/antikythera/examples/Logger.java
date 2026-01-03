package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.sun.source.tree.DoWhileLoopTree;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class Logger {
    static int count;
    static Set<String> loggerFields = new HashSet<>();

    public static void main(String[] args) throws IOException {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        for (var entry : AntikytheraRunTime.getResolvedCompilationUnits().entrySet()) {
            try {
                processClass(entry.getKey(), entry.getValue());
            } catch (UnsupportedOperationException uoe) {
                System.out.println(entry.getKey() + " : " + uoe.getMessage());
            }
        }

    }

    private static void processClass(String classname, CompilationUnit cu) throws FileNotFoundException {
        try {
            LexicalPreservingPrinter.setup(cu);
        } catch (Exception e) {
            System.out.println("Warning : " + classname + " cannot be pretty formatted");
        }

        for (TypeDeclaration<?> decl : cu.getTypes()) {
            // Clear logger fields for each class
            loggerFields.clear();

            FieldVisitor visitor = new FieldVisitor();
            if (decl.getAnnotationByName("Slf4j").isPresent() ||
                    decl.getAnnotationByName("Log4j2").isPresent()) {
                loggerFields.add("log");
            }
            // Always visit fields to find any explicitly declared loggers
            decl.accept(visitor, cu);

            for (MethodDeclaration m : decl.getMethods()) {
                m.accept(new LoggerVisitor(decl), false);
                BlockVisitor blockVisitor = new BlockVisitor();
                m.accept(blockVisitor, null);
                // Remove forEach calls with empty lambda bodies
                m.accept(new EmptyForEachRemover(), null);
            }

            String fullPath = Settings.getBasePath() + "/src/main/java/" + AbstractCompiler.classToPath(classname);
            File f = new File(fullPath);

            if (f.exists()) {
                PrintWriter writer = new PrintWriter(f);

                writer.print(LexicalPreservingPrinter.print(cu));
                writer.close();
            }
        }
    }

    static class FieldVisitor extends VoidVisitorAdapter<CompilationUnit> {
        @Override
        public void visit(FieldDeclaration field, CompilationUnit cu) {
            super.visit(field, cu);

            VariableDeclarator vdecl = field.getVariable(0);
            TypeWrapper wrapper = AbstractCompiler.findType(cu, vdecl.getTypeAsString());
            if (wrapper != null && wrapper.getFullyQualifiedName().equals("org.slf4j.Logger")) {
                loggerFields.add(vdecl.getNameAsString());
            }

        }
    }

    static class BlockVisitor extends ModifierVisitor<Void> {
        public BlockStmt visit(BlockStmt block, Void arg) {
            super.visit(block, arg);
            if (block.getStatements().isEmpty()) {
                Optional<Node> parent = block.getParentNode();
                if (parent.isPresent()) {
                    Node parentNode = parent.get();
                    // Don't remove empty catch blocks
                    if (parentNode instanceof CatchClause) {
                        return block;
                    }
                    // Don't remove empty method bodies
                    if (parentNode instanceof MethodDeclaration) {
                        return block;
                    }
                    // Check if this is part of a switch case (including default)
                    if (parentNode instanceof com.github.javaparser.ast.stmt.SwitchEntry) {
                        // Remove the entire switch entry (case or default)
                        parentNode.remove();
                        return null;
                    }
                    if (parentNode instanceof IfStmt ifStmt) {
                        if (ifStmt.getElseStmt().isPresent()) {
                            Statement elseStmt = ifStmt.getElseStmt().orElseThrow();
                            if (elseStmt instanceof BlockStmt elseBlock && elseBlock.getStatements().isEmpty()) {
                                return null;
                            }
                        }
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            return block;
        }
    }

    static class EmptyForEachRemover extends ModifierVisitor<Void> {
        @Override
        public ExpressionStmt visit(ExpressionStmt stmt, Void arg) {
            super.visit(stmt, arg);

            if (stmt.getExpression().isMethodCallExpr()) {
                MethodCallExpr mce = stmt.getExpression().asMethodCallExpr();
                if (hasEmptyLambda(mce)) {
                    // Remove the entire statement
                    return null;
                }
            }
            return stmt;
        }

        private boolean hasEmptyLambda(MethodCallExpr mce) {
            // Check if this is a forEach or similar method with an empty lambda
            String methodName = mce.getNameAsString();

            // Common stream terminal operations and forEach methods
            if (methodName.equals("forEach") ||
                methodName.equals("forEachOrdered") ||
                methodName.equals("peek") ||
                methodName.equals("ifPresent")) {

                // Check if the argument is a lambda with an empty body
                if (!mce.getArguments().isEmpty()) {
                    Expression arg = mce.getArguments().get(0);
                    if (arg.isLambdaExpr()) {
                        LambdaExpr lambda = arg.asLambdaExpr();
                        if (lambda.getBody().isBlockStmt()) {
                            BlockStmt block = lambda.getBody().asBlockStmt();
                            return block.getStatements().isEmpty();
                        }
                        // Single expression lambdas that are now null would have been removed
                        // by the LoggerVisitor, so we check if the body is empty/null-like
                    }
                }
            }

            // Recursively check the scope (for chained calls like stream().forEach(...))
            if (mce.getScope().isPresent() && mce.getScope().get().isMethodCallExpr()) {
                return hasEmptyLambda(mce.getScope().get().asMethodCallExpr());
            }

            return false;
        }
    }

    static class LoggerVisitor extends ModifierVisitor<Boolean> {
        final TypeDeclaration<?> cdecl;

        LoggerVisitor(TypeDeclaration<?> cdecl) {
            this.cdecl = cdecl;
        }

        public MethodCallExpr visit(MethodCallExpr mce, Boolean functional) {
            super.visit(mce, functional);

            boolean isLoggerCall = false;
            boolean isSystemOut = false;

            // Check if the method call's scope is the logger field
            if (mce.getScope().isPresent()) {
                String scope = mce.getScope().get().toString();
                if (loggerFields.contains(scope)) {
                    isLoggerCall = true;
                } else if (scope.equals("System.out") || scope.equals("System.err")) {
                    isSystemOut = true;
                }
            }

            if (isLoggerCall) {
                // Skip utility methods like isDebugEnabled(), isInfoEnabled(), etc.
                String methodName = mce.getNameAsString();
                if (methodName.startsWith("is") && methodName.endsWith("Enabled")) {
                    return mce;
                }

                count++;

                BlockStmt block = AbstractCompiler.findBlockStatement(mce);

                if (block != null) {
                    Optional<Node> n = block.getParentNode();
                    if (n.isPresent()) {
                        Node node = n.get();
                        if (node instanceof CatchClause) {
                            mce.setName("error");
                            return mce;
                        }
                    }
                }

                if (functional || cdecl.isAnnotationPresent("RestController") || isLooping(mce)) {
                    return null;
                }
                mce.setName("debug");
            } else if (isSystemOut) {
                // Handle System.out.println, System.out.print, System.out.printf, System.err.*
                // Always remove these statements completely
                String methodName = mce.getNameAsString();
                if (methodName.equals("println") || methodName.equals("print") ||
                    methodName.equals("printf") || methodName.equals("format")) {
                    count++;
                    return null;  // Remove System.out/err calls completely
                }
            } else {
                for (Expression expr : mce.getArguments()) {
                    if (expr.isLambdaExpr()) {
                        expr.asLambdaExpr().getBody().accept(new LoggerVisitor(cdecl), true);
                    }
                }
            }
            return mce;
        }

        private boolean isLooping(Node n) {
            if (n instanceof MethodDeclaration || n instanceof ClassOrInterfaceDeclaration || n instanceof CatchClause) {
                return false;
            }
            if (n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof ForStmt || n instanceof DoWhileLoopTree) {
                return true;
            }
            if (n.getParentNode().isPresent()) {
                return isLooping(n.getParentNode().get());
            }
            return false;
        }
    }
}
