package com.raditha.spring;

/**
 * Interface for Spring Boot migration phases.
 * Each phase handles a specific aspect of the migration.
 */
public interface MigrationPhase {

    /**
     * Execute this migration phase.
     * 
     * @return the result of this migration phase
     * @throws Exception if the migration fails
     */
    MigrationPhaseResult migrate() throws Exception;

    /**
     * Get the name of this migration phase.
     * 
     * @return the phase name
     */
    String getPhaseName();

    /**
     * Get the priority of this phase. Lower values run first.
     * 
     * @return the priority (default 100)
     */
    default int getPriority() {
        return 100;
    }
}


