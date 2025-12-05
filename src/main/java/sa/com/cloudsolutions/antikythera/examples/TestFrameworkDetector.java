package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;

/**
 * Detects which unit test framework a given CompilationUnit is using.
 * Prefers source-level imports; falls back to POM-derived hint when ambiguous.
 */
public final class TestFrameworkDetector {

    private TestFrameworkDetector() {}

    public static TestFramework detect(CompilationUnit cu, boolean isJUnit5FromPom) {
        boolean hasJupiter = cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.junit.jupiter."));
        boolean hasVintage = cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.junit.")) && !hasJupiter;
        if (hasJupiter) return TestFramework.JUNIT5;
        if (hasVintage) return TestFramework.JUNIT4;
        return isJUnit5FromPom ? TestFramework.JUNIT5 : TestFramework.JUNIT4;
    }
}
