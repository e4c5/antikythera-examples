package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.examples.util.GitOperationsManager.GitException;
import sa.com.cloudsolutions.antikythera.examples.util.GitOperationsManager.GitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitOperationsManager utility class.
 * Focuses on testing data classes only for maximum speed.
 */
class GitOperationsManagerTest {

    @Test
    void testGitResultClass() {
        // Test GitResult class functionality
        GitResult successResult = new GitResult(true, "Success output", 0);
        assertTrue(successResult.isSuccess());
        assertEquals("Success output", successResult.getOutput());
        assertEquals(0, successResult.getExitCode());

        GitResult failureResult = new GitResult(false, "Error output", 1);
        assertFalse(failureResult.isSuccess());
        assertEquals("Error output", failureResult.getOutput());
        assertEquals(1, failureResult.getExitCode());
    }

    @Test
    void testGitExceptionClass() {
        // Test GitException class functionality
        GitException simpleException = new GitException("Simple error message");
        assertEquals("Simple error message", simpleException.getMessage());
        assertNull(simpleException.getCause());

        RuntimeException cause = new RuntimeException("Root cause");
        GitException exceptionWithCause = new GitException("Error with cause", cause);
        assertEquals("Error with cause", exceptionWithCause.getMessage());
        assertEquals(cause, exceptionWithCause.getCause());
    }

    @Test
    void testGitResultEquality() {
        // Test GitResult with different values
        GitResult result1 = new GitResult(true, "output", 0);
        GitResult result2 = new GitResult(false, "error", 1);
        
        assertNotEquals(result1.isSuccess(), result2.isSuccess());
        assertNotEquals(result1.getOutput(), result2.getOutput());
        assertNotEquals(result1.getExitCode(), result2.getExitCode());
    }

    @Test
    void testGitExceptionMessage() {
        // Test that GitException properly handles messages
        GitException exception = new GitException("test message");
        assertEquals("test message", exception.getMessage());
    }
}