package sa.com.cloudsolutions.antikythera.examples.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Analyzes Java source code to identify similar functionality patterns across classes.
 * Focuses on detecting code duplication in file operations, Git operations, 
 * Liquibase generation, and repository analysis.
 * 
 * The analysis is designed to distinguish between legitimate design patterns
 * (like Visitor pattern methods) and actual code duplication that should be consolidated.
 */
public class CodeAnalysisEngine {
    
    private final List<SimilarCodePattern> detectedPatterns = new ArrayList<>();
    private final Map<String, List<CodeLocation>> functionalGroups = new HashMap<>();
    
    /**
     * Analyzes the specified classes for similar functionality patterns.
     * 
     * @param sourceRoot the root directory containing Java source files
     * @return code analysis report with detected similar patterns
     */
    public CodeAnalysisReport analyzeCodebase(Path sourceRoot) throws IOException {
        // Target classes for analysis as specified in task 2.1
        List<String> targetClasses = Arrays.asList(
            "QueryOptimizationChecker",
            "QueryOptimizer", 
            "RepoProcessor",
            "HardDelete",
            "UsageFinder"
        );
        
        Map<String, String> classContents = new HashMap<>();
        int totalMethods = 0;
        
        // First try to find target classes
        for (String className : targetClasses) {
            Path classPath = findClassFile(sourceRoot, className);
            if (classPath != null) {
                String content = Files.readString(classPath);
                classContents.put(className, content);
                totalMethods += countMethods(content);
            }
        }
        
        // If no target classes found (e.g., in test scenarios), analyze all Java files
        if (classContents.isEmpty()) {
            classContents = findAllJavaFiles(sourceRoot);
            totalMethods = classContents.values().stream()
                .mapToInt(this::countMethods)
                .sum();
        }
        
        // Analyze each functional area
        analyzeFileOperations(classContents);
        analyzeGitOperations(classContents);
        analyzeLiquibaseGeneration(classContents);
        analyzeRepositoryAnalysis(classContents);
        
        // Convert detected patterns to similar method groups
        List<SimilarMethodGroup> similarGroups = convertPatternsToGroups(detectedPatterns);
        
        // Categorize by functional area
        Map<FunctionalArea, List<SimilarMethodGroup>> categorizedPatterns = categorizeSimilarGroups(similarGroups);
        
        return new CodeAnalysisReport(sourceRoot, totalMethods, similarGroups, categorizedPatterns);
    }
    
    /**
     * Finds the Java file for a given class name in the source tree.
     */
    private Path findClassFile(Path sourceRoot, String className) throws IOException {
        return Files.walk(sourceRoot)
            .filter(path -> path.toString().endsWith(className + ".java"))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Finds all Java files in the source tree and returns their contents.
     */
    private Map<String, String> findAllJavaFiles(Path sourceRoot) throws IOException {
        Map<String, String> allFiles = new HashMap<>();
        
        Files.walk(sourceRoot)
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> {
                try {
                    String fileName = path.getFileName().toString().replace(".java", "");
                    String content = Files.readString(path);
                    allFiles.put(fileName, content);
                } catch (IOException e) {
                    // Skip files that can't be read
                }
            });
        
        return allFiles;
    }
    
    /**
     * Analyzes file I/O operations across classes to identify similar patterns.
     */
    private void analyzeFileOperations(Map<String, String> classContents) {
        List<CodeLocation> fileOperations = new ArrayList<>();
        
        // Patterns for file operations - focus on actual duplicated logic
        Pattern[] filePatterns = {
            Pattern.compile("Files\\.readString\\([^)]+\\)", Pattern.MULTILINE),
            Pattern.compile("Files\\.writeString\\([^)]+\\)", Pattern.MULTILINE),
            Pattern.compile("Files\\.write\\([^)]+\\)", Pattern.MULTILINE),
            Pattern.compile("Files\\.readAllLines\\([^)]+\\)", Pattern.MULTILINE),
            Pattern.compile("PrintWriter\\([^)]+\\)", Pattern.MULTILINE)
        };
        
        for (Map.Entry<String, String> entry : classContents.entrySet()) {
            String className = entry.getKey();
            String content = entry.getValue();
            
            for (Pattern pattern : filePatterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int lineNumber = getLineNumber(content, matcher.start());
                    String methodName = extractMethodName(content, matcher.start());
                    
                    // Only include if it's not a simple getter/setter or utility method
                    if (!isSimpleUtilityMethod(methodName, matcher.group())) {
                        fileOperations.add(new CodeLocation(
                            className,
                            methodName,
                            lineNumber,
                            lineNumber,
                            Arrays.asList(matcher.group()),
                            "File I/O operation: " + matcher.group()
                        ));
                    }
                }
            }
        }
        
        // Group by similar functionality and filter out single occurrences
        Map<String, List<CodeLocation>> groupedOperations = groupSimilarOperations(fileOperations);
        List<CodeLocation> consolidatedOperations = new ArrayList<>();
        
        for (List<CodeLocation> group : groupedOperations.values()) {
            if (group.size() >= 2) {
                consolidatedOperations.addAll(group);
            }
        }
        
        if (consolidatedOperations.size() >= 2) {
            functionalGroups.put("File Operations", consolidatedOperations);
            detectedPatterns.add(new SimilarCodePattern(
                "File Operations",
                consolidatedOperations,
                calculateSimilarityScore(consolidatedOperations),
                ConsolidationStrategy.UTILITY_CLASS,
                calculateLinesSaved(consolidatedOperations),
                ComplexityReduction.HIGH
            ));
        }
    }
    
    /**
     * Analyzes Git operations across classes to identify similar patterns.
     */
    private void analyzeGitOperations(Map<String, String> classContents) {
        List<CodeLocation> gitOperations = new ArrayList<>();
        
        // Patterns for Git operations
        Pattern[] gitPatterns = {
            Pattern.compile("git\\s+checkout\\s+[^\"\\n]+", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+pull[^\\n]*", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard[^\\n]*", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("ProcessBuilder\\([^)]*git[^)]*\\)", Pattern.MULTILINE),
            Pattern.compile("runGitCommand\\([^)]+\\)", Pattern.MULTILINE)
        };
        
        for (Map.Entry<String, String> entry : classContents.entrySet()) {
            String className = entry.getKey();
            String content = entry.getValue();
            
            for (Pattern pattern : gitPatterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int lineNumber = getLineNumber(content, matcher.start());
                    gitOperations.add(new CodeLocation(
                        className,
                        extractMethodName(content, matcher.start()),
                        lineNumber,
                        lineNumber,
                        Arrays.asList(matcher.group()),
                        "Git operation: " + matcher.group()
                    ));
                }
            }
        }
        
        if (gitOperations.size() >= 2) {
            functionalGroups.put("Git Operations", gitOperations);
            detectedPatterns.add(new SimilarCodePattern(
                "Git Operations",
                gitOperations,
                calculateSimilarityScore(gitOperations),
                ConsolidationStrategy.UTILITY_CLASS,
                calculateLinesSaved(gitOperations),
                ComplexityReduction.MEDIUM
            ));
        }
    }
    
    /**
     * Analyzes Liquibase generation operations across classes.
     */
    private void analyzeLiquibaseGeneration(Map<String, String> classContents) {
        List<CodeLocation> liquibaseOperations = new ArrayList<>();
        
        // Patterns for Liquibase operations
        Pattern[] liquibasePatterns = {
            Pattern.compile("buildLiquibase[^(]*\\([^)]*\\)", Pattern.MULTILINE),
            Pattern.compile("<changeSet[^>]*>", Pattern.MULTILINE),
            Pattern.compile("CREATE\\s+INDEX[^;]*;", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+INDEX[^;]*;", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            Pattern.compile("getString\\([^)]+\\)", Pattern.MULTILINE)
        };
        
        for (Map.Entry<String, String> entry : classContents.entrySet()) {
            String className = entry.getKey();
            String content = entry.getValue();
            
            for (Pattern pattern : liquibasePatterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int lineNumber = getLineNumber(content, matcher.start());
                    liquibaseOperations.add(new CodeLocation(
                        className,
                        extractMethodName(content, matcher.start()),
                        lineNumber,
                        lineNumber,
                        Arrays.asList(matcher.group()),
                        "Liquibase operation: " + matcher.group()
                    ));
                }
            }
        }
        
        if (liquibaseOperations.size() >= 2) {
            functionalGroups.put("Liquibase Generation", liquibaseOperations);
            detectedPatterns.add(new SimilarCodePattern(
                "Liquibase Generation",
                liquibaseOperations,
                calculateSimilarityScore(liquibaseOperations),
                ConsolidationStrategy.ENHANCED_COMPONENT,
                calculateLinesSaved(liquibaseOperations),
                ComplexityReduction.HIGH
            ));
        }
    }
    
    /**
     * Analyzes repository analysis operations across classes.
     */
    private void analyzeRepositoryAnalysis(Map<String, String> classContents) {
        List<CodeLocation> repositoryOperations = new ArrayList<>();
        
        // Patterns for repository analysis - focus on actual duplication, not design patterns
        Pattern[] repositoryPatterns = {
            Pattern.compile("isJpaRepository\\([^)]*\\)", Pattern.MULTILINE),
            Pattern.compile("getExtendedTypes\\(\\)", Pattern.MULTILINE),
            Pattern.compile("TypeWrapper", Pattern.MULTILINE),
            Pattern.compile("AntikytheraRunTime\\.getResolvedTypes\\(\\)", Pattern.MULTILINE)
        };
        
        for (Map.Entry<String, String> entry : classContents.entrySet()) {
            String className = entry.getKey();
            String content = entry.getValue();
            
            for (Pattern pattern : repositoryPatterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int lineNumber = getLineNumber(content, matcher.start());
                    String methodName = extractMethodName(content, matcher.start());
                    
                    // Skip visitor pattern methods - they're supposed to have similar signatures
                    if (isVisitorPatternMethod(methodName, content, matcher.start())) {
                        continue;
                    }
                    
                    repositoryOperations.add(new CodeLocation(
                        className,
                        methodName,
                        lineNumber,
                        lineNumber,
                        Arrays.asList(matcher.group()),
                        "Repository analysis: " + matcher.group()
                    ));
                }
            }
        }
        
        // Filter out patterns that are likely legitimate design patterns
        repositoryOperations = filterOutDesignPatterns(repositoryOperations);
        
        if (repositoryOperations.size() >= 2) {
            functionalGroups.put("Repository Analysis", repositoryOperations);
            detectedPatterns.add(new SimilarCodePattern(
                "Repository Analysis",
                repositoryOperations,
                calculateSimilarityScore(repositoryOperations),
                ConsolidationStrategy.UTILITY_CLASS,
                calculateLinesSaved(repositoryOperations),
                ComplexityReduction.MEDIUM
            ));
        }
    }
    
    /**
     * Calculates the line number for a given position in the content.
     */
    private int getLineNumber(String content, int position) {
        return (int) content.substring(0, position).chars().filter(ch -> ch == '\n').count() + 1;
    }
    
    /**
     * Extracts the method name containing the given position.
     */
    private String extractMethodName(String content, int position) {
        // Find the method declaration before this position
        String beforePosition = content.substring(0, position);
        Pattern methodPattern = Pattern.compile("(public|private|protected)\\s+[^{]*\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        Matcher matcher = methodPattern.matcher(beforePosition);
        
        String lastMethodName = "unknown";
        while (matcher.find()) {
            lastMethodName = matcher.group(2);
        }
        
        return lastMethodName;
    }
    
    /**
     * Calculates similarity score based on pattern frequency and distribution.
     */
    private double calculateSimilarityScore(List<CodeLocation> locations) {
        if (locations.size() < 2) return 0.0;
        
        // Calculate based on number of classes involved and frequency
        Set<String> uniqueClasses = locations.stream()
            .map(CodeLocation::className)
            .collect(Collectors.toSet());
        
        double classDistribution = (double) uniqueClasses.size() / locations.size();
        double frequency = Math.min(1.0, locations.size() / 10.0);
        
        return (classDistribution + frequency) / 2.0;
    }
    
    /**
     * Estimates lines of code that could be saved through consolidation.
     */
    private int calculateLinesSaved(List<CodeLocation> locations) {
        // Estimate 3-5 lines per duplicate pattern that could be consolidated
        return Math.max(0, (locations.size() - 1) * 4);
    }
    
    /**
     * Returns the functional groups identified during analysis.
     */
    public Map<String, List<CodeLocation>> getFunctionalGroups() {
        return functionalGroups;
    }
    
    /**
     * Checks if a method is part of the Visitor pattern implementation.
     */
    private boolean isVisitorPatternMethod(String methodName, String content, int position) {
        // Visitor pattern methods typically:
        // 1. Are named "visit"
        // 2. Have similar parameter patterns
        // 3. Are in classes that implement visitor interfaces
        
        if (!"visit".equals(methodName)) {
            return false;
        }
        
        // Check if the class implements a visitor interface or extends a visitor class
        return content.contains("implements") && (
            content.contains("Visitor") || 
            content.contains("NodeVisitor") ||
            content.contains("ASTVisitor")
        );
    }
    
    /**
     * Filters out code locations that are likely part of legitimate design patterns.
     */
    private List<CodeLocation> filterOutDesignPatterns(List<CodeLocation> locations) {
        return locations.stream()
            .filter(location -> !isLikelyDesignPattern(location))
            .collect(Collectors.toList());
    }
    
    /**
     * Determines if a code location is likely part of a legitimate design pattern.
     */
    private boolean isLikelyDesignPattern(CodeLocation location) {
        String methodName = location.methodName();
        String functionality = location.functionality();
        
        // Filter out visitor pattern methods
        if ("visit".equals(methodName)) {
            return true;
        }
        
        // Filter out getter/setter patterns
        if (methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")) {
            return true;
        }
        
        // Filter out builder pattern methods
        if (methodName.equals("build") || methodName.equals("builder") || methodName.startsWith("with")) {
            return true;
        }
        
        // Filter out factory pattern methods
        if (methodName.startsWith("create") || methodName.startsWith("make") || methodName.contains("Factory")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a method is a simple utility method that shouldn't be flagged for consolidation.
     */
    private boolean isSimpleUtilityMethod(String methodName, String operation) {
        // Skip simple getters, setters, and single-line operations
        if (methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")) {
            return true;
        }
        
        // Skip if it's just a simple file operation without complex logic
        if (operation.contains("Files.readString") && methodName.contains("Path")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Groups similar operations together for better analysis.
     */
    private Map<String, List<CodeLocation>> groupSimilarOperations(List<CodeLocation> operations) {
        Map<String, List<CodeLocation>> groups = new HashMap<>();
        
        for (CodeLocation operation : operations) {
            String key = extractOperationType(operation.functionality());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(operation);
        }
        
        return groups;
    }
    
    /**
     * Extracts the type of operation from the functionality description.
     */
    private String extractOperationType(String functionality) {
        if (functionality.contains("readString") || functionality.contains("readAllLines")) {
            return "file_read";
        } else if (functionality.contains("writeString") || functionality.contains("write")) {
            return "file_write";
        } else if (functionality.contains("PrintWriter")) {
            return "file_output";
        } else if (functionality.contains("git")) {
            return "git_operation";
        } else if (functionality.contains("Liquibase") || functionality.contains("changeSet")) {
            return "liquibase_generation";
        } else if (functionality.contains("Repository") || functionality.contains("JpaRepository")) {
            return "repository_analysis";
        }
        return "other";
    }
    
    /**
     * Counts the number of methods in a class content.
     */
    private int countMethods(String content) {
        Pattern methodPattern = Pattern.compile("(public|private|protected)\\s+[^{]*\\s+\\w+\\s*\\([^)]*\\)\\s*\\{");
        Matcher matcher = methodPattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
    
    /**
     * Converts SimilarCodePattern objects to SimilarMethodGroup objects.
     */
    private List<SimilarMethodGroup> convertPatternsToGroups(List<SimilarCodePattern> patterns) {
        List<SimilarMethodGroup> groups = new ArrayList<>();
        
        for (SimilarCodePattern pattern : patterns) {
            List<MethodInfo> methods = new ArrayList<>();
            
            for (CodeLocation location : pattern.getLocations()) {
                // Create a simplified MethodInfo from CodeLocation
                MethodInfo methodInfo = new MethodInfo(
                    location.className(),
                    location.methodName(),
                    null, // methodDeclaration not available from pattern analysis
                    location.functionality(),
                    String.join("\n", location.parameters()),
                    location.parameters()
                );
                methods.add(methodInfo);
            }
            
            SimilarMethodGroup group = new SimilarMethodGroup(methods, pattern.getSimilarityScore());
            group.setFunctionalArea(mapToFunctionalArea(pattern.getFunctionalArea()));
            group.setRecommendedStrategy(pattern.getRecommendedStrategy());
            groups.add(group);
        }
        
        return groups;
    }
    
    /**
     * Maps string functional area names to FunctionalArea enum values.
     */
    private FunctionalArea mapToFunctionalArea(String functionalAreaName) {
        return switch (functionalAreaName) {
            case "File Operations" -> FunctionalArea.FILE_OPERATIONS;
            case "Git Operations" -> FunctionalArea.GIT_OPERATIONS;
            case "Liquibase Generation" -> FunctionalArea.LIQUIBASE_GENERATION;
            case "Repository Analysis" -> FunctionalArea.REPOSITORY_ANALYSIS;
            default -> FunctionalArea.UNKNOWN;
        };
    }
    
    /**
     * Categorizes similar method groups by functional area.
     */
    private Map<FunctionalArea, List<SimilarMethodGroup>> categorizeSimilarGroups(List<SimilarMethodGroup> groups) {
        Map<FunctionalArea, List<SimilarMethodGroup>> categorized = new HashMap<>();
        
        for (SimilarMethodGroup group : groups) {
            FunctionalArea area = group.getFunctionalArea();
            categorized.computeIfAbsent(area, k -> new ArrayList<>()).add(group);
        }
        
        return categorized;
    }
    
    /**
     * Represents a similar code pattern found across multiple classes.
     */
    public static class SimilarCodePattern {
        private final String functionalArea;
        private final List<CodeLocation> locations;
        private final double similarityScore;
        private final ConsolidationStrategy recommendedStrategy;
        private final int estimatedLinesSaved;
        private final ComplexityReduction complexityImpact;
        
        public SimilarCodePattern(String functionalArea, List<CodeLocation> locations, 
                                double similarityScore, ConsolidationStrategy recommendedStrategy,
                                int estimatedLinesSaved, ComplexityReduction complexityImpact) {
            this.functionalArea = functionalArea;
            this.locations = locations;
            this.similarityScore = similarityScore;
            this.recommendedStrategy = recommendedStrategy;
            this.estimatedLinesSaved = estimatedLinesSaved;
            this.complexityImpact = complexityImpact;
        }
        
        // Getters
        public String getFunctionalArea() { return functionalArea; }
        public List<CodeLocation> getLocations() { return locations; }
        public double getSimilarityScore() { return similarityScore; }
        public ConsolidationStrategy getRecommendedStrategy() { return recommendedStrategy; }
        public int getEstimatedLinesSaved() { return estimatedLinesSaved; }
        public ComplexityReduction getComplexityImpact() { return complexityImpact; }
    }
    
    /**
     * Represents a specific location where similar code was found.
     */
    public static class CodeLocation {
        private final String className;
        private final String methodName;
        private final int startLine;
        private final int endLine;
        private final List<String> parameters;
        private final String functionality;
        
        public CodeLocation(String className, String methodName, int startLine, int endLine,
                          List<String> parameters, String functionality) {
            this.className = className;
            this.methodName = methodName;
            this.startLine = startLine;
            this.endLine = endLine;
            this.parameters = parameters;
            this.functionality = functionality;
        }
        
        // Getters
        public String className() { return className; }
        public String methodName() { return methodName; }
        public int startLine() { return startLine; }
        public int endLine() { return endLine; }
        public List<String> parameters() { return parameters; }
        public String functionality() { return functionality; }
    }
    
    /**
     * Impact levels for complexity reduction.
     */
    public enum ComplexityReduction {
        LOW, MEDIUM, HIGH
    }
}