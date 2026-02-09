package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages checkpoint/resume functionality for QueryOptimizer.
 * Saves state to a JSON file after each successful repository analysis,
 * allowing the optimizer to resume from where it left off if interrupted.
 *
 * <p>The checkpoint file is automatically deleted on successful completion
 * of a full run.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * CheckpointManager checkpoint = new CheckpointManager();
 * checkpoint.load(); // Load existing checkpoint if present
 *
 * // Before processing each repository:
 * if (checkpoint.isProcessed(repoName)) {
 *     // Skip - already processed in previous run
 *     continue;
 * }
 *
 * // After successful processing:
 * checkpoint.markProcessed(repoName);
 * checkpoint.save();
 *
 * // On successful completion of all repositories:
 * checkpoint.clear();
 * </pre>
 */
public class CheckpointManager {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);
    private static final String DEFAULT_CHECKPOINT_FILE = ".query-optimizer-checkpoint.json";

    private final File checkpointFile;
    private final ObjectMapper objectMapper;
    private Checkpoint checkpoint;
    private boolean loaded = false;

    /**
     * Creates a CheckpointManager using the default checkpoint file location.
     */
    public CheckpointManager() {
        this(new File(DEFAULT_CHECKPOINT_FILE));
    }

    /**
     * Creates a CheckpointManager with a custom checkpoint file location.
     *
     * @param checkpointFile the file to use for checkpoint storage
     */
    public CheckpointManager(File checkpointFile) {
        this.checkpointFile = checkpointFile;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.checkpoint = new Checkpoint();
    }

    /**
     * Loads an existing checkpoint from disk if present.
     * If no checkpoint exists or loading fails, starts with a fresh checkpoint.
     * This method is idempotent - calling it multiple times returns the same result.
     *
     * @return true if a checkpoint was loaded, false if starting fresh
     */
    public boolean load() {
        // Return cached result if already loaded
        if (loaded) {
            return checkpoint.getProcessedRepositories() != null && !checkpoint.getProcessedRepositories().isEmpty();
        }

        if (!checkpointFile.exists()) {
            logger.debug("No checkpoint file found, starting fresh");
            checkpoint = new Checkpoint();
            checkpoint.setSessionId(UUID.randomUUID().toString());
            checkpoint.setStartTime(Instant.now().toString());
            loaded = true;
            return false;
        }

        try {
            checkpoint = objectMapper.readValue(checkpointFile, Checkpoint.class);
            logger.info("Loaded checkpoint from {} - {} repositories already processed",
                    checkpointFile.getName(), checkpoint.getProcessedRepositories().size());
            loaded = true;
            return true;
        } catch (IOException e) {
            logger.warn("Failed to load checkpoint file: {}. Starting fresh.", e.getMessage());
            checkpoint = new Checkpoint();
            checkpoint.setSessionId(UUID.randomUUID().toString());
            checkpoint.setStartTime(Instant.now().toString());
            loaded = true;
            return false;
        }
    }

    /**
     * Saves the current checkpoint state to disk.
     * Call this after each successful repository analysis.
     */
    public void save() {
        checkpoint.setLastUpdate(Instant.now().toString());
        try {
            objectMapper.writeValue(checkpointFile, checkpoint);
            logger.debug("Checkpoint saved: {} repositories processed",
                    checkpoint.getProcessedRepositories().size());
        } catch (IOException e) {
            logger.error("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Clears the checkpoint file. Call this on successful completion of a full run.
     */
    public void clear() {
        if (checkpointFile.exists()) {
            if (checkpointFile.delete()) {
                logger.info("Checkpoint cleared - run completed successfully");
            } else {
                logger.warn("Failed to delete checkpoint file: {}", checkpointFile.getName());
            }
        }
        checkpoint = new Checkpoint();
        loaded = false;
    }

    /**
     * Checks if a repository has already been processed in this session.
     *
     * @param fullyQualifiedName the fully qualified repository class name
     * @return true if already processed
     */
    public boolean isProcessed(String fullyQualifiedName) {
        return checkpoint.getProcessedRepositories().contains(fullyQualifiedName);
    }

    /**
     * Marks a repository as successfully processed.
     *
     * @param fullyQualifiedName the fully qualified repository class name
     */
    public void markProcessed(String fullyQualifiedName) {
        checkpoint.getProcessedRepositories().add(fullyQualifiedName);
    }

    /**
     * Gets the set of already processed repositories.
     *
     * @return unmodifiable set of processed repository names
     */
    public Set<String> getProcessedRepositories() {
        return Set.copyOf(checkpoint.getProcessedRepositories());
    }

    /**
     * Gets the number of repositories processed so far.
     *
     * @return count of processed repositories
     */
    public int getProcessedCount() {
        return checkpoint.getProcessedRepositories().size();
    }

    /**
     * Stores accumulated index suggestions for resume capability.
     *
     * @param singleColumnIndexes single-column index suggestions (table|column format)
     * @param multiColumnIndexes multi-column index suggestions (table|col1,col2 format)
     */
    public void setIndexSuggestions(Set<String> singleColumnIndexes,
                                     Set<String> multiColumnIndexes) {
        checkpoint.setSuggestedNewIndexes(new LinkedHashSet<>(singleColumnIndexes));
        checkpoint.setSuggestedMultiColumnIndexes(new LinkedHashSet<>(multiColumnIndexes));
    }

    /**
     * Gets the accumulated single-column index suggestions.
     *
     * @return set of index suggestions in table|column format
     */
    public Set<String> getSuggestedNewIndexes() {
        return checkpoint.getSuggestedNewIndexes() != null
                ? new LinkedHashSet<>(checkpoint.getSuggestedNewIndexes())
                : new LinkedHashSet<>();
    }

    /**
     * Gets the accumulated multi-column index suggestions.
     *
     * @return set of index suggestions in table|col1,col2 format
     */
    public Set<String> getSuggestedMultiColumnIndexes() {
        return checkpoint.getSuggestedMultiColumnIndexes() != null
                ? new LinkedHashSet<>(checkpoint.getSuggestedMultiColumnIndexes())
                : new LinkedHashSet<>();
    }

    /**
     * Stores the set of modified files for resume capability.
     *
     * @param modifiedFiles set of fully qualified class names that were modified
     */
    public void setModifiedFiles(Set<String> modifiedFiles) {
        checkpoint.setModifiedFiles(new HashSet<>(modifiedFiles));
    }

    /**
     * Gets the set of files that were modified in this session.
     *
     * @return set of modified file class names
     */
    public Set<String> getModifiedFiles() {
        return checkpoint.getModifiedFiles() != null
                ? new HashSet<>(checkpoint.getModifiedFiles())
                : new HashSet<>();
    }

    /**
     * Checks if there's an existing checkpoint to resume from.
     *
     * @return true if a checkpoint file exists
     */
    public boolean hasCheckpoint() {
        return checkpointFile.exists();
    }

    /**
     * Gets the session ID for the current checkpoint.
     *
     * @return the session UUID string
     */
    public String getSessionId() {
        return checkpoint.getSessionId();
    }

    /**
     * Gets the checkpoint file location.
     *
     * @return the checkpoint file
     */
    public File getCheckpointFile() {
        return checkpointFile;
    }

    /**
     * Data class representing the checkpoint state.
     * Uses Jackson for JSON serialization.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Checkpoint {
        private String sessionId;
        private String startTime;
        private String lastUpdate;
        private Set<String> processedRepositories = new HashSet<>();
        private Set<String> suggestedNewIndexes = new LinkedHashSet<>();
        private Set<String> suggestedMultiColumnIndexes = new LinkedHashSet<>();
        private Set<String> modifiedFiles = new HashSet<>();

        // Default constructor for Jackson
        public Checkpoint() {
            this.sessionId = UUID.randomUUID().toString();
            this.startTime = Instant.now().toString();
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(String lastUpdate) {
            this.lastUpdate = lastUpdate;
        }

        public Set<String> getProcessedRepositories() {
            return processedRepositories;
        }

        public void setProcessedRepositories(Set<String> processedRepositories) {
            this.processedRepositories = processedRepositories;
        }

        public Set<String> getSuggestedNewIndexes() {
            return suggestedNewIndexes;
        }

        public void setSuggestedNewIndexes(Set<String> suggestedNewIndexes) {
            this.suggestedNewIndexes = suggestedNewIndexes;
        }

        public Set<String> getSuggestedMultiColumnIndexes() {
            return suggestedMultiColumnIndexes;
        }

        public void setSuggestedMultiColumnIndexes(Set<String> suggestedMultiColumnIndexes) {
            this.suggestedMultiColumnIndexes = suggestedMultiColumnIndexes;
        }

        public Set<String> getModifiedFiles() {
            return modifiedFiles;
        }

        public void setModifiedFiles(Set<String> modifiedFiles) {
            this.modifiedFiles = modifiedFiles;
        }
    }
}
