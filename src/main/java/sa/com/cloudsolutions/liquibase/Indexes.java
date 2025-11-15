package sa.com.cloudsolutions.liquibase;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// JSQLParser (already used in the project)
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

public class Indexes {

    public static final String UNIQUE_CONSTRAINT = "UNIQUE_CONSTRAINT";
    public static final String UNIQUE_INDEX = "UNIQUE_INDEX";
    public static final String PRIMARY_KEY = "PRIMARY_KEY";

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
        }
    /**
     * @param type PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, INDEX
     */
    private record Index(String type, String name, List<String> columns) {
        String display() {
            return name + " [" + String.join(",", columns) + "]";
        }
    }

    /**
     * Load Liquibase indexes from the given XML and return a table->indexes map.
     * The map value is a list of IndexInfo entries in declaration order (after includes and drops applied).
     */
    public static Map<String, List<IndexInfo>> load(File liquibaseXml) throws Exception {
        Map<String, List<Index>> raw = parseLiquibaseFile(liquibaseXml);
        Map<String, List<IndexInfo>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Index>> e : raw.entrySet()) {
            List<IndexInfo> list = new ArrayList<>();
            for (Index idx : e.getValue()) {
                list.add(new IndexInfo(idx.type, idx.name, new ArrayList<>(idx.columns)));
            }
            out.put(e.getKey(), list);
        }
        return out;
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

        Map<String, List<Index>> byTable = parseLiquibaseFile(file);
        // Display table-based with indentation: PK, UNIQUE, and other indexes
        byTable.keySet().stream().sorted().forEach(table -> {
            System.out.println(table);
            List<Index> indexes = byTable.get(table);
            // PK
            indexes.stream().filter(i -> PRIMARY_KEY.equals(i.type))
                    .forEach(i -> System.out.println("  PK: " + i.display()));
            // UNIQUE (constraints or unique indexes)
            indexes.stream().filter(i -> UNIQUE_CONSTRAINT.equals(i.type) || UNIQUE_INDEX.equals(i.type))
                    .forEach(i -> System.out.println("  UNIQUE: " + i.display()));
            // Other indexes
            indexes.stream().filter(i -> "INDEX".equals(i.type))
                    .forEach(i -> System.out.println("  INDEX: " + i.display()));
        });

    }

    private static Map<String, List<Index>> parseLiquibaseFile(File file) throws Exception {
        return parseLiquibaseFile(file, new HashSet<>());
    }

    private static Map<String, List<Index>> parseLiquibaseFile(File file, Set<String> visited) throws Exception {
        String canonical = file.getCanonicalPath();
        if (visited.contains(canonical)) {
            return Map.of();
        }
        visited.add(canonical);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);
        doc.getDocumentElement().normalize();

        Map<String, List<Index>> result = new LinkedHashMap<>();

        handleCreateIndex(doc, result);
        handleUniqueConstraints(doc, "addUniqueConstraint", "name", result, UNIQUE_CONSTRAINT);
        handleUniqueConstraints(doc, "addPrimaryKey", "pkName", result, PRIMARY_KEY);

        handleCreateTable(doc, result);
        handleAddColumn(doc, result);

        // includes (<include>)
        handleIncludes(file, visited, doc, result);


        handleIncludeAll(file, visited, doc, result);

        // Raw <sql> blocks: process after includes so DROP statements can remove previously added indexes
        handleSqlElements(doc, result);

        handleDropIndex(doc, result);

        handleDropUniques(doc, result);

        handleDropPrimary(doc, result);

        return result;
    }

    private static void handleDropPrimary(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "dropPrimaryKey")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            if (!isBlank(table)) {
                removePrimaryKey(result, table, name);
            }
        }
    }

    private static void handleDropUniques(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "dropUniqueConstraint")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table)) {
                removeUniqueConstraint(result, table, name, cols);
            }
        }
    }

    private static void handleDropIndex(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "dropIndex")) {
            String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(name)) {
                removeIndexByName(result, table, name);
            } else if (!isBlank(table) && !cols.isEmpty()) {
                removeIndexByColumns(result, table, cols);
            }
        }
    }

    private static void handleIncludeAll(File file, Set<String> visited, Document doc, Map<String, List<Index>> result) throws Exception {
        for (Element el : elementsByLocalName(doc, "includeAll")) {
            String dir = firstNonEmpty(el.getAttribute("path"), el.getAttribute("relativePath"));
            if (isBlank(dir)) continue;
            File baseDir = resolveRelative(file, dir);
            if (!baseDir.exists() || !baseDir.isDirectory()) {
                // Fallback: try again after stripping 'db/changelog/' (and Windows variant) from the path
                String stripped = dir;
                if (dir.contains("db/changelog/")) {
                    stripped = dir.replace("db/changelog/", "");
                } else if (dir.contains("db\\changelog\\")) {
                    stripped = dir.replace("db\\changelog\\", "");
                }
                if (!Objects.equals(stripped, dir)) {
                    File retryDir = resolveRelative(file, stripped);
                    if (retryDir.exists() && retryDir.isDirectory()) {
                        baseDir = retryDir;
                    } else {
                        System.err.println("Warning: includeAll path not found or not a directory (after fallback): " + baseDir.getPath() + " | retried: " + retryDir.getPath());
                        continue;
                    }
                } else {
                    System.err.println("Warning: includeAll path not found or not a directory: " + baseDir.getPath());
                    continue;
                }
            }
            File[] files = baseDir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                Map<String, List<Index>> childMap = parseLiquibaseFile(f, visited);
                merge(result, childMap);
            }
        }
    }

    private static void handleIncludes(File file, Set<String> visited, Document doc, Map<String, List<Index>> result) throws Exception {
        for (Element el : elementsByLocalName(doc, "include")) {
            String ref = firstNonEmpty(el.getAttribute("file"), el.getAttribute("path"));
            if (isBlank(ref)) continue;
            File child = resolveRelative(file, ref);
            if (!child.exists()) {
                // Fallback: try again after stripping 'db/changelog/' (and Windows variant) from the path
                String stripped = ref;
                if (ref.contains("db/changelog/")) {
                    stripped = ref.replace("db/changelog/", "");
                } else if (ref.contains("db\\changelog\\")) {
                    stripped = ref.replace("db\\changelog\\", "");
                }
                if (!Objects.equals(stripped, ref)) {
                    File retry = resolveRelative(file, stripped);
                    if (retry.exists()) {
                        child = retry;
                    } else {
                        System.err.println("Warning: included file not found (after fallback): " + child.getPath() + " | retried: " + retry.getPath());
                        continue;
                    }
                } else {
                    System.err.println("Warning: included file not found: " + child.getPath());
                    continue;
                }
            }
            Map<String, List<Index>> childMap = parseLiquibaseFile(child, visited);
            merge(result, childMap);
        }
    }

    private static void handleAddColumn(Document doc, Map<String, List<Index>> result) {
        // addColumn with inline constraints
        for (Element el : elementsByLocalName(doc, "addColumn")) {
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            if (isBlank(table)) continue;
            NodeList columns = el.getElementsByTagName("column");
            for (int i = 0; i < columns.getLength(); i++) {
                Node n = columns.item(i);
                if (n instanceof Element colEl) {
                    String colName = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                    NodeList constraintsList = colEl.getElementsByTagName("constraints");
                    if (constraintsList.getLength() > 0) {
                        Element c = (Element) constraintsList.item(0);
                        if ("true".equalsIgnoreCase(c.getAttribute("unique"))) {
                            String uniqueName = c.getAttribute("uniqueConstraintName");
                            if (isBlank(uniqueName)) uniqueName = null;
                            // Unique per column
                            List<String> cols = new ArrayList<>();
                            cols.add(colName);
                            add(result, table, new Index(UNIQUE_CONSTRAINT, orUnknown(uniqueName), cols));
                        }
                    }
                }
            }
        }
    }

    private static void handleCreateTable(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "createTable")) {
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            if (isBlank(table)) continue;
            NodeList columns = el.getElementsByTagName("column");
            List<String> pkCols = new ArrayList<>();
            String pkName = null;
            List<Index> uniquesInline = new ArrayList<>();
            for (int i = 0; i < columns.getLength(); i++) {
                Node n = columns.item(i);
                if (n instanceof Element colEl) {
                    String colName = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                    NodeList constraintsList = colEl.getElementsByTagName("constraints");
                    if (constraintsList.getLength() > 0) {
                        Element c = (Element) constraintsList.item(0);
                        // primary key
                        if ("true".equalsIgnoreCase(c.getAttribute("primaryKey"))) {
                            pkCols.add(colName);
                            String n1 = c.getAttribute("primaryKeyName");
                            if (!isBlank(n1)) pkName = n1;
                        }
                        // unique
                        if ("true".equalsIgnoreCase(c.getAttribute("unique"))) {
                            String uniqueName = c.getAttribute("uniqueConstraintName");
                            uniquesInline.add(new Index(UNIQUE_CONSTRAINT, orUnknown(uniqueName), List.of(colName)));
                        }
                    }
                }
            }
            if (!pkCols.isEmpty()) {
                add(result, table, new Index(PRIMARY_KEY, orUnknown(pkName), pkCols));
            }
            for (Index idx : uniquesInline) add(result, table, idx);
        }
    }

    private static void handleUniqueConstraints(Document doc, String addUniqueConstraint, String name, Map<String, List<Index>> result, String uniqueConstraint) {
        for (Element el : elementsByLocalName(doc, addUniqueConstraint)) {
            String constraintName = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute(name));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new Index(uniqueConstraint, orUnknown(constraintName), cols));
            }
        }
    }

    private static void handleCreateIndex(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "createIndex")) {
            String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            boolean unique = "true".equalsIgnoreCase(el.getAttribute("unique"));
            List<String> cols = extractColumns(el);
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new Index(unique ? UNIQUE_INDEX : "INDEX", orUnknown(name), cols));
            }
        }
    }

    /**
     * Handle all <sql> elements in the document: split into statements and process create-index statements.
     * Skips any <sql> that appears under a <rollback> block.
     */
    private static void handleSqlElements(Document doc, Map<String, List<Index>> result) {
        for (Element el : elementsByLocalName(doc, "sql")) {
            if (isInRollback(el)) continue;
            String sqlText = el.getTextContent();
            if (isBlank(sqlText)) continue;
            try {
                net.sf.jsqlparser.statement.Statements stmts = CCJSqlParserUtil.parseStatements(sqlText);
                if (stmts != null && stmts.getStatements() != null && !stmts.getStatements().isEmpty()) {
                    for (Statement st : stmts.getStatements()) {
                        if (st == null) continue;
                        String s = st.toString();
                        processCreateIndexSql(result, s);
                        processDropSql(result, s);
                    }
                    continue;
                }
            } catch (Exception ignore) {
                // Fall back to manual splitting below
            }
            for (String part : sqlText.split(";")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                processCreateIndexSql(result, s);
                processDropSql(result, s);
            }
        }
    }

    /**
     * Return true if the provided node is under a <rollback> element.
     */
    private static boolean isInRollback(Node node) {
        Node p = node;
        while (p != null) {
            if (p instanceof Element pe) {
                String ln = pe.getLocalName();
                String nn = pe.getNodeName();
                if ("rollback".equalsIgnoreCase(nn) || "rollback".equalsIgnoreCase(ln)) {
                    return true;
                }
            }
            p = p.getParentNode();
        }
        return false;
    }

    /**
     * Parse a single SQL statement text and add index info if it is a CREATE INDEX.
     * Supports vendor-specific options like CONCURRENTLY (PostgreSQL), ONLINE (Oracle), and IF NOT EXISTS.
     */
    private static void processCreateIndexSql(Map<String, List<Index>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        // Remove trailing semicolon
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        // Strip line breaks for regex simplicity
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        // Quick guard
        if (!normalized.toUpperCase().startsWith("CREATE")) return;
        if (!normalized.toUpperCase().contains("INDEX")) return;

        // Remove vendor-specific noise that can confuse simple regex
        String cleaned = normalized
                .replaceAll("(?i)\\bCONCURRENTLY\\b", " ")
                .replaceAll("(?i)\\bONLINE\\b", " ")
                .replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", " ")
                .replaceAll("\\s+", " ").trim();

        // Pattern: CREATE [UNIQUE] INDEX indexName ON tableName (col1 [ASC|DESC], col2, ...)
        Pattern p = Pattern.compile(
                "(?i)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+([\"`\\w.]+)\\s+ON\\s+([\"`\\w.]+)\\s*\\((.*?)\\)"
        );
        Matcher m = p.matcher(cleaned);
        if (!m.find()) {
            // As a fallback, try using JSQLParser to normalize then retry regex
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
        String indexName = unquote(m.group(2));
        String tableName = unquote(m.group(3));
        String colsCsv = m.group(4);
        if (isBlank(tableName) || isBlank(colsCsv)) return;

        List<String> cols = Arrays.stream(colsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(c -> {
                    // Remove ordering and other column-level options
                    String base = c.replaceAll("(?i)\\bASC\\b|\\bDESC\\b", "");
                    // Remove function wrappers or expressions: keep identifier when it's simple
                    // For safety, take content before first space after removing ASC/DESC
                    base = base.trim();
                    int sp = base.indexOf(' ');
                    if (sp > 0) base = base.substring(0, sp);
                    base = base.trim();
                    // Remove quotes
                    base = unquote(base);
                    // Remove table qualifier if present
                    int dot = base.lastIndexOf('.')
                            ;
                    if (dot >= 0) base = base.substring(dot + 1);
                    return base;
                })
                .filter(s -> !s.isEmpty())
                .toList();

        if (cols.isEmpty()) return;
        // Remove schema from table if qualified
        int dot = tableName.lastIndexOf('.');
        if (dot >= 0) tableName = tableName.substring(dot + 1);
        add(result, tableName, new Index(isUnique ? UNIQUE_INDEX : "INDEX", orUnknown(indexName), cols));
    }

    /**
     * Parse and apply raw SQL DROP statements affecting indexes/constraints.
     * Supports patterns:
     * - DROP INDEX [CONCURRENTLY] [IF EXISTS] indexName
     * - ALTER TABLE <table> DROP CONSTRAINT [IF EXISTS] <name>
     * - ALTER TABLE <table> DROP PRIMARY KEY
     */
    private static void processDropSql(Map<String, List<Index>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        String upper = normalized.toUpperCase();

        // Handle DROP INDEX ...
        if (upper.startsWith("DROP") && upper.contains("INDEX")) {
            String cleaned = normalized
                    .replaceAll("(?i)\\bCONCURRENTLY\\b", " ")
                    .replaceAll("(?i)\\bIF\\s+EXISTS\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            // Pattern: DROP INDEX indexName
            Matcher m = Pattern.compile("(?i)^DROP\\s+INDEX\\s+([\"`\\w.]+)").matcher(cleaned);
            if (m.find()) {
                String rawName = unquote(m.group(1));
                // For fully qualified names like schema.index, take the last token
                int dot = rawName.lastIndexOf('.');
                String idxName = dot >= 0 ? rawName.substring(dot + 1) : rawName;
                removeIndexByNameAnyTable(result, idxName);
            }
            return;
        }

        // Handle ALTER TABLE ... DROP ...
        if (upper.startsWith("ALTER TABLE")) {
            String cleaned = normalized.replaceAll("(?i)\\bIF\\s+EXISTS\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            // Extract table
            Matcher tm = Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([\"`\\w.]+)\\s+(.*)$").matcher(cleaned);
            if (tm.find()) {
                String tableName = unquote(tm.group(1));
                int dot = tableName.lastIndexOf('.');
                if (dot >= 0) tableName = tableName.substring(dot + 1);
                String rest = tm.group(2).trim();

                // ... DROP PRIMARY KEY
                Matcher dpk = Pattern.compile("(?i)^DROP\\s+PRIMARY\\s+KEY(.*)$").matcher(rest);
                if (dpk.find()) {
                    // Some dialects may specify USING INDEX <name>, but removal by table is fine
                    removePrimaryKey(result, tableName, null);
                    return;
                }
                // ... DROP CONSTRAINT name
                Matcher dc = Pattern.compile("(?i)^DROP\\s+CONSTRAINT\\s+([\"`\\w.]+)(.*)$").matcher(rest);
                if (dc.find()) {
                    String rawName = unquote(dc.group(1));
                    int d2 = rawName.lastIndexOf('.');
                    String consName = d2 >= 0 ? rawName.substring(d2 + 1) : rawName;
                    // Try both unique constraint and primary key by this name on the table
                    removeUniqueConstraint(result, tableName, consName, null);
                    removePrimaryKey(result, tableName, consName);
                    return;
                }
            }
        }
    }

    private static void removeIndexByNameAnyTable(Map<String, List<Index>> map, String name) {
        if (isBlank(name) || map.isEmpty()) return;
        List<String> emptyTables = new ArrayList<>();
        for (Map.Entry<String, List<Index>> e : map.entrySet()) {
            List<Index> list = e.getValue();
            if (list == null) continue;
            String target = name.toLowerCase();
            list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
            if (list.isEmpty()) emptyTables.add(e.getKey());
        }
        for (String t : emptyTables) map.remove(t);
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        // Remove surrounding quotes `name`, "name"
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("`") && s.endsWith("`"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void add(Map<String, List<Index>> map, String table, Index index) {
        map.computeIfAbsent(table, k -> new ArrayList<>()).add(index);
    }

    private static void merge(Map<String, List<Index>> target, Map<String, List<Index>> source) {
        for (Map.Entry<String, List<Index>> e : source.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    // Removal helpers for drop operations
    private static void removeIndexByName(Map<String, List<Index>> map, String table, String name) {
        if (isBlank(table) || isBlank(name)) return;
        List<Index> list = map.get(table);
        if (list == null) return;
        String target = name.toLowerCase();
        list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeIndexByColumns(Map<String, List<Index>> map, String table, List<String> cols) {
        if (isBlank(table) || cols == null || cols.isEmpty()) return;
        List<Index> list = map.get(table);
        if (list == null) return;
        Set<String> target = new HashSet<>(cols.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && sameColumnsIgnoreCase(i.columns, target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeUniqueConstraint(Map<String, List<Index>> map, String table, String name, List<String> cols) {
        List<Index> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            String target = name.toLowerCase();
            list.removeIf(i -> UNIQUE_CONSTRAINT.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(target));
        } else if (cols != null && !cols.isEmpty()) {
            Set<String> target = new HashSet<>(cols.stream().map(String::toLowerCase).collect(Collectors.toSet()));
            list.removeIf(i -> UNIQUE_CONSTRAINT.equals(i.type) && sameColumnsIgnoreCase(i.columns, target));
        }
        if (list.isEmpty()) map.remove(table);
    }

    private static void removePrimaryKey(Map<String, List<Index>> map, String table, String name) {
        List<Index> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            String target = name.toLowerCase();
            list.removeIf(i -> PRIMARY_KEY.equals(i.type) && i.name != null && i.name.equalsIgnoreCase(target));
        } else {
            list.removeIf(i -> PRIMARY_KEY.equals(i.type));
        }
        if (list.isEmpty()) map.remove(table);
    }

    private static boolean sameColumnsIgnoreCase(List<String> cols, Set<String> targetLower) {
        if (cols == null) return false;
        Set<String> self = cols.stream().map(String::toLowerCase).collect(Collectors.toSet());
        return self.equals(targetLower);
    }

    private static File resolveRelative(File base, String ref) {
        File candidate = new File(ref);
        if (candidate.isAbsolute()) return candidate;
        File parent = base.getParentFile();
        return new File(parent, ref);
    }

    private static List<Element> elementsByLocalName(Document doc, String localName) {
        // Handle both namespaced and non-namespaced tags
        NodeList all = doc.getElementsByTagName("*");
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element el) {
                String ln = el.getLocalName();
                String nn = el.getNodeName();
                if (localName.equals(nn) || localName.equals(ln)) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    private static List<String> extractColumns(Element createIndexEl) {
        List<String> cols = new ArrayList<>();
        // Child <column name="..."/> tags
        NodeList columnNodes = createIndexEl.getElementsByTagName("column");
        for (int i = 0; i < columnNodes.getLength(); i++) {
            Node n = columnNodes.item(i);
            if (n instanceof Element colEl) {
                String name = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                if (!isBlank(name)) cols.add(name.trim());
            }
        }
        // Some forms use a comma-separated attribute
        String attrCols = createIndexEl.getAttribute("columns");
        if (!isBlank(attrCols)) {
            cols.addAll(splitColumns(attrCols));
        }
        return cols;
    }

    private static List<String> splitColumns(String csv) {
        if (isBlank(csv)) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String textOfFirstChild(Element parent, String childName) {
        NodeList list = parent.getElementsByTagName(childName);
        if (list.getLength() == 0) return null;
        Node n = list.item(0);
        return n.getTextContent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonEmpty(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
    }

    private static String orUnknown(String s) {
        return isBlank(s) ? "<unnamed>" : s;
    }
}
