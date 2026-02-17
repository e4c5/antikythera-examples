package com.raditha.perf;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Benchmark to compare performance of two methods for finding a specific method declaration:
 * 1. Using TypeDeclaration.findAll(MethodDeclaration.class)
 * 2. Using a VoidVisitor pattern
 */
public class JavaParserMethodFindingBenchmark {

    private static final String FILE_PATH = "../antikythera/src/main/java/sa/com/cloudsolutions/antikythera/parser/AbstractCompiler.java";
    private static final String TARGET_METHOD_NAME = "camelToSnakeCase";
    private static final int ITERATIONS = 1000;

    private CompilationUnit cu;
    private TypeDeclaration<?> classDeclaration;

    /**
     * Optimized inner class visitor for finding a method by name.
     * Avoids anonymous class overhead and uses a simple field instead of AtomicReference.
     */
    private static class MethodFinderVisitor extends VoidVisitorAdapter<String> {
        private MethodDeclaration foundMethod;

        @Override
        public void visit(MethodDeclaration md, String targetMethodName) {
            if (md.getNameAsString().equals(targetMethodName)) {
                foundMethod = md;
            }
            super.visit(md, targetMethodName);
        }

        public MethodDeclaration getFoundMethod() {
            return foundMethod;
        }

        public void reset() {
            foundMethod = null;
        }
    }

    public static void main(String[] args) throws Exception {
        JavaParserMethodFindingBenchmark benchmark = new JavaParserMethodFindingBenchmark();
        benchmark.setup();
        benchmark.runBenchmarks();
    }

    /**
     * Setup: Load and parse the AbstractCompiler.java file
     */
    public void setup() throws IOException {
        System.out.println("Setting up benchmark...");
        System.out.println("Loading file: " + FILE_PATH);

        // Configure JavaParser to support Java 21 features
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(parserConfiguration);

        File file = new File(FILE_PATH);
        cu = StaticJavaParser.parse(file);

        // Get the AbstractCompiler class declaration
        Optional<ClassOrInterfaceDeclaration> classOpt = cu.getClassByName("AbstractCompiler");
        if (classOpt.isEmpty()) {
            throw new IllegalStateException("Could not find AbstractCompiler class in the parsed file");
        }

        classDeclaration = classOpt.get();
        System.out.println("Loaded class: " + classDeclaration.getNameAsString());
        System.out.println("Target method: " + TARGET_METHOD_NAME);
        System.out.println("Iterations per test: " + ITERATIONS);
        System.out.println();
    }

    /**
     * Run both benchmarks and compare results
     */
    public void runBenchmarks() {
        // Warm up the JVM
        System.out.println("Warming up JVM...");
        for (int i = 0; i < 100; i++) {
            findMethodUsingFindAll();
            findMethodUsingVisitor();
        }
        System.out.println("Warm up complete.\n");

        // Benchmark 1: Using findAll
        System.out.println("=== Benchmark 1: Using findAll() ===");
        long startTime1 = System.nanoTime();
        MethodDeclaration result1 = null;
        for (int i = 0; i < ITERATIONS; i++) {
            result1 = findMethodUsingFindAll();
        }
        long endTime1 = System.nanoTime();
        long duration1 = endTime1 - startTime1;

        System.out.println("Found method: " + (result1 != null ? result1.getNameAsString() : "null"));
        System.out.println("Total time: " + duration1 / 1_000_000.0 + " ms");
        System.out.println("Average time per iteration: " + duration1 / (ITERATIONS * 1_000_000.0) + " ms");
        System.out.println();

        // Benchmark 2: Using Visitor
        System.out.println("=== Benchmark 2: Using VoidVisitor ===");
        long startTime2 = System.nanoTime();
        MethodDeclaration result2 = null;
        for (int i = 0; i < ITERATIONS; i++) {
            result2 = findMethodUsingVisitor();
        }
        long endTime2 = System.nanoTime();
        long duration2 = endTime2 - startTime2;

        System.out.println("Found method: " + (result2 != null ? result2.getNameAsString() : "null"));
        System.out.println("Total time: " + duration2 / 1_000_000.0 + " ms");
        System.out.println("Average time per iteration: " + duration2 / (ITERATIONS * 1_000_000.0) + " ms");
        System.out.println();

        // Comparison
        System.out.println("=== Results Comparison ===");
        double speedup = (double) duration1 / duration2;
        System.out.println("findAll() took: " + duration1 / 1_000_000.0 + " ms");
        System.out.println("Visitor took: " + duration2 / 1_000_000.0 + " ms");

        if (speedup > 1.0) {
            System.out.println("Visitor is " + String.format("%.2f", speedup) + "x FASTER than findAll()");
        } else {
            System.out.println("findAll() is " + String.format("%.2f", 1.0 / speedup) + "x FASTER than Visitor");
        }

        System.out.println("\nVerification: Both methods found the same method? " +
            (result1 != null && result2 != null && result1.equals(result2)));
    }

    /**
     * Method 1: Find method using findAll()
     */
    private MethodDeclaration findMethodUsingFindAll() {
        return classDeclaration.findAll(MethodDeclaration.class).stream()
            .filter(md -> md.getNameAsString().equals(TARGET_METHOD_NAME))
            .findFirst()
            .orElse(null);
    }

    /**
     * Method 2: Find method using VoidVisitor (optimized with inner class)
     */
    private MethodDeclaration findMethodUsingVisitor() {
        MethodFinderVisitor visitor = new MethodFinderVisitor();
        classDeclaration.accept(visitor, TARGET_METHOD_NAME);
        return visitor.getFoundMethod();
    }
}

