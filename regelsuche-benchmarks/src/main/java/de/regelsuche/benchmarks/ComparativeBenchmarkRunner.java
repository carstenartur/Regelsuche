package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.InformationParityManifest;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.benchmarks.ComparativeBenchmark.Result;
import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.ValidationSystem;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.solver.ir.PolynomialNormalFormSolverBackend;
import de.regelsuche.solver.portfolio.BackendAvailability;
import de.regelsuche.solver.portfolio.Z3SmtSolverBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Initial executable #235 slice with honest track-scoped capability claims. */
public final class ComparativeBenchmarkRunner {
    public static final String SUITE_ID =
        "comparative-baselines-initial/v1";

    private final List<SearchSystem> searchSystems;
    private final List<ValidationSystem> validationSystems;
    private final List<SimplificationSystem> simplificationSystems;
    private final ComparativeBenchmarkExecutor executor =
        new ComparativeBenchmarkExecutor();

    ComparativeBenchmarkRunner(
        List<SearchSystem> searchSystems,
        List<ValidationSystem> validationSystems,
        List<SimplificationSystem> simplificationSystems
    ) {
        this.searchSystems = List.copyOf(
            Objects.requireNonNull(searchSystems, "searchSystems"));
        this.validationSystems = List.copyOf(
            Objects.requireNonNull(validationSystems, "validationSystems"));
        this.simplificationSystems = List.copyOf(
            Objects.requireNonNull(
                simplificationSystems, "simplificationSystems"));
        if (this.searchSystems.isEmpty()
                || this.validationSystems.isEmpty()
                || this.simplificationSystems.isEmpty()) {
            throw new IllegalArgumentException(
                "comparative benchmark systems must not be empty");
        }
    }

    public static ComparativeBenchmarkRunner system() {
        List<SearchSystem> search = List.of(
            new SearchSystem(
                "best-first",
                "1",
                new BestFirstSearchStrategy(),
                List.of("INTERNAL_REGELSUCHE_SEARCH")),
            new SearchSystem(
                "a-star",
                "1",
                new AStarSearchStrategy(),
                List.of(
                    "INTERNAL_REGELSUCHE_SEARCH",
                    "HEURISTIC_NOT_CLAIMED_ADMISSIBLE")),
            new SearchSystem(
                "beam",
                "1",
                new BeamSearchStrategy(),
                List.of(
                    "INTERNAL_REGELSUCHE_SEARCH",
                    "TARGET_NOT_USED_FOR_FRONTIER_ORDERING",
                    "BEAM_WIDTH_8")));

        PolynomialNormalFormSolverBackend polynomial =
            new PolynomialNormalFormSolverBackend();
        ExternalSymPySolverBackend.Detection sympy =
            ExternalSymPySolverBackend.detectSystemSymPy();
        Z3SmtSolverBackend.Detection z3 =
            Z3SmtSolverBackend.detectSystemZ3();
        List<ValidationSystem> validation = List.of(
            new ValidationSystem(
                polynomial,
                SystemKind.REGELSUCHE,
                List.of(Role.VALIDATION, Role.COUNTEREXAMPLE),
                true,
                "java=21\nbackend=polynomial-normal-form@1",
                List.of("REAL_POLYNOMIAL_EQUALITY_ONLY")),
            new ValidationSystem(
                sympy.backend(),
                SystemKind.EXTERNAL_BASELINE,
                List.of(Role.EQUALITY_REWRITE, Role.VALIDATION),
                sympy.available(),
                "sympy=" + sympy.backend().descriptor().backendVersion(),
                sympy.available()
                    ? List.of(
                        "REAL_POLYNOMIAL_EQUALITY_ONLY",
                        "VALIDATION_ONLY_NOT_DISCOVERY_OR_FORMAL_PROOF")
                    : List.of(
                        "BACKEND_UNAVAILABLE",
                        sympy.detail())),
            new ValidationSystem(
                z3.backend(),
                SystemKind.EXTERNAL_BASELINE,
                List.of(
                    Role.VALIDATION,
                    Role.COUNTEREXAMPLE,
                    Role.PROOF),
                z3.availability() == BackendAvailability.AVAILABLE,
                "z3=" + z3.backend().descriptor().backendVersion(),
                z3.availability() == BackendAvailability.AVAILABLE
                    ? List.of(
                        "SHARED_REAL_ARITHMETIC_FRAGMENT",
                        "PROOF_ROLE_REPORTED_SEPARATELY_FROM_VALIDATION")
                    : List.of(
                        "BACKEND_UNAVAILABLE",
                        z3.detail())));

        ExternalSymPySimplificationBaseline simplifier =
            ExternalSymPySimplificationBaseline.detectSystemSymPy();
        List<SimplificationSystem> simplification = List.of(
            SimplificationSystem.internal(
                "regelsuche-untargeted-best-first",
                "1",
                new BestFirstSearchStrategy(),
                List.of(
                    "INTERNAL_REGELSUCHE_SEARCH",
                    "NO_TARGET_AND_NO_REFERENCE_FORM_VISIBLE",
                    "EMITTED_SIDE_CONDITIONS_CHECKED_AGAINST_"
                        + SimplificationAssumptionContract.CONTRACT_ID)),
            SimplificationSystem.external(
                simplifier,
                simplifier.available()
                    ? List.of(
                        "EXTERNAL_CAS_NATIVE_SIMPLIFIER",
                        "NO_TARGET_AND_NO_REFERENCE_FORM_VISIBLE",
                        "DECLARED_ASSUMPTIONS_PASSED_VIA_"
                            + SimplificationAssumptionContract.CONTRACT_ID,
                        "COMPOSITE_SIDE_CONDITIONS_NOT_BINDABLE_AS_SYMBOL_ASSUMPTIONS",
                        "INDEPENDENT_ASSUMPTION_AWARE_OUTPUT_VALIDATION_NOT_YET_AVAILABLE",
                        "SIMPLIFICATION_IS_NOT_DISCOVERY_OR_FORMAL_PROOF")
                    : List.of(
                        "BACKEND_UNAVAILABLE",
                        simplifier.detail())));
        return new ComparativeBenchmarkRunner(
            search, validation, simplification);
    }

    public Report run() {
        List<Case> searchCases = ComparativeBenchmarkCatalog.searchCases();
        List<Case> validationCases =
            ComparativeBenchmarkCatalog.validationCases();
        InformationParityManifest searchParity =
            ComparativeBenchmarkCatalog.searchParity(searchCases);
        InformationParityManifest validationParity =
            ComparativeBenchmarkCatalog.validationParity(validationCases);
        List<Case> simplificationCases =
            ComparativeBenchmarkCatalog.simplificationCases();
        InformationParityManifest simplificationParity =
            ComparativeBenchmarkCatalog.simplificationParity(
                simplificationCases);

        List<Configuration> configurations = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        for (SearchSystem system : searchSystems) {
            Configuration configuration =
                ComparativeBenchmarkCatalog.searchConfiguration(
                    system, searchParity);
            configurations.add(configuration);
            for (Case benchmarkCase : searchCases) {
                results.add(executor.runSearch(
                    system, configuration, benchmarkCase));
            }
        }
        for (ValidationSystem system : validationSystems) {
            Configuration configuration =
                ComparativeBenchmarkCatalog.validationConfiguration(
                    system, validationParity);
            configurations.add(configuration);
            for (Case benchmarkCase : validationCases) {
                results.add(executor.runValidation(
                    system, configuration, benchmarkCase));
            }
        }
        for (SimplificationSystem system : simplificationSystems) {
            Configuration configuration =
                ComparativeBenchmarkCatalog.simplificationConfiguration(
                    system, simplificationParity);
            configurations.add(configuration);
            for (Case benchmarkCase : simplificationCases) {
                results.add(executor.runSimplification(
                    system, configuration, benchmarkCase));
            }
        }

        List<Result> searchResults = results.stream()
            .filter(result -> result.track()
                == Track.TARGET_DIRECTED_SEARCH)
            .toList();
        List<Result> validationResults = results.stream()
            .filter(result -> result.track()
                == Track.EQUALITY_VALIDATION)
            .toList();
        List<Result> simplificationResults = results.stream()
            .filter(result -> result.track()
                == Track.SIMPLIFICATION_COMPETITION)
            .toList();
        List<CapabilityClaim> claims = List.of(
            searchClaim(searchResults),
            validationClaim(validationResults),
            simplificationClaim(simplificationResults));
        List<Case> cases = new ArrayList<>(searchCases);
        cases.addAll(validationCases);
        cases.addAll(simplificationCases);
        return Report.create(
            SUITE_ID,
            List.of(searchParity, validationParity, simplificationParity),
            configurations,
            cases,
            results,
            claims,
            ComparativeBenchmarkCatalog.coverageGaps());
    }

    private static CapabilityClaim searchClaim(List<Result> results) {
        ClaimStatus status = status(results);
        return CapabilityClaim.create(
            "target-directed-shared-budget",
            Track.TARGET_DIRECTED_SEARCH,
            status,
            status == ClaimStatus.SUPPORTED
                ? "BestFirst, AStar and Beam each reached all three pinned simplification targets under identical inputs, target visibility, rewrite inventory and budgets."
                : "The initial target-directed comparison did not establish complete shared-budget success for every configured strategy.",
            hashes(results),
            List.of(
                "THREE_SMALL_INTERNAL_ALGEBRA_CASES_ONLY",
                "NO_RUNTIME_SUPERIORITY_CLAIM",
                "NO_UNIVERSAL_STRATEGY_RANKING"));
    }

    private static CapabilityClaim validationClaim(List<Result> results) {
        ClaimStatus status = status(results);
        return CapabilityClaim.create(
            "shared-polynomial-equality-validation",
            Track.EQUALITY_VALIDATION,
            status,
            status == ClaimStatus.SUPPORTED
                ? "The internal polynomial normal form, external SymPy CAS and external Z3 backend agreed with both pinned confirmed/refuted verdicts on the same polynomial statements."
                : "The initial shared-fragment validation comparison lacks a complete correct result from every configured backend.",
            hashes(results),
            List.of(
                "TWO_REAL_POLYNOMIAL_EQUALITY_CASES_ONLY",
                "VALIDATION_IS_NOT_DISCOVERY",
                "ONLY_Z3_PROOF_OBJECTS_COUNT_AS_FORMAL_PROOF"));
    }

    /** Head-to-head claim for the target-free simplification track. */
    private static CapabilityClaim simplificationClaim(List<Result> results) {
        ClaimStatus status = status(results);
        return CapabilityClaim.create(
            "target-free-simplification-head-to-head",
            Track.SIMPLIFICATION_COMPETITION,
            status,
            switch (status) {
                case SUPPORTED ->
                    "Every configured competitor reached the pinned reference form of every case from the input expression alone.";
                case NEGATIVE ->
                    "At least one configured competitor did not reach a pinned reference form; all per-result outcomes are retained unchanged.";
                case INSUFFICIENT_EVIDENCE ->
                    "At least one configured competitor could not be executed, so no head-to-head comparison is authorized.";
            },
            hashes(results),
            List.of(
                "SEVEN_SMALL_ALGEBRAIC_CASES_ONLY",
                "PINNED_REFERENCE_FORM_IS_NOT_A_UNIVERSAL_SIMPLICITY_ORDER",
                "NO_RUNTIME_OR_SCALABILITY_CLAIM",
                "SHARED_SURFACE_JUDGE_IS_THE_REGELSUCHE_CANONICALIZER",
                "INDEPENDENT_ASSUMPTION_AWARE_OUTPUT_VALIDATION_NOT_YET_AVAILABLE",
                "REACHING_A_REFERENCE_FORM_IS_NOT_DISCOVERY_OR_PROOF"));
    }

    private static ClaimStatus status(List<Result> results) {
        boolean complete = !results.isEmpty()
            && results.stream().allMatch(result ->
                result.disposition() == Disposition.EXECUTED);
        if (complete && results.stream().allMatch(Result::correct)) {
            return ClaimStatus.SUPPORTED;
        }
        if (results.stream().anyMatch(result ->
                result.disposition() == Disposition.EXECUTED
                    && !result.correct())) {
            return ClaimStatus.NEGATIVE;
        }
        return ClaimStatus.INSUFFICIENT_EVIDENCE;
    }

    private static List<String> hashes(List<Result> results) {
        return results.stream().map(Result::contentHash).sorted().toList();
    }
}
