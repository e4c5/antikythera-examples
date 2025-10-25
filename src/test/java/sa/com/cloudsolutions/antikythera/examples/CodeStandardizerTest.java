package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.Evaluator;
import sa.com.cloudsolutions.antikythera.evaluator.EvaluatorFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CodeStandardizerTest {


    private static Callable wrap(MethodDeclaration md) {
        // Use a dummy method call expr to satisfy MCEWrapper constructor
        MethodCallExpr dummyCall = new MethodCallExpr("dummy");
        return new Callable(md, new MCEWrapper(dummyCall));
    }

    private static ClassOrInterfaceDeclaration newRepoClass(CompilationUnit cu, String name) {
        cu.setPackageDeclaration("sa.com.cloudsolutions.antikythera.examples");
        return cu.addClass(name);
    }

    private static OptimizationIssue newIssue(Callable callable, String currentFirst, String recommended, String queryText) {
        RepositoryQuery rq = new RepositoryQuery();
        rq.setMethodDeclaration(callable);
        // For this test, we don't need RepositoryQuery to parse the SQL; avoid calling setQuery which expects entity context
        return new OptimizationIssue(rq, currentFirst, recommended, "reorder for performance", OptimizationIssue.Severity.HIGH, queryText);
    }

}
