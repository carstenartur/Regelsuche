package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import java.nio.file.Path;

/** Writes the real initial #235 bundle and fails closed on incomplete baselines. */
public final class ComparativeBenchmarkMain {
    private ComparativeBenchmarkMain() {
    }

    public static void main(String[] args) {
        Path output = args.length == 0
            ? Path.of("build", "reports", "comparative-benchmarks")
            : Path.of(args[0]);
        var report = ComparativeBenchmarkRunner.system().run();
        new ComparativeBenchmarkBundleWriter().write(output, report);

        long searchResults = report.results().stream()
            .filter(result -> result.track()
                == Track.TARGET_DIRECTED_SEARCH)
            .count();
        long validationResults = report.results().stream()
            .filter(result -> result.track()
                == Track.EQUALITY_VALIDATION)
            .count();
        if (searchResults != 9L || validationResults != 6L) {
            throw new IllegalStateException(
                "unexpected comparative result counts: search="
                    + searchResults + ", validation=" + validationResults);
        }
        if (report.results().stream().anyMatch(result ->
                result.disposition() != Disposition.EXECUTED
                    || !result.correct())) {
            throw new IllegalStateException(
                "comparative benchmark contains incomplete or incorrect results: "
                    + report.toCanonicalJson());
        }
        if (report.claims().stream().anyMatch(claim ->
                claim.status() != ClaimStatus.SUPPORTED)) {
            throw new IllegalStateException(
                "comparative capability claim is not supported: "
                    + report.toCanonicalJson());
        }
    }
}
