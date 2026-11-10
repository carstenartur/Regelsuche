package de.regelsuche.plugin;

import de.regelsuche.extension.ExtensionOrigin.OriginKind;
import de.regelsuche.sdk.discovery.DiscoveryDomainCatalog;
import de.regelsuche.sdk.discovery.DiscoveryExtensionPoints;
import de.regelsuche.sdk.discovery.cli.DiscoveryCli;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceConfigurationError;

/** One current installed snapshot, retained through synchronous canonical evidence export. */
public final class InstalledDiscoveryCommand {
    private InstalledDiscoveryCommand() { }

    public static int run(String[] args, PrintStream out, Map<String, String> environment) {
        return run(args, out, environment, PluginPackageSession::open);
    }

    @FunctionalInterface
    interface SessionFactory {
        PluginPackageSession open(PluginPackageCommand.Config config, Map<String, String> environment) throws IOException;
    }

    static int run(String[] args, PrintStream out, Map<String, String> environment, SessionFactory sessions) {
        String category = "REQUEST";
        try {
            String[] request = request(args);
            category = "CONFIGURATION";
            var config = PluginPackageCommand.readConfiguration(PluginPackageCommand.absolute(args[1]));
            category = "ACTIVATION";
            try (var session = sessions.open(config, environment)) {
                var defaults = InstalledExtensionRuntime.Options.defaults();
                var options = new InstalledExtensionRuntime.Options(defaults.coreCompatibilityVersion(),
                    defaults.parentClassLoader(), InstalledDiscoveryHostTypes.TYPES);
                try (var runtime = InstalledExtensionRuntime.open(session.transactions(), options)) {
                    String installation = runtime.installation().map(PluginInstallationEvidence::contentHash).orElse("");
                    var extensions = runtime.catalog();
                    for (var entry : extensions.registrations(DiscoveryExtensionPoints.DOMAIN_PROVIDERS)) {
                        var origin = entry.origin();
                        if (origin.kind() != OriginKind.EXTERNAL_PLUGIN || origin.artifactSha256().isEmpty()
                                || installation.isEmpty() || !installation.equals(origin.trustEvidenceSha256())) {
                            throw new SecurityException("contribution does not belong to selected installation");
                        }
                    }
                    category = "PROVIDER";
                    var catalog = DiscoveryDomainCatalog.fromExtensions(extensions);
                    category = "EXECUTION";
                    try (var result = new CommandOutput()) {
                        int code = DiscoveryCli.execute(request, result, catalog);
                        if (code == 1) throw new DiscoveryRequestFailure();
                        if (request[0].equals("list")) out.println("installation=" +
                            (installation.isEmpty() ? "NONE (pre-genesis)" : installation));
                        result.publish(out);
                        return code;
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError | ServiceConfigurationError failure) {
            out.println("Installed discovery failed: " + category + " (" + failure.getClass().getSimpleName() + ")");
            return 1;
        }
    }

    private static String[] request(String[] args) {
        if (args.length < 3 || !args[0].equals("--config")) throw new IllegalArgumentException();
        String[] request = Arrays.copyOfRange(args, 2, args.length);
        for (String arg : request) if (arg.startsWith("--")) throw new IllegalArgumentException();
        if (request.length == 1 && request[0].equals("list")) return request;
        if ((request.length != 6 && request.length != 7) || !request[0].equals("run")) throw new IllegalArgumentException();
        // DomainCanonical and DiscoveryDomainBuilder use this identifier contract for both fields.
        String identifier = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}";
        if (!request[2].matches(identifier + "@" + identifier))
            throw new IllegalArgumentException();
        if (request.length == 7 && !request[6].equals("small") && !request[6].equals("tiny"))
            throw new IllegalArgumentException();
        return request;
    }

    /** The SDK deliberately retains legacy diagnostics. Never encode or retain those untrusted messages here. */
    private static final class CommandOutput extends PrintStream {
        private final StringBuilder lines = new StringBuilder();
        CommandOutput() { super(OutputStream.nullOutputStream()); }
        @Override public void println(String line) {
            if (line.startsWith("Discovery request failed:")) return;
            if (line.length() > 1_048_576 - lines.length() - 1) throw new IllegalStateException("command output limit");
            lines.append(line).append('\n');
        }
        void publish(PrintStream out) { out.print(lines); }
    }

    /** The SDK reports request failures as code 1 rather than exposing the underlying exception. */
    private static final class DiscoveryRequestFailure extends RuntimeException { }
}
