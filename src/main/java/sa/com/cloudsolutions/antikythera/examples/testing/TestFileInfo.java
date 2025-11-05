package sa.com.cloudsolutions.antikythera.examples.testing;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Represents information about a test file for coverage analysis.
 */
public class TestFileInfo {
    private final Path filePath;
    private final String className;
    private final List<String> testMethods;
    private final Set<String> testedClasses;
    
    public TestFileInfo(Path filePath, String className, List<String> testMethods, Set<String> testedClasses) {
        this.filePath = filePath;
        this.className = className;
        this.testMethods = testMethods;
        this.testedClasses = testedClasses;
    }
    
    public Path getFilePath() {
        return filePath;
    }
    
    public String getClassName() {
        return className;
    }
    
    public List<String> getTestMethods() {
        return testMethods;
    }
    
    public Set<String> getTestedClasses() {
        return testedClasses;
    }
    
    public int getTestMethodCount() {
        return testMethods.size();
    }
    
    @Override
    public String toString() {
        return String.format("TestFileInfo{class='%s', testMethods=%d, testedClasses=%s}", 
                           className, testMethods.size(), testedClasses);
    }
}