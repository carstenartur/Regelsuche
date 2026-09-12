package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

final class TestPluginJar {
    private TestPluginJar() {
    }

    static Path buildPlugin(Path root) throws IOException {
        return buildPluginWithServiceDescriptor(root, "fixture.ExternalPlugin");
    }

    static Path buildPluginWithServiceDescriptor(Path root, String descriptor) throws IOException {
        return buildPlugin(root, List.of(), false, descriptor);
    }

    static Path withDuplicateServiceDescriptorEntry(Path source, Path output) throws IOException {
        String service = "META-INF/services/de.regelsuche.extension.RegelsuchePlugin";
        String alternate = "META-INF/serviceX/de.regelsuche.extension.RegelsuchePlugin";
        try (var input = new JarFile(source.toFile());
                var jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (var entry : input.stream().toList()) {
                jar.putNextEntry(new JarEntry(entry.getName()));
                try (var content = input.getInputStream(entry)) {
                    content.transferTo(jar);
                }
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry(alternate));
            jar.write("fixture.ExternalPlugin\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }

        // JarOutputStream rejects duplicates. Rename the placeholder in the local
        // and central ZIP headers without changing payload bytes or their CRCs.
        byte[] bytes = Files.readAllBytes(output);
        var headers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] from = alternate.getBytes(StandardCharsets.UTF_8);
        byte[] to = service.getBytes(StandardCharsets.UTF_8);
        int renamed = 0;
        for (int offset = 0; offset + 4 <= bytes.length; offset++) {
            int nameOffset = switch (headers.getInt(offset)) {
                case 0x04034b50 -> offset + 30;
                case 0x02014b50 -> offset + 46;
                default -> -1;
            };
            if (nameOffset >= 0 && nameOffset + from.length <= bytes.length
                    && Arrays.equals(bytes, nameOffset, nameOffset + from.length,
                        from, 0, from.length)) {
                System.arraycopy(to, 0, bytes, nameOffset, to.length);
                renamed++;
            }
        }
        assertEquals(2, renamed, "fixture must rename both ZIP entry headers");
        Files.write(output, bytes);
        return output;
    }

    static Path buildPluginUsingLibrary(Path root, Path libraryJar) throws IOException {
        return buildPlugin(root, List.of(libraryJar), true, "fixture.ExternalPlugin");
    }

    static Path buildLibrary(Path root) throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = sourceRoot.resolve("fixture/SharedValue.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            public final class SharedValue {
                private SharedValue() {}
                public static String value() { return "from-library"; }
            }
            """, StandardCharsets.UTF_8);
        compile(List.of(source), classes, List.of());
        Path jar = root.resolve("library.jar");
        pack(classes, jar, null);
        return jar;
    }

    private static Path buildPlugin(
        Path root,
        List<Path> extraClasspath,
        boolean useLibrary,
        String serviceDescriptor
    ) throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = sourceRoot.resolve("fixture/ExternalPlugin.java");
        Files.createDirectories(source.getParent());
        String contributionName = useLibrary ? "SharedValue.value()" : "\"external\"";
        Files.writeString(source, """
            package fixture;

            import de.regelsuche.extension.ExtensionApi;
            import de.regelsuche.extension.ExtensionContext;
            import de.regelsuche.extension.ExtensionDescriptor;
            import de.regelsuche.extension.ExtensionPoint;
            import de.regelsuche.extension.PluginDescriptor;
            import de.regelsuche.extension.RegelsuchePlugin;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.List;
            import java.util.Set;

            public final class ExternalPlugin implements RegelsuchePlugin {
                static {
                    String sentinel = System.getProperty("regelsuche.fixture.sentinel");
                    if (sentinel != null) {
                        try {
                            Files.writeString(Path.of(sentinel), "loaded");
                        } catch (java.io.IOException failure) {
                            throw new ExceptionInInitializerError(failure);
                        }
                    }
                }

                @Override
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor(
                        "external-fixture",
                        "External fixture",
                        "1",
                        ExtensionApi.VERSION,
                        ExtensionApi.CORE_COMPATIBILITY_VERSION,
                        Set.of(),
                        List.of(),
                        "fixture"
                    );
                }

                @Override
                public void contribute(ExtensionContext context) {
                    context.contribute(
                        ExtensionPoint.of("fixture.external", Runnable.class),
                        new ExtensionDescriptor("run", %s, List.of("fixture")),
                        (Runnable) () -> {}
                    );
                }
            }
            """.formatted(contributionName), StandardCharsets.UTF_8);
        compile(List.of(source), classes, extraClasspath);
        Path jar = root.resolve("plugin.jar");
        pack(classes, jar, serviceDescriptor);
        return jar;
    }

    private static void compile(
        List<Path> sources,
        Path classes,
        List<Path> extraClasspath
    ) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("tests require a JDK compiler");
        }
        var classpath = new ArrayList<String>();
        classpath.add(System.getProperty("java.class.path"));
        extraClasspath.stream().map(Path::toString).forEach(classpath::add);
        var arguments = new ArrayList<String>();
        arguments.add("--release");
        arguments.add(Integer.toString(Runtime.version().feature()));
        arguments.add("-classpath");
        arguments.add(String.join(java.io.File.pathSeparator, classpath));
        arguments.add("-d");
        arguments.add(classes.toString());
        sources.stream().map(Path::toString).forEach(arguments::add);
        int exit = compiler.run(null, null, null, arguments.toArray(String[]::new));
        assertEquals(0, exit, "dynamic plugin fixture compilation failed");
    }

    private static void pack(Path classes, Path jar, String provider) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = classes.relativize(file).toString().replace('\\', '/');
                    output.putNextEntry(new JarEntry(name));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            if (provider != null) {
                output.putNextEntry(new JarEntry(
                    "META-INF/services/de.regelsuche.extension.RegelsuchePlugin"));
                output.write((provider + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
