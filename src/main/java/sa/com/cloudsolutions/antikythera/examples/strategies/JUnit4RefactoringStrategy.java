package sa.com.cloudsolutions.antikythera.examples.strategies;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

/**
 * JUnit 4-specific strategy implementation (initial wiring phase).
 * Delegates to existing TestRefactorer pipeline via Abstract base.
 */
public class JUnit4RefactoringStrategy extends AbstractTestRefactoringStrategy {

    @Override
    protected boolean isJunit5() {
        return false;
    }

    @Override
    protected boolean applyFrameworkUnitStyle(ClassOrInterfaceDeclaration decl, boolean isMockito1, String currentAnnotation) {
        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> testAnnotation = decl.getAnnotationByName(currentAnnotation);
        if (testAnnotation.isPresent()) {
            com.github.javaparser.ast.expr.SingleMemberAnnotationExpr runWith = new com.github.javaparser.ast.expr.SingleMemberAnnotationExpr(
                    new com.github.javaparser.ast.expr.Name("RunWith"),
                    new com.github.javaparser.ast.expr.ClassExpr(new com.github.javaparser.ast.type.ClassOrInterfaceType(null, "MockitoJUnitRunner")));
            testAnnotation.get().replace(runWith);
            decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.junit.runner.RunWith"));
            if (isMockito1) {
                decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.runners.MockitoJUnitRunner"));
            } else {
                decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.junit.MockitoJUnitRunner"));
            }
            return true;
        }
        return false;
    }
}
