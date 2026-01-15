package sa.com.cloudsolutions.liquibase;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
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

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true); // Matches DOM implementation
        SAXParser saxParser = factory.newSAXParser();

        LiquibaseHandler handler = new LiquibaseHandler(file, visited, result);
        saxParser.parse(file, handler);
    }

    private static class LiquibaseHandler extends DefaultHandler {
        private final File currentFile;
        private final Set<String> visited;
        private final Map<String, List<String>> result;

        private boolean inRollback = false;
        private boolean inSql = false;
        private StringBuilder sqlBuffer = new StringBuilder();

        // Context for creating/modifying tables
        private String currentTable = null;
        private boolean isCreateTable = false;
        private boolean isAddColumn = false;
        private boolean isDropColumn = false;

        public LiquibaseHandler(File currentFile, Set<String> visited, Map<String, List<String>> result) {
            this.currentFile = currentFile;
            this.visited = visited;
            this.result = result;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            String ln = localName != null && !localName.isEmpty() ? localName : qName;

            if (inRollback) {
                // If we are in rollback, we track nesting to know when we are out,
                // but for simple rollback tag check, we just check if it IS rollback.
                // However, XML is hierarchical. If we see <rollback>, we enter rollback mode.
                // If we see another <rollback> inside (unlikely), we need to track depth?
                // Liquibase doesn't nest rollbacks inside rollbacks usually.
                // But we must ignore everything until </rollback>.
                // BUT, wait, 'rollback' is also a top level tag?
                // The DOM logic was `isInRollback(element)` which checked ancestors.
                // Here, if we hit <rollback>, we set inRollback=true.
                // We should push to a stack if we want to be 100% correct about nesting,
                // but boolean is probably enough for standard changelogs.
                return;
            }

            if ("rollback".equalsIgnoreCase(ln)) {
                inRollback = true;
                return;
            }

            if ("include".equalsIgnoreCase(ln)) {
                try {
                    handleIncludeElement(attributes);
                } catch (Exception e) {
                    throw new SAXException(e);
                }
                return;
            }
            if ("includeAll".equalsIgnoreCase(ln)) {
                try {
                    handleIncludeAllElement(attributes);
                } catch (Exception e) {
                    throw new SAXException(e);
                }
                return;
            }

            if ("changeSet".equalsIgnoreCase(ln)) {
                // Just enter changeSet, nothing specific to capture
                return;
            }

            if ("sql".equalsIgnoreCase(ln)) {
                inSql = true;
                sqlBuffer.setLength(0);
                return;
            }

            // Columns operations
            if ("createTable".equalsIgnoreCase(ln)) {
                currentTable = firstNonEmpty(attributes.getValue("tableName"), attributes.getValue(TABLE));
                isCreateTable = true;
            } else if ("addColumn".equalsIgnoreCase(ln)) {
                currentTable = firstNonEmpty(attributes.getValue("tableName"), attributes.getValue(TABLE));
                isAddColumn = true;
            } else if ("dropColumn".equalsIgnoreCase(ln)) {
                currentTable = firstNonEmpty(attributes.getValue("tableName"), attributes.getValue(TABLE));
                isDropColumn = true;

                // Handle attribute based drop
                String colName = firstNonEmpty(attributes.getValue("columnName"), null); // no text lookup here
                if (!isBlank(currentTable) && !isBlank(colName)) {
                     removeColumns(result, currentTable, List.of(colName));
                }
            } else if ("renameColumn".equalsIgnoreCase(ln)) {
                 handleRenameColumnElement(attributes);
            } else if ("dropTable".equalsIgnoreCase(ln)) {
                 handleDropTableElement(attributes);
            } else if ("column".equalsIgnoreCase(ln)) {
                // Inside createTable, addColumn, or dropColumn
                String name = attributes.getValue("name");
                if (!isBlank(name)) {
                    if (isCreateTable || isAddColumn) {
                        if (!isBlank(currentTable)) {
                            addColumns(result, currentTable, List.of(name));
                        }
                    } else if (isDropColumn) {
                        if (!isBlank(currentTable)) {
                            removeColumns(result, currentTable, List.of(name));
                        }
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
             String ln = localName != null && !localName.isEmpty() ? localName : qName;

             if ("rollback".equalsIgnoreCase(ln)) {
                 inRollback = false;
                 return;
             }
             if (inRollback) return;

             if ("sql".equalsIgnoreCase(ln)) {
                 inSql = false;
                 handleSqlText(sqlBuffer.toString());
             } else if ("createTable".equalsIgnoreCase(ln)) {
                 isCreateTable = false;
                 currentTable = null;
             } else if ("addColumn".equalsIgnoreCase(ln)) {
                 isAddColumn = false;
                 currentTable = null;
             } else if ("dropColumn".equalsIgnoreCase(ln)) {
                 isDropColumn = false;
                 currentTable = null;
             }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (inRollback) return;
            if (inSql) {
                sqlBuffer.append(ch, start, length);
            }
        }

        // --- Handlers ---

        private void handleIncludeElement(Attributes atts) throws Exception {
            String ref = firstNonEmpty(atts.getValue("file"), atts.getValue("path"));
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

        private void handleIncludeAllElement(Attributes atts) throws Exception {
            String dir = firstNonEmpty(atts.getValue("path"), atts.getValue("relativePath"));
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

        private void handleRenameColumnElement(Attributes atts) {
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            String oldName = atts.getValue("oldColumnName");
            String newName = atts.getValue("newColumnName");
            if (!isBlank(table) && !isBlank(oldName) && !isBlank(newName)) {
                renameColumn(result, table, oldName, newName);
            }
        }

        private void handleDropTableElement(Attributes atts) {
            String table = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
            if (!isBlank(table)) {
                result.remove(table);
            }
        }

        private void handleSqlText(String sqlText) {
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
                 // fallback? The DOM version didn't fallback for Columns.
             }
        }
    }

    // --- Static Helpers (shared with DOM version logic) ---

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

    private static File resolveRelative(File base, String ref) {
        File candidate = new File(ref);
        if (candidate.isAbsolute()) return candidate;
        File parent = base.getParentFile();
        return new File(parent, ref);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonEmpty(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
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
