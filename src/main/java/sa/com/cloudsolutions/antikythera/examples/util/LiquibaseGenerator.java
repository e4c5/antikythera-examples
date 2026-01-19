package sa.com.cloudsolutions.antikythera.examples.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

/**
 * Enhanced Liquibase changeset generator with consolidated functionality for index creation,
 * drop operations, multi-column indexes, and multi-database dialect support.
 * 
 * Consolidates Liquibase generation patterns from:
 * - QueryOptimizationChecker.buildLiquibaseNonLockingIndexChangeSet()
 * - QueryOptimizationChecker.buildLiquibaseMultiColumnIndexChangeSet()
 * - QueryOptimizationChecker.buildLiquibaseDropIndexChangeSet()
 */
public class LiquibaseGenerator {
    /**
     * Supported database dialects for Liquibase generation.
     */
    public enum DatabaseDialect {
        POSTGRESQL("postgresql"),
        ORACLE("oracle"),
        MYSQL("mysql"),
        H2("h2");
        
        private final String liquibaseDbms;
        
        DatabaseDialect(String liquibaseDbms) {
            this.liquibaseDbms = liquibaseDbms;
        }
        
        public String getLiquibaseDbms() {
            return liquibaseDbms;
        }

        /**
         * Parses a dialect name from string (case-insensitive).
         *
         * @param name the dialect name (e.g., "postgresql", "ORACLE", "mysql", "h2")
         * @return Optional containing the matching DatabaseDialect, or empty if not found
         */
        public static Optional<DatabaseDialect> fromString(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            String normalized = name.trim().toLowerCase();
            for (DatabaseDialect dialect : values()) {
                if (dialect.liquibaseDbms.equals(normalized)) {
                    return Optional.of(dialect);
                }
            }
            return Optional.empty();
        }
    }

    /**
         * Configuration for changeset generation.
         */
        public record ChangesetConfig(String author, Set<DatabaseDialect> supportedDialects, boolean includePreconditions,
                                      boolean includeRollback, String liquibaseMasterFile) {

        public static ChangesetConfig defaultConfig() {
                return new ChangesetConfig("antikythera",
                        Set.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.ORACLE),
                        true, true, null);
            }

            /**
             * Creates a ChangesetConfig by reading supported dialects from the generator.yml configuration.
             * Looks for the property: query_optimizer.supported_dialects
             * Falls back to defaultConfig() if not configured.
             *
             * @return ChangesetConfig with dialects from configuration
             */
            @SuppressWarnings("unchecked")
            public static ChangesetConfig fromConfiguration() {
                Map<String, Object> queryOptimizer = (Map<String, Object>) Settings.getProperty("query_optimizer");
                if (queryOptimizer != null) {
                    List<String> dialectNames = (List<String>) queryOptimizer.get("supported_dialects");
                    String masterFile = (String) queryOptimizer.get("liquibase_master_file");

                    Set<DatabaseDialect> dialects = Set.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.ORACLE);
                    if (dialectNames != null && !dialectNames.isEmpty()) {
                        Set<DatabaseDialect> parsedDialects = dialectNames.stream()
                                .map(DatabaseDialect::fromString)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .collect(Collectors.toSet());
                        if (!parsedDialects.isEmpty()) {
                            dialects = parsedDialects;
                        }
                    }

                    return new ChangesetConfig("antikythera", dialects, true, true, masterFile);
                }
                return defaultConfig();
            }
        }
    
    /**
     * Result of a changeset file write operation.
     */
    public static class WriteResult {
        private final File changesFile;
        private final boolean wasWritten;
        
        public WriteResult(File changesFile, boolean wasWritten) {
            this.changesFile = changesFile;
            this.wasWritten = wasWritten;
        }
        
        public File getChangesFile() { return changesFile; }
        public boolean wasWritten() { return wasWritten; }
    }
    
    private final ChangesetConfig config;
    private final Set<String> generatedChangesetIds;
    
    public LiquibaseGenerator() {
        this(ChangesetConfig.defaultConfig());
    }
    
    public LiquibaseGenerator(ChangesetConfig config) {
        this.config = config;
        this.generatedChangesetIds = new HashSet<>();
    }

    /**
     * Gets the configured Liquibase master file path from configuration.
     *
     * @return Optional containing the master file path, or empty if not configured
     */
    public Optional<Path> getConfiguredMasterFile() {
        String masterFilePath = config.liquibaseMasterFile();
        if (masterFilePath != null && !masterFilePath.isBlank()) {
            return Optional.of(Path.of(masterFilePath));
        }
        return Optional.empty();
    }

    /**
     * Writes changesets to the configured Liquibase master file location.
     * Uses the liquibase_master_file path from query_optimizer configuration.
     *
     * @param changesets the changeset XML content to write
     * @return result of the write operation
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if liquibase_master_file is not configured
     */
    public WriteResult writeChangesetToConfiguredFile(String changesets) throws IOException {
        Optional<Path> masterFile = getConfiguredMasterFile();
        if (masterFile.isEmpty()) {
            throw new IllegalStateException(
                "liquibase_master_file not configured in query_optimizer section of generator.yml");
        }
        return writeChangesetToFile(masterFile.get(), changesets);
    }

    /**
     * Creates a Liquibase changeset for a single-column index.
     * 
     * @param tableName the table name
     * @param columnName the column name
     * @return XML changeset string
     */
    public String createIndexChangeset(String tableName, String columnName) {
        String indexName = generateIndexName(tableName, List.of(columnName));
        return createIndexChangesetInternal(tableName, List.of(columnName), indexName);
    }
    
    /**
     * Creates a Liquibase changeset for a multi-column index.
     * 
     * @param tableName the table name
     * @param columns list of column names in index order
     * @return XML changeset string
     */
    public String createMultiColumnIndexChangeset(String tableName, List<String> columns) {
        if (columns == null || columns.isEmpty()) return "";
        
        String indexName = generateIndexName(tableName, columns);
        return createIndexChangesetInternal(tableName, columns, indexName);
    }
    
    /**
     * Creates a Liquibase changeset for a multi-column index using LinkedHashSet.
     * 
     * @param tableName the table name
     * @param columns LinkedHashSet of column names in index order
     * @return XML changeset string
     */
    public String createMultiColumnIndexChangeset(String tableName, LinkedHashSet<String> columns) {
        return createMultiColumnIndexChangeset(tableName, new ArrayList<>(columns));
    }
    
    /**
     * Creates a Liquibase changeset for dropping an index.
     * 
     * @param indexName the name of the index to drop
     * @return XML changeset string
     */
    public String createDropIndexChangeset(String indexName) {
        if (indexName == null || indexName.isEmpty()) indexName = "<INDEX_NAME>";
        
        String changesetId = generateChangesetId("drop_" + sanitize(indexName));
        StringBuilder sb = new StringBuilder();
        
        sb.append("<changeSet id=\"").append(changesetId).append("\" author=\"").append(config.author()).append("\">\n");
        
        if (config.includePreconditions()) {
            sb.append("    <preConditions onFail=\"MARK_RAN\">\n");
            sb.append("        <indexExists indexName=\"").append(indexName).append("\"/>\n");
            sb.append("    </preConditions>\n");
        }
        
        // Add SQL for each supported dialect
        for (DatabaseDialect dialect : config.supportedDialects()) {
            sb.append("    <sql dbms=\"").append(dialect.getLiquibaseDbms()).append("\">");
            sb.append(getDropIndexSql(dialect, indexName));
            sb.append("</sql>\n");
        }
        
        if (config.includeRollback()) {
            sb.append("    <rollback>\n");
            sb.append("        <comment>Index ").append(indexName).append(" was dropped - manual recreation required if rollback needed</comment>\n");
            sb.append("    </rollback>\n");
        }
        
        sb.append("</changeSet>");
        
        return sb.toString();
    }
    
    /**
     * Creates a composite changeset containing multiple individual changesets.
     * 
     * @param changesets list of changeset XML strings
     * @return combined XML string
     */
    public String createCompositeChangeset(List<String> changesets) {
        if (changesets == null || changesets.isEmpty()) return "";
        
        return changesets.stream()
                .filter(cs -> cs != null && !cs.trim().isEmpty())
                .collect(Collectors.joining("\n\n"));
    }
    
    /**
     * Writes changesets to a new Liquibase file and includes it in the master changelog.
     * 
     * @param masterFile the master Liquibase changelog file
     * @param changesets the changeset XML content to write
     * @return result of the write operation
     * @throws IOException if an I/O error occurs
     */
    public WriteResult writeChangesetToFile(Path masterFile, String changesets) throws IOException {
        if (changesets == null || changesets.trim().isEmpty()) {
            return new WriteResult(null, false);
        }
        
        Path masterPath = masterFile.toAbsolutePath();
        Path dir = masterPath.getParent();
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        long nanos = System.nanoTime() % 1000000; // Get microsecond precision
        String fileName = "antikythera-indexes-" + timestamp + "-" + nanos + ".xml";
        Path outputFile = dir.resolve(fileName);
        
        String content = createLiquibaseDocument(changesets);
        PrintWriter writer = new PrintWriter(outputFile.toFile());
        writer.println(content);
        writer.close();

        // Update master file to include the new changeset file using relative path
        String relativePath = dir.relativize(outputFile).toString();
        updateMasterFile(masterPath, relativePath);

        return new WriteResult(outputFile.toFile(), true);
    }
    
    /**
     * Writes changesets to a new Liquibase file and includes it in the master changelog.
     * 
     * @param masterFile the master Liquibase changelog file
     * @param changesets the changeset XML content to write
     * @return result of the write operation
     * @throws IOException if an I/O error occurs
     */
    public WriteResult writeChangesetToFile(File masterFile, String changesets) throws IOException {
        return writeChangesetToFile(masterFile.toPath(), changesets);
    }
    
    /**
     * Generates a unique index name based on table and column names.
     * Ensures the name doesn't exceed 60 characters. If it does, truncates and adds a hash suffix.
     *
     * @param tableName the table name
     * @param columns the column names
     * @return generated index name (max 60 characters)
     */
    public String generateIndexName(String tableName, List<String> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Columns cannot be empty");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        String columnPart = columns.stream()
                .map(this::sanitize)
                .collect(Collectors.joining("_"));
        
        String fullName = ("idx_" + sanitize(tableName) + "_" + columnPart).toLowerCase();

        // Oracle and other databases have index name limits (typically 30-128 chars)
        // Using 60 as a safe limit for portability
        if (fullName.length() <= 60) {
            return fullName;
        }

        // If too long, truncate and add hash suffix for uniqueness
        // Format: idx_<table>_<truncated_cols>_<hash>
        // Reserve 8 chars for "_" + 7-char hash
        int maxBaseLength = 60 - 8;

        // Create a deterministic hash from the full name
        int hash = Math.abs(fullName.hashCode());
        String hashSuffix = String.format("%07d", hash % 10000000);

        String truncatedBase = fullName.substring(0, Math.min(fullName.length(), maxBaseLength));
        return truncatedBase + "_" + hashSuffix;
    }
    
    /**
     * Checks if a changeset with the given ID has already been generated.
     * 
     * @param changesetId the changeset ID to check
     * @return true if the changeset has been generated, false otherwise
     */
    public boolean isChangesetGenerated(String changesetId) {
        return generatedChangesetIds.contains(changesetId);
    }
    
    /**
     * Gets all generated changeset IDs for duplicate prevention.
     * 
     * @return set of generated changeset IDs
     */
    public Set<String> getGeneratedChangesetIds() {
        return new HashSet<>(generatedChangesetIds);
    }
    
    /**
     * Clears the duplicate prevention cache.
     */
    public void clearGeneratedChangesets() {
        generatedChangesetIds.clear();
    }
    
    // Private helper methods
    
    private String createIndexChangesetInternal(String tableName, List<String> columns, String indexName) {
        String changesetId = generateChangesetId(indexName);
        String columnList = String.join(", ", columns);
        
        StringBuilder sb = new StringBuilder();
        
        sb.append("<changeSet id=\"").append(changesetId).append("\" author=\"").append(config.author()).append("\">\n");
        
        if (config.includePreconditions()) {
            sb.append("    <preConditions onFail=\"MARK_RAN\">\n");
            sb.append("        <not>\n");
            sb.append("            <indexExists tableName=\"").append(tableName).append("\" columnNames=\"").append(columnList).append("\"/>\n");
            sb.append("        </not>\n");
            sb.append("    </preConditions>\n");
        }
        
        // Add SQL for each supported dialect
        for (DatabaseDialect dialect : config.supportedDialects()) {
            sb.append("    <sql dbms=\"").append(dialect.getLiquibaseDbms()).append("\">");
            sb.append(getCreateIndexSql(dialect, indexName, tableName, columnList));
            sb.append("</sql>\n");
        }
        
        if (config.includeRollback()) {
            sb.append("    <rollback>\n");
            for (DatabaseDialect dialect : config.supportedDialects()) {
                sb.append("        <sql dbms=\"").append(dialect.getLiquibaseDbms()).append("\">");
                sb.append(getDropIndexSql(dialect, indexName));
                sb.append("</sql>\n");
            }
            sb.append("    </rollback>\n");
        }
        
        sb.append("</changeSet>");
        
        return sb.toString();
    }
    
    private String getCreateIndexSql(DatabaseDialect dialect, String indexName, String tableName, String columnList) {
        return switch (dialect) {
            case POSTGRESQL -> "CREATE INDEX CONCURRENTLY " + indexName + " ON " + tableName + " (" + columnList + ");";
            case ORACLE -> "CREATE INDEX " + indexName + " ON " + tableName + " (" + columnList + ") ONLINE";
            case MYSQL -> "CREATE INDEX " + indexName + " ON " + tableName + " (" + columnList + ");";
            case H2 -> "CREATE INDEX " + indexName + " ON " + tableName + " (" + columnList + ");";
        };
    }
    
    private String getDropIndexSql(DatabaseDialect dialect, String indexName) {
        return switch (dialect) {
            case POSTGRESQL -> "DROP INDEX CONCURRENTLY IF EXISTS " + indexName + ";";
            case ORACLE -> "DROP INDEX " + indexName;
            case MYSQL -> "DROP INDEX " + indexName + ";";
            case H2 -> "DROP INDEX IF EXISTS " + indexName + ";";
        };
    }
    

    private String generateChangesetId(String baseName) {
        String baseId = baseName + "_" + System.currentTimeMillis();
        
        // Ensure uniqueness within this generator instance
        String changesetId = baseId;
        int counter = 1;
        while (generatedChangesetIds.contains(changesetId)) {
            changesetId = baseId + "_" + counter++;
        }
        
        generatedChangesetIds.add(changesetId);
        return changesetId;
    }
    
    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }
    
    private String createLiquibaseDocument(String changesets) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
                %s
                </databaseChangeLog>
                """.formatted(changesets);
    }
    
    private void updateMasterFile(Path masterFile, String fileName) throws IOException {
        String masterText = Files.readString(masterFile, StandardCharsets.UTF_8);
        String includeTag = String.format("    <include file=\"%s\"/>", fileName);
        
        // Check if this specific file is already included
        if (!masterText.contains("file=\"" + fileName + "\"")) {
            int idx = masterText.lastIndexOf("</databaseChangeLog>");

            String updated = masterText.substring(0, idx) + includeTag + "\n" + masterText.substring(idx);
            try (PrintWriter writer = new PrintWriter(masterFile.toFile())) {
                writer.println(updated);
            }
        }
    }
}
