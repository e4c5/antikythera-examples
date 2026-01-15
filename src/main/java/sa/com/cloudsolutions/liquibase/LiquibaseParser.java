package sa.com.cloudsolutions.liquibase;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@SuppressWarnings("java:S106")
public abstract class LiquibaseParser extends DefaultHandler {

    protected static final String TABLE = "table";
    protected static final String TABLE_NAME_ATTR = "tableName";

    protected final File currentFile;
    protected final Set<String> visited;

    protected boolean inRollback = false;
    protected boolean inSql = false;
    protected StringBuilder sqlBuffer = new StringBuilder();

    protected LiquibaseParser(File currentFile, Set<String> visited) {
        this.currentFile = currentFile;
        this.visited = visited;
    }

    protected static <R> void parseLiquibaseFileInto(File file, Set<String> visited, R result, ParserFactory<R> parserFactory) throws Exception {
        String canonical = file.getCanonicalPath();
        if (visited.contains(canonical)) {
            return;
        }
        visited.add(canonical);

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        SAXParser saxParser = factory.newSAXParser();

        LiquibaseParser handler = parserFactory.create(file, visited, result);
        saxParser.parse(file, handler);
    }

    @FunctionalInterface
    protected interface ParserFactory<R> {
        LiquibaseParser create(File file, Set<String> visited, R result);
    }

    // Abstract methods to continue recursion with the specific subclass implementation
    protected abstract void parseChild(File file) throws Exception;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        String ln = localName != null && !localName.isEmpty() ? localName : qName;

        if (inRollback) return;
        if ("rollback".equalsIgnoreCase(ln)) {
            inRollback = true;
            return;
        }

        if ("include".equalsIgnoreCase(ln)) {
            try {
                handleIncludeElement(atts);
            } catch (Exception e) {
                throw new SAXException(e);
            }
            return;
        }
        if ("includeAll".equalsIgnoreCase(ln)) {
            try {
                handleIncludeAllElement(atts);
            } catch (Exception e) {
                throw new SAXException(e);
            }
            return;
        }

        if ("sql".equalsIgnoreCase(ln)) {
            inSql = true;
            sqlBuffer.setLength(0);
            return;
        }

        handleElement(ln, atts);
    }

    // Subclasses implement this for their specific tags
    protected abstract void handleElement(String localName, Attributes atts);

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
        }

        handleEndElement(ln);
    }

    // Subclasses implement this for their specific tags
    protected abstract void handleEndElement(String localName);

    protected abstract void handleSqlText(String sqlText);

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (inRollback) return;
        if (inSql) {
            sqlBuffer.append(ch, start, length);
        }
    }

    protected void handleIncludeElement(Attributes atts) throws Exception {
        String ref = firstNonEmpty(atts.getValue("file"), atts.getValue("path"));
        if (isBlank(ref)) return;
        File child = resolveRelative(currentFile, ref);
        if (!child.exists()) {
            child = resolveFallback(currentFile, ref);
            if (child == null) return;
        }
        parseChild(child);
    }

    protected void handleIncludeAllElement(Attributes atts) throws Exception {
        String dir = firstNonEmpty(atts.getValue("path"), atts.getValue("relativePath"));
        if (isBlank(dir)) return;
        File baseDir = resolveRelative(currentFile, dir);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            baseDir = resolveFallback(currentFile, dir);
            if (baseDir == null || !baseDir.isDirectory()) {
                 System.err.println("Warning: includeAll path not found or not a directory: " + (baseDir != null ? baseDir.getPath() : dir));
                 return;
            }
        }
        File[] files = baseDir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            parseChild(f);
        }
    }

    protected File resolveFallback(File base, String path) {
        String stripped = path;
        if (path.contains("db/changelog/")) {
            stripped = path.replace("db/changelog/", "");
        } else if (path.contains("db\\changelog\\")) {
            stripped = path.replace("db\\changelog\\", "");
        }
        if (!Objects.equals(stripped, path)) {
            File retry = resolveRelative(base, stripped);
            if (retry.exists()) {
                return retry;
            }
        }
        System.err.println("Warning: included file not found: " + resolveRelative(base, path).getPath());
        return null;
    }

    protected static File resolveRelative(File base, String ref) {
        File candidate = new File(ref);
        if (candidate.isAbsolute()) return candidate;
        File parent = base.getParentFile();
        return new File(parent, ref);
    }

    protected static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    protected static String firstNonEmpty(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
    }

    protected static String orUnknown(String s) {
        return isBlank(s) ? "<unnamed>" : s;
    }

    protected static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("`") && s.endsWith("`"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    protected static List<String> splitColumnsPreserveOrder(String csv) {
        if (isBlank(csv)) return List.of();
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
