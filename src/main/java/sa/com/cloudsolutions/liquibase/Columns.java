package sa.com.cloudsolutions.liquibase;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

@SuppressWarnings("java:S106")
public class Columns {

    public static final String TABLE = "table";

    /**
     * Load Liquibase columns from the given XML and return a table->columns map.
     * The map value is a list of column names in order.
     */
    public static Map<String, List<String>> load(File liquibaseXml) throws Exception {
        return parseLiquibaseFile(liquibaseXml);
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            System.err.println("Usage: java sa.com.cloudsolutions.liquibase.Columns <path-to-liquibase-xml>");
            System.exit(1);
        }
        File file = new File(args[0]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found: " + file.getAbsolutePath());
            System.exit(2);
        }

        Map<String, List<String>> byTable = parseLiquibaseFile(file);
        byTable.keySet().stream().sorted().forEach(table -> {
            System.out.println(table);
            List<String> cols = byTable.get(table);
            if (cols != null) {
                cols.forEach(c -> System.out.println("  " + c));
            }
        });
    }

    private static Map<String, List<String>> parseLiquibaseFile(File file) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        parseLiquibaseFileInto(file, visited, result);
        return result;
    }

    private static void parseLiquibaseFileInto(File file, Set<String> visited, Map<String, List<String>> result) throws Exception {
        String canonical = file.getCanonicalPath();
        if (visited.contains(canonical)) {
            return;
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

    private static void processTopLevelElement(Element el, File currentFile, Set<String> visited, Map<String, List<String>> result) throws Exception {
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
        processColumnRelatedElement(el, result);
    }

    private static void processChangeSet(Element changeSetEl, File currentFile, Set<String> visited, Map<String, List<String>> result) throws Exception {
        NodeList nodes = changeSetEl.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el) {
                if (isInRollback(el)) continue;
                String ln = localName(el);
                if ("include".equalsIgnoreCase(ln)) {
                    handleIncludeElement(el, currentFile, visited, result);
                } else if ("includeAll".equalsIgnoreCase(ln)) {
                    handleIncludeAllElement(el, currentFile, visited, result);
                } else {
                    processColumnRelatedElement(el, result);
                }
            }
        }
    }

    private static void processColumnRelatedElement(Element el, Map<String, List<String>> result) {
        String ln = localName(el);
        switch (ln.toLowerCase()) {
            case "createtable" -> handleCreateTableElement(el, result);
            case "addcolumn" -> handleAddColumnElement(el, result);
            case "dropcolumn" -> handleDropColumnElement(el, result);
            case "renamecolumn" -> handleRenameColumnElement(el, result);
            case "droptable" -> handleDropTableElement(el, result);
            case "sql" -> handleSqlElement(el, result);
            default -> {
                // ignore others
            }
        }
    }

    private static void handleIncludeElement(Element el, File currentFile, Set<String> visited, Map<String, List<String>> result) throws Exception {
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
        parseLiquibaseFileInto(child, visited, result);
    }

    private static void handleIncludeAllElement(Element el, File currentFile, Set<String> visited, Map<String, List<String>> result) throws Exception {
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

    private static void handleCreateTableElement(Element el, Map<String, List<String>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute(TABLE));
        if (isBlank(table)) return;
        List<String> columns = new ArrayList<>();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element colEl && "column".equalsIgnoreCase(localName(colEl))) {
                String name = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                if (!isBlank(name)) {
                    columns.add(name);
                }
            }
        }
        if (!columns.isEmpty()) {
            result.put(table, columns);
        }
    }

    private static void handleAddColumnElement(Element el, Map<String, List<String>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute(TABLE));
        if (isBlank(table)) return;
        List<String> newCols = new ArrayList<>();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element colEl && "column".equalsIgnoreCase(localName(colEl))) {
                String name = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                if (!isBlank(name)) {
                    newCols.add(name);
                }
            }
        }
        if (!newCols.isEmpty()) {
            addColumns(result, table, newCols);
        }
    }

    private static void handleDropColumnElement(Element el, Map<String, List<String>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute(TABLE));
        if (isBlank(table)) return;

        List<String> toDrop = new ArrayList<>();
        String colName = firstNonEmpty(el.getAttribute("columnName"), textOfFirstChild(el, "columnName"));
        if (!isBlank(colName)) {
            toDrop.add(colName);
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element colEl && "column".equalsIgnoreCase(localName(colEl))) {
                String name = firstNonEmpty(colEl.getAttribute("name"), textOfFirstChild(colEl, "name"));
                if (!isBlank(name)) {
                    toDrop.add(name);
                }
            }
        }

        if (!toDrop.isEmpty()) {
            removeColumns(result, table, toDrop);
        }
    }

    private static void handleRenameColumnElement(Element el, Map<String, List<String>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute(TABLE));
        String oldName = el.getAttribute("oldColumnName");
        String newName = el.getAttribute("newColumnName");

        if (!isBlank(table) && !isBlank(oldName) && !isBlank(newName)) {
            renameColumn(result, table, oldName, newName);
        }
    }

    private static void handleDropTableElement(Element el, Map<String, List<String>> result) {
        String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute(TABLE));
        if (!isBlank(table)) {
            result.remove(table);
        }
    }

    private static void handleSqlElement(Element el, Map<String, List<String>> result) {
        if (isInRollback(el)) return;
        String sqlText = el.getTextContent();
        if (isBlank(sqlText)) return;

        try {
            net.sf.jsqlparser.statement.Statements stmts = CCJSqlParserUtil.parseStatements(sqlText);
             if (stmts != null && stmts.getStatements() != null && !stmts.getStatements().isEmpty()) {
                for (Statement st : stmts.getStatements()) {
                    if (st == null) continue;
                    processSqlStatement(result, st);
                }
             }
        } catch (Exception ignore) {
            // fallback
        }
    }

    private static void processSqlStatement(Map<String, List<String>> result, Statement st) {
        if (st instanceof CreateTable ct) {
            String tableName = ct.getTable().getName();
            List<String> cols = new ArrayList<>();
            if (ct.getColumnDefinitions() != null) {
                for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                    cols.add(cd.getColumnName());
                }
            }
            tableName = unquote(tableName);
            cols.replaceAll(Columns::unquote);
            result.put(tableName, cols);
        } else if (st instanceof Drop drop) {
             if ("TABLE".equalsIgnoreCase(drop.getType())) {
                String tableName = drop.getName().getName();
                result.remove(unquote(tableName));
             }
        } else if (st instanceof Alter alter) {
            String tableName = alter.getTable().getName();
            tableName = unquote(tableName);

             if (alter.getAlterExpressions() != null) {
                 for (var exp : alter.getAlterExpressions()) {
                     // ADD COLUMN
                     if (exp.getColDataTypeList() != null && !exp.getColDataTypeList().isEmpty()) {
                         List<String> toAdd = new ArrayList<>();
                         for (var cdt : exp.getColDataTypeList()) {
                             toAdd.add(unquote(cdt.getColumnName()));
                         }
                         addColumns(result, tableName, toAdd);
                     }

                     // DROP COLUMN
                     // Check if it's a drop operation
                     boolean isDrop = exp.toString().trim().toUpperCase().startsWith("DROP");
                     if (isDrop && exp.getColumnName() != null) {
                          removeColumns(result, tableName, List.of(unquote(exp.getColumnName())));
                     }
                 }
             }
        }
    }

    private static void addColumns(Map<String, List<String>> map, String table, List<String> newCols) {
        map.computeIfAbsent(table, k -> new ArrayList<>()).addAll(newCols);
    }

    private static void removeColumns(Map<String, List<String>> map, String table, List<String> toDrop) {
        List<String> current = map.get(table);
        if (current != null) {
            for (String d : toDrop) {
                current.removeIf(c -> c.equalsIgnoreCase(d));
            }
        }
    }

    private static void renameColumn(Map<String, List<String>> map, String table, String oldName, String newName) {
        List<String> current = map.get(table);
        if (current != null) {
            for (int i=0; i<current.size(); i++) {
                if (current.get(i).equalsIgnoreCase(oldName)) {
                    current.set(i, newName);
                }
            }
        }
    }

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

    private static File resolveRelative(File base, String ref) {
        File candidate = new File(ref);
        if (candidate.isAbsolute()) return candidate;
        File parent = base.getParentFile();
        return new File(parent, ref);
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

    private static String localName(Element el) {
        String ln = el.getLocalName();
        if (ln != null) return ln;
        return el.getNodeName();
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("`") && s.endsWith("`"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
