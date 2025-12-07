package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

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

    // Mapping of JUnit 4 imports to JUnit 5 equivalents
    private static final Map<String, String> IMPORT_MAPPINGS = new HashMap<>();
    public static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";

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

    private final List<String> conversions = new ArrayList<>();

    /**
     * Migrate imports in a compilation unit from JUnit 4 to JUnit 5.
     * Uses JavaParser's Name API to properly handle qualified names.
     * 
     * @param cu the compilation unit to migrate
     * @return true if any imports were changed
     */
    public boolean migrateImports(CompilationUnit cu) {
        boolean modified = false;
        List<ImportDeclaration> importsToRemove = new ArrayList<>();
        Map<String, Boolean> importsToAdd = new HashMap<>(); // Import -> isStatic

        // Process existing imports
        for (ImportDeclaration importDecl : cu.getImports()) {
            String importName = importDecl.getNameAsString();

            // Handle static imports from JUnit 4 Assert/Assume
            if (importDecl.isStatic()) {
                String newStaticImport = convertStaticImport(importDecl);
                if (newStaticImport != null) {
                    importsToRemove.add(importDecl);
                    importsToAdd.put(newStaticImport, true);
                    conversions.add(importName + " → " + newStaticImport);
                    modified = true;
                    continue;
                }
            }

            // Handle exact import mappings
            if (IMPORT_MAPPINGS.containsKey(importName)) {
                String newImport = IMPORT_MAPPINGS.get(importName);
                importsToRemove.add(importDecl);
                importsToAdd.put(newImport, false);
                conversions.add(importName + " → " + newImport);
                modified = true;
                continue;
            }

            // Handle wildcard imports
            if (importDecl.isAsterisk()) {
                String packageName = importDecl.getNameAsString();
                if (packageName.equals("org.junit.Assert")) {
                    importsToRemove.add(importDecl);
                    importsToAdd.put("org.junit.jupiter.api.Assertions", false);
                    conversions.add("org.junit.Assert.* → org.junit.jupiter.api.Assertions.*");
                    modified = true;
                    continue;
                } else if (packageName.equals("org.junit.Assume")) {
                    importsToRemove.add(importDecl);
                    importsToAdd.put("org.junit.jupiter.api.Assumptions", false);
                    conversions.add("org.junit.Assume.* → org.junit.jupiter.api.Assumptions.*");
                    modified = true;
                    continue;
                }
            }

            // Check if import should be removed (runners, rules, etc.)
            if (shouldRemoveImport(importDecl)) {
                importsToRemove.add(importDecl);
                conversions.add("Removed: " + importName);
                modified = true;
            }
        }

        // Remove old imports
        for (ImportDeclaration importDecl : importsToRemove) {
            importDecl.remove();
        }

        // Add new imports
        for (Map.Entry<String, Boolean> entry : importsToAdd.entrySet()) {
            String newImport = entry.getKey();
            boolean isStatic = entry.getValue();
            if (!hasImport(cu, newImport)) {
                if (isStatic) {
                    cu.addImport(newImport, true, false);
                } else {
                    cu.addImport(newImport);
                }
            }
        }

        return modified;
    }

    /**
     * Convert static import from JUnit 4 to JUnit 5 using JavaParser's Name API.
     * 
     * @param importDecl the import declaration
     * @return the new import name, or null if not a JUnit 4 static import
     */
    private String convertStaticImport(ImportDeclaration importDecl) {
        var name = importDecl.getName();

        // Get the qualifier (package + class) and identifier (method/field)
        if (name.getQualifier().isEmpty()) {
            return null;
        }

        String qualifier = name.getQualifier().get().asString();
        String identifier = name.getIdentifier();

        // Check if this is a static import from org.junit.Assert
        if (qualifier.equals("org.junit.Assert")) {
            return "org.junit.jupiter.api.Assertions." + identifier;
        }

        // Check if this is a static import from org.junit.Assume
        if (qualifier.equals("org.junit.Assume")) {
            return "org.junit.jupiter.api.Assumptions." + identifier;
        }

        return null;
    }

    /**
     * Check if import should be removed using JavaParser's Name API.
     * Checks package hierarchy instead of string matching.
     * 
     * @param importDecl the import declaration
     * @return true if import should be removed
     */
    private boolean shouldRemoveImport(ImportDeclaration importDecl) {
        var name = importDecl.getName();

        // Check exact matches first
        String fullName = name.asString();
        if (fullName.equals("org.junit.runner.RunWith") ||
                fullName.equals("org.junit.Rule") ||
                fullName.equals("org.junit.ClassRule")) {
            return true;
        }

        // Check package prefixes using qualifier chain
        return isInPackage(name, "org.junit.runners") ||
                isInPackage(name, "org.junit.rules") ||
                isInPackage(name, "org.mockito.junit") ||
                isInPackage(name, "org.mockito.runners");
    }

    /**
     * Check if a Name is in a given package using JavaParser's qualifier chain.
     * 
     * @param name        the Name to check
     * @param packageName the package name to check against
     * @return true if the name is in the package
     */
    private boolean isInPackage(com.github.javaparser.ast.expr.Name name, String packageName) {
        String fullName = name.asString();

        // Check if it starts with the package name and has more components
        if (fullName.startsWith(packageName + ".")) {
            return true;
        }

        // For wildcard imports, check if the package matches exactly
        return fullName.equals(packageName);
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
            if (!hasImport(cu, EXTEND_WITH)) {
                cu.addImport(EXTEND_WITH);
                conversions.add("Added: org.junit.jupiter.api.extension.ExtendWith");
            }
            if (!hasImport(cu, "org.mockito.junit.jupiter.MockitoExtension")) {
                cu.addImport("org.mockito.junit.jupiter.MockitoExtension");
                conversions.add("Added: org.mockito.junit.jupiter.MockitoExtension");
            }
        }

        if (addSpringExtension) {
            if (!hasImport(cu, EXTEND_WITH)) {
                cu.addImport(EXTEND_WITH);
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

    // Check if compilation unit already has an import
    private boolean hasImport(CompilationUnit cu, String importName) {
        return cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(importName));
    }
}
