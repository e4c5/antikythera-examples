package com.raditha.spring.cycle.strategies;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.CopyUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Base class for strategies that extract code (Method/Interface).
 */
public abstract class AbstractExtractionStrategy {

    protected boolean dryRun;
    protected final Set<CompilationUnit> modifiedCUs = new HashSet<>();

    public AbstractExtractionStrategy() {
        this.dryRun = false;
    }

    public AbstractExtractionStrategy(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public void writeChanges(String basePath) throws IOException {
        if (dryRun) {
            return;
        }

        for (CompilationUnit cu : modifiedCUs) {
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString().replace('.', '/'))
                    .orElse("");
            String className = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElseThrow(() -> new IllegalStateException("No class found in CU"));

            Path filePath = Path.of(basePath, packageName, className + ".java");
            CopyUtils.writeFileAbsolute(filePath.toString(), cu.toString());
        }
    }

    protected ClassOrInterfaceDeclaration findClassDeclaration(String beanName) {
        // Use AntikytheraRunTime directly instead of internal Graph APIs
        return AntikytheraRunTime.getResolvedTypes().values().stream()
                .filter(w -> w.getFullyQualifiedName().equals(beanName))
                .findFirst()
                .map(w -> w.getType())
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .orElse(null);
    }

    protected boolean isMethodCallOnField(MethodCallExpr mce, String fieldName) {
        Optional<com.github.javaparser.ast.expr.Expression> scope = mce.getScope();
        return scope.isPresent()
                && scope.get().isNameExpr()
                && scope.get().asNameExpr().getNameAsString().equals(fieldName);
    }
}
