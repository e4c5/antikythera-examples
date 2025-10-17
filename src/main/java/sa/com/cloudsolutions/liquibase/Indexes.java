package sa.com.cloudsolutions.liquibase;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class Indexes {

    /** Simple DTO to expose index information to callers. */
    public static class IndexInfo {
        public final String type; // PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, INDEX
        public final String name;
        public final List<String> columns;
        public IndexInfo(String type, String name, List<String> columns) {
            this.type = type;
            this.name = name;
            this.columns = columns;
        }
        @Override
        public String toString() {
            return type + ";" + name + ";" + String.join(",", columns);
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

    /**
     * Convenience: return the set of all PK columns per table.
     */
    public static Map<String, Set<String>> primaryKeyColumns(File liquibaseXml) throws Exception {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexInfo>> e : load(liquibaseXml).entrySet()) {
            Set<String> cols = e.getValue().stream()
                    .filter(i -> "PRIMARY_KEY".equals(i.type))
                    .flatMap(i -> i.columns.stream())
                    .map(String::toLowerCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            map.put(e.getKey(), cols);
        }
        return map;
    }

    /**
     * Convenience: return the set of first columns of UNIQUE constraints/indexes per table.
     */
    public static Map<String, Set<String>> uniqueFirstColumns(File liquibaseXml) throws Exception {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexInfo>> e : load(liquibaseXml).entrySet()) {
            Set<String> cols = e.getValue().stream()
                    .filter(i -> "UNIQUE_CONSTRAINT".equals(i.type) || "UNIQUE_INDEX".equals(i.type))
                    .map(i -> i.columns.isEmpty()? null : i.columns.get(0))
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            map.put(e.getKey(), cols);
        }
        return map;
    }

    /**
     * Convenience: return the set of first columns of non-unique indexes per table.
     */
    public static Map<String, Set<String>> indexFirstColumns(File liquibaseXml) throws Exception {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexInfo>> e : load(liquibaseXml).entrySet()) {
            Set<String> cols = e.getValue().stream()
                    .filter(i -> "INDEX".equals(i.type))
                    .map(i -> i.columns.isEmpty()? null : i.columns.get(0))
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            map.put(e.getKey(), cols);
        }
        return map;
    }

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
            Map<String, List<Index>> byTable = parseLiquibaseFile(file);
            // Display table-based with indentation: PK, UNIQUE, and other indexes
            byTable.keySet().stream().sorted().forEach(table -> {
                System.out.println(table);
                List<Index> indexes = byTable.get(table);
                // PK
                indexes.stream().filter(i -> "PRIMARY_KEY".equals(i.type))
                        .forEach(i -> System.out.println("  PK: " + i.display()));
                // UNIQUE (constraints or unique indexes)
                indexes.stream().filter(i -> "UNIQUE_CONSTRAINT".equals(i.type) || "UNIQUE_INDEX".equals(i.type))
                        .forEach(i -> System.out.println("  UNIQUE: " + i.display()));
                // Other indexes
                indexes.stream().filter(i -> "INDEX".equals(i.type))
                        .forEach(i -> System.out.println("  INDEX: " + i.display()));
            });
        } catch (Exception e) {
            System.err.println("Error processing file: " + e.getMessage());
            System.exit(3);
        }
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

        // createIndex
        for (Element el : elementsByLocalName(doc, "createIndex")) {
            String name = firstNonEmpty(el.getAttribute("indexName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            boolean unique = "true".equalsIgnoreCase(el.getAttribute("unique"));
            List<String> cols = extractColumns(el);
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new Index(unique ? "UNIQUE_INDEX" : "INDEX", orUnknown(name), cols));
            }
        }

        // addUniqueConstraint
        for (Element el : elementsByLocalName(doc, "addUniqueConstraint")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new Index("UNIQUE_CONSTRAINT", orUnknown(name), cols));
            }
        }

        // addPrimaryKey
        for (Element el : elementsByLocalName(doc, "addPrimaryKey")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table) && !cols.isEmpty()) {
                add(result, table, new Index("PRIMARY_KEY", orUnknown(name), cols));
            }
        }

        // createTable with inline constraints
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
                            uniquesInline.add(new Index("UNIQUE_CONSTRAINT", orUnknown(uniqueName), List.of(colName)));
                        }
                    }
                }
            }
            if (!pkCols.isEmpty()) {
                add(result, table, new Index("PRIMARY_KEY", orUnknown(pkName), pkCols));
            }
            for (Index idx : uniquesInline) add(result, table, idx);
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
                            add(result, table, new Index("UNIQUE_CONSTRAINT", orUnknown(uniqueName), cols));
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
            Map<String, List<Index>> childMap = parseLiquibaseFile(child, visited);
            merge(result, childMap);
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
                Map<String, List<Index>> childMap = parseLiquibaseFile(f, visited);
                merge(result, childMap);
            }
        }

        // Handle drops (processed after additions and includes in this file)
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

        for (Element el : elementsByLocalName(doc, "dropUniqueConstraint")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("name"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            List<String> cols = splitColumns(el.getAttribute("columnNames"));
            if (!isBlank(table)) {
                removeUniqueConstraint(result, table, name, cols);
            }
        }

        for (Element el : elementsByLocalName(doc, "dropPrimaryKey")) {
            String name = firstNonEmpty(el.getAttribute("constraintName"), el.getAttribute("pkName"));
            String table = firstNonEmpty(el.getAttribute("tableName"), el.getAttribute("table"));
            if (!isBlank(table)) {
                removePrimaryKey(result, table, name);
            }
        }

        return result;
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
        list.removeIf(i -> ("INDEX".equals(i.type) || "UNIQUE_INDEX".equals(i.type)) && name.equals(i.name));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeIndexByColumns(Map<String, List<Index>> map, String table, List<String> cols) {
        if (isBlank(table) || cols == null || cols.isEmpty()) return;
        List<Index> list = map.get(table);
        if (list == null) return;
        Set<String> target = new HashSet<>(cols.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        list.removeIf(i -> ("INDEX".equals(i.type) || "UNIQUE_INDEX".equals(i.type)) && sameColumnsIgnoreCase(i.columns, target));
        if (list.isEmpty()) map.remove(table);
    }

    private static void removeUniqueConstraint(Map<String, List<Index>> map, String table, String name, List<String> cols) {
        List<Index> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            list.removeIf(i -> "UNIQUE_CONSTRAINT".equals(i.type) && name.equals(i.name));
        } else if (cols != null && !cols.isEmpty()) {
            Set<String> target = new HashSet<>(cols.stream().map(String::toLowerCase).collect(Collectors.toSet()));
            list.removeIf(i -> "UNIQUE_CONSTRAINT".equals(i.type) && sameColumnsIgnoreCase(i.columns, target));
        }
        if (list.isEmpty()) map.remove(table);
    }

    private static void removePrimaryKey(Map<String, List<Index>> map, String table, String name) {
        List<Index> list = map.get(table);
        if (list == null) return;
        if (!isBlank(name)) {
            list.removeIf(i -> "PRIMARY_KEY".equals(i.type) && name.equals(i.name));
        } else {
            list.removeIf(i -> "PRIMARY_KEY".equals(i.type));
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

    private static class Index {
        final String type; // PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, INDEX
        final String name;
        final List<String> columns;
        Index(String type, String name, List<String> columns) {
            this.type = type;
            this.name = name;
            this.columns = columns;
        }
        String display() {
            return name + " [" + String.join(",", columns) + "]";
        }
        @Override
        public String toString() {
            return type + ";" + name + ";" + String.join(",", columns);
        }
    }
}
