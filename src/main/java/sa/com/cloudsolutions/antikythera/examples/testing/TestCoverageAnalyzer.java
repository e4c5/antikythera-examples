package sa.com.cloudsolutions.antikythera.examples.testing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyzes test coverage and identifies gaps in the codebase.
 * Implements requirements 2.1, 2.2, 2.3, 2.4, 2.5 for test coverage assessment.
 */
public class TestCoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(TestCoverageAnalyzer.class);
    
    private final JavaParser javaParser;
    private final double lineCoverageThreshold;
    private final double branchCoverageThreshold;
    private final double methodCoverageThreshold;
    
    public TestCoverageAnalyzer() {
        this(80.0, 75.0, 90.0); // Default thresholds
    }
    
    public TestCoverageAnalyzer(double lineCoverageThreshold, double branchCoverageThreshold, 
                               double methodCoverageThreshold) {
        this.javaParser = new JavaParser();
        this.lineCoverageThreshold = lineCoverageThreshold;
        this.branchCoverageThreshold = branchCoverageThreshold;
        this.methodCoverageThreshold = methodCoverageThreshold;
    }
    
    /**
     * Analyzes test coverage for the given source and test directories.
     * Requirement 2.1: Measure line coverage for all source files.
     */
    public TestCoverageReport analyzeCoverage(Path sourceRoot, Path testRoot) throws IOException {
        logger.info("Analyzing test coverage for source: {} and tests: {}", sourceRoot, testRoot);
        
        // Parse source files
        List<SourceFileInfo> sourceFiles = parseSourceFiles(sourceRoot);
        
        // Parse test files
        List<TestFileInfo> testFiles = parseTestFiles(testRoot);
        
        // Analyze coverage gaps
        List<CoverageGap> coverageGaps = identifyCoverageGaps(sourceFiles, testFiles);
        
        // Calculate coverage metrics
        CoverageMetrics metrics = calculateCoverageMetrics(sourceFiles, testFiles, coverageGaps);
        
        TestCoverageReport report = new TestCoverageReport(
            sourceRoot, testRoot, sourceFiles, testFiles, coverageGaps, metrics
        );
        
        logger.info("Coverage analysis complete. Line coverage: {:.1f}%, Method coverage: {:.1f}%", 
                   metrics.getLineCoverage(), metrics.getMethodCoverage());
        
        return report;
    }
    
    /**
     * Parses source files and extracts method information.
     */
    private List<SourceFileInfo> parseSourceFiles(Path sourceRoot) throws IOException {
        List<SourceFileInfo> sourceFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                 .filter(path -> !path.toString().contains("/test/"))
                 .forEach(path -> {
                     try {
                         CompilationUnit cu = javaParser.parse(path).getResult().orElse(null);
                         if (cu != null) {
                             SourceFileInfo fileInfo = extractSourceFileInfo(path, cu);
                             sourceFiles.add(fileInfo);
                         }
                     } catch (IOException e) {
                         logger.warn("Failed to parse source file: {}", path, e);
                     }
                 });
        }
        
        return sourceFiles;
    }
    
    /**
     * Parses test files and extracts test method information.
     */
    private List<TestFileInfo> parseTestFiles(Path testRoot) throws IOException {
        List<TestFileInfo> testFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                 .filter(path -> path.toString().contains("Test"))
                 .forEach(path -> {
                     try {
                         CompilationUnit cu = javaParser.parse(path).getResult().orElse(null);
                         if (cu != null) {
                             TestFileInfo fileInfo = extractTestFileInfo(path, cu);
                             testFiles.add(fileInfo);
                         }
                     } catch (IOException e) {
                         logger.warn("Failed to parse test file: {}", path, e);
                     }
                 });
        }
        
        return testFiles;
    }
    
    /**
     * Extracts information from a source file.
     */
    private SourceFileInfo extractSourceFileInfo(Path filePath, CompilationUnit cu) {
        SourceFileExtractor extractor = new SourceFileExtractor();
        cu.accept(extractor, null);
        
        return new SourceFileInfo(
            filePath,
            extractor.getClassName(),
            extractor.getMethods(),
            extractor.getTotalLines(),
            extractor.getBranchPoints()
        );
    }
    
    /**
     * Extracts information from a test file.
     */
    private TestFileInfo extractTestFileInfo(Path filePath, CompilationUnit cu) {
        TestFileExtractor extractor = new TestFileExtractor();
        cu.accept(extractor, null);
        
        return new TestFileInfo(
            filePath,
            extractor.getClassName(),
            extractor.getTestMethods(),
            extractor.getTestedClasses()
        );
    }
    
    /**
     * Identifies coverage gaps by comparing source and test files.
     * Requirement 2.5: Identify untested error handling paths and edge cases.
     */
    private List<CoverageGap> identifyCoverageGaps(List<SourceFileInfo> sourceFiles, 
                                                  List<TestFileInfo> testFiles) {
        List<CoverageGap> gaps = new ArrayList<>();
        
        // Create a map of tested classes for quick lookup
        Map<String, TestFileInfo> testMap = new HashMap<>();
        for (TestFileInfo testFile : testFiles) {
            for (String testedClass : testFile.getTestedClasses()) {
                if (testedClass != null && !testedClass.trim().isEmpty()) {
                    testMap.put(testedClass, testFile);
                }
            }
        }
        
        for (SourceFileInfo sourceFile : sourceFiles) {
            String className = sourceFile.getClassName();
            if (className == null || className.trim().isEmpty()) {
                className = "UnknownClass";
            }
            TestFileInfo testFile = testMap.get(className);
            
            if (testFile == null) {
                // No test file found for this class
                gaps.add(new CoverageGap(
                    className, null, CoverageGapType.NO_TEST_FILE,
                    "No test file found for class " + className, 10
                ));
                continue;
            }
            
            // Check method coverage
            for (MethodInfo method : sourceFile.getMethods()) {
                String methodName = method.getName();
                if (methodName == null || methodName.trim().isEmpty()) {
                    methodName = "unknownMethod";
                }
                
                if (!isMethodTested(method, testFile)) {
                    gaps.add(new CoverageGap(
                        className, methodName, CoverageGapType.UNTESTED_METHOD,
                        "Method " + methodName + " has no corresponding test", 8
                    ));
                }
                
                // Check for untested error handling
                if (method.hasExceptionHandling() && !hasErrorHandlingTests(method, testFile)) {
                    gaps.add(new CoverageGap(
                        className, methodName, CoverageGapType.UNTESTED_ERROR_HANDLING,
                        "Error handling in method " + methodName + " is not tested", 9
                    ));
                }
                
                // Check for untested branch conditions
                if (method.getBranchCount() > 0 && !hasBranchTests(method, testFile)) {
                    gaps.add(new CoverageGap(
                        className, methodName, CoverageGapType.UNTESTED_BRANCHES,
                        "Branch conditions in method " + methodName + " are not fully tested", 7
                    ));
                }
            }
        }
        
        return gaps;
    }
    
    /**
     * Calculates coverage metrics based on source files, test files, and gaps.
     */
    private CoverageMetrics calculateCoverageMetrics(List<SourceFileInfo> sourceFiles,
                                                   List<TestFileInfo> testFiles,
                                                   List<CoverageGap> gaps) {
        int totalMethods = sourceFiles.stream().mapToInt(f -> f.getMethods().size()).sum();
        int totalLines = sourceFiles.stream().mapToInt(SourceFileInfo::getTotalLines).sum();
        int totalBranches = sourceFiles.stream().mapToInt(SourceFileInfo::getBranchPoints).sum();
        
        // Calculate tested methods (methods without UNTESTED_METHOD gaps)
        long untestedMethods = gaps.stream()
            .filter(gap -> gap.getGapType() == CoverageGapType.UNTESTED_METHOD)
            .count();
        int testedMethods = totalMethods - (int) untestedMethods;
        
        // Estimate line and branch coverage based on method coverage and gaps
        double methodCoverage = totalMethods > 0 ? (double) testedMethods / totalMethods * 100 : 100.0;
        
        // Estimate line coverage (assume tested methods have ~80% line coverage)
        double lineCoverage = methodCoverage * 0.8;
        
        // Estimate branch coverage (assume tested methods have ~70% branch coverage)
        double branchCoverage = methodCoverage * 0.7;
        
        return new CoverageMetrics(
            lineCoverage, branchCoverage, methodCoverage,
            totalLines, totalBranches, totalMethods,
            (int) (totalLines * lineCoverage / 100),
            (int) (totalBranches * branchCoverage / 100),
            testedMethods
        );
    }
    
    /**
     * Checks if a method is tested by looking for corresponding test methods.
     */
    private boolean isMethodTested(MethodInfo method, TestFileInfo testFile) {
        String methodName = method.getName().toLowerCase();
        return testFile.getTestMethods().stream()
            .anyMatch(testMethod -> testMethod.toLowerCase().contains(methodName));
    }
    
    /**
     * Checks if error handling for a method is tested.
     */
    private boolean hasErrorHandlingTests(MethodInfo method, TestFileInfo testFile) {
        String methodName = method.getName().toLowerCase();
        return testFile.getTestMethods().stream()
            .anyMatch(testMethod -> testMethod.toLowerCase().contains(methodName) &&
                     (testMethod.toLowerCase().contains("exception") ||
                      testMethod.toLowerCase().contains("error") ||
                      testMethod.toLowerCase().contains("fail")));
    }
    
    /**
     * Checks if branch conditions for a method are tested.
     */
    private boolean hasBranchTests(MethodInfo method, TestFileInfo testFile) {
        String methodName = method.getName().toLowerCase();
        // Look for multiple test methods for the same method (indicating branch testing)
        long testCount = testFile.getTestMethods().stream()
            .filter(testMethod -> testMethod.toLowerCase().contains(methodName))
            .count();
        
        return testCount >= method.getBranchCount();
    }
    
    /**
     * Visitor to extract source file information.
     */
    private static class SourceFileExtractor extends VoidVisitorAdapter<Void> {
        private String className;
        private final List<MethodInfo> methods = new ArrayList<>();
        private int totalLines = 0;
        private int branchPoints = 0;
        
        @Override
        public void visit(ClassOrInterfaceDeclaration clazz, Void arg) {
            className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
            if (className == null) {
                className = "UnknownClass";
            }
            super.visit(clazz, arg);
        }
        
        @Override
        public void visit(MethodDeclaration method, Void arg) {
            if (method.isPublic() || method.isProtected()) {
                String body = method.getBody().map(Object::toString).orElse("");
                int methodLines = body.split("\n").length;
                int methodBranches = countBranches(body);
                boolean hasExceptionHandling = body.contains("try") || body.contains("catch") || 
                                             body.contains("throw") || method.getThrownExceptions().size() > 0;
                
                methods.add(new MethodInfo(method.getNameAsString(), methodLines, methodBranches, hasExceptionHandling));
                totalLines += methodLines;
                branchPoints += methodBranches;
            }
            super.visit(method, arg);
        }
        
        private int countBranches(String body) {
            int branches = 0;
            branches += countOccurrences(body, "if");
            branches += countOccurrences(body, "else");
            branches += countOccurrences(body, "switch");
            branches += countOccurrences(body, "case");
            branches += countOccurrences(body, "while");
            branches += countOccurrences(body, "for");
            return branches;
        }
        
        private int countOccurrences(String text, String pattern) {
            return text.split("\\b" + pattern + "\\b").length - 1;
        }
        
        public String getClassName() { return className; }
        public List<MethodInfo> getMethods() { return methods; }
        public int getTotalLines() { return totalLines; }
        public int getBranchPoints() { return branchPoints; }
    }
    
    /**
     * Visitor to extract test file information.
     */
    private static class TestFileExtractor extends VoidVisitorAdapter<Void> {
        private String className;
        private final List<String> testMethods = new ArrayList<>();
        private final Set<String> testedClasses = new HashSet<>();
        
        @Override
        public void visit(ClassOrInterfaceDeclaration clazz, Void arg) {
            className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
            if (className == null) {
                className = "UnknownTestClass";
            }
            
            // Infer tested class from test class name
            String testedClassName = className.replaceAll("Test$", "");
            if (!testedClassName.equals(className) && !testedClassName.trim().isEmpty()) {
                testedClasses.add(testedClassName);
            }
            
            super.visit(clazz, arg);
        }
        
        @Override
        public void visit(MethodDeclaration method, Void arg) {
            // Check if method has @Test annotation or follows test naming convention
            boolean isTestMethod = method.getAnnotations().stream()
                .anyMatch(ann -> ann.getNameAsString().equals("Test")) ||
                method.getNameAsString().startsWith("test");
            
            if (isTestMethod) {
                testMethods.add(method.getNameAsString());
            }
            
            super.visit(method, arg);
        }
        
        public String getClassName() { return className; }
        public List<String> getTestMethods() { return testMethods; }
        public Set<String> getTestedClasses() { return testedClasses; }
    }
}