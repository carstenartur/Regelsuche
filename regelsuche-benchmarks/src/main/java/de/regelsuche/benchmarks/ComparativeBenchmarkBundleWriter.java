package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.CoverageGap;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Replaces one complete canonical comparative-benchmark evidence bundle. */
public final class ComparativeBenchmarkBundleWriter {

    public void write(Path outputDirectory, Report report) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(report, "report");
        requireCompleteTrackMatrices(report);
        try {
            clearContents(outputDirectory);
            Files.createDirectories(outputDirectory);
            writeFile(
                outputDirectory.resolve("report.json"),
                report.toCanonicalJson());

            for (var manifest : report.parityManifests()) {
                writeFile(
                    outputDirectory.resolve("parity-manifests")
                        .resolve(safe(manifest.id()) + ".json"),
                    manifest.toCanonicalJson());
            }
            for (var configuration : report.configurations()) {
                writeFile(
                    outputDirectory.resolve("configurations")
                        .resolve(safe(configuration.id()) + ".json"),
                    configuration.toCanonicalJson());
            }
            for (var benchmarkCase : report.cases()) {
                writeFile(
                    outputDirectory.resolve("cases")
                        .resolve(safe(benchmarkCase.id()) + ".json"),
                    benchmarkCase.toCanonicalJson());
            }
            for (int index = 0; index < report.results().size(); index++) {
                var result = report.results().get(index);
                String filename = String.format(
                    "%03d-%s-%s.json",
                    index,
                    shortHash(result.configurationHash()),
                    shortHash(result.caseHash()));
                writeFile(
                    outputDirectory.resolve("results").resolve(filename),
                    result.toCanonicalJson());
            }
            for (CapabilityClaim claim : report.claims()) {
                writeFile(
                    outputDirectory.resolve("claims")
                        .resolve(safe(claim.id()) + ".json"),
                    claimJson(claim));
            }
            for (CoverageGap gap : report.coverageGaps()) {
                writeFile(
                    outputDirectory.resolve("coverage-gaps")
                        .resolve(gap.track().name().toLowerCase() + ".json"),
                    gapJson(gap));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write comparative benchmark evidence", exception);
        }
    }

    /**
     * Requires the Cartesian product of every configuration and every case in
     * the same track. A partially executed benchmark must never replace a prior
     * complete evidence bundle.
     */
    private static void requireCompleteTrackMatrices(Report report) {
        Set<String> expected = new HashSet<>();
        for (var configuration : report.configurations()) {
            boolean hasCase = false;
            for (var benchmarkCase : report.cases()) {
                if (benchmarkCase.track() == configuration.track()) {
                    hasCase = true;
                    expected.add(pair(
                        configuration.contentHash(), benchmarkCase.contentHash()));
                }
            }
            if (!hasCase) {
                throw new IllegalArgumentException(
                    "comparative configuration has no case in its track: "
                        + configuration.id());
            }
        }
        for (var benchmarkCase : report.cases()) {
            boolean hasConfiguration = report.configurations().stream()
                .anyMatch(configuration ->
                    configuration.track() == benchmarkCase.track());
            if (!hasConfiguration) {
                throw new IllegalArgumentException(
                    "comparative case has no configuration in its track: "
                        + benchmarkCase.id());
            }
        }

        Set<String> actual = new HashSet<>();
        report.results().forEach(result -> actual.add(pair(
            result.configurationHash(), result.caseHash())));
        if (!actual.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new HashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                "comparative result matrix is incomplete; missing=" + missing
                    + "; unexpected=" + unexpected);
        }
    }

    private static String pair(String configurationHash, String caseHash) {
        return configurationHash + '|' + caseHash;
    }

    private static String claimJson(CapabilityClaim claim) {
        return claim.write(new JsonWriter().beginObject())
            .endObject()
            .toString();
    }

    private static String gapJson(CoverageGap gap) {
        return gap.write(new JsonWriter().beginObject())
            .endObject()
            .toString();
    }

    private static void writeFile(Path path, String content)
            throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Deletes every previous evidence entry while preserving the requested root.
     * The root may be a Docker volume mount and therefore must never be removed.
     */
    private static void clearContents(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Path normalizedRoot = directory.toAbsolutePath().normalize();
        try (var paths = Files.walk(normalizedRoot)) {
            for (Path path : paths
                    .filter(item -> !item.equals(normalizedRoot))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.delete(path);
            }
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String shortHash(String value) {
        return value.substring("sha256:".length(), "sha256:".length() + 12);
    }
}
