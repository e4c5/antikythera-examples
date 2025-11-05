package sa.com.cloudsolutions.antikythera.examples.testing;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents information about a source file for test coverage analysis.
 */
public class SourceFileInfo {
    private final Path filePath;
    private final String className;
    private final List<MethodInfo> methods;
    private final int totalLines;
    private final int branchPoints;
    
    public SourceFileInfo(Path filePath, String className, List<MethodInfo> methods, 
                         int totalLines, int branchPoints) {
        this.filePath = filePath;
        this.className = className;
        this.methods = methods;
        this.totalLines = totalLines;
        this.branchPoints = branchPoints;
    }
    
    public Path getFilePath() {
        return filePath;
    }
    
    public String getClassName() {
        return className;
    }
    
    public List<MethodInfo> getMethods() {
        return methods;
    }
    
    public int getTotalLines() {
        return totalLines;
    }
    
    public int getBranchPoints() {
        return branchPoints;
    }
    
    public int getMethodCount() {
        return methods.size();
    }
    
    @Override
    public String toString() {
        return String.format("SourceFileInfo{class='%s', methods=%d, lines=%d, branches=%d}", 
                           className, methods.size(), totalLines, branchPoints);
    }
}