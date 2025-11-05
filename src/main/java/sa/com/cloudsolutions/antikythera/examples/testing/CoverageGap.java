package sa.com.cloudsolutions.antikythera.examples.testing;

/**
 * Represents a gap in test coverage that needs to be addressed.
 */
public class CoverageGap {
    private final String className;
    private final String methodName;
    private final CoverageGapType gapType;
    private final String description;
    private final int priority;
    
    public CoverageGap(String className, String methodName, CoverageGapType gapType, 
                      String description, int priority) {
        this.className = className;
        this.methodName = methodName;
        this.gapType = gapType;
        this.description = description;
        this.priority = priority;
    }
    
    public String getClassName() {
        return className;
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public CoverageGapType getGapType() {
        return gapType;
    }
    
    public String getDescription() {
        return description;
    }
    
    public int getPriority() {
        return priority;
    }
    
    public String getFullMethodName() {
        return methodName != null ? className + "." + methodName : className;
    }
    
    @Override
    public String toString() {
        return String.format("CoverageGap{%s, type=%s, priority=%d, desc='%s'}", 
                           getFullMethodName(), gapType, priority, description);
    }
}