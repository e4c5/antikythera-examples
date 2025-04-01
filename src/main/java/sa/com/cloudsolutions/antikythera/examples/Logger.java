package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.sun.source.tree.DoWhileLoopTree;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

public class Logger {
    static int count;

    static class LoggerVisitor extends ModifierVisitor<Boolean> {
        public MethodCallExpr visit(MethodCallExpr mce, Boolean functional) {
            if (mce.toString().startsWith("logger.")) {
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

                if (functional || isLooping(mce)) {
                    if (block.getStatements().size() == 1) {
                        // This block will be empty after removal
                        Optional<Node> blockParent = block.getParentNode();
                        if (blockParent.isPresent() && blockParent.get() instanceof Statement) {
                            // Remove the statement containing the empty block
                            blockParent.get().remove(block);
                        }
                    }
                    return null;
                }
                mce.setName("debug");
            } else {
                for (Expression expr : mce.getArguments()) {
                    if (expr.isLambdaExpr()) {
                        expr.asLambdaExpr().getBody().accept(new LoggerVisitor(), true);
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
            if(n.getParentNode().isPresent()) {
                return isLooping(n.getParentNode().get());
            }
            return false;
        }
    }

    public static void main(String[] args) throws IOException {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        for (var entry : AntikytheraRunTime.getResolvedClasses().entrySet()) {
            try {
                processClass(entry.getKey(), entry.getValue());
            } catch (UnsupportedOperationException uoe) {
                System.out.println(entry.getKey() + " : " +uoe.getMessage());
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
            for (MethodDeclaration m : decl.getMethods()) {
                m.accept(new LoggerVisitor(), false);
            }

            String fullPath = Settings.getBasePath() + "/" + AbstractCompiler.classToPath(classname);
            File f = new File(fullPath);

            if (f.exists()) {
               // System.out.println(f.getPath());

                PrintWriter writer = new PrintWriter(f);

                writer.print(LexicalPreservingPrinter.print(cu));
                writer.close();

            }
        }
    }
}