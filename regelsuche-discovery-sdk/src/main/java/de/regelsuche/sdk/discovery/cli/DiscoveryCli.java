package de.regelsuche.sdk.discovery.cli;

import de.regelsuche.sdk.discovery.*;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.Set;

/** Headless provider selection and canonical run export. No app runtime is required. */
public final class DiscoveryCli {
    private DiscoveryCli() { }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, Thread.currentThread().getContextClassLoader()));
    }

    /** Runs synchronously; returns distinct status codes and never overwrites evidence. */
    public static int execute(String[] args, PrintStream out, ClassLoader loader) {
        try {
            if (args.length == 1 && args[0].equals("list")) {
                ServiceLoader.load(DiscoveryDomainProvider.class, loader).stream()
                    .map(provider -> provider.type().getName()).sorted().forEach(out::println);
                return 0;
            }
            if (args.length == 2 && args[0].equals("list")) {
                var catalog = DiscoveryDomainCatalog.load(loader, Set.of(args[1]));
                for (var entry : catalog.registrations()) {
                    out.println(entry.domain().domainId() + "@" + entry.domain().revision()
                        + " provider=" + entry.providerId() + " artifact="
                        + entry.artifact().orElseThrow().artifactSha256());
                }
                return 0;
            }
            if ((args.length == 6 || args.length == 7) && args[0].equals("run")) {
                var budget = args.length == 6 ? DiscoveryBudgets.small() : switch (args[6]) {
                    case "small" -> DiscoveryBudgets.small();
                    case "tiny" -> DiscoveryBudgets.tiny();
                    default -> throw new IllegalArgumentException("budget must be small or tiny");
                };
                int separator = args[2].lastIndexOf('@');
                if (separator < 1) throw new IllegalArgumentException("select domain-id@revision");
                var registration = DiscoveryDomainCatalog.load(loader, Set.of(args[1]))
                    .find(args[2].substring(0, separator), args[2].substring(separator + 1))
                    .orElseThrow(() -> new IllegalArgumentException("domain revision is not installed"));
                byte[] seedBytes;
                try (var stream = Files.newInputStream(Path.of(args[4]))) {
                    seedBytes = stream.readNBytes(1024 * 1024 + 1);
                }
                if (seedBytes.length > 1024 * 1024) throw new IllegalArgumentException("seed exceeds 1 MiB");
                String seed = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(seedBytes)).toString();
                var run = RegelsucheDiscovery.forRegistration(registration)
                    .campaign(args[3]).seed("cli-seed", seed, "discovery-cli")
                    .budget(budget).run();
                run.writeEvidence(Path.of(args[5]));
                out.println("outcome=" + run.outcome());
                out.println("evidenceHash=" + run.evidence().contentHash());
                return switch (run.outcome()) {
                    case CONFIRMED -> 0;
                    case REFUTED -> 2;
                    case BUDGET_EXHAUSTED -> 3;
                    case INCONCLUSIVE -> 4;
                    case UNSUPPORTED -> 5;
                    case INVALID_SEED -> 6;
                };
            }
            out.println("Usage: domains list [provider.class] | domains run provider.class "
                + "domain-id@revision campaign-id seed.txt evidence.json [small|tiny]");
            return 1;
        } catch (IOException | IllegalArgumentException | IllegalStateException | java.util.ServiceConfigurationError ex) {
            out.println("Discovery request failed: " + ex.getMessage());
            return 1;
        }
    }
}
