package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast test coverage for GitOperationsManager
 * Note: Only tests class structure without file system operations.
 */
class GitOperationsManagerCoverageTest {

    @Test
    void testGitExceptionConstructors() {
        // Test GitException with message only
        GitOperationsManager.GitException exception1 = new GitOperationsManager.GitException("Test message");
        assertEquals("Test message", exception1.getMessage());
        assertNull(exception1.getCause());

        // Test GitException with message and cause
        RuntimeException cause = new RuntimeException("Root cause");
        GitOperationsManager.GitException exception2 = new GitOperationsManager.GitException("Test message", cause);
        assertEquals("Test message", exception2.getMessage());
        assertEquals(cause, exception2.getCause());
    }

    @Test
    void testGitResultClass() {
        // Test successful result
        GitOperationsManager.GitResult successResult = new GitOperationsManager.GitResult(true, "Success output", 0);
        assertTrue(successResult.isSuccess());
        assertEquals("Success output", successResult.getOutput());
        assertEquals(0, successResult.getExitCode());

        // Test failed result
        GitOperationsManager.GitResult failResult = new GitOperationsManager.GitResult(false, "Error output", 1);
        assertFalse(failResult.isSuccess());
        assertEquals("Error output", failResult.getOutput());
        assertEquals(1, failResult.getExitCode());
    }

    @Test
    void testGitExceptionInheritance() {
        // Test that GitException is properly extending Exception
        GitOperationsManager.GitException exception = new GitOperationsManager.GitException("Test");
        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof Throwable);
    }
}