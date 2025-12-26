package com.raditha.spring.cycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

public class TestHelper {

    /**
     * Read all Java files and store their contents for reverting.
     */
    protected Map<String, String> readAllJavaFiles(Path basePath) throws IOException {
        return Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toMap(
                        Path::toString,
                        p -> {
                            try {
                                return Files.readString(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                ));
    }

    protected void revertFiles(Map<String, String> originalContents) throws IOException {
        for (Map.Entry<String, String> entry : originalContents.entrySet()) {
            Path filePath = Paths.get(entry.getKey());
            if (Files.exists(filePath)) {
                Files.writeString(filePath, entry.getValue());
            }
        }
    }
}
