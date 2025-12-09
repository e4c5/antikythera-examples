package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.Set;

/**
 * Strategy SPI for framework-specific test refactoring.
 * Concrete implementations handle JUnit 4 vs JUnit 5 specifics while
 * reusing the shared analysis and decision pipeline.
 */
public interface TestRefactoringStrategy {

    /**
     * Refactor the provided test class declaration according to the chosen framework.
     *
     * @param decl the test class declaration
     * @param resources detected resources used by tests (DB, WEB, etc.)
     * @param hasSliceSupport whether slice tests are available in deps
     * @param springBootVersion spring boot version string
     * @param isMockito1 whether we detected Mockito 1.x
     * @param cu the owning compilation unit
     * @param refactorer access to shared types like RefactorOutcome and ResourceType
     * @return the outcome of refactoring
     */
    TestRefactorer.RefactorOutcome refactor(ClassOrInterfaceDeclaration decl,
                                            Set<TestRefactorer.ResourceType> resources,
                                            boolean hasSliceSupport,
                                            String springBootVersion,
                                            boolean isMockito1,
                                            CompilationUnit cu,
                                            TestRefactorer refactorer);
}
