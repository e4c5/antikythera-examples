package sa.com.cloudsolutions.liquibase;

import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;

import java.nio.file.Path;
import java.util.*;

public class LiquibaseParser {

    public static final String PRIMARY_KEY = "PRIMARY_KEY";
    public static final String INDEX = "INDEX";

    /**
     * Simple DTO to expose index metadata to callers.
     *
     * @param type PRIMARY_KEY, INDEX
     * @param name the name of the index or constraint
     * @param columns the columns included in the index
     */
    public record IndexMetadata(String type, String name, List<String> columns) {
        @Override
        public String toString() {
            return type + ";" + name + ";" + String.join(",", columns);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexMetadata other)) return false;
            return Objects.equals(this.type, other.type)
                    && Objects.equals(this.name, other.name)
                    && Objects.equals(this.columns, other.columns);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, name, columns);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            System.err.println("Usage: java sa.com.cloudsolutions.liquibase.LiquibaseParser <path-to-liquibase-xml>");
            System.exit(1);
        }
        java.io.File file = new java.io.File(args[0]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found: " + file.getAbsolutePath());
            System.exit(2);
        }

        // Use the parent directory of the changelog file as the resource accessor root
        Path rootPath = file.getParentFile().toPath().toAbsolutePath().normalize();
        DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(rootPath);

        // Use the Factory to get the correct parser
        String changelogFile = file.getName();
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changelogFile, accessor);

        DatabaseChangeLog changeLog = parser.parse(changelogFile, new ChangeLogParameters(), accessor);

        Map<String, Map<String, List<IndexMetadata>>> masterMap = new HashMap<>();

        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            Set<String> targetDbs = changeSet.getDbmsSet();
            Collection<String> dbs = (targetDbs == null || targetDbs.isEmpty())
                    ? Collections.singletonList("common") : targetDbs;

            for (Change change : changeSet.getChanges()) {
                processChange(change, dbs, masterMap);
            }
        }

        renderOutput(masterMap);
    }

    private static void processChange(Change change, Collection<String> dbs, Map<String, Map<String, List<IndexMetadata>>> masterMap) {
        String tableName;
        IndexMetadata meta;

        if (change instanceof CreateIndexChange cic) {
            tableName = cic.getTableName();
            List<String> cols = cic.getColumns().stream()
                    .map(ColumnConfig::getName)
                    .toList();
            meta = new IndexMetadata(INDEX, cic.getIndexName(), cols);
            addToMap(masterMap, dbs, tableName, meta);

        } else if (change instanceof AddPrimaryKeyChange apk) {
            tableName = apk.getTableName();
            List<String> cols = Arrays.asList(apk.getColumnNames().split("\\s*,\\s*"));
            meta = new IndexMetadata(PRIMARY_KEY, apk.getConstraintName(), cols);
            addToMap(masterMap, dbs, tableName, meta);

        } else if (change instanceof CreateTableChange ctc) {
            tableName = ctc.getTableName();
            List<String> pkCols = new ArrayList<>();
            String pkName = null;

            for (ColumnConfig col : ctc.getColumns()) {
                if (col.getConstraints() != null && Boolean.TRUE.equals(col.getConstraints().isPrimaryKey())) {
                    pkCols.add(col.getName());
                    if (col.getConstraints().getPrimaryKeyName() != null) {
                        pkName = col.getConstraints().getPrimaryKeyName();
                    }
                }
            }
            if (!pkCols.isEmpty()) {
                addToMap(masterMap, dbs, tableName, new IndexMetadata(PRIMARY_KEY, pkName, pkCols));
            }

        } else if (change instanceof AddColumnChange acc) {
            tableName = acc.getTableName();
            for (ColumnConfig col : acc.getColumns()) {
                if (col.getConstraints() != null && Boolean.TRUE.equals(col.getConstraints().isPrimaryKey())) {
                    List<String> cols = Collections.singletonList(col.getName());
                    addToMap(masterMap, dbs, tableName, new IndexMetadata(PRIMARY_KEY, col.getConstraints().getPrimaryKeyName(), cols));
                }
            }
        }
    }

    private static void addToMap(Map<String, Map<String, List<IndexMetadata>>> masterMap, Collection<String> dbs, String table, IndexMetadata meta) {
        for (String db : dbs) {
            masterMap.computeIfAbsent(db.toLowerCase(), k -> new HashMap<>())
                    .computeIfAbsent(table, k -> new ArrayList<>())
                    .add(meta);
        }
    }

    private static void renderOutput(Map<String, Map<String, List<IndexMetadata>>> masterMap) {
        if (masterMap.isEmpty()) {
            System.out.println("No indexes or primary keys found.");
            return;
        }
        masterMap.forEach((db, tables) -> {
            System.out.println("\n--- DATABASE: " + db.toUpperCase() + " ---");
            tables.forEach((table, indexes) -> {
                System.out.println("Table: " + table);
                indexes.forEach(idx -> System.out.println("  └─ " + idx));
            });
        });
    }
}