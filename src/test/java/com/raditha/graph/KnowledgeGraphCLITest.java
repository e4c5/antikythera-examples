package com.raditha.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphCLITest {

    @Test
    void testParseArgsRejectsConflictingClearFlags() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeGraphCLI.parseArgs(new String[] {
                "--config=src/test/resources/graph-test.yml",
                "--base-path=src/test/java",
                "--clear",
                "--no-clear"
        }));
    }

    @Test
    void testParseClearOverrideHandlesSingleFlags() {
        assertTrue(KnowledgeGraphCLI.parseClearOverride(new String[] {"--clear"}));
        assertFalse(KnowledgeGraphCLI.parseClearOverride(new String[] {"--no-clear"}));
        assertNull(KnowledgeGraphCLI.parseClearOverride(new String[0]));
    }

    @Test
    void testParseClearOnStartAcceptsExplicitBooleanValues() {
        assertTrue(KnowledgeGraphCLI.parseClearOnStart(true));
        assertFalse(KnowledgeGraphCLI.parseClearOnStart(false));
        assertTrue(KnowledgeGraphCLI.parseClearOnStart("yes"));
        assertFalse(KnowledgeGraphCLI.parseClearOnStart("off"));
        assertTrue(KnowledgeGraphCLI.parseClearOnStart(1));
        assertFalse(KnowledgeGraphCLI.parseClearOnStart(0));
        assertFalse(KnowledgeGraphCLI.parseClearOnStart(null));
    }

    @Test
    void testParseClearOnStartRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeGraphCLI.parseClearOnStart("maybe"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeGraphCLI.parseClearOnStart(2));
    }

    @Test
    void testParseArgsStillFailsForMissingConfig() {
        assertThrows(IOException.class, () -> KnowledgeGraphCLI.parseArgs(new String[] {
                "--config=/missing/graph.yml",
                "--base-path=src/test/java"
        }));
    }
}
