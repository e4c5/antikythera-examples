package com.raditha.cleanunit;

import java.util.Set;

/**
 * Data class representing the outcome of an embedded resource conversion.
 */
public class ConversionOutcome {
    public String className;
    public Set<TestContainerDetector.ContainerType> containersRemoved;
    public Set<LiveConnectionDetector.LiveConnectionType> connectionsReplaced;
    public String embeddedAlternative;
    public boolean modified;
    public String action;
    public String reason;

    public ConversionOutcome(String className) {
        this.className = className;
    }

    @Override
    public String toString() {
        if (action == null) {
            return "";
        }

        return String.format("%-40s | %-20s | %-20s | %s",
                className,
                action,
                embeddedAlternative != null ? embeddedAlternative : "None",
                reason);
    }
}
