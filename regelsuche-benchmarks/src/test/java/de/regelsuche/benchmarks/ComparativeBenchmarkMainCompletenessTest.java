package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.EvidenceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.ExpectedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.InformationParityManifest;
import de.regelsuche.benchmarks.ComparativeBenchmark.ObservedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.benchmarks.ComparativeBenchmark.ResourceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.Result;
import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.solver.ir.SolverIr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparativeBenchmarkMainCompletenessTest {
    @Test
    void caseOnlyTrackIsRejectedInsteadOfPassingAsZeroByZero() {
        Report baseline = baselineReport();
        Case orphan = benchmarkCase(
            "case-only-open-target",
            Track.OPEN_TARGET_DISCOVERY);
        Report incomplete = Report.create(
            baseline.suiteId(),
            baseline.parityManifests(),
            baseline.configurations(),
            appended(baseline.cases(), orphan),
            baseline.results(),
            baseline.claims(),
            baseline.coverageGaps());

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ComparativeBenchmarkMain.verify(incomplete));

        assertTrue(error.getMessage().contains("OPEN_TARGET_DISCOVERY"));
        assertTrue(error.getMessage().contains("configurations=0"));
        assertTrue(error.getMessage().contains("cases=1"));
    }

    @Test
    void configurationOnlyTrackIsRejectedInsteadOfPassingAsZeroByZero() {
        Report baseline = baselineReport();
        InformationParityManifest orphanManifest = manifest(
            "configuration-only-open-target/v1",
            Track.OPEN_TARGET_DISCOVERY);
        Configuration orphanConfiguration = configuration(
            "configuration-only-open-target",
            Track.OPEN_TARGET_DISCOVERY,
            orphanManifest);
        Report incomplete = Report.create(
            baseline.suiteId(),
            appended(baseline.parityManifests(), orphanManifest),
            appended(baseline.configurations(), orphanConfiguration),
            baseline.cases(),
            baseline.results(),
            baseline.claims(),
            baseline.coverageGaps());

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ComparativeBenchmarkMain.verify(incomplete));

        assertTrue(error.getMessage().contains("OPEN_TARGET_DISCOVERY"));
        assertTrue(error.getMessage().contains("configurations=1"));
        assertTrue(error.getMessage().contains("cases=0"));
    }

    private static Report baselineReport() {
        InformationParityManifest manifest = manifest(
            "baseline-search/v1",
            Track.TARGET_DIRECTED_SEARCH);
        Configuration configuration = configuration(
            "baseline-search",
            Track.TARGET_DIRECTED_SEARCH,
            manifest);
        Case benchmarkCase = benchmarkCase(
            "baseline-search-case",
            Track.TARGET_DIRECTED_SEARCH);
        Result result = Result.create(
            configuration,
            benchmarkCase,
            Disposition.EXECUTED,
            ObservedVerdict.TARGET_REACHED,
            new ResourceMetrics(1, 1, 0, 0, 1, 0, 0, 1, 1, 1),
            new EvidenceMetrics(
                "TARGET_REACHED",
                "NOT_REQUESTED",
                "NOT_REQUESTED",
                "",
                List.of()),
            Map.of("targetReached", 1L),
            List.of());
        CapabilityClaim claim = CapabilityClaim.create(
            "baseline-search-claim",
            Track.TARGET_DIRECTED_SEARCH,
            ClaimStatus.SUPPORTED,
            "The configured baseline reached its target.",
            List.of(result.contentHash()),
            List.of("TEST_ONLY"));
        return Report.create(
            "comparative-completeness-test/v1",
            List.of(manifest),
            List.of(configuration),
            List.of(benchmarkCase),
            List.of(result),
            List.of(claim),
            List.of());
    }

    private static InformationParityManifest manifest(
        String id,
        Track track
    ) {
        return InformationParityManifest.create(
            id,
            track,
            true,
            false,
            false,
            false,
            false,
            false,
            SolverIr.sha256(id + ":input-corpus"),
            SolverIr.sha256(id + ":inventory"),
            SolverIr.sha256(id + ":budget"),
            SolverIr.sha256(id + ":research-brief"),
            SolverIr.sha256(id + ":qualification-split"),
            List.of("TEST_ONLY"));
    }

    private static Configuration configuration(
        String id,
        Track track,
        InformationParityManifest manifest
    ) {
        return Configuration.create(
            id,
            track,
            SystemKind.REGELSUCHE,
            List.of(Role.SEARCH),
            manifest.contentHash(),
            id + "-backend",
            "test",
            SolverIr.sha256(id + ":policy"),
            SolverIr.sha256(id + ":model"),
            SolverIr.sha256(id + ":environment"),
            true,
            List.of("TEST_ONLY"));
    }

    private static Case benchmarkCase(String id, Track track) {
        return Case.create(
            id,
            track,
            "test-family",
            "x + 0",
            "x",
            List.of(),
            ExpectedVerdict.TARGET_REACHED);
    }

    private static <T> List<T> appended(List<T> values, T extra) {
        List<T> result = new ArrayList<>(values);
        result.add(extra);
        return List.copyOf(result);
    }
}
