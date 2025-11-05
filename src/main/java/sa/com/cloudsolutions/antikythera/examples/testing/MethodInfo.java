package sa.com.cloudsolutions.antikythera.examples.testing;

/**
 * Represents information about a method for test coverage analysis.
 */
public class MethodInfo {
    private final String name;
    private final int lineCount;
    private final int branchCount;
    private final boolean hasExceptionHandling;
    
    public MethodInfo(String name, int lineCount, int branchCount, boolean hasExceptionHandling) {
        this.name = name;
        this.lineCount = lineCount;
        this.branchCount = branchCount;
        this.hasExceptionHandling = hasExceptionHandling;
    }
    
    public String getName() {
        return name;
    }
    
    public int getLineCount() {
        return lineCount;
    }
    
    public int getBranchCount() {
        return branchCount;
    }
    
    public boolean hasExceptionHandling() {
        return hasExceptionHandling;
    }
    
    @Override
    public String toString() {
        return String.format("MethodInfo{name='%s', lines=%d, branches=%d, hasExceptions=%s}", 
                           name, lineCount, branchCount, hasExceptionHandling);
    }
}