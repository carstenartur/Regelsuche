package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
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

final class ComparativeBenchmarkTestSupport {
    private ComparativeBenchmarkTestSupport() {
    }

    static ComparativeBenchmarkRunner completeRunner() {
        List<SearchSystem> searchSystems = List.of(
            new SearchSystem(
                "best-first", "test-1", new BestFirstSearchStrategy(),
                List.of("TEST_FIXTURE")),
            new SearchSystem(
                "a-star", "test-1", new AStarSearchStrategy(),
                List.of("TEST_FIXTURE")),
            new SearchSystem(
                "beam", "test-1", new BeamSearchStrategy(),
                List.of("TEST_FIXTURE")));

        List<ValidationSystem> validationSystems = List.of(
            validationSystem("fixture-polynomial", SystemKind.REGELSUCHE),
            validationSystem("fixture-cas", SystemKind.EXTERNAL_BASELINE),
            validationSystem("fixture-prover", SystemKind.EXTERNAL_BASELINE));
        return new ComparativeBenchmarkRunner(searchSystems, validationSystems);
    }

    static ComparativeBenchmarkRunner runnerWithUnavailableValidation() {
        List<SearchSystem> searchSystems = List.of(
            new SearchSystem(
                "best-first", "test-1", new BestFirstSearchStrategy(),
                List.of("TEST_FIXTURE")));
        ValidationSystem unavailable = new ValidationSystem(
            new FixtureValidationBackend("fixture-unavailable"),
            SystemKind.EXTERNAL_BASELINE,
            List.of(Role.VALIDATION),
            false,
            "fixture=unavailable",
            List.of("BACKEND_UNAVAILABLE"));
        return new ComparativeBenchmarkRunner(searchSystems, List.of(unavailable));
    }

    private static ValidationSystem validationSystem(
        String backendId,
        SystemKind kind
    ) {
        return new ValidationSystem(
            new FixtureValidationBackend(backendId),
            kind,
            List.of(Role.VALIDATION, Role.COUNTEREXAMPLE),
            true,
            "fixture=" + backendId + "@1",
            List.of("TEST_FIXTURE"));
    }

    private static final class FixtureValidationBackend implements SolverBackend {
        private final BackendDescriptor descriptor;

        private FixtureValidationBackend(String backendId) {
            this.descriptor = new BackendDescriptor(
                backendId,
                "1",
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
            ResultStatus status = obligation.obligationId().contains("shift-refuted")
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
                List.of("DETERMINISTIC_TEST_VALIDATION"),
                List.of(),
                "fixture verdict " + status.name(),
                status == ResultStatus.REFUTED
                    ? Map.of("witness", "x=0")
                    : Map.of(),
                SolverIr.sha256(
                    descriptor.backendId() + ':' + obligation.contentHash()
                        + ':' + status.name()));
            return SolverExecution.create(obligation, translation, result);
        }
    }
}
