package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Catch source-test inventory drift before building the isolated SDK archive consumers. */
class MavenStudentSdkConsumerInventoryContractTest {
    @Test void fixedQualificationCountsMatchTheConsumerTestDeclarations() throws Exception {
        Path consumers = MavenPomTestSupport.repositoryRoot().resolve("examples/external-consumers");
        for (var spec : StudentSdkMavenConsumers.CONSUMERS) {
            List<String> declared = declaredTests(consumers.resolve(spec.name()).resolve("src/test/java"));
            assertEquals(spec.tests(), declared.size(),
                spec.name() + " qualification inventory must include every declared consumer test: " + declared);
        }
    }

    private static List<String> declaredTests(Path sourceRoot) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "consumer inventory parsing requires the build JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var tests = new ArrayList<String>();
        try (var paths = Files.walk(sourceRoot);
                var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var sources = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                .sorted().toList();
            assertFalse(sources.isEmpty(), "missing consumer test sources: " + sourceRoot);
            var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"),
                null, fileManager.getJavaFileObjectsFromPaths(sources));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        boolean test = method.getModifiers().getAnnotations().stream()
                            .map(annotation -> annotation.getAnnotationType().toString())
                            .anyMatch(name -> name.equals("Test") || name.equals("org.junit.jupiter.api.Test"));
                        if (test) tests.add(unit.getSourceFile().getName() + "#" + method.getName());
                        return super.visitMethod(method, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(List.of(), diagnostics.getDiagnostics().stream()
            .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).toList(),
            "consumer test sources must parse before their inventory can be checked");
        return tests;
    }
}
