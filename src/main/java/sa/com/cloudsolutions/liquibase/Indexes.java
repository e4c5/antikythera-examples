package sa.com.cloudsolutions.liquibase;

import liquibase.change.AbstractSQLChange;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static sa.com.cloudsolutions.liquibase.LiquibaseResourceUtil.determineResourceRoot;
import static sa.com.cloudsolutions.liquibase.LiquibaseResourceUtil.getRelativeChangelogPath;

@SuppressWarnings("java:S106")
public class Indexes {

    public static final String UNIQUE_CONSTRAINT = "UNIQUE_CONSTRAINT";
    public static final String UNIQUE_INDEX = "UNIQUE_INDEX";
    public static final String PRIMARY_KEY = "PRIMARY_KEY";
    public static final String INDEX = "INDEX";
    private static final Pattern CREATE_INDEX_STATEMENT = Pattern.compile(
            "^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+|ONLINE\\s+|IF\\s+NOT\\s+EXISTS\\s+)*"
                    + "([\\w\"`\\[\\].]+)\\s+ON\\s+([\\w\"`\\[\\].]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP_INDEX_STATEMENT = Pattern.compile(
            "^\\s*DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+|ONLINE\\s+|IF\\s+EXISTS\\s+)*"
                    + "([\\w\"`\\[\\].]+)(?:\\s+ON\\s+([\\w\"`\\[\\].]+))?.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String IDENTIFIER_PATTERN = "\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z0-9_]+";
    private static final Pattern SIMPLE_COLUMN_PATTERN = Pattern.compile(
            "^\\s*((" + IDENTIFIER_PATTERN + ")(?:\\s*\\.\\s*(" + IDENTIFIER_PATTERN + "))*)\\s*"
                    + "(?:\\bASC\\b|\\bDESC\\b)?"
                    + "(?:\\s+\\bNULLS\\b\\s+\\w+)?"
                    + "(?:\\s+\\bCOLLATE\\b\\s+\\S+)?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        } else if (change instanceof AbstractSQLChange sqlChange) {
            handleSqlChange(sqlChange, result);
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
        addConstraint(result, apk.getTableName(), apk.getColumnNames(),
                      PRIMARY_KEY, apk.getConstraintName());
    }

    private static void handleAddUniqueConstraint(AddUniqueConstraintChange auc, Map<String, Set<IndexInfo>> result) {
        addConstraint(result, auc.getTableName(), auc.getColumnNames(),
                      UNIQUE_CONSTRAINT, auc.getConstraintName());
    }

    private static void addConstraint(Map<String, Set<IndexInfo>> result, String tableName,
                                       String columnNames, String type, String constraintName) {
        if (isBlank(tableName) || isBlank(columnNames))
            return;

        List<String> cols = splitColumns(columnNames);
        if (cols.isEmpty())
            return;

        add(result, tableName, new IndexInfo(type, orUnknown(constraintName), cols));
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

    private static void handleSqlChange(AbstractSQLChange sqlChange, Map<String, Set<IndexInfo>> result) {
        String sql = sqlChange.getSql();
        if (isBlank(sql))
            return;

        for (String statement : splitSqlStatements(sql)) {
            if (isBlank(statement))
                continue;
            if (handleCreateIndexStatement(statement, result))
                continue;
            handleDropIndexStatement(statement, result);
        }
    }

    private static boolean handleCreateIndexStatement(String statement, Map<String, Set<IndexInfo>> result) {
        Matcher matcher = CREATE_INDEX_STATEMENT.matcher(statement);
        if (!matcher.find())
            return false;

        boolean unique = matcher.group(1) != null;
        String indexName = normalizeIdentifier(matcher.group(2));
        String tableName = normalizeIdentifier(matcher.group(3));

        int columnStart = findNextCharOutsideQuotes(statement, matcher.end(), '(');
        if (columnStart < 0)
            return true;
        int columnEnd = findMatchingParen(statement, columnStart);
        if (columnEnd < 0)
            return true;

        String columnList = statement.substring(columnStart + 1, columnEnd);
        List<String> columns = parseSqlIndexColumns(columnList);
        if (isBlank(tableName) || columns.isEmpty())
            return true;

        add(result, tableName, new IndexInfo(unique ? UNIQUE_INDEX : INDEX, orUnknown(indexName), columns));
        return true;
    }

    private static void handleDropIndexStatement(String statement, Map<String, Set<IndexInfo>> result) {
        Matcher matcher = DROP_INDEX_STATEMENT.matcher(statement);
        if (!matcher.find())
            return;

        String indexName = normalizeIdentifier(matcher.group(1));
        String tableName = normalizeIdentifier(matcher.group(2));

        if (!isBlank(indexName)) {
            if (!isBlank(tableName)) {
                removeIndexByName(result, tableName, indexName);
            } else {
                removeIndexByNameAnyTable(result, indexName);
            }
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

    private static List<String> splitSqlStatements(String sql) {
        String cleaned = stripSqlComments(sql);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    if ((quote == '\'' || quote == '"') && i + 1 < cleaned.length()
                            && cleaned.charAt(i + 1) == quote) {
                        current.append(cleaned.charAt(i + 1));
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '[') {
                quote = ']';
                current.append(c);
                continue;
            }
            if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static String stripSqlComments(String sql) {
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("(?m)--.*?$", " ");
    }

    private static int findNextCharOutsideQuotes(String sql, int start, char target) {
        char quote = 0;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if ((quote == '\'' || quote == '"') && i + 1 < sql.length()
                            && sql.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '[') {
                quote = ']';
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingParen(String sql, int start) {
        if (start < 0 || start >= sql.length() || sql.charAt(start) != '(')
            return -1;
        int depth = 0;
        char quote = 0;

        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if ((quote == '\'' || quote == '"') && i + 1 < sql.length()
                            && sql.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '[') {
                quote = ']';
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    private static List<String> parseSqlIndexColumns(String columnList) {
        if (isBlank(columnList))
            return List.of();
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;

        for (int i = 0; i < columnList.length(); i++) {
            char c = columnList.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    if ((quote == '\'' || quote == '"') && i + 1 < columnList.length()
                            && columnList.charAt(i + 1) == quote) {
                        current.append(columnList.charAt(i + 1));
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '[') {
                quote = ']';
                current.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')') {
                if (depth > 0)
                    depth--;
                current.append(c);
                continue;
            }
            if (c == ',' && depth == 0) {
                addColumnToken(columns, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addColumnToken(columns, current.toString());
        return columns;
    }

    private static void addColumnToken(List<String> columns, String token) {
        if (isBlank(token))
            return;
        Matcher matcher = SIMPLE_COLUMN_PATTERN.matcher(token);
        if (!matcher.matches())
            return;
        String columnName = normalizeIdentifier(matcher.group(1));
        if (!isBlank(columnName)) {
            columns.add(columnName);
        }
    }

    private static String normalizeIdentifier(String identifier) {
        if (isBlank(identifier))
            return null;
        String cleaned = identifier.replace("\"", "")
                .replace("`", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
        int lastDot = cleaned.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < cleaned.length() - 1) {
            cleaned = cleaned.substring(lastDot + 1);
        }
        return cleaned.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String orUnknown(String s) {
        return isBlank(s) ? "<unnamed>" : s;
    }
}
