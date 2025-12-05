package sa.com.cloudsolutions.antikythera.examples.strategies;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import sa.com.cloudsolutions.antikythera.examples.TestRefactorer;

import java.util.Set;

/**
 * JUnit 5-specific strategy implementation (initial wiring phase).
 * Delegates to existing TestRefactorer pipeline via Abstract base.
 */
public class JUnit5RefactoringStrategy extends AbstractTestRefactoringStrategy {

    @Override
    protected boolean isJunit5() {
        return true;
    }

    @Override
    protected boolean applyFrameworkUnitStyle(ClassOrInterfaceDeclaration decl, boolean isMockito1, String currentAnnotation) {
        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> testAnnotation = decl.getAnnotationByName(currentAnnotation);
        if (testAnnotation.isPresent()) {
            com.github.javaparser.ast.expr.SingleMemberAnnotationExpr extendWith = new com.github.javaparser.ast.expr.SingleMemberAnnotationExpr(
                    new com.github.javaparser.ast.expr.Name("ExtendWith"),
                    new com.github.javaparser.ast.expr.ClassExpr(new com.github.javaparser.ast.type.ClassOrInterfaceType(null, "MockitoExtension")));
            testAnnotation.get().replace(extendWith);
            decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.junit.jupiter.api.extension.ExtendWith"));
            decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.junit.jupiter.MockitoExtension"));
            return true;
        }
        return false;
    }
}
