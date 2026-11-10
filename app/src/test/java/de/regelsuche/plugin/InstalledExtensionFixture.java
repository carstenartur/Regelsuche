package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Real provider bytes deliberately absent from the host test classpath. */
final class InstalledExtensionFixture {
    private InstalledExtensionFixture() { }

    static byte[] jar(Path root, String marker, String value, String api, String minimumCore,
            boolean manifestClasspath) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = root.resolve("RealProvider.java");
        Files.writeString(source, """
            package installed;
            import de.regelsuche.extension.*;
            import java.util.*;
            public final class RealProvider implements RegelsuchePlugin {
                static { System.setProperty("%s", "initialized"); }
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("actual-provider", "Actual provider", "1.0.0", "%s", "%s",
                        Set.of(), List.of(), "signed-fixture");
                }
                public void contribute(ExtensionContext context) {
                    context.contribute(ExtensionPoint.of("installed.run", Runnable.class),
                        ExtensionDescriptor.named("run"), (Runnable) () -> {
                            try (var input = RealProvider.class.getResourceAsStream("/value.txt")) {
                                if (input == null) throw new IllegalStateException("missing admitted resource");
                                System.setProperty("%s.value", new String(input.readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                        });
                }
            }
            """.formatted(marker, api, minimumCore, marker), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    entry(jar, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            entry(jar, "META-INF/services/de.regelsuche.extension.RegelsuchePlugin",
                "installed.RealProvider\n".getBytes(StandardCharsets.UTF_8));
            entry(jar, "value.txt", value.getBytes(StandardCharsets.UTF_8));
            if (manifestClasspath) entry(jar, "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nClass-Path: foreign.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static void entry(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }
}
