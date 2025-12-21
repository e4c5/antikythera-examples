package com.raditha.maven;

/**
 * Exception thrown when a parent POM cannot be resolved.
 */
public class ParentResolutionException extends Exception {
    private final String groupId;
    private final String artifactId;
    private final String version;

    public ParentResolutionException(String message, String groupId,
            String artifactId, String version) {
        super(message);
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public ParentResolutionException(String message, String groupId,
            String artifactId, String version, Throwable cause) {
        super(message, cause);
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    /**
     * Get Maven coordinates in standard format
     * 
     * @return coordinates as groupId:artifactId:version
     */
    public String getFormattedCoordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
