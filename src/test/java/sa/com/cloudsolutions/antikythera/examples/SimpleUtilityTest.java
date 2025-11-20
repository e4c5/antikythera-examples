package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for utility functionality.
 * Tests basic string manipulation and validation logic.
 */
class SimpleUtilityTest {
    @Test
    void testTableNameInference() {
        // Test table name inference logic (similar to QueryOptimizationChecker)
        
        // Test repository class name to table name conversion
        assertEquals("user", convertRepositoryNameToTableName("UserRepository"));
        assertEquals("order_item", convertRepositoryNameToTableName("OrderItemRepository"));
        assertEquals("product", convertRepositoryNameToTableName("ProductRepository"));
        
        // Test edge cases
        assertEquals("", convertRepositoryNameToTableName("Repository"));
        assertEquals("test", convertRepositoryNameToTableName("TestRepository"));
    }

    @Test
    void testSqlQueryParsing() {
        // Test basic SQL query parsing logic
        
        String query1 = "SELECT * FROM users WHERE email = ?";
        String query2 = "SELECT u FROM User u WHERE u.active = true";
        String query3 = "SELECT * FROM `order_items` WHERE id = ?";
        
        // Test table name extraction
        assertEquals("users", extractTableNameFromQuery(query1));
        assertEquals("User", extractTableNameFromQuery(query2));
        assertEquals("order_items", extractTableNameFromQuery(query3));
        
        // Test invalid queries
        assertNull(extractTableNameFromQuery("INVALID QUERY"));
        assertNull(extractTableNameFromQuery(null));
        assertNull(extractTableNameFromQuery(""));
    }

    @Test
    void testAnnotationDetection() {
        // Test annotation detection logic (similar to HardDelete)
        
        String methodWithQuery = "@Query(\"SELECT * FROM users\") User findByEmail(String email);";
        String methodWithoutQuery = "User findByEmail(String email);";
        String methodWithEntity = "@Entity public class User { }";
        
        assertTrue(hasAnnotation(methodWithQuery, "Query"));
        assertFalse(hasAnnotation(methodWithoutQuery, "Query"));
        assertTrue(hasAnnotation(methodWithEntity, "Entity"));
        assertFalse(hasAnnotation(methodWithEntity, "Query"));
    }

    @Test
    void testDeleteMethodDetection() {
        // Test delete method detection logic (similar to HardDelete)
        
        assertTrue(isDeleteMethod("deleteById"));
        assertTrue(isDeleteMethod("deleteByEmail"));
        assertTrue(isDeleteMethod("removeUser"));
        assertTrue(isDeleteMethod("hardDelete"));
        
        assertFalse(isDeleteMethod("findById"));
        assertFalse(isDeleteMethod("save"));
        assertFalse(isDeleteMethod("update"));
        assertFalse(isDeleteMethod("create"));
    }

    @Test
    void testFilePathHandling() {
        // Test file path handling logic (similar to RepoProcessor)
        
        String basePath = "/old-project/old-repo/src/main/java/";
        String newPath = updateProjectPath(basePath, "new-project", "new-repo");
        
        assertEquals("/new-project/new-repo/src/main/java/", newPath);
        
        // Test edge cases
        assertEquals("", updateProjectPath("", "project", "repo"));
        assertEquals("/project/repo/", updateProjectPath("/", "project", "repo"));
    }

    private String convertRepositoryNameToTableName(String repositoryName) {
        if (repositoryName.equals("Repository")) {
            return "";
        }
        
        String tableName = repositoryName.replace("Repository", "");
        
        // Convert CamelCase to snake_case
        return tableName.replaceAll("([A-Z])", "_$1")
                        .toLowerCase()
                        .replaceFirst("^_", "");
    }

    private String extractTableNameFromQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        
        // Simple regex to extract table name from FROM clause
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)\\bfrom\\s+([\\w`'\"]+)");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        
        if (matcher.find()) {
            String tableName = matcher.group(1);
            return tableName.replaceAll("[`'\"]", "");
        }
        
        return null;
    }

    private boolean hasAnnotation(String code, String annotationName) {
        return code.contains("@" + annotationName);
    }

    private boolean isDeleteMethod(String methodName) {
        return methodName.toLowerCase().contains("delete") || 
               methodName.toLowerCase().contains("remove");
    }

    private String updateProjectPath(String originalPath, String newProject, String newRepo) {
        if (originalPath.isEmpty()) {
            return "";
        }
        
        if (originalPath.equals("/")) {
            return "/" + newProject + "/" + newRepo + "/";
        }
        
        // Simple replacement logic - replace the first two path segments after root
        return originalPath.replaceFirst("/[^/]+/[^/]+/", "/" + newProject + "/" + newRepo + "/");
    }
}
