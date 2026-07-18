package de.regelsuche.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line entry point for the autonomous discovery result-card walkthrough. */
public final class AutonomousDiscoveryWalkthroughMain {
    private AutonomousDiscoveryWalkthroughMain() {
    }

    public static void main(String[] args) {
        Arguments options = Arguments.parse(args);
        var card = new AutonomousDiscoveryWalkthroughRunner().run(
            options.outputDirectory(), options.repositoryRevision());
        System.out.println(card.contentHash());
    }

    private record Arguments(Path outputDirectory, String repositoryRevision) {
        static Arguments parse(String[] args) {
            Path output = Path.of(
                "build", "reports", "autonomous-discovery-walkthrough");
            String revision = null;
            List<String> positional = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                if ("--repository-revision".equals(args[index])) {
                    if (index + 1 >= args.length
                            || args[index + 1].startsWith("--")) {
                        throw new IllegalArgumentException(
                            "--repository-revision requires a value");
                    }
                    revision = args[++index];
                } else if (args[index].startsWith("--")) {
                    throw new IllegalArgumentException(
                        "unsupported option: " + args[index]);
                } else {
                    positional.add(args[index]);
                }
            }
            if (positional.size() > 1) {
                throw new IllegalArgumentException(
                    "at most one output directory may be supplied");
            }
            if (!positional.isEmpty()) {
                output = Path.of(positional.getFirst());
            }
            if (revision == null || revision.isBlank()) {
                revision = System.getenv("REGELSUCHE_REPOSITORY_REVISION");
            }
            if (revision == null || revision.isBlank()) {
                revision = gitRevision();
            }
            return new Arguments(output, revision);
        }

        private static String gitRevision() {
            try {
                Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
                String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .trim();
                int exit = process.waitFor();
                if (exit != 0 || !output.matches("[0-9a-f]{40}")) {
                    throw new IllegalStateException(
                        "repository revision is unavailable; set "
                            + "REGELSUCHE_REPOSITORY_REVISION");
                }
                return output;
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "repository revision is unavailable; set "
                        + "REGELSUCHE_REPOSITORY_REVISION",
                    exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "interrupted while resolving repository revision", exception);
            }
        }
    }
}
