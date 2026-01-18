package sa.com.cloudsolutions.liquibase;

import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.*;
import liquibase.exception.LiquibaseException;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

@SuppressWarnings("java:S106")
public class Indexes {

    public static final String UNIQUE_CONSTRAINT = "UNIQUE_CONSTRAINT";
    public static final String UNIQUE_INDEX = "UNIQUE_INDEX";
    public static final String PRIMARY_KEY = "PRIMARY_KEY";
    public static final String INDEX = "INDEX";

    /**
     * Simple DTO to expose index information to callers.
     *
     * @param type PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, INDEX
     */
    public record IndexInfo(String type, String name, List<String> columns) {
        @Override
        public String toString() {
            return type + ";" + name + ";" + String.join(",", columns);
        }

        // Explicit equals to honor ordered column list
        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof IndexInfo other))
                return false;
            return Objects.equals(this.type, other.type)
                    && Objects.equals(this.name, other.name)
                    && Objects.equals(this.columns, other.columns); // order-sensitive
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, name, columns);
        }
    }

    /**
     * Load Liquibase indexes from the given XML and return a table->indexes map.
     * The map value is a set of IndexInfo entries in declaration order (after
     * includes and drops applied).
     */
    public static Map<String, Set<IndexInfo>> load(File liquibaseXml) throws LiquibaseException, java.io.IOException {
        return parseLiquibaseFile(liquibaseXml);
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            System.err.println("Usage: java sa.com.cloudsolutions.liquibase.Indexes <path-to-liquibase-xml>");
            System.exit(1);
        }
        File file = new File(args[0]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found: " + file.getAbsolutePath());
            System.exit(2);
        }

        Map<String, Set<IndexInfo>> byTable = parseLiquibaseFile(file);
        // Display table-based with indentation: PK, UNIQUE, and other indexes
        byTable.keySet().stream().sorted().forEach(table -> {
            System.out.println(table);
            Set<IndexInfo> indexes = byTable.get(table);
            // PK
            indexes.stream().filter(i -> PRIMARY_KEY.equals(i.type))
                    .forEach(i -> System.out.println("  PK: " + i));
            // UNIQUE (constraints or unique indexes)
            indexes.stream().filter(i -> UNIQUE_CONSTRAINT.equals(i.type) || UNIQUE_INDEX.equals(i.type))
                    .forEach(i -> System.out.println("  UNIQUE: " + i));
            // Other indexes
            indexes.stream().filter(i -> INDEX.equals(i.type))
                    .forEach(i -> System.out.println("  INDEX: " + i));
        });
    }

    private static Map<String, Set<IndexInfo>> parseLiquibaseFile(File file)
            throws LiquibaseException, java.io.IOException {
        // Determine the root path for resource resolution
        // For Spring Boot projects, includes are relative to src/main/resources or
        // src/test/resources
        Path rootPath = determineResourceRoot(file);
        DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(rootPath);

        // Get the changelog file path relative to the resource root
        String changelogFile = getRelativeChangelogPath(file, rootPath);
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changelogFile, accessor);
        DatabaseChangeLog changeLog = parser.parse(changelogFile, new ChangeLogParameters(), accessor);

        Map<String, Set<IndexInfo>> result = new LinkedHashMap<>();

        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            for (Change change : changeSet.getChanges()) {
                processChange(change, result);
            }
        }

        return result;
    }

    /**
     * Determines the resource root directory for Liquibase file resolution.
     * For Spring Boot projects, this should be src/main/resources or
     * src/test/resources.
     * Otherwise, falls back to the file's parent directory.
     *
     * @param file the changelog file
     * @return the root path for resource resolution
     */
    private static Path determineResourceRoot(File file) {
        String absolutePath = file.getAbsolutePath();

        // Check if this is in a Spring Boot resources directory
        if (absolutePath.contains("/src/main/resources/")) {
            int idx = absolutePath.indexOf("/src/main/resources/");
            String rootDir = absolutePath.substring(0, idx + "/src/main/resources".length());
            return new File(rootDir).toPath().toAbsolutePath().normalize();
        } else if (absolutePath.contains("/src/test/resources/")) {
            int idx = absolutePath.indexOf("/src/test/resources/");
            String rootDir = absolutePath.substring(0, idx + "/src/test/resources".length());
            return new File(rootDir).toPath().toAbsolutePath().normalize();
        }

        // Fallback: use parent directory
        return file.getParentFile().toPath().toAbsolutePath().normalize();
    }

    /**
     * Gets the changelog file path relative to the resource root.
     * This is necessary for Liquibase to properly resolve include directives.
     *
     * @param file     the changelog file
     * @param rootPath the resource root path
     * @return the relative path from root to the changelog file
     */
    private static String getRelativeChangelogPath(File file, Path rootPath) {
        Path filePath = file.toPath().toAbsolutePath().normalize();
        Path relativePath = rootPath.relativize(filePath);
        return relativePath.toString().replace(File.separatorChar, '/');
    }

    private static void processChange(Change change, Map<String, Set<IndexInfo>> result) {
        if (change instanceof CreateIndexChange cic) {
            handleCreateIndex(cic, result);
        } else if (change instanceof AddPrimaryKeyChange apk) {
            handleAddPrimaryKey(apk, result);
        } else if (change instanceof AddUniqueConstraintChange auc) {
            handleAddUniqueConstraint(auc, result);
        } else if (change instanceof CreateTableChange ctc) {
            handleCreateTable(ctc, result);
        } else if (change instanceof AddColumnChange acc) {
            handleAddColumn(acc, result);
        } else if (change instanceof DropIndexChange dic) {
            handleDropIndex(dic, result);
        } else if (change instanceof DropPrimaryKeyChange dpk) {
            handleDropPrimaryKey(dpk, result);
        } else if (change instanceof DropUniqueConstraintChange duc) {
            handleDropUniqueConstraint(duc, result);
        }
    }

    private static void handleCreateIndex(CreateIndexChange cic, Map<String, Set<IndexInfo>> result) {
        String tableName = cic.getTableName();
        if (isBlank(tableName))
            return;

        List<String> cols = cic.getColumns().stream()
                .map(ColumnConfig::getName)
                .filter(n -> !isBlank(n))
                .toList();
        if (cols.isEmpty())
            return;

        boolean unique = Boolean.TRUE.equals(cic.isUnique());
        String type = unique ? UNIQUE_INDEX : INDEX;
        add(result, tableName, new IndexInfo(type, orUnknown(cic.getIndexName()), cols));
    }

    private static void handleAddPrimaryKey(AddPrimaryKeyChange apk, Map<String, Set<IndexInfo>> result) {
        String tableName = apk.getTableName();
        if (isBlank(tableName) || isBlank(apk.getColumnNames()))
            return;

        List<String> cols = splitColumns(apk.getColumnNames());
        if (cols.isEmpty())
            return;

        add(result, tableName, new IndexInfo(PRIMARY_KEY, orUnknown(apk.getConstraintName()), cols));
    }

    private static void handleAddUniqueConstraint(AddUniqueConstraintChange auc, Map<String, Set<IndexInfo>> result) {
        String tableName = auc.getTableName();
        if (isBlank(tableName) || isBlank(auc.getColumnNames()))
            return;

        List<String> cols = splitColumns(auc.getColumnNames());
        if (cols.isEmpty())
            return;

        add(result, tableName, new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(auc.getConstraintName()), cols));
    }

    private static void handleCreateTable(CreateTableChange ctc, Map<String, Set<IndexInfo>> result) {
        String tableName = ctc.getTableName();
        if (isBlank(tableName))
            return;

        List<String> pkCols = new ArrayList<>();
        String pkName = null;

        for (ColumnConfig col : ctc.getColumns()) {
            if (col.getConstraints() != null) {
                if (Boolean.TRUE.equals(col.getConstraints().isPrimaryKey())) {
                    pkCols.add(col.getName());
                    if (col.getConstraints().getPrimaryKeyName() != null) {
                        pkName = col.getConstraints().getPrimaryKeyName();
                    }
                }
                if (Boolean.TRUE.equals(col.getConstraints().isUnique())) {
                    String uniqueName = col.getConstraints().getUniqueConstraintName();
                    add(result, tableName,
                            new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(uniqueName), List.of(col.getName())));
                }
            }
        }

        if (!pkCols.isEmpty()) {
            add(result, tableName, new IndexInfo(PRIMARY_KEY, orUnknown(pkName), pkCols));
        }
    }

    private static void handleAddColumn(AddColumnChange acc, Map<String, Set<IndexInfo>> result) {
        String tableName = acc.getTableName();
        if (isBlank(tableName))
            return;

        for (ColumnConfig col : acc.getColumns()) {
            if (col.getConstraints() != null) {
                if (Boolean.TRUE.equals(col.getConstraints().isPrimaryKey())) {
                    add(result, tableName, new IndexInfo(PRIMARY_KEY,
                            orUnknown(col.getConstraints().getPrimaryKeyName()),
                            List.of(col.getName())));
                }
                if (Boolean.TRUE.equals(col.getConstraints().isUnique())) {
                    add(result, tableName, new IndexInfo(UNIQUE_CONSTRAINT,
                            orUnknown(col.getConstraints().getUniqueConstraintName()),
                            List.of(col.getName())));
                }
            }
        }
    }

    private static void handleDropIndex(DropIndexChange dic, Map<String, Set<IndexInfo>> result) {
        String indexName = dic.getIndexName();
        String tableName = dic.getTableName();

        if (!isBlank(indexName)) {
            if (!isBlank(tableName)) {
                removeIndexByName(result, tableName, indexName);
            } else {
                removeIndexByNameAnyTable(result, indexName);
            }
        }
    }

    private static void handleDropPrimaryKey(DropPrimaryKeyChange dpk, Map<String, Set<IndexInfo>> result) {
        String tableName = dpk.getTableName();
        String constraintName = dpk.getConstraintName();

        if (!isBlank(tableName)) {
            removePrimaryKey(result, tableName, constraintName);
        }
    }

    private static void handleDropUniqueConstraint(DropUniqueConstraintChange duc, Map<String, Set<IndexInfo>> result) {
        String tableName = duc.getTableName();
        String constraintName = duc.getConstraintName();

        if (!isBlank(tableName)) {
            removeUniqueConstraint(result, tableName, constraintName);
        }
    }

    // --- Helper methods ---

    private static void add(Map<String, Set<IndexInfo>> map, String table, IndexInfo index) {
        map.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(index);
    }

    private static void removeIndexByName(Map<String, Set<IndexInfo>> map, String table, String name) {
        if (isBlank(table) || isBlank(name))
            return;
        Set<IndexInfo> set = map.get(table);
        if (set == null)
            return;
        set.removeIf(i -> (INDEX.equals(i.type) || UNIQUE_INDEX.equals(i.type))
                && i.name != null && i.name.equalsIgnoreCase(name));
        if (set.isEmpty())
            map.remove(table);
    }

    private static void removeIndexByNameAnyTable(Map<String, Set<IndexInfo>> map, String name) {
        if (isBlank(name) || map.isEmpty())
            return;
        List<String> emptyTables = new ArrayList<>();
        for (Map.Entry<String, Set<IndexInfo>> e : map.entrySet()) {
            Set<IndexInfo> set = e.getValue();
            if (set == null)
                continue;
            set.removeIf(i -> (INDEX.equals(i.type) || UNIQUE_INDEX.equals(i.type))
                    && i.name != null && i.name.equalsIgnoreCase(name));
            if (set.isEmpty())
                emptyTables.add(e.getKey());
        }
        emptyTables.forEach(map::remove);
    }

    private static void removePrimaryKey(Map<String, Set<IndexInfo>> map, String table, String name) {
        Set<IndexInfo> set = map.get(table);
        if (set == null)
            return;
        if (!isBlank(name)) {
            set.removeIf(i -> PRIMARY_KEY.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(name));
        } else {
            set.removeIf(i -> PRIMARY_KEY.equals(i.type));
        }
        if (set.isEmpty())
            map.remove(table);
    }

    private static void removeUniqueConstraint(Map<String, Set<IndexInfo>> map, String table, String name) {
        Set<IndexInfo> set = map.get(table);
        if (set == null)
            return;
        if (!isBlank(name)) {
            set.removeIf(i -> UNIQUE_CONSTRAINT.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(name));
        }
        if (set.isEmpty())
            map.remove(table);
    }

    private static List<String> splitColumns(String csv) {
        if (isBlank(csv))
            return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String orUnknown(String s) {
        return isBlank(s) ? "<unnamed>" : s;
    }
}
