package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.benchmarks.ComparativeBenchmark.ResourceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.ValidationSystem;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverTranslation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ComparativeBenchmarkRunnerTest {

    @Test
    void fixedSystemsProduceEquivalentTrackScopedReport() {
        ComparativeBenchmarkRunner runner = runner(true);

        Report first = runner.run();
        Report second = runner.run();

        assertEquals(36, first.results().size());
        assertEquals(3, first.claims().size());
        assertEquals(7, first.coverageGaps().size());
        assertTrue(first.results().stream()
            .filter(result -> result.track() != Track.SIMPLIFICATION_COMPETITION)
            .allMatch(result -> result.correct()));
        assertTrue(first.claims().stream()
            .filter(claim -> claim.track() != Track.SIMPLIFICATION_COMPETITION)
            .allMatch(claim -> claim.status() == ClaimStatus.SUPPORTED));
        assertEquals(ComparativeBenchmark.SCORE_POLICY, first.scorePolicy());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertFalse(first.toCanonicalJson().contains("elapsedMillis"));
    }

    @Test
    void unavailableExternalBaselineRemainsVisibleAndBlocksClaim() {
        Report report = runner(false).run();

        assertTrue(report.results().stream().anyMatch(result ->
            result.observedVerdict()
                == ComparativeBenchmark.ObservedVerdict.UNSUPPORTED));
        CapabilityClaim validation = report.claims().stream()
            .filter(claim -> claim.track() == Track.EQUALITY_VALIDATION)
            .findFirst()
            .orElseThrow();
        assertEquals(ClaimStatus.INSUFFICIENT_EVIDENCE, validation.status());
    }

    @Test
    void simplificationCompetitorsNeverSeeTheReferenceForm() {
        Report report = runner(true).run();

        var parity = report.parityManifests().stream()
            .filter(manifest ->
                manifest.track() == Track.SIMPLIFICATION_COMPETITION)
            .findFirst()
            .orElseThrow();
        assertFalse(parity.targetVisible());
        assertFalse(parity.hiddenReferenceVisible());
        assertEquals(3L, report.configurations().stream()
            .filter(configuration ->
                configuration.track() == Track.SIMPLIFICATION_COMPETITION)
            .count());
        assertTrue(report.configurations().stream()
            .filter(configuration ->
                configuration.track() == Track.SIMPLIFICATION_COMPETITION)
            .anyMatch(configuration ->
                configuration.kind() == SystemKind.EXTERNAL_BASELINE));
        assertTrue(report.configurations().stream()
            .filter(configuration ->
                configuration.track() == Track.SIMPLIFICATION_COMPETITION)
            .anyMatch(configuration ->
                configuration.kind() == SystemKind.ABLATION));
    }

    @Test
    void randomControlBindsSeedAndResetsForEveryRepeatedRun() {
        ComparativeBenchmarkRunner firstRunner = runner(true);
        ComparativeBenchmarkRunner secondRunner = runner(true);

        Report first = firstRunner.run();
        Report repeatedOnSameRunner = firstRunner.run();
        Report independent = secondRunner.run();

        assertEquals(first.contentHash(), repeatedOnSameRunner.contentHash());
        assertEquals(first.contentHash(), independent.contentHash());
        var randomConfiguration = first.configurations().stream()
            .filter(configuration -> configuration.backendId().equals(
                "randomized-valid-rewrite-control"))
            .findFirst()
            .orElseThrow();
        var bestFirstConfiguration = first.configurations().stream()
            .filter(configuration -> configuration.backendId().equals(
                "regelsuche-untargeted-best-first"))
            .findFirst()
            .orElseThrow();
        assertEquals(SystemKind.ABLATION, randomConfiguration.kind());
        assertNotEquals(
            bestFirstConfiguration.policyHash(),
            randomConfiguration.policyHash()
        );
        assertTrue(randomConfiguration.limitations().contains(
            "FIXED_SEED_BOUND_IN_CONFIGURATION_IDENTITY"));
        assertEquals(7L, first.results().stream()
            .filter(result -> result.configurationHash().equals(
                randomConfiguration.contentHash()))
            .count());
    }

    @Test
    void externalCompetitorOutperformsUntargetedRegelsucheSearch() {
        Assumptions.assumeTrue(
            ExternalSymPySimplificationBaseline.detectSystemSymPy().available(),
            "SymPy is required for the head-to-head simplification track");
        Report report = runner(true).run();

        assertEquals(7L, correctResults(report, "sympy-cas-simplifier"));
        assertEquals(
            6L, correctResults(report, "regelsuche-untargeted-best-first"));
        var claim = report.claims().stream()
            .filter(candidate ->
                candidate.track() == Track.SIMPLIFICATION_COMPETITION)
            .findFirst()
            .orElseThrow();
        assertEquals(ClaimStatus.NEGATIVE, claim.status());
    }

    @Test
    void losingHeadToHeadResultsAreRetainedInsteadOfFailingTheGate() {
        Assumptions.assumeTrue(
            ExternalSymPySimplificationBaseline.detectSystemSymPy().available(),
            "SymPy is required for the head-to-head simplification track");
        Report report = runner(true).run();

        assertTrue(report.results().stream()
            .filter(result ->
                result.track() == Track.SIMPLIFICATION_COMPETITION)
            .anyMatch(result -> !result.correct()));
        assertTrue(report.results().stream()
            .filter(result ->
                result.track() == Track.SIMPLIFICATION_COMPETITION)
            .allMatch(result ->
                result.disposition() == Disposition.EXECUTED));
        ComparativeBenchmarkMain.verify(report);
    }

    @Test
    void aClaimStrongerThanItsOwnTrackIsRejected() {
        Assumptions.assumeTrue(
            ExternalSymPySimplificationBaseline.detectSystemSymPy().available(),
            "SymPy is required for the head-to-head simplification track");
        Report report = runner(true).run();
        List<CapabilityClaim> overclaimed = report.claims().stream()
            .map(claim -> claim.track() == Track.SIMPLIFICATION_COMPETITION
                ? CapabilityClaim.create(
                    claim.id(),
                    claim.track(),
                    ClaimStatus.SUPPORTED,
                    claim.statement(),
                    claim.evidenceResultHashes(),
                    claim.limitations())
                : claim)
            .toList();
        Report overclaiming = Report.create(
            report.suiteId(),
            report.parityManifests(),
            report.configurations(),
            report.cases(),
            report.results(),
            overclaimed,
            report.coverageGaps());

        assertThrows(IllegalStateException.class, () ->
            ComparativeBenchmarkMain.verify(overclaiming));
    }

    private static long correctResults(Report report, String backendId) {
        var configuration = report.configurations().stream()
            .filter(candidate -> candidate.backendId().equals(backendId))
            .findFirst()
            .orElseThrow();
        return report.results().stream()
            .filter(result -> result.configurationHash()
                .equals(configuration.contentHash()))
            .filter(result -> result.correct())
            .count();
    }

    @Test
    void universalScorePolicyIsRejected() {
        Report report = runner(true).run();

        assertThrows(IllegalArgumentException.class, () -> new Report(
            report.schema(),
            report.suiteId(),
            report.parityManifests(),
            report.configurations(),
            report.cases(),
            report.results(),
            report.claims(),
            report.coverageGaps(),
            "UNIVERSAL_LEADERBOARD",
            report.contentHash()));
    }

    @Test
    void claimCannotBorrowEvidenceFromAnotherTrack() {
        Report report = runner(true).run();
        String searchResultHash = report.results().stream()
            .filter(result -> result.track() == Track.TARGET_DIRECTED_SEARCH)
            .findFirst()
            .orElseThrow()
            .contentHash();
        CapabilityClaim invalid = CapabilityClaim.create(
            "cross-track-invalid",
            Track.EQUALITY_VALIDATION,
            ClaimStatus.SUPPORTED,
            "invalid cross-track claim",
            List.of(searchResultHash),
            List.of("TEST_ONLY"));

        assertThrows(IllegalArgumentException.class, () -> Report.create(
            report.suiteId(),
            report.parityManifests(),
            report.configurations(),
            report.cases(),
            report.results(),
            List.of(invalid),
            report.coverageGaps()));
    }

    @Test
    void configuredWorkMustBalance() {
        assertThrows(IllegalArgumentException.class, () ->
            new ResourceMetrics(3, 1, 1, 0, 0, 0, -1, 0, 1, 0));
    }

    private static ComparativeBenchmarkRunner runner(boolean externalAvailable) {
        List<SearchSystem> search = List.of(
            new SearchSystem("best-first", "test", new BestFirstSearchStrategy(), List.of()),
            new SearchSystem("a-star", "test", new AStarSearchStrategy(), List.of()),
            new SearchSystem("beam", "test", new BeamSearchStrategy(), List.of()));
        List<ValidationSystem> validation = List.of(
            system("internal-polynomial", SystemKind.REGELSUCHE, true),
            system("external-cas", SystemKind.EXTERNAL_BASELINE, externalAvailable),
            system("external-prover", SystemKind.EXTERNAL_BASELINE, true));
        return new ComparativeBenchmarkRunner(
            search, validation, simplificationSystems());
    }

    private static List<SimplificationSystem> simplificationSystems() {
        return List.of(
            SimplificationSystem.internal(
                "regelsuche-untargeted-best-first",
                "test",
                new BestFirstSearchStrategy(),
                List.of()),
            SimplificationSystem.internalControl(
                "randomized-valid-rewrite-control",
                "test",
                new DeterministicRandomValidRewriteStrategy(),
                List.of(
                    "FIXED_SEED_BOUND_IN_CONFIGURATION_IDENTITY",
                    "NO_TARGET_AND_NO_REFERENCE_FORM_VISIBLE")),
            SimplificationSystem.external(
                ExternalSymPySimplificationBaseline.detectSystemSymPy(),
                List.of()));
    }

    private static ValidationSystem system(
        String id,
        SystemKind kind,
        boolean available
    ) {
        return new ValidationSystem(
            new DeterministicFakeBackend(id),
            kind,
            List.of(Role.VALIDATION, Role.COUNTEREXAMPLE),
            available,
            "test-environment:" + id,
            available ? List.of() : List.of("BACKEND_UNAVAILABLE"));
    }

    private static final class DeterministicFakeBackend
            implements SolverBackend {
        private final BackendDescriptor descriptor;

        private DeterministicFakeBackend(String id) {
            descriptor = new BackendDescriptor(
                id,
                "test",
                List.of(Theory.REAL_ARITHMETIC),
                List.of(Relation.EQUALS),
                List.of(RequestedEvidence.SYMBOLIC_CERTIFICATE),
                true);
        }

        @Override
        public BackendDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public SolverExecution execute(Obligation obligation) {
            ResultStatus status = obligation.obligationId().contains("refuted")
                ? ResultStatus.REFUTED
                : ResultStatus.CONFIRMED;
            SolverTranslation translation = SolverTranslation.create(
                obligation,
                descriptor,
                TranslationStatus.LOSSLESS,
                List.of(),
                Map.of(
                    "goal.left", obligation.goal().left().canonicalMaterial(),
                    "goal.right", obligation.goal().right().canonicalMaterial()));
            SolverResult result = SolverResult.create(
                obligation,
                descriptor,
                status,
                TranslationStatus.LOSSLESS,
                List.of("DETERMINISTIC_TEST_BACKEND"),
                List.of(),
                status.name(),
                Map.of(),
                SolverIr.sha256(
                    descriptor.backendId() + ':' + status.name()
                        + ':' + obligation.contentHash()));
            return SolverExecution.create(obligation, translation, result);
        }
    }
}
