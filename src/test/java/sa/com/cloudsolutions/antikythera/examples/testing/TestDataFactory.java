package sa.com.cloudsolutions.antikythera.examples.testing;

import sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo;
import sa.com.cloudsolutions.antikythera.examples.analysis.SimilarMethodGroup;
import sa.com.cloudsolutions.antikythera.examples.analysis.FunctionalArea;
import sa.com.cloudsolutions.antikythera.examples.analysis.ConsolidationStrategy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Factory for creating test data and mock objects for comprehensive testing.
 * Supports requirements for test data creation and mock object generation.
 */
public class TestDataFactory {
    
    /**
     * Creates sample method info objects for testing.
     */
    public static List<sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo> createSampleMethodInfos() {
        return Arrays.asList(
            createMethodInfo("TestClass1", "readFile", "readFile(String path)", 
                           "Files.readAllLines(Paths.get(path))", Arrays.asList("readAllLines", "get")),
            createMethodInfo("TestClass2", "writeFile", "writeFile(String path, String content)", 
                           "Files.write(Paths.get(path), content.getBytes())", Arrays.asList("write", "get", "getBytes")),
            createMethodInfo("TestClass3", "readConfiguration", "readConfiguration(String configPath)", 
                           "Files.readAllLines(Paths.get(configPath))", Arrays.asList("readAllLines", "get")),
            createMethodInfo("GitClass1", "checkoutBranch", "checkoutBranch(String branch)", 
                           "ProcessBuilder pb = new ProcessBuilder(\"git\", \"checkout\", branch)", Arrays.asList("ProcessBuilder")),
            createMethodInfo("GitClass2", "pullLatest", "pullLatest()", 
                           "ProcessBuilder pb = new ProcessBuilder(\"git\", \"pull\")", Arrays.asList("ProcessBuilder"))
        );
    }
    
    /**
     * Creates a sample method info object.
     */
    public static sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo createMethodInfo(
            String className, String methodName, String signature, String body, List<String> methodCalls) {
        return new sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo(
            className, methodName, null, signature, body, methodCalls
        );
    }
    
    /**
     * Creates sample similar method groups for testing.
     */
    public static List<SimilarMethodGroup> createSampleSimilarMethodGroups() {
        List<sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo> fileOperationMethods = Arrays.asList(
            createMethodInfo("Class1", "readFile", "readFile(String)", "Files.readAllLines()", Arrays.asList("readAllLines")),
            createMethodInfo("Class2", "loadFile", "loadFile(String)", "Files.readAllLines()", Arrays.asList("readAllLines")),
            createMethodInfo("Class3", "readConfig", "readConfig(String)", "Files.readAllLines()", Arrays.asList("readAllLines"))
        );
        
        List<sa.com.cloudsolutions.antikythera.examples.analysis.MethodInfo> gitOperationMethods = Arrays.asList(
            createMethodInfo("Class1", "gitCheckout", "gitCheckout(String)", "ProcessBuilder git checkout", Arrays.asList("ProcessBuilder")),
            createMethodInfo("Class2", "checkoutBranch", "checkoutBranch(String)", "ProcessBuilder git checkout", Arrays.asList("ProcessBuilder"))
        );
        
        SimilarMethodGroup fileGroup = new SimilarMethodGroup(fileOperationMethods, 0.85);
        fileGroup.setFunctionalArea(FunctionalArea.FILE_OPERATIONS);
        fileGroup.setRecommendedStrategy(ConsolidationStrategy.UTILITY_CLASS);
        
        SimilarMethodGroup gitGroup = new SimilarMethodGroup(gitOperationMethods, 0.92);
        gitGroup.setFunctionalArea(FunctionalArea.GIT_OPERATIONS);
        gitGroup.setRecommendedStrategy(ConsolidationStrategy.UTILITY_CLASS);
        
        return Arrays.asList(fileGroup, gitGroup);
    }
    
    /**
     * Creates sample source file info objects for testing.
     */
    public static List<SourceFileInfo> createSampleSourceFiles() {
        return Arrays.asList(
            new SourceFileInfo(
                Paths.get("src/main/java/TestClass1.java"),
                "com.example.TestClass1",
                Arrays.asList(
                    new sa.com.cloudsolutions.antikythera.examples.testing.MethodInfo("method1", 10, 2, true),
                    new sa.com.cloudsolutions.antikythera.examples.testing.MethodInfo("method2", 5, 0, false)
                ),
                50, 5
            ),
            new SourceFileInfo(
                Paths.get("src/main/java/TestClass2.java"),
                "com.example.TestClass2",
                Arrays.asList(
                    new sa.com.cloudsolutions.antikythera.examples.testing.MethodInfo("process", 15, 3, true),
                    new sa.com.cloudsolutions.antikythera.examples.testing.MethodInfo("validate", 8, 1, false)
                ),
                75, 8
            )
        );
    }
    
    /**
     * Creates sample test file info objects for testing.
     */
    public static List<TestFileInfo> createSampleTestFiles() {
        return Arrays.asList(
            new TestFileInfo(
                Paths.get("src/test/java/TestClass1Test.java"),
                "com.example.TestClass1Test",
                Arrays.asList("testMethod1", "testMethod1Exception", "testMethod2"),
                Set.of("com.example.TestClass1")
            ),
            new TestFileInfo(
                Paths.get("src/test/java/TestClass2Test.java"),
                "com.example.TestClass2Test",
                Arrays.asList("testProcess", "testValidate"),
                Set.of("com.example.TestClass2")
            )
        );
    }
    
    /**
     * Creates sample coverage gaps for testing.
     */
    public static List<CoverageGap> createSampleCoverageGaps() {
        return Arrays.asList(
            new CoverageGap("com.example.TestClass1", "method1", 
                          CoverageGapType.UNTESTED_BRANCHES, "Branch conditions not fully tested", 7),
            new CoverageGap("com.example.TestClass2", "process", 
                          CoverageGapType.UNTESTED_ERROR_HANDLING, "Error handling not tested", 9),
            new CoverageGap("com.example.TestClass3", null, 
                          CoverageGapType.NO_TEST_FILE, "No test file found", 10)
        );
    }
    
    /**
     * Creates sample coverage metrics for testing.
     */
    public static CoverageMetrics createSampleCoverageMetrics() {
        return new CoverageMetrics(
            75.5, // line coverage
            68.2, // branch coverage
            85.0, // method coverage
            1000, // total lines
            150,  // total branches
            50,   // total methods
            755,  // covered lines
            102,  // covered branches
            42    // covered methods
        );
    }
    
    /**
     * Creates a sample test coverage report for testing.
     */
    public static TestCoverageReport createSampleTestCoverageReport() {
        return new TestCoverageReport(
            Paths.get("src/main/java"),
            Paths.get("src/test/java"),
            createSampleSourceFiles(),
            createSampleTestFiles(),
            createSampleCoverageGaps(),
            createSampleCoverageMetrics()
        );
    }
    
    /**
     * Creates a sample code analysis report for testing.
     */
    public static sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisReport createSampleCodeAnalysisReport() {
        List<SimilarMethodGroup> groups = createSampleSimilarMethodGroups();
        java.util.Map<sa.com.cloudsolutions.antikythera.examples.analysis.FunctionalArea, List<SimilarMethodGroup>> categorizedMap = 
            new java.util.EnumMap<>(sa.com.cloudsolutions.antikythera.examples.analysis.FunctionalArea.class);
        
        if (groups.size() >= 2) {
            categorizedMap.put(sa.com.cloudsolutions.antikythera.examples.analysis.FunctionalArea.FILE_OPERATIONS, 
                             java.util.Arrays.asList(groups.get(0)));
            categorizedMap.put(sa.com.cloudsolutions.antikythera.examples.analysis.FunctionalArea.GIT_OPERATIONS, 
                             java.util.Arrays.asList(groups.get(1)));
        }
        
        return new sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisReport(
            Paths.get("src/main/java"),
            25, // total methods
            groups,
            categorizedMap
        );
    }
    
    /**
     * Creates temporary test files for file operation testing.
     */
    public static Path createTempTestFile(String content) throws Exception {
        Path tempFile = java.nio.file.Files.createTempFile("test", ".java");
        java.nio.file.Files.write(tempFile, content.getBytes());
        return tempFile;
    }
    
    /**
     * Creates a sample Java class content for testing.
     */
    public static String createSampleJavaClassContent(String className) {
        return String.format("""
            package com.example;
            
            import java.io.IOException;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            
            public class %s {
                
                public String readFile(String path) throws IOException {
                    return Files.readString(Paths.get(path));
                }
                
                public void writeFile(String path, String content) throws IOException {
                    Files.write(Paths.get(path), content.getBytes());
                }
                
                public boolean processData(String data) {
                    if (data == null) {
                        throw new IllegalArgumentException("Data cannot be null");
                    }
                    
                    if (data.isEmpty()) {
                        return false;
                    }
                    
                    try {
                        // Process the data
                        return data.length() > 0;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            """, className);
    }
    
    /**
     * Creates a sample test class content for testing.
     */
    public static String createSampleTestClassContent(String testClassName, String testedClassName) {
        return String.format("""
            package com.example;
            
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class %s {
                
                private %s instance;
                
                @BeforeEach
                void setUp() {
                    instance = new %s();
                }
                
                @Test
                void testReadFile() throws Exception {
                    // Test implementation
                    assertNotNull(instance);
                }
                
                @Test
                void testWriteFile() throws Exception {
                    // Test implementation
                    assertNotNull(instance);
                }
                
                @Test
                void testProcessDataValid() {
                    assertTrue(instance.processData("valid data"));
                }
                
                @Test
                void testProcessDataEmpty() {
                    assertFalse(instance.processData(""));
                }
                
                @Test
                void testProcessDataNull() {
                    assertThrows(IllegalArgumentException.class, () -> {
                        instance.processData(null);
                    });
                }
            }
            """, testClassName, testedClassName, testedClassName);
    }
}