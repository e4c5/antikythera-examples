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
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class LiquibaseParser {

    public static class IndexMetadata {
        public String name;
        public List<String> columns;
        public boolean isPrimaryKey;

        public IndexMetadata(String name, List<String> columns, boolean isPrimaryKey) {
            this.name = name;
            this.columns = columns;
            this.isPrimaryKey = isPrimaryKey;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (PK: %b)",
                    (name != null ? name : "Auto-Generated"), columns, isPrimaryKey);
        }
    }

    public static void main(String[] args) throws Exception {
        // Path to your changelog file
        String changelogFile = "src/main/resources/db/changelog.xml";

        // 1. Fix Deprecation: Use java.nio.file.Path
        Path rootPath = Paths.get(".").toAbsolutePath().normalize();
        DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(rootPath);

        // 2. Fix Compiler Error: Use the Factory correctly
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changelogFile, accessor);

        // The interface parse method: parse(String physicalChangeLogLocation, ChangeLogParameters changeLogParameters, ResourceAccessor resourceAccessor)
        // Note: Some versions of the factory-returned parser might need a cast or specific signature.
        // We use the standard Liquibase parsing flow here:
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
        String tableName = null;
        IndexMetadata meta = null;

        if (change instanceof CreateIndexChange) {
            CreateIndexChange cic = (CreateIndexChange) change;
            tableName = cic.getTableName();
            List<String> cols = cic.getColumns().stream()
                    .map(ColumnConfig::getName)
                    .collect(Collectors.toList());
            meta = new IndexMetadata(cic.getIndexName(), cols, false);
            addToMap(masterMap, dbs, tableName, meta);

        } else if (change instanceof AddPrimaryKeyChange) {
            AddPrimaryKeyChange apk = (AddPrimaryKeyChange) change;
            tableName = apk.getTableName();
            List<String> cols = Arrays.asList(apk.getColumnNames().split("\\s*,\\s*"));
            meta = new IndexMetadata(apk.getConstraintName(), cols, true);
            addToMap(masterMap, dbs, tableName, meta);

        } else if (change instanceof CreateTableChange) {
            CreateTableChange ctc = (CreateTableChange) change;
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
                addToMap(masterMap, dbs, tableName, new IndexMetadata(pkName, pkCols, true));
            }

        } else if (change instanceof AddColumnChange) {
            AddColumnChange acc = (AddColumnChange) change;
            tableName = acc.getTableName();
            for (ColumnConfig col : acc.getColumns()) {
                if (col.getConstraints() != null && Boolean.TRUE.equals(col.getConstraints().isPrimaryKey())) {
                    List<String> cols = Collections.singletonList(col.getName());
                    addToMap(masterMap, dbs, tableName, new IndexMetadata(col.getConstraints().getPrimaryKeyName(), cols, true));
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