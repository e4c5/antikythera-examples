package sa.com.cloudsolutions.antikythera.examples.testing;

/**
 * Represents test coverage metrics for a codebase.
 */
public class CoverageMetrics {
    private final double lineCoverage;
    private final double branchCoverage;
    private final double methodCoverage;
    private final int totalLines;
    private final int totalBranches;
    private final int totalMethods;
    private final int coveredLines;
    private final int coveredBranches;
    private final int coveredMethods;
    
    public CoverageMetrics(double lineCoverage, double branchCoverage, double methodCoverage,
                          int totalLines, int totalBranches, int totalMethods,
                          int coveredLines, int coveredBranches, int coveredMethods) {
        this.lineCoverage = lineCoverage;
        this.branchCoverage = branchCoverage;
        this.methodCoverage = methodCoverage;
        this.totalLines = totalLines;
        this.totalBranches = totalBranches;
        this.totalMethods = totalMethods;
        this.coveredLines = coveredLines;
        this.coveredBranches = coveredBranches;
        this.coveredMethods = coveredMethods;
    }
    
    public double getLineCoverage() {
        return lineCoverage;
    }
    
    public double getBranchCoverage() {
        return branchCoverage;
    }
    
    public double getMethodCoverage() {
        return methodCoverage;
    }
    
    public int getTotalLines() {
        return totalLines;
    }
    
    public int getTotalBranches() {
        return totalBranches;
    }
    
    public int getTotalMethods() {
        return totalMethods;
    }
    
    public int getCoveredLines() {
        return coveredLines;
    }
    
    public int getCoveredBranches() {
        return coveredBranches;
    }
    
    public int getCoveredMethods() {
        return coveredMethods;
    }
    
    public int getUncoveredLines() {
        return totalLines - coveredLines;
    }
    
    public int getUncoveredBranches() {
        return totalBranches - coveredBranches;
    }
    
    public int getUncoveredMethods() {
        return totalMethods - coveredMethods;
    }
    
    /**
     * Checks if line coverage meets the specified threshold.
     */
    public boolean meetsLineCoverageThreshold(double threshold) {
        return lineCoverage >= threshold;
    }
    
    /**
     * Checks if branch coverage meets the specified threshold.
     */
    public boolean meetsBranchCoverageThreshold(double threshold) {
        return branchCoverage >= threshold;
    }
    
    /**
     * Checks if method coverage meets the specified threshold.
     */
    public boolean meetsMethodCoverageThreshold(double threshold) {
        return methodCoverage >= threshold;
    }
    
    @Override
    public String toString() {
        return String.format("CoverageMetrics{line=%.1f%%, branch=%.1f%%, method=%.1f%%, " +
                           "lines=%d/%d, branches=%d/%d, methods=%d/%d}",
                           lineCoverage, branchCoverage, methodCoverage,
                           coveredLines, totalLines, coveredBranches, totalBranches,
                           coveredMethods, totalMethods);
    }
}