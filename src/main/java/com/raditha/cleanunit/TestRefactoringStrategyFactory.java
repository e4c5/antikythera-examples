package com.raditha.cleanunit;

import sa.com.cloudsolutions.antikythera.examples.strategies.JUnit4RefactoringStrategy;
import sa.com.cloudsolutions.antikythera.examples.strategies.JUnit5RefactoringStrategy;

/**
 * Factory for selecting the appropriate refactoring strategy based on the test
 * framework.
 */
public class TestRefactoringStrategyFactory {

    private TestRefactoringStrategyFactory() {
    }

    public static TestRefactoringStrategy get(TestFrameworkDetector.TestFramework framework) {
        return switch (framework) {
            case JUNIT4 -> new JUnit4RefactoringStrategy();
            case JUNIT5 -> new JUnit5RefactoringStrategy();
        };
    }
}
