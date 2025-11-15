package sa.com.cloudsolutions.liquibase;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;
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
        // Explicit equals to honor ordered column list
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexInfo other)) return false;
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
     * The map value is a list of IndexInfo entries in declaration order (after includes and drops applied).
     */
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
            indexes.stream().filter(i -> "INDEX".equals(i.type))
                .forEach(i -> System.out.println("  INDEX: " + i));
        });

    }

    private static Map<String, Set<IndexInfo>> parseLiquibaseFile(File file) throws Exception {
        Map<String, Set<IndexInfo>> result = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        parseLiquibaseFileInto(file, visited, result);
        return result;
    }

    // Mutating parser to allow includes to modify existing state
    private static void parseLiquibaseFileInto(File file, Set<String> visited, Map<String, Set<IndexInfo>> result) throws Exception {
        String canonical = file.getCanonicalPath();
        if (visited.contains(canonical)) {
            return; // already processed (prevent circular includes)
        }
        visited.add(canonical);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el) {
                processTopLevelElement(el, file, visited, result);
            }
        }
    }

    private static void processTopLevelElement(Element el, File currentFile, Set<String> visited, Map<String, Set<IndexInfo>> result) throws Exception {
        String ln = localName(el);
        if ("include".equalsIgnoreCase(ln)) {
            handleIncludeElement(el, currentFile, visited, result);
            return;
        }
        if ("includeAll".equalsIgnoreCase(ln)) {
            handleIncludeAllElement(el, currentFile, visited, result);
            return;
        }
        if ("changeSet".equalsIgnoreCase(ln)) {
            processChangeSet(el, currentFile, visited, result);
            return;
        }
        // Allow index-related tags at root level (rare but possible)
        processIndexRelatedElement(el, result);
    }

    private static void processChangeSet(Element changeSetEl, File currentFile, Set<String> visited, Map<String, Set<IndexInfo>> result) throws Exception {
        NodeList nodes = changeSetEl.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el) {
                if (isInRollback(el)) continue; // skip anything in rollback
                String ln = localName(el);
                if ("include".equalsIgnoreCase(ln)) {
                    handleIncludeElement(el, currentFile, visited, result);
                } else if ("includeAll".equalsIgnoreCase(ln)) {
                    handleIncludeAllElement(el, currentFile, visited, result);
                } else {
                    processIndexRelatedElement(el, result);
                }
            }
        }
    }

    private static void processIndexRelatedElement(Element el, Map<String, Set<IndexInfo>> result) {
        String ln = localName(el);
        switch (ln.toLowerCase()) {
            case "createindex" -> handleCreateIndexElement(el, result);
            case "adduniqueconstraint" -> handleAddUniqueConstraintElement(el, result);
            case "addprimarykey" -> handleAddPrimaryKeyElement(el, result);
            case "createtable" -> handleCreateTableElement(el, result);
            case "addcolumn" -> handleAddColumnElement(el, result);
            case "dropindex" -> handleDropIndexElement(el, result);
            case "dropuniqueconstraint" -> handleDropUniqueConstraintElement(el, result);
            case "dropprimarykey" -> handleDropPrimaryKeyElement(el, result);
            case "sql" -> handleSqlElement(el, result);
            default -> {
                // ignore others
            }
        }
    }

    // --- Include handling (inline to preserve ordering) ---
    private static void handleIncludeElement(Element el, File currentFile, Set<String> visited, Map<String, Set<IndexInfo>> result) throws Exception {
        String ref = firstNonEmpty(el.getAttribute("file"), el.getAttribute("path"));
        if (isBlank(ref)) return;
        File child = resolveRelative(currentFile, ref);
        if (!child.exists()) {
            String stripped = ref;
            if (ref.contains("db/changelog/")) {
                stripped = ref.replace("db/changelog/", "");
            } else if (ref.contains("db\\changelog\\")) {
                stripped = ref.replace("db\\changelog\\", "");
            }
            if (!Objects.equals(stripped, ref)) {
                File retry = resolveRelative(currentFile, stripped);
                if (retry.exists()) {
                    child = retry;
                } else {
                    System.err.println("Warning: included file not found (after fallback): " + child.getPath());
                    return;
                }
            } else {
                System.err.println("Warning: included file not found: " + child.getPath());
                return;
            }
        }
        // Stream parse into current result so drops inside child can affect prior indexes
        parseLiquibaseFileInto(child, visited, result);
    }

    private static void handleIncludeAllElement(Element el, File currentFile, Set<String> visited, Map<String, Set<IndexInfo>> result) throws Exception {
        String dir = firstNonEmpty(el.getAttribute("path"), el.getAttribute("relativePath"));
        if (isBlank(dir)) return;
        File baseDir = resolveRelative(currentFile, dir);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            String stripped = dir;
            if (dir.contains("db/changelog/")) {
                stripped = dir.replace("db/changelog/", "");
            } else if (dir.contains("db\\changelog\\")) {
                stripped = dir.replace("db\\changelog\\", "");
            }
            if (!Objects.equals(stripped, dir)) {
                File retryDir = resolveRelative(currentFile, stripped);
                if (retryDir.exists() && retryDir.isDirectory()) {
                    baseDir = retryDir;
                } else {
                    System.err.println("Warning: includeAll path not found or not a directory (after fallback): " + baseDir.getPath());
                    return;
                }
            } else {
                System.err.println("Warning: includeAll path not found or not a directory: " + baseDir.getPath());
                return;
            }
        }
        File[] files = baseDir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            parseLiquibaseFileInto(f, visited, result);
        }
    }

    // --- Element handlers ---
    private static void handleCreateIndexElement(Element el, Map<String, Set<IndexInfo>> result) {
        String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        boolean unique = "true".equalsIgnoreCase(el.getAttribute("unique"));
        List<String> cols = extractColumnsExactOrder(el);
        if (!isBlank(table) && !cols.isEmpty()) {
            add(result, table, new IndexInfo(unique ? UNIQUE_INDEX : "INDEX", orUnknown(name), cols));
        }
    }

    private static void handleAddUniqueConstraintElement(Element el, Map<String, Set<IndexInfo>> result) {
        String constraintName = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        List<String> cols = splitColumnsPreserveOrder(el.getAttribute("columnNames"));
        if (!isBlank(table) && !cols.isEmpty()) {
            add(result, table, new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(constraintName), cols));
        }
    }

    private static void handleAddPrimaryKeyElement(Element el, Map<String, Set<IndexInfo>> result) {
        String pkName = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        List<String> cols = splitColumnsPreserveOrder(el.getAttribute("columnNames"));
        if (!isBlank(table) && !cols.isEmpty()) {
            add(result, table, new IndexInfo(PRIMARY_KEY, orUnknown(pkName), cols));
        }
    }

    private static void handleCreateTableElement(Element el, Map<String, Set<IndexInfo>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        if (isBlank(table)) return;
        NodeList columns = el.getElementsByTagName("column");
        List<String> pkCols = new ArrayList<>();
        String pkName = null;
        Set<IndexInfo> uniquesInline = new HashSet<>();
        for (int i = 0; i < columns.getLength(); i++) {
            Node n = columns.item(i);
            if (n instanceof Element colEl) {
                String colName = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                NodeList constraintsList = colEl.getElementsByTagName("constraints");
                if (constraintsList.getLength() > 0) {
                    Element c = (Element) constraintsList.item(0);
                    if ("true".equalsIgnoreCase(c.getAttribute("primaryKey"))) {
                        pkCols.add(colName);
                        String n1 = c.getAttribute("primaryKeyName");
                        if (!isBlank(n1)) pkName = n1;
                    }
                    if ("true".equalsIgnoreCase(c.getAttribute("unique"))) {
                        String uniqueName = c.getAttribute("uniqueConstraintName");
                        uniquesInline.add(new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(uniqueName), List.of(colName)));
                    }
                }
            }
        }
        if (!pkCols.isEmpty()) add(result, table, new IndexInfo(PRIMARY_KEY, orUnknown(pkName), pkCols));
        for (IndexInfo idx : uniquesInline) add(result, table, idx);
    }

    private static void handleAddColumnElement(Element el, Map<String, Set<IndexInfo>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        if (isBlank(table)) return;
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
                        add(result, table, new IndexInfo(UNIQUE_CONSTRAINT, orUnknown(uniqueName), List.of(colName)));
                    }
                }
            }
        }
    }

    private static void handleDropIndexElement(Element el, Map<String, Set<IndexInfo>> result) {
        String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        List<String> cols = splitColumnsPreserveOrder(el.getAttribute("columnNames"));
        if (!isBlank(name)) {
            removeIndexByName(result, table, name);
        } else if (!isBlank(table) && !cols.isEmpty()) {
            removeIndexByColumnsOrdered(result, table, cols);
        }
    }

    private static void handleDropUniqueConstraintElement(Element el, Map<String, Set<IndexInfo>> result) {
        String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        List<String> cols = splitColumnsPreserveOrder(el.getAttribute("columnNames"));
        if (!isBlank(table)) {
            removeUniqueConstraint(result, table, name, cols);
        }
    }

    private static void handleDropPrimaryKeyElement(Element el, Map<String, Set<IndexInfo>> result) {
        String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
        if (!isBlank(table)) {
            removePrimaryKey(result, table, name);
        }
    }

    private static void handleSqlElement(Element el, Map<String, Set<IndexInfo>> result) {
        if (isInRollback(el)) return;
        String sqlText = el.getTextContent();
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
            // fallback manual splitting
        }
        for (String part : sqlText.split(";")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            processCreateIndexSql(result, s);
            processDropSql(result, s);
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
    private static void processCreateIndexSql(Map<String, Set<IndexInfo>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        if (!normalized.toUpperCase().startsWith("CREATE")) return;
        if (!normalized.toUpperCase().contains("INDEX")) return;

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
        String indexName = unquote(m.group(2));
        String tableName = unquote(m.group(3));
        String colsCsv = m.group(4);
        if (isBlank(tableName) || isBlank(colsCsv)) return;

        List<String> cols = Arrays.stream(colsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(c -> {
                String base = c.replaceAll("(?i)\\bASC\\b|\\bDESC\\b", "").trim();
                // ignore functional/expression columns (contains '(' or ')' not just parentheses around identifier)
                if (base.contains("(") || base.contains(")")) return ""; // ignore for now
                int sp = base.indexOf(' ');
                if (sp > 0) base = base.substring(0, sp).trim();
                base = unquote(base);
                int dot = base.lastIndexOf('.');
                if (dot >= 0) base = base.substring(dot + 1);
                return base.trim();
            })
            .filter(s -> !s.isEmpty())
            .toList();

        if (cols.isEmpty()) return; // ignore if all columns were expressions/functions
        int dot = tableName.lastIndexOf('.');
        if (dot >= 0) tableName = tableName.substring(dot + 1);
        add(result, tableName, new IndexInfo(isUnique ? UNIQUE_INDEX : "INDEX", orUnknown(indexName), cols));
    }

    /**
     * Parse and apply raw SQL DROP statements affecting indexes/constraints.
     */
    private static void processDropSql(Map<String, Set<IndexInfo>> result, String sql) {
        if (isBlank(sql)) return;
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.replace('\n', ' ').replace('\r', ' ');
        String upper = normalized.toUpperCase();

        if (upper.startsWith("DROP") && upper.contains("INDEX")) {
            String cleaned = normalized
                .replaceAll("(?i)\\bCONCURRENTLY\\b", " ")
                .replaceAll("(?i)\\bIF\\s+EXISTS\\b", " ")
                .replaceAll("\\s+", " ").trim();
            Matcher m = Pattern.compile("(?i)^DROP\\s+INDEX\\s+([\"`\\w.]+)").matcher(cleaned);
            if (m.find()) {
                String rawName = unquote(m.group(1));
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
                String tableName = unquote(tm.group(1));
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
                    String rawName = unquote(dc.group(1));
                    int d2 = rawName.lastIndexOf('.');
                    String consName = d2 >= 0 ? rawName.substring(d2 + 1) : rawName;
                    removeUniqueConstraint(result, tableName, consName, null);
                    removePrimaryKey(result, tableName, consName);
                }
            }
        }
    }

    // --- Removal helpers (order-sensitive column handling) ---
    private static void removeIndexByNameAnyTable(Map<String, Set<IndexInfo>> map, String name) {
        if (isBlank(name) || map.isEmpty()) return;
        List<String> emptyTables = new ArrayList<>();
        for (Map.Entry<String, Set<IndexInfo>> e : map.entrySet()) {
            Set<IndexInfo> list = e.getValue();
            if (list == null) continue;
            String target = name.toLowerCase();
            list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
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
        list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && i.name != null && i.name.equalsIgnoreCase(target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeIndexByColumnsOrdered(Map<String, Set<IndexInfo>> map, String table, List<String> cols) {
        if (isBlank(table) || cols == null || cols.isEmpty()) return;
        Set<IndexInfo> list = map.get(table);
        if (list == null) return;
        List<String> target = cols.stream().map(String::toLowerCase).toList();
        list.removeIf(i -> ("INDEX".equals(i.type) || UNIQUE_INDEX.equals(i.type)) && orderedColumnsMatch(i.columns, target));
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

    private static File resolveRelative(File base, String ref) {
        File candidate = new File(ref);
        if (candidate.isAbsolute()) return candidate;
        File parent = base.getParentFile();
        return new File(parent, ref);
    }

    // Column extraction preserving order exactly as defined
    private static List<String> extractColumnsExactOrder(Element createIndexEl) {
        List<String> cols = new ArrayList<>();
        NodeList columnNodes = createIndexEl.getElementsByTagName("column");
        for (int i = 0; i < columnNodes.getLength(); i++) {
            Node n = columnNodes.item(i);
            if (n instanceof Element colEl) {
                String name = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                if (!isBlank(name)) cols.add(name.trim());
            }
        }
        String attrCols = createIndexEl.getAttribute("columns");
        if (!isBlank(attrCols)) {
            cols.addAll(splitColumnsPreserveOrder(attrCols));
        }
        return cols;
    }

    private static List<String> splitColumnsPreserveOrder(String csv) {
        if (isBlank(csv)) return List.of();
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
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

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("`") && s.endsWith("`"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String localName(Element el) {
        String ln = el.getLocalName();
        if (ln != null) return ln;
        return el.getNodeName();
    }
}
