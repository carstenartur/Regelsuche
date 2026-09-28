package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.TransformationEngine;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RewriteProgramsCompilationTest {
    @TempDir Path directory;

    @Test
    void compilerRejectsProcedureNamesWhereObjectsAreRequired() throws Exception {
        compile("valid", "preferRules(rule);", true);
        compile("engine-string", "source(\"factorization\");", false);
        compile("rule-string", "preferRules(\"normalize\");", false);
        compile("rule-string-list", "preferRules(java.util.List.of(\"normalize\"));", false);
    }

    private void compile(String name, String statement, boolean expected) throws Exception {
        Path output = Files.createDirectories(directory.resolve(name));
        Path source = output.resolve("Consumer.java");
        Files.writeString(source, """
            import static de.regelsuche.search.program.RewritePrograms.*;
            import de.regelsuche.search.program.RewriteProgram;
            import de.regelsuche.transform.TransformationEngine;
            import de.regelsuche.transform.RewriteRule;
            class Consumer {
                void configure(TransformationEngine engine, RewriteRule rule) {
                    RewriteProgram program = firstApplicable(source(engine),
                        sequence(source(engine), source(engine))).named("trace-name");
                    %s
                }
            }
            """.formatted(statement), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "These contract tests require a full JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "-Xlint:unchecked", "-Werror", "-classpath", classPath(),
                    "-d", output.toString()), null,
                manager.getJavaFileObjectsFromPaths(List.of(source))).call();
            assertEquals(expected, success, () -> name + ": " + diagnostics.getDiagnostics());
            if (!expected) {
                assertTrue(diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .anyMatch(diagnostic -> diagnostic.getCode().contains("incompatible.types")
                        || diagnostic.getCode().contains("cant.apply")),
                    () -> "Expected a type error, not a missing dependency: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String classPath() throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (Class<?> type : List.of(RewritePrograms.class, TransformationEngine.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        for (ClassLoader loader = RewriteProgramsCompilationTest.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }
}
