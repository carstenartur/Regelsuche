package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.benchmarks.ComparativeBenchmark.Result;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the real #235 bundle and fails closed on overclaiming.
 *
 * <p>The gate deliberately does <em>not</em> require every configured system to
 * win. A comparative benchmark that can only be published when the local system
 * succeeds everywhere cannot produce information about competing systems. What
 * must not happen is an incomplete toolchain silently replacing prior evidence,
 * or a claim that is stronger than its own track's retained results.</p>
 */
public final class ComparativeBenchmarkMain {
    private ComparativeBenchmarkMain() {
    }

    public static void main(String[] args) {
        Path output = args.length == 0
            ? Path.of("build", "reports", "comparative-benchmarks")
            : Path.of(args[0]);
        Report report = ComparativeBenchmarkRunner.system().run();
        new ComparativeBenchmarkBundleWriter().write(output, report);
        verify(report);
    }

    static void verify(Report report) {
        List<Track> measuredTracks = report.configurations().stream()
            .map(Configuration::track)
            .distinct()
            .sorted()
            .toList();
        for (Track track : measuredTracks) {
            long configurations = report.configurations().stream()
                .filter(configuration -> configuration.track() == track)
                .count();
            long cases = report.cases().stream()
                .filter(benchmarkCase -> benchmarkCase.track() == track)
                .count();
            long expected = Math.multiplyExact(configurations, cases);
            long actual = report.results().stream()
                .filter(result -> result.track() == track)
                .count();
            if (actual != expected) {
                throw new IllegalStateException(
                    "incomplete comparative result matrix for " + track
                        + ": expected " + expected + ", found " + actual);
            }
        }

        // An unavailable, timed-out or crashed system means the evidence was
        // not produced at all. That must never replace a prior complete bundle.
        List<Result> notExecuted = report.results().stream()
            .filter(result -> result.disposition() != Disposition.EXECUTED)
            .toList();
        if (!notExecuted.isEmpty()) {
            throw new IllegalStateException(
                "comparative benchmark did not execute every configured system: "
                    + report.toCanonicalJson());
        }

        // Retained incorrect results are legitimate evidence, but they must be
        // reflected in the claim of their own track.
        for (CapabilityClaim claim : report.claims()) {
            List<Result> trackResults = report.results().stream()
                .filter(result -> result.track() == claim.track())
                .toList();
            if (trackResults.isEmpty()) {
                throw new IllegalStateException(
                    "comparative claim without track evidence: " + claim.id());
            }
            ClaimStatus derived = trackResults.stream().allMatch(Result::correct)
                ? ClaimStatus.SUPPORTED
                : ClaimStatus.NEGATIVE;
            if (claim.status() != derived) {
                throw new IllegalStateException(
                    "comparative claim status is not derived from its own track: "
                        + claim.id() + " declares " + claim.status()
                        + " but its results imply " + derived);
            }
        }

        for (Track track : measuredTracks) {
            if (report.claims().stream()
                    .noneMatch(claim -> claim.track() == track)) {
                throw new IllegalStateException(
                    "comparative track without a retained claim: " + track);
            }
        }
    }
}
