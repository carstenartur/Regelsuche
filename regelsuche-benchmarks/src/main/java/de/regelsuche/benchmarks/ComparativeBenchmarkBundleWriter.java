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
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Replaces one complete canonical comparative-benchmark evidence bundle. */
public final class ComparativeBenchmarkBundleWriter {

    public void write(Path outputDirectory, Report report) {
        write(outputDirectory, report, Map.of());
    }

    void write(Path outputDirectory, Report report, Map<Path, byte[]> attachments) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(report, "report");
        requireCompleteTrackMatrices(report);
        ComparativeBenchmarkMain.verify(report);
        try {
            ComparativeBenchmarkPublication.replace(outputDirectory.toAbsolutePath().normalize(),
                staging -> writeBundle(staging, report, attachments));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write comparative benchmark evidence", exception);
        }
    }

    private static void writeBundle(Path outputDirectory, Report report, Map<Path, byte[]> attachments)
            throws IOException {
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
        for (var attachment : attachments.entrySet()) {
            Path path = outputDirectory.resolve(attachment.getKey()).normalize();
            if (!path.startsWith(outputDirectory.resolve("v2"))) {
                throw new IOException("Attachment lies outside the public evidence directory: " + attachment.getKey());
            }
            Files.createDirectories(path.getParent());
            Files.write(path, attachment.getValue());
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

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String shortHash(String value) {
        return value.substring("sha256:".length(), "sha256:".length() + 12);
    }
}
