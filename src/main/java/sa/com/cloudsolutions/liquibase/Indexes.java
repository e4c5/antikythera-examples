package sa.com.cloudsolutions.liquibase;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Indexes {

    public static void main(String[] args) {
        if (args == null || args.length != 1) {
            System.err.println("Usage: java sa.com.cloudsolutions.liquibase.Indexes <path-to-liquibase-xml>");
            System.exit(1);
        }
        File file = new File(args[0]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found: " + file.getAbsolutePath());
            System.exit(2);
        }

        try {
            List<IndexEntry> entries = parseLiquibaseFile(file);
            for (IndexEntry e : entries) {
                System.out.println(e);
            }
        } catch (Exception e) {
            System.err.println("Error processing file: " + e.getMessage());
            System.exit(3);
        }
    }

    private static List<IndexEntry> parseLiquibaseFile(File file) throws Exception {
        return parseLiquibaseFile(file, new HashSet<>());
    }

    private static List<IndexEntry> parseLiquibaseFile(File file, Set<String> visited) throws Exception {
        String canonical = file.getCanonicalPath();
        if (visited.contains(canonical)) {
            return List.of();
        }
        visited.add(canonical);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);
        doc.getDocumentElement().normalize();

        List<IndexEntry> result = new ArrayList<>();

        // createIndex
        for (Element el : elementsByLocalName(doc, "createIndex")) {
            String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            boolean unique = "true".equalsIgnoreCase(el.getAttribute("unique"));
            List<String> cols = extractColumns(el);
            if (!isBlank(table) && !cols.isEmpty()) {
                result.add(new IndexEntry(unique ? "UNIQUE_INDEX" : "INDEX", table, orUnknown(name), cols));
            }
        }

        // addUniqueConstraint
        for (Element el : elementsByLocalName(doc, "addUniqueConstraint")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                result.add(new IndexEntry("UNIQUE_CONSTRAINT", table, orUnknown(name), cols));
            }
        }

        // addPrimaryKey
        for (Element el : elementsByLocalName(doc, "addPrimaryKey")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                result.add(new IndexEntry("PRIMARY_KEY", table, orUnknown(name), cols));
            }
        }

        // createTable with inline constraints
        for (Element el : elementsByLocalName(doc, "createTable")) {
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            if (isBlank(table)) continue;
            NodeList columns = el.getElementsByTagName("column");
            List<String> pkCols = new ArrayList<>();
            String pkName = null;
            List<IndexEntry> uniquesInline = new ArrayList<>();
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
                            uniquesInline.add(new IndexEntry("UNIQUE_CONSTRAINT", table, orUnknown(uniqueName), List.of(colName)));
                        }
                    }
                }
            }
            if (!pkCols.isEmpty()) {
                result.add(new IndexEntry("PRIMARY_KEY", table, orUnknown(pkName), pkCols));
            }
            result.addAll(uniquesInline);
        }

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
                            result.add(new IndexEntry("UNIQUE_CONSTRAINT", table, orUnknown(uniqueName), cols));
                        }
                    }
                }
            }
        }

        // includes (<include>)
        for (Element el : elementsByLocalName(doc, "include")) {
            String ref = firstNonEmpty(el.getAttribute("file"), el.getAttribute("path"));
            if (isBlank(ref)) continue;
            File child = resolveRelative(file, ref);
            if (!child.exists()) {
                System.err.println("Warning: included file not found: " + child.getPath());
                continue;
            }
            result.addAll(parseLiquibaseFile(child, visited));
        }

        // includeAll
        for (Element el : elementsByLocalName(doc, "includeAll")) {
            String dir = firstNonEmpty(el.getAttribute("path"), el.getAttribute("relativePath"));
            if (isBlank(dir)) continue;
            File baseDir = resolveRelative(file, dir);
            if (!baseDir.exists() || !baseDir.isDirectory()) {
                System.err.println("Warning: includeAll path not found or not a directory: " + baseDir.getPath());
                continue;
            }
            File[] files = baseDir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                result.addAll(parseLiquibaseFile(f, visited));
            }
        }

        return result;
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
                .collect(Collectors.toList());
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

    private record IndexEntry(String type, String table, String name, List<String> columns) {
        @Override
        public String toString() {
            return type + ";" + table + ";" + name + ";" + String.join(",", columns);
        }
    }
}
