package sa.com.cloudsolutions.liquibase;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.xml.sax.Attributes;

import java.io.File;
import java.util.*;

@SuppressWarnings("java:S106")
public class Columns {

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
        LiquibaseParser.parseLiquibaseFileInto(file, visited, result, ColumnsHandler::new);
        return result;
    }

    private static class ColumnsHandler extends LiquibaseParser {
        private final Map<String, List<String>> result;

        // Context
        private String currentTable = null;
        private boolean isCreateTable = false;
        private boolean isAddColumn = false;
        private boolean isDropColumn = false;

        public ColumnsHandler(File currentFile, Set<String> visited, Map<String, List<String>> result) {
            super(currentFile, visited);
            this.result = result;
        }

        @Override
        protected void parseChild(File file) throws Exception {
            LiquibaseParser.parseLiquibaseFileInto(file, visited, result, ColumnsHandler::new);
        }

        @Override
        protected void handleElement(String localName, Attributes atts) {
            if ("createTable".equalsIgnoreCase(localName)) {
                currentTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
                isCreateTable = true;
            } else if ("addColumn".equalsIgnoreCase(localName)) {
                currentTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
                isAddColumn = true;
            } else if ("dropColumn".equalsIgnoreCase(localName)) {
                currentTable = firstNonEmpty(atts.getValue("tableName"), atts.getValue(TABLE));
                isDropColumn = true;

                String colName = firstNonEmpty(atts.getValue("columnName"), null);
                if (!isBlank(currentTable) && !isBlank(colName)) {
                     removeColumns(result, currentTable, List.of(colName));
                }
            } else if ("renameColumn".equalsIgnoreCase(localName)) {
                 handleRenameColumnElement(atts);
            } else if ("dropTable".equalsIgnoreCase(localName)) {
                 handleDropTableElement(atts);
            } else if ("column".equalsIgnoreCase(localName)) {
                String name = atts.getValue("name");
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
        protected void handleEndElement(String localName) {
             if ("createTable".equalsIgnoreCase(localName)) {
                 isCreateTable = false;
                 currentTable = null;
             } else if ("addColumn".equalsIgnoreCase(localName)) {
                 isAddColumn = false;
                 currentTable = null;
             } else if ("dropColumn".equalsIgnoreCase(localName)) {
                 isDropColumn = false;
                 currentTable = null;
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
                         processSqlStatement(result, st);
                     }
                 }
             } catch (Exception ignore) {
                 // fallback
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
    }

    // --- Static Helpers ---

    private static void processSqlStatement(Map<String, List<String>> result, Statement st) {
        if (st instanceof CreateTable ct) {
            String tableName = ct.getTable().getName();
            List<String> cols = new ArrayList<>();
            if (ct.getColumnDefinitions() != null) {
                for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                    cols.add(cd.getColumnName());
                }
            }
            tableName = LiquibaseParser.unquote(tableName);
            cols.replaceAll(LiquibaseParser::unquote);
            result.put(tableName, cols);
        } else if (st instanceof Drop drop) {
             if ("TABLE".equalsIgnoreCase(drop.getType())) {
                String tableName = drop.getName().getName();
                result.remove(LiquibaseParser.unquote(tableName));
             }
        } else if (st instanceof Alter alter) {
            String tableName = alter.getTable().getName();
            tableName = LiquibaseParser.unquote(tableName);

             if (alter.getAlterExpressions() != null) {
                 for (var exp : alter.getAlterExpressions()) {
                     // ADD COLUMN
                     if (exp.getColDataTypeList() != null && !exp.getColDataTypeList().isEmpty()) {
                         List<String> toAdd = new ArrayList<>();
                         for (var cdt : exp.getColDataTypeList()) {
                             toAdd.add(LiquibaseParser.unquote(cdt.getColumnName()));
                         }
                         addColumns(result, tableName, toAdd);
                     }

                     // DROP COLUMN
                     boolean isDrop = exp.toString().trim().toUpperCase().startsWith("DROP");
                     if (isDrop && exp.getColumnName() != null) {
                          removeColumns(result, tableName, List.of(LiquibaseParser.unquote(exp.getColumnName())));
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

    // Helper to access inherited static method if needed, though they are accessible if protected.
    // However, they are in LiquibaseParser, so accessible.
    private static boolean isBlank(String s) { return LiquibaseParser.isBlank(s); }
    private static String firstNonEmpty(String a, String b) { return LiquibaseParser.firstNonEmpty(a, b); }
}
