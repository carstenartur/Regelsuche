package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain;
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

class TypedDiscoveryCompilationTest {
    @TempDir Path directory;

    @Test
    void compilerAcceptsOnlyTheInputBoundToTheSelectedDomain() throws Exception {
        // Every negative fixture has a positive control using the same imports and classpath.
        compile("valid", "new SequenceInput(2)", true);
        compile("wrong-domain", "new MatrixInput(2)", false);
        compile("raw-text", "\"value=2\"", false);
        compile("object", "new Object()", false);
    }

    private void compile(String name, String value, boolean expected) throws Exception {
        Path output = Files.createDirectories(directory.resolve(name));
        Path source = output.resolve("Consumer.java");
        Files.writeString(source, """
            import de.regelsuche.sdk.discovery.*;
            class Consumer {
                record SequenceInput(int value) { }
                record MatrixInput(int value) { }
                void run(TypedDiscoveryDomain<SequenceInput, Integer, Integer, Integer> domain) {
                    RegelsucheDiscovery.forDomain(domain).campaign("compile-test")
                        .seed("seed", %s, "consumer").budget(DiscoveryBudgets.small()).run();
                }
            }
            """.formatted(value), StandardCharsets.UTF_8);
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
                        || diagnostic.getCode().contains("cant.apply")
                        || diagnostic.getCode().equals("compiler.err.prob.found.req")),
                    () -> "Expected a type error, not a missing dependency: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String classPath() throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (Class<?> type : List.of(RegelsucheDiscovery.class, DiscoveryDomain.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        for (ClassLoader loader = TypedDiscoveryCompilationTest.class.getClassLoader();
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
