package sa.com.cloudsolutions.liquibase;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.xml.sax.Attributes;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("java:S106")
public class Indexes {

    public static final String UNIQUE_CONSTRAINT = "UNIQUE_CONSTRAINT";
    public static final String UNIQUE_INDEX = "UNIQUE_INDEX";
    public static final String PRIMARY_KEY = "PRIMARY_KEY";
    public static final String INDEX = "INDEX";

    public record IndexInfo(String type, String name, List<String> columns) {
        @Override
        public String toString() {
            return type + ";" + name + ";" + String.join(",", columns);
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexInfo other)) return false;
            return Objects.equals(this.type, other.type)
                    && Objects.equals(this.name, other.name)
                    && Objects.equals(this.columns, other.columns);
        }
        @Override
        public int hashCode() {
            return Objects.hash(type, name, columns);
        }
    }

    public static Map<String, Set<IndexInfo>> load(File liquibaseXml) throws Exception {
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
        byTable.keySet().stream().sorted().forEach(table -> {
            System.out.println(table);
            Set<IndexInfo> indexes = byTable.get(table);
            indexes.stream().filter(i -> PRIMARY_KEY.equals(i.type))
                .forEach(i -> System.out.println("  PK: " + i));
            indexes.stream().filter(i -> UNIQUE_CONSTRAINT.equals(i.type) || UNIQUE_INDEX.equals(i.type))
                .forEach(i -> System.out.println("  UNIQUE: " + i));
            indexes.stream().filter(i -> INDEX.equals(i.type))
                .forEach(i -> System.out.println("  INDEX: " + i));
        });

    }

    private static Map<String, Set<IndexInfo>> parseLiquibaseFile(File file) throws Exception {
        Map<String, Set<IndexInfo>> result = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        LiquibaseParser.parseLiquibaseFileInto(file, visited, result, LiquibaseIndexHandler::new);
        return result;
    }

    private static class LiquibaseIndexHandler extends LiquibaseParser {
        private final Map<String, Set<IndexInfo>> result;

        // Context
        private String currentTable = null;
        private String currentColumn = null;
        private boolean isCreateTable = false;
        private boolean isAddColumn = false;

        // Additional state for createIndex parsing
        private boolean inCreateIndex = false;
        private String createIndexName = null;
        private String createIndexTable = null;
        private boolean createIndexUnique = false;
        private List<String> createIndexColumns = new ArrayList<>();

        public LiquibaseIndexHandler(File currentFile, Set<String> visited, Map<String, Set<IndexInfo>> result) {
            super(currentFile, visited);
            this.result = result;
        }

        @Override
        protected void parseChild(File file) throws Exception {
             LiquibaseParser.parseLiquibaseFileInto(file, visited, result, LiquibaseIndexHandler::new);
        }

        @Override
        protected void handleElement(String ln, Attributes atts) {
            if ("createIndex".equalsIgnoreCase(ln)) {
                inCreateIndex = true;
                createIndexName = firstNonEmpty(atts.getValue("indexName"), atts.getValue("name"));
                createIndexTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
                createIndexUnique = "true".equalsIgnoreCase(atts.getValue("unique"));
                createIndexColumns = new ArrayList<>();
                String attrCols = atts.getValue("columns");
                if (!isBlank(attrCols)) {
                    createIndexColumns.addAll(splitColumnsPreserveOrder(attrCols));
                }
            } else if ("column".equalsIgnoreCase(ln)) {
                if (inCreateIndex) {
                    String name = atts.getValue("name");
                    if (!isBlank(name)) createIndexColumns.add(name);
                } else if (isCreateTable || isAddColumn) {
                    currentColumn = atts.getValue("name");
                }
            } else if ("createTable".equalsIgnoreCase(ln)) {
                isCreateTable = true;
                currentTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            } else if ("addColumn".equalsIgnoreCase(ln)) {
                isAddColumn = true;
                currentTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            } else if ("constraints".equalsIgnoreCase(ln)) {
                if ((isCreateTable || isAddColumn) && !isBlank(currentTable) && !isBlank(currentColumn)) {
                    if ("true".equalsIgnoreCase(atts.getValue("primaryKey"))) {
                        String pkName = atts.getValue("primaryKeyName");
                        add(result, currentTable, new IndexInfo(PRIMARY_KEY, orUnknown(pkName), List.of(currentColumn)));
                    }
                    if ("true".equalsIgnoreCase(atts.getValue("unique"))) {
                        String uniqueName = atts.getValue("uniqueConstraintName");
                        add(result, currentTable, new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(uniqueName), List.of(currentColumn)));
                    }
                }
            } else if ("addUniqueConstraint".equalsIgnoreCase(ln)) {
                handleAddUniqueConstraintElement(atts);
            } else if ("addPrimaryKey".equalsIgnoreCase(ln)) {
                handleAddPrimaryKeyElement(atts);
            } else if ("dropIndex".equalsIgnoreCase(ln)) {
                handleDropIndexElement(atts);
            } else if ("dropUniqueConstraint".equalsIgnoreCase(ln)) {
                handleDropUniqueConstraintElement(atts);
            } else if ("dropPrimaryKey".equalsIgnoreCase(ln)) {
                handleDropPrimaryKeyElement(atts);
            }
        }

        @Override
        protected void handleEndElement(String ln) {
            if ("createIndex".equalsIgnoreCase(ln)) {
                if (inCreateIndex) {
                    if (!isBlank(createIndexTable) && !createIndexColumns.isEmpty()) {
                         add(result, createIndexTable, new IndexInfo(createIndexUnique ? UNIQUE_INDEX : INDEX, orUnknown(createIndexName), new ArrayList<>(createIndexColumns)));
                    }
                    inCreateIndex = false;
                    createIndexName = null;
                    createIndexTable = null;
                    createIndexColumns = null;
                }
            } else if ("createTable".equalsIgnoreCase(ln)) {
                isCreateTable = false;
                currentTable = null;
            } else if ("addColumn".equalsIgnoreCase(ln)) {
                isAddColumn = false;
                currentTable = null;
            } else if ("column".equalsIgnoreCase(ln)) {
                if (!inCreateIndex) {
                    currentColumn = null;
                }
            }
        }

        @Override
        protected void handleSqlText(String sqlText) {
            if (isBlank(sqlText)) return;
            try {
                net.sf.jsqlparser.statement.Statements stmts = CCJSqlParserUtil.parseStatements(sqlText);
                if (stmts != null && stmts.getStatements() != null && !stmts.getStatements().isEmpty()) {
                    for (Statement st : stmts.getStatements()) {
                        if (st == null) continue;
                        String s = st.toString();
                        processCreateIndexSql(result, s);
                        processDropSql(result, s);
                    }
                    return;
                }
            } catch (Exception ignore) {
            }
            for (String part : sqlText.split(";")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                processCreateIndexSql(result, s);
                processDropSql(result, s);
            }
        }

        // --- Handlers ---
        private void handleAddUniqueConstraintElement(Attributes atts) {
            String constraintName = firstNonEmpty(atts.getValue("constraintName"), atts.getValue("name"));
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            List<String> cols = splitColumnsPreserveOrder(atts.getValue("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(constraintName), cols));
            }
        }

        private void handleAddPrimaryKeyElement(Attributes atts) {
            String pkName = firstNonEmpty(atts.getValue("constraintName"), atts.getValue("pkName"));
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            List<String> cols = splitColumnsPreserveOrder(atts.getValue("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new IndexInfo(PRIMARY_KEY, orUnknown(pkName), cols));
            }
        }

        private void handleDropIndexElement(Attributes atts) {
            String name = firstNonEmpty(atts.getValue("indexName"), atts.getValue("name"));
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            List<String> cols = splitColumnsPreserveOrder(atts.getValue("columnNames"));
            if (!isBlank(name)) {
                removeIndexByName(result, table, name);
            } else if (!isBlank(table) && !cols.isEmpty()) {
                removeIndexByColumnsOrdered(result, table, cols);
            }
        }

        private void handleDropUniqueConstraintElement(Attributes atts) {
            String name = firstNonEmpty(atts.getValue("constraintName"), atts.getValue("name"));
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            List<String> cols = splitColumnsPreserveOrder(atts.getValue("columnNames"));
            if (!isBlank(table)) {
                removeUniqueConstraint(result, table, name, cols);
            }
        }

        private void handleDropPrimaryKeyElement(Attributes atts) {
            String name = firstNonEmpty(atts.getValue("constraintName"), atts.getValue("pkName"));
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            if (!isBlank(table)) {
                removePrimaryKey(result, table, name);
            }
        }
    }

    // --- Static Logic ---

    private static void processCreateIndexSql(Map<String, Set<IndexInfo>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        if (!normalized.toUpperCase().startsWith("CREATE")) return;
        if (!normalized.toUpperCase().contains(INDEX)) return;

        String cleaned = normalized
            .replaceAll("(?i)\\bCONCURRENTLY\\b", " ")
            .replaceAll("(?i)\\bONLINE\\b", " ")
            .replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", " ")
            .replaceAll("\\s+", " ").trim();

        Pattern p = Pattern.compile("(?i)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+([\"`\\w.]+)\\s+ON\\s+([\"`\\w.]+)\\s*\\((.*?)\\)");
        Matcher m = p.matcher(cleaned);
        if (!m.find()) {
            try {
                Statement st = CCJSqlParserUtil.parse(cleaned);
                String s2 = st.toString();
                m = p.matcher(s2);
                if (!m.find()) return;
            } catch (Exception ignore) {
                return;
            }
        }
        boolean isUnique = m.group(1) != null && !m.group(1).isBlank();
        String indexName = LiquibaseParser.unquote(m.group(2));
        String tableName = LiquibaseParser.unquote(m.group(3));
        String colsCsv = m.group(4);
        if (isBlank(tableName) || isBlank(colsCsv)) return;

        List<String> cols = Arrays.stream(colsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(c -> {
                String base = c.replaceAll("(?i)\\bASC\\b|\\bDESC\\b", "").trim();
                if (base.contains("(") || base.contains(")")) return "";
                int sp = base.indexOf(' ');
                if (sp > 0) base = base.substring(0, sp).trim();
                base = LiquibaseParser.unquote(base);
                int dot = base.lastIndexOf('.');
                if (dot >= 0) base = base.substring(dot + 1);
                return base.trim();
            })
            .filter(s -> !s.isEmpty())
            .toList();

        if (cols.isEmpty()) return;
        int dot = tableName.lastIndexOf('.');
        if (dot >= 0) tableName = tableName.substring(dot + 1);
        add(result, tableName, new IndexInfo(isUnique ? UNIQUE_INDEX : INDEX, orUnknown(indexName), cols));
    }

    private static void processDropSql(Map<String, Set<IndexInfo>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        String upper = normalized.toUpperCase();

        if (upper.startsWith("DROP") && upper.contains(INDEX)) {
            String cleaned = normalized
                .replaceAll("(?i)\\bCONCURRENTLY\\b", " ")
                .replaceAll("(?i)\\bIF\\s+EXISTS\\b", " ")
                .replaceAll("\\s+", " ").trim();
            Matcher m = Pattern.compile("(?i)^DROP\\s+INDEX\\s+([\"`\\w.]+)").matcher(cleaned);
            if (m.find()) {
                String rawName = LiquibaseParser.unquote(m.group(1));
                int dot = rawName.lastIndexOf('.');
                String idxName = dot >= 0 ? rawName.substring(dot + 1) : rawName;
                removeIndexByNameAnyTable(result, idxName);
            }
            return;
        }

        if (upper.startsWith("ALTER TABLE")) {
            String cleaned = normalized.replaceAll("(?i)\\bIF\\s+EXISTS\\b", " ").replaceAll("\\s+", " ").trim();
            Matcher tm = Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([\"`\\w.]+)\\s+(.*)$").matcher(cleaned);
            if (tm.find()) {
                String tableName = LiquibaseParser.unquote(tm.group(1));
                int dot = tableName.lastIndexOf('.');
                if (dot >= 0) tableName = tableName.substring(dot + 1);
                String rest = tm.group(2).trim();

                Matcher dpk = Pattern.compile("(?i)^DROP\\s+PRIMARY\\s+KEY(.*)$").matcher(rest);
                if (dpk.find()) {
                    removePrimaryKey(result, tableName, null);
                    return;
                }
                Matcher dc = Pattern.compile("(?i)^DROP\\s+CONSTRAINT\\s+([\"`\\w.]+)(.*)$").matcher(rest);
                if (dc.find()) {
                    String rawName = LiquibaseParser.unquote(dc.group(1));
                    int d2 = rawName.lastIndexOf('.');
                    String consName = d2 >= 0 ? rawName.substring(d2 + 1) : rawName;
                    removeUniqueConstraint(result, tableName, consName, null);
                    removePrimaryKey(result, tableName, consName);
                }
            }
        }
    }

    private static void removeIndexByNameAnyTable(Map<String, Set<IndexInfo>> map, String name) {
        if (isBlank(name) || map.isEmpty()) return;
        List<String> emptyTables = new ArrayList<>();
        for (Map.Entry<String, Set<IndexInfo>> e : map.entrySet()) {
            Set<IndexInfo> list = e.getValue();
            if (list == null) continue;
            String target = name.toLowerCase();
            list.removeIf(i -> (INDEX.equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
            if (list.isEmpty()) emptyTables.add(e.getKey());
        }
        for (String t : emptyTables) map.remove(t);
    }

    private static void add(Map<String, Set<IndexInfo>> map, String table, IndexInfo index) {
        map.computeIfAbsent(table, k -> new HashSet<>()).add(index);
    }

    private static void removeIndexByName(Map<String, Set<IndexInfo>> map, String table, String name) {
        if (isBlank(table) || isBlank(name)) return;
        Set<IndexInfo> list = map.get(table);
        if (list == null) return;
        String target = name.toLowerCase();
        list.removeIf(i -> (INDEX.equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeIndexByColumnsOrdered(Map<String, Set<IndexInfo>> map, String table, List<String> cols) {
        if (isBlank(table) || cols == null || cols.isEmpty()) return;
        Set<IndexInfo> list = map.get(table);
        if (list == null) return;
        List<String> target = cols.stream().map(String::toLowerCase).toList();
        list.removeIf(i -> (INDEX.equals(i.type) || UNIQUE_INDEX.equals(i.type)) && orderedColumnsMatch(i.columns, target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeUniqueConstraint(Map<String, Set<IndexInfo>> map, String table, String name, List<String> cols) {
        Set<IndexInfo> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            String target = name.toLowerCase();
            list.removeIf(i -> UNIQUE_CONSTRAINT.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(target));
        } else if (cols != null && !cols.isEmpty()) {
            List<String> lower = cols.stream().map(String::toLowerCase).toList();
            list.removeIf(i -> UNIQUE_CONSTRAINT.equals(i.type) && orderedColumnsMatch(i.columns, lower));
        }
        if (list.isEmpty()) map.remove(table);
    }

    private static void removePrimaryKey(Map<String, Set<IndexInfo>> map, String table, String name) {
        Set<IndexInfo> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            String target = name.toLowerCase();
            list.removeIf(i -> PRIMARY_KEY.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(target));
        } else {
            list.removeIf(i -> PRIMARY_KEY.equals(i.type));
        }
        if (list.isEmpty()) map.remove(table);
    }

    private static boolean orderedColumnsMatch(List<String> cols, List<String> targetLowerOrdered) {
        if (cols == null || cols.size() != targetLowerOrdered.size()) return false;
        for (int i = 0; i < cols.size(); i++) {
            if (!cols.get(i).equalsIgnoreCase(targetLowerOrdered.get(i))) return false;
        }
        return true;
    }

    // Helpers
    private static boolean isBlank(String s) { return LiquibaseParser.isBlank(s); }
    private static String firstNonEmpty(String a, String b) { return LiquibaseParser.firstNonEmpty(a, b); }
    private static String orUnknown(String s) { return LiquibaseParser.orUnknown(s); }
}
