package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles import statement conversions from JUnit 4 to JUnit 5.
 * 
 * Converts:
 * - org.junit.* → org.junit.jupiter.api.*
 * - org.junit.Assert → org.junit.jupiter.api.Assertions
 * - org.junit.Assume → org.junit.jupiter.api.Assumptions
 * - Removes runner/rule imports
 * - Adds extension imports as needed
 */
public class ImportMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ImportMigrator.class);

    // Mapping of JUnit 4 imports to JUnit 5 equivalents
    private static final Map<String, String> IMPORT_MAPPINGS = new HashMap<>();

    static {
        // Core annotations
        IMPORT_MAPPINGS.put("org.junit.Test", "org.junit.jupiter.api.Test");
        IMPORT_MAPPINGS.put("org.junit.Before", "org.junit.jupiter.api.BeforeEach");
        IMPORT_MAPPINGS.put("org.junit.After", "org.junit.jupiter.api.AfterEach");
        IMPORT_MAPPINGS.put("org.junit.BeforeClass", "org.junit.jupiter.api.BeforeAll");
        IMPORT_MAPPINGS.put("org.junit.AfterClass", "org.junit.jupiter.api.AfterAll");
        IMPORT_MAPPINGS.put("org.junit.Ignore", "org.junit.jupiter.api.Disabled");

        // Assertions
        IMPORT_MAPPINGS.put("org.junit.Assert", "org.junit.jupiter.api.Assertions");

        // Assumptions
        IMPORT_MAPPINGS.put("org.junit.Assume", "org.junit.jupiter.api.Assumptions");
    }

    // Imports to remove (runners, rules)
    private static final List<String> IMPORTS_TO_REMOVE = List.of(
            "org.junit.runner.RunWith",
            "org.junit.Rule",
            "org.junit.ClassRule",
            "org.junit.runners.", // All runner classes
            "org.junit.rules." // All rule classes
    );

    private final List<String> conversions = new ArrayList<>();

    /**
     * Migrate imports in a compilation unit from JUnit 4 to JUnit 5.
     * 
     * @param cu the compilation unit to migrate
     * @return true if any imports were changed
     */
    public boolean migrateImports(CompilationUnit cu) {
        boolean modified = false;
        List<ImportDeclaration> importsToRemove = new ArrayList<>();
        Map<String, String> importsToAdd = new HashMap<>();

        // Process existing imports
        for (ImportDeclaration importDecl : cu.getImports()) {
            String importName = importDecl.getNameAsString();

            // Check for exact mapping
            if (IMPORT_MAPPINGS.containsKey(importName)) {
                String newImport = IMPORT_MAPPINGS.get(importName);
                importsToRemove.add(importDecl);
                importsToAdd.put(newImport, importName);
                conversions.add(importName + " → " + newImport);
                modified = true;
            }
            // Check for wildcard imports
            else if (importDecl.isAsterisk() && importName.equals("org.junit.Assert")) {
                importsToRemove.add(importDecl);
                importsToAdd.put("org.junit.jupiter.api.Assertions", importName);
                conversions.add("org.junit.Assert.* → org.junit.jupiter.api.Assertions.*");
                modified = true;
            } else if (importDecl.isAsterisk() && importName.equals("org.junit.Assume")) {
                importsToRemove.add(importDecl);
                importsToAdd.put("org.junit.jupiter.api.Assumptions", importName);
                conversions.add("org.junit.Assume.* → org.junit.jupiter.api.Assumptions.*");
                modified = true;
            }
            // Check for imports to remove
            else if (shouldRemoveImport(importName)) {
                importsToRemove.add(importDecl);
                conversions.add("Removed: " + importName);
                modified = true;
            }
        }

        // Remove old imports
        for (ImportDeclaration importDecl : importsToRemove) {
            importDecl.remove();
        }

        // Add new imports (check for duplicates)
        for (Map.Entry<String, String> entry : importsToAdd.entrySet()) {
            String newImport = entry.getKey();
            if (!hasImport(cu, newImport)) {
                cu.addImport(newImport);
            }
        }

        return modified;
    }

    /**
     * Add extension imports based on detected patterns.
     * Should be called after annotation migration to add necessary extension
     * imports.
     * 
     * @param cu                  the compilation unit
     * @param addMockitoExtension whether to add Mockito extension import
     * @param addSpringExtension  whether to add Spring extension import
     */
    public void addExtensionImports(CompilationUnit cu, boolean addMockitoExtension, boolean addSpringExtension) {
        if (addMockitoExtension) {
            if (!hasImport(cu, "org.junit.jupiter.api.extension.ExtendWith")) {
                cu.addImport("org.junit.jupiter.api.extension.ExtendWith");
                conversions.add("Added: org.junit.jupiter.api.extension.ExtendWith");
            }
            if (!hasImport(cu, "org.mockito.junit.jupiter.MockitoExtension")) {
                cu.addImport("org.mockito.junit.jupiter.MockitoExtension");
                conversions.add("Added: org.mockito.junit.jupiter.MockitoExtension");
            }
        }

        if (addSpringExtension) {
            if (!hasImport(cu, "org.junit.jupiter.api.extension.ExtendWith")) {
                cu.addImport("org.junit.jupiter.api.extension.ExtendWith");
                conversions.add("Added: org.junit.jupiter.api.extension.ExtendWith");
            }
            if (!hasImport(cu, "org.springframework.test.context.junit.jupiter.SpringExtension")) {
                cu.addImport("org.springframework.test.context.junit.jupiter.SpringExtension");
                conversions.add("Added: org.springframework.test.context.junit.jupiter.SpringExtension");
            }
        }
    }

    public List<String> getConversions() {
        return conversions;
    }

    // Check if import should be removed
    private boolean shouldRemoveImport(String importName) {
        for (String pattern : IMPORTS_TO_REMOVE) {
            if (importName.startsWith(pattern) || importName.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    // Check if compilation unit already has an import
    private boolean hasImport(CompilationUnit cu, String importName) {
        return cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(importName));
    }
}
