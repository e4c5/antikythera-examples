package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

public class Reproduction {
    public static void main(String[] args) {
        String code = "package com.example;\n" +
                "\n" +
                "import org.junit.Test;\n" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "\n" +
                "@SpringBootTest\n" +
                "public class MyTest {\n" +
                "    @Test\n" +
                "    public void test() {}\n" +
                "}";

        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);

        ClassOrInterfaceDeclaration decl = cu.getClassByName("MyTest").get();

        // Simulate refactoring: replace @SpringBootTest with @DataJpaTest
        decl.getAnnotationByName("SpringBootTest").ifPresent(ann -> {
            // Bad way: remove and add
            // ann.remove();
            // decl.addAnnotation("DataJpaTest");

            // Better way: replace
            ann.replace(new MarkerAnnotationExpr("DataJpaTest"));
        });

        // Add import
        cu.addImport("org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest");

        System.out.println(LexicalPreservingPrinter.print(cu));
    }
}
