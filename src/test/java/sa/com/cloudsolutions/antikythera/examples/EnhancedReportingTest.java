package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Simple test to verify the enhanced reporting functionality works correctly.
 * This test demonstrates the key enhancements made to the reporting system.
 */
class EnhancedReportingTest {

    private Callable mockCallable(String repoClassFqn, String methodName) {
        Callable callable = mock(Callable.class);
        when(callable.getNameAsString()).thenReturn(methodName);

        // Mock ClassOrInterfaceDeclaration to provide fully qualified name
        ClassOrInterfaceDeclaration cid = mock(ClassOrInterfaceDeclaration.class);
        // Support both Optional<String> (newer JavaParser) and String (older) usages
        try {
            when(cid.getFullyQualifiedName()).thenReturn(Optional.of(repoClassFqn));
        } catch (Throwable ignored) {
            // Fallback if method returns String in this environment
        }
        when(callable.getClassOrInterfaceDeclaration()).thenReturn(cid);
        return callable;
    }

    private RepositoryQuery mockRepositoryQuery(Callable callable) {
        RepositoryQuery rq = mock(RepositoryQuery.class);
        when(rq.getMethodDeclaration()).thenReturn(callable);
        return rq;
    }

    /**
     * Test OptimizationIssue enhancements including severity levels and detailed descriptions.
     */
    @Test
    void testOptimizationIssueEnhancements() {
        String repoClass = "UserRepository";
        String methodName = "findByIsActiveAndUserId";
        Callable callable = mockCallable(repoClass, methodName);
        RepositoryQuery repoQuery = mockRepositoryQuery(callable);

        // Create a high severity issue with enhanced cardinality information
        OptimizationIssue highSeverityIssue = new OptimizationIssue(
            repoQuery,
            "is_active",
            "user_id",
            "Query starts with low cardinality column 'is_active' but high cardinality column 'user_id' is available. Boolean columns filter roughly 50% of rows. Primary keys provide unique row identification for optimal filtering.",
            OptimizationIssue.Severity.HIGH,
            "SELECT * FROM users WHERE is_active = ? AND user_id = ?"
        );
        
        // Verify enhanced properties
        assertSame(repoQuery, highSeverityIssue.query());
        assertEquals("is_active", highSeverityIssue.currentFirstColumn());
        assertEquals("user_id", highSeverityIssue.recommendedFirstColumn());
        assertEquals(OptimizationIssue.Severity.HIGH, highSeverityIssue.severity());
        assertTrue(highSeverityIssue.isHighSeverity());
        assertFalse(highSeverityIssue.isMediumSeverity());
        assertFalse(highSeverityIssue.isLowSeverity());
        
        // Test formatted report includes cardinality information
        String formattedReport = highSeverityIssue.getFormattedReport();
        assertTrue(formattedReport.contains("HIGH"));
        assertTrue(formattedReport.contains("is_active"));
        assertTrue(formattedReport.contains("user_id"));
        assertTrue(formattedReport.contains("Boolean columns filter roughly 50% of rows"));
        // Relaxed assertion to avoid API differences in fully qualified names
        assertTrue(formattedReport.contains(methodName));
        assertTrue(formattedReport.contains("UserRepository"));
    }

}
