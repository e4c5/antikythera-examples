package com.raditha.spring;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the result of a single migration phase.
 */
public class MigrationPhaseResult {
    private final List<String> changes = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean successful = true;

    /**
     * Add a change made during this phase.
     */
    public void addChange(String change) {
        changes.add(change);
    }

    /**
     * Add an error.
     */
    public void addError(String error) {
        errors.add(error);
        successful = false;
    }

    /**
     * Add a warning.
     */
    public void addWarning(String warning) {
        warnings.add(warning);
    }

    /**
     * Check if this phase was successful.
     */
    public boolean isSuccessful() {
        return successful;
    }

    /**
     * Check if this phase has critical errors.
     */
    public boolean hasCriticalErrors() {
        return !errors.isEmpty();
    }

    /**
     * Get the number of changes made.
     */
    public int getChangeCount() {
        return changes.size();
    }

    /**
     * Get all changes.
     */
    public List<String> getChanges() {
        return new ArrayList<>(changes);
    }

    /**
     * Get all errors.
     */
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Get all warnings.
     */
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
}
