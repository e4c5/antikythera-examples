package sa.com.cloudsolutions.antikythera.examples;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Writes consolidated Liquibase changes (index creations/drops) to a new file and
 * appends an <include> entry to the provided master changelog as the last include.
 */
public class LiquibaseChangesWriter {

    public record Written(File changesFile) {}

    /**
     * Writes a new changelog containing the provided XML fragments (already formatted changeSets)
     * and updates the master file to include it last.
     *
     * @param masterXml master Liquibase changelog file
     * @param changeSetsXml concatenated changeSet XML fragments
     * @return info about written file
     */
    public Written write(File masterXml, String changeSetsXml) throws IOException {
        if (changeSetsXml == null || changeSetsXml.isBlank()) return new Written(null);
        Path master = masterXml.toPath();
        Path dir = master.getParent();
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        String name = "antikythera-indexes-" + timestamp + ".xml";
        Path out = dir.resolve(name);

        String content = """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd">
                %s
                </databaseChangeLog>
                """.formatted(changeSetsXml);
        Files.writeString(out, content, StandardCharsets.UTF_8);

        // Append include to master file
        String masterText = Files.readString(master, StandardCharsets.UTF_8);
        String includeTag = String.format("<include file=\"%s\"/>", name);
        if (!masterText.contains(includeTag)) {
            // insert before closing tag if present, otherwise just append
            int idx = masterText.lastIndexOf("</databaseChangeLog>");
            if (idx >= 0) {
                String updated = masterText.substring(0, idx) + "  " + includeTag + "\n" + masterText.substring(idx);
                Files.writeString(master, updated, StandardCharsets.UTF_8);
            } else {
                Files.writeString(master, masterText + "\n" + includeTag + "\n", StandardCharsets.UTF_8);
            }
        }
        return new Written(out.toFile());
    }
}
