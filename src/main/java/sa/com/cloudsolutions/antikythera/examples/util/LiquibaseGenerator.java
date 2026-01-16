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
    }

    /**
         * Configuration for changeset generation.
         */
        public record ChangesetConfig(String author, Set<DatabaseDialect> supportedDialects, boolean includePreconditions,
                                      boolean includeRollback) {

        public static ChangesetConfig defaultConfig() {
                return new ChangesetConfig("antikythera",
                        Set.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.ORACLE),
                        true, true);
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
     * 
     * @param tableName the table name
     * @param columns the column names
     * @return generated index name
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
        
        return ("idx_" + sanitize(tableName) + "_" + columnPart).toLowerCase();
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
            // Use OR to combine checks for each dialect - skip if ANY returns count > 0
            sb.append("        <or>\n");
            for (DatabaseDialect dialect : config.supportedDialects()) {
                sb.append("            <sqlCheck expectedResult=\"0\" dbms=\"").append(dialect.getLiquibaseDbms()).append("\">\n");
                sb.append("                ").append(getIndexExistsByColumnsSql(dialect, tableName, columns)).append("\n");
                sb.append("            </sqlCheck>\n");
            }
            sb.append("        </or>\n");
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
    
    /**
     * Generates a SQL query that returns the count of existing indexes on the specified columns.
     * Returns 0 if no such index exists, or a positive number if an index covering those exact columns exists.
     * This allows detection of indexes regardless of their name.
     *
     * @param dialect the database dialect
     * @param tableName the table name
     * @param columns the columns to check (in order)
     * @return SQL query that returns index count
     */
    private String getIndexExistsByColumnsSql(DatabaseDialect dialect, String tableName, List<String> columns) {
        String upperTableName = tableName.toUpperCase();
        String columnCount = String.valueOf(columns.size());

        // Build a comma-separated list of uppercase column names for comparison
        String columnListForIn = columns.stream()
                .map(c -> "'" + c.toUpperCase() + "'")
                .collect(Collectors.joining(", "));

        return switch (dialect) {
            case POSTGRESQL -> buildPostgresIndexExistsQuery(tableName.toLowerCase(), columns);
            case ORACLE -> buildOracleIndexExistsQuery(upperTableName, columnCount, columnListForIn);
            case MYSQL -> buildMySqlIndexExistsQuery(tableName, columns);
            case H2 -> buildH2IndexExistsQuery(upperTableName, columnCount, columnListForIn);
        };
    }

    /**
     * PostgreSQL query to check for existing index on columns.
     * Uses pg_index and pg_attribute to find indexes with matching column sets.
     * Returns 0 if no index exists, or a positive number if an index on the exact columns exists.
     */
    private String buildPostgresIndexExistsQuery(String tableName, List<String> columns) {
        String columnCount = String.valueOf(columns.size());
        String columnListForIn = columns.stream()
                .map(c -> "'" + c.toLowerCase() + "'")
                .collect(Collectors.joining(", "));

        // Query finds indexes where:
        // 1. The index is on the specified table
        // 2. The index has exactly the specified number of columns
        // 3. All specified columns are in the index
        return """
            SELECT COALESCE((
                SELECT COUNT(*) FROM (
                    SELECT i.indexrelid
                    FROM pg_index i
                    JOIN pg_class t ON t.oid = i.indrelid
                    JOIN pg_class ix ON ix.oid = i.indexrelid
                    WHERE t.relname = '%s'
                    AND array_length(i.indkey, 1) = %s
                    AND NOT EXISTS (
                        SELECT 1 FROM generate_series(0, %s - 1) AS gs(n)
                        WHERE (SELECT a.attname FROM pg_attribute a
                               WHERE a.attrelid = i.indrelid AND a.attnum = i.indkey[n + 1])
                              NOT IN (%s)
                    )
                ) sub
            ), 0)""".formatted(tableName, columnCount, columnCount, columnListForIn);
    }

    /**
     * Oracle query to check for existing index on columns.
     * Uses ALL_IND_COLUMNS to find indexes with matching column sets.
     * Returns 0 if no index exists, or a positive number if an index on the exact columns exists.
     */
    private String buildOracleIndexExistsQuery(String tableName, String columnCount, String columnListForIn) {
        // Query finds indexes where:
        // 1. The index is on the specified table
        // 2. The index has exactly the specified number of columns
        // 3. All specified columns are in the index
        // NVL ensures we return 0 when no matching index exists (rather than no rows)
        return """
            SELECT NVL((
                SELECT COUNT(*) FROM (
                    SELECT ic.INDEX_NAME
                    FROM ALL_IND_COLUMNS ic
                    WHERE ic.TABLE_NAME = '%s'
                    GROUP BY ic.INDEX_NAME
                    HAVING COUNT(*) = %s
                    AND COUNT(CASE WHEN ic.COLUMN_NAME IN (%s) THEN 1 END) = %s
                )
            ), 0) FROM DUAL""".formatted(tableName, columnCount, columnListForIn, columnCount);
    }

    /**
     * MySQL query to check for existing index on columns.
     * Uses INFORMATION_SCHEMA.STATISTICS to find indexes with matching column sets.
     * Returns 0 if no index exists, or a positive number if an index on the exact columns exists.
     */
    private String buildMySqlIndexExistsQuery(String tableName, List<String> columns) {
        String columnCount = String.valueOf(columns.size());
        String columnListForIn = columns.stream()
                .map(c -> "'" + c + "'")
                .collect(Collectors.joining(", "));

        // Query finds indexes where:
        // 1. The index is on the specified table
        // 2. The index has exactly the specified number of columns
        // 3. All specified columns are in the index
        // COALESCE ensures we return 0 when no matching index exists
        return """
            SELECT COALESCE((
                SELECT COUNT(*) FROM (
                    SELECT INDEX_NAME
                    FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_NAME = '%s'
                    GROUP BY INDEX_NAME
                    HAVING COUNT(*) = %s
                    AND SUM(CASE WHEN COLUMN_NAME IN (%s) THEN 1 ELSE 0 END) = %s
                ) sub
            ), 0)""".formatted(tableName, columnCount, columnListForIn, columnCount);
    }

    /**
     * H2 query to check for existing index on columns.
     * Uses INFORMATION_SCHEMA.INDEX_COLUMNS to find indexes with matching column sets.
     * Returns 0 if no index exists, or a positive number if an index on the exact columns exists.
     */
    private String buildH2IndexExistsQuery(String tableName, String columnCount, String columnListForIn) {
        // Query finds indexes where:
        // 1. The index is on the specified table
        // 2. The index has exactly the specified number of columns
        // 3. All specified columns are in the index
        // COALESCE ensures we return 0 when no matching index exists
        return """
            SELECT COALESCE((
                SELECT COUNT(*) FROM (
                    SELECT INDEX_NAME
                    FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                    WHERE TABLE_NAME = '%s'
                    GROUP BY INDEX_NAME
                    HAVING COUNT(*) = %s
                    AND SUM(CASE WHEN COLUMN_NAME IN (%s) THEN 1 ELSE 0 END) = %s
                ) sub
            ), 0)""".formatted(tableName, columnCount, columnListForIn, columnCount);
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
