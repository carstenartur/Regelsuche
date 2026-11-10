package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Compiled only into signed external artifacts, never the host classpath. */
final class InstalledDiscoveryFixture {
    private InstalledDiscoveryFixture() { }
    static byte[] jar(Path root, String marker, String version, String behavior) throws Exception {
        return jar(root, marker, version, behavior, "v1");
    }
    static byte[] jar(Path root, String marker, String version, String behavior, String revision) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = root.resolve("Provider.java");
        Files.writeString(source, """
            package installeddiscovery;
            import de.regelsuche.extension.*;
            import de.regelsuche.sdk.discovery.*;
            import de.regelsuche.discovery.domain.DiscoveryDomain;
            import de.regelsuche.discovery.domain.DiscoveryDomain.*;
            import java.util.*;
            public final class Provider implements RegelsuchePlugin, DiscoveryDomainProvider {
                static {
                    System.setProperty("%s", "initialized");
                    System.setProperty("%s.stage", Provider.class.getProtectionDomain().getCodeSource().getLocation().toString());
                }
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("discovery-plugin", "Discovery", "%s", "2", "1.0.0",
                        Set.of(), List.of(), "signed-fixture");
                }
                public void contribute(ExtensionContext context) {
                    context.contribute(DiscoveryExtensionPoints.DOMAIN_PROVIDERS,
                        ExtensionDescriptor.named("search"), this);
                }
                public String id() { return "installed-provider"; }
                public String version() { return "%s"; }
                public Collection<DiscoveryDomain<?, ?, ?>> domains() {
                    if ("%s".equals("domains-failure")) throw new IllegalStateException("jdbc:secret-password");
                    if ("%s".equals("forbidden")) {
                        try { Class.forName("de.regelsuche.plugin.PluginPackageCommand"); }
                        catch (ClassNotFoundException expected) { throw new IllegalStateException("forbidden", expected); }
                        throw new AssertionError("App type escaped host boundary");
                    }
                    var built = DiscoveryDomainBuilder.<Integer,Integer,Integer>domain("installed-search", "%s")
                        .generator(seed -> List.of(0)).stateCodec(Object::toString)
                        .invariant("positive", value -> InvariantResult.pass())
                        .operator("next", value -> value < 3 ? List.of(new Successor<>("increment", value+1, 1, true, List.of(), Map.of())) : List.of())
                        .objective(value -> new ObjectiveAssessment(value, value == 3, Map.of()))
                        .candidate(context -> context.currentState(), Object::toString)
                        .counterexamples((candidate,budget) -> CounterexampleResult.noneFound(1, Map.of()))
                        .evaluator(candidate -> Evaluation.confirmed(candidate, "bounded fixture", Map.of()))
                        .certificate("FIXTURE", Object::toString, value -> "certificate=" + value + resource()).build();
                    DiscoveryDomain<?,?,?> domain = (DiscoveryDomain<?,?,?>) java.lang.reflect.Proxy.newProxyInstance(
                        Provider.class.getClassLoader(), new Class<?>[]{DiscoveryDomain.class}, (proxy, method, args) -> {
                            if (method.getName().equals("evidenceAdapter")) return new EvidenceAdapter<Integer,Integer,Integer>() {
                                public String id() { return "late-evidence"; }
                                public DomainPayload adapt(Optional<Integer> initial, Optional<Integer> candidate, Optional<Integer> certificate) {
                                    if ("%s".equals("evidence-failure")) throw new IllegalStateException("jdbc:secret-password".repeat(100000));
                                    return new DomainPayload("fixture", Map.of("lateResource", resource()));
                                }
                            };
                            return method.invoke(built, args);
                        });
                    return List.of(domain);
                }
                private static String resource() {
                    try (var input = Provider.class.getResourceAsStream("/late.txt")) {
                        if (input == null) throw new IllegalStateException("runtime closed early");
                        var url = Provider.class.getResource("/late.txt");
                        System.setProperty("%s.resource", url.toString());
                        return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                }
            }
            """.formatted(marker, marker, version, version, behavior, behavior, revision, behavior, marker));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    entry(jar, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            entry(jar, "META-INF/services/de.regelsuche.extension.RegelsuchePlugin", "installeddiscovery.Provider\n".getBytes(StandardCharsets.UTF_8));
            entry(jar, "late.txt", ("late-" + version).getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
    record Closure(byte[] contributor, byte[] provider) { }
    static Closure closure(Path root, String marker) throws Exception {
        byte[] combined = jar(root, marker, "1.0.0", "normal");
        var provider = new ByteArrayOutputStream();
        try (var input = new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(combined));
                var output = new JarOutputStream(provider)) {
            for (var item = input.getNextJarEntry(); item != null; item = input.getNextJarEntry()) {
                if (!item.getName().startsWith("META-INF/services/")) entry(output,item.getName(),input.readAllBytes());
            }
        }
        Path source = root.resolve("Contributor.java");
        Path classes = Files.createDirectories(root.resolve("contributor"));
        Files.writeString(source, """
            package installeddiscovery;
            import de.regelsuche.extension.*;
            import de.regelsuche.sdk.discovery.*;
            import java.util.*;
            public final class Contributor implements RegelsuchePlugin {
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("contributor", "Contributor", "1.0.0", "2", "1.0.0",Set.of(),List.of(),"fixture");
                }
                public void contribute(ExtensionContext context) {
                    context.contribute(DiscoveryExtensionPoints.DOMAIN_PROVIDERS,ExtensionDescriptor.named("search"),new Provider());
                }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","-classpath",
            System.getProperty("java.class.path") + java.io.File.pathSeparator + root.resolve("classes"),
            "-d",classes.toString(),source.toString()));
        var contributor = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(contributor)) {
            entry(output,"installeddiscovery/Contributor.class",Files.readAllBytes(classes.resolve("installeddiscovery/Contributor.class")));
            entry(output,"META-INF/services/de.regelsuche.extension.RegelsuchePlugin","installeddiscovery.Contributor\n".getBytes(StandardCharsets.UTF_8));
        }
        return new Closure(contributor.toByteArray(),provider.toByteArray());
    }
    private static void entry(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        var entry = new JarEntry(name); entry.setTime(0); jar.putNextEntry(entry); jar.write(bytes); jar.closeEntry();
    }
}
