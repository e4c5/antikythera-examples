package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.examples.strategies.JUnit4RefactoringStrategy;
import sa.com.cloudsolutions.antikythera.examples.strategies.JUnit5RefactoringStrategy;

/**
 * Factory for obtaining the appropriate TestRefactoringStrategy implementation
 * for a given test framework.
 */
public final class TestRefactoringStrategyFactory {

    private TestRefactoringStrategyFactory() {}

    public static TestRefactoringStrategy get(TestFramework framework) {
        switch (framework) {
            case JUNIT5:
                return new JUnit5RefactoringStrategy();
            case JUNIT4:
            default:
                return new JUnit4RefactoringStrategy();
        }
    }
}
