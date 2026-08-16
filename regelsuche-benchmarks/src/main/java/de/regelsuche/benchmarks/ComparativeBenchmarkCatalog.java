package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.CoverageGap;
import de.regelsuche.benchmarks.ComparativeBenchmark.ExpectedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.InformationParityManifest;
import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.ValidationSystem;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;

/** Pinned information regimes, cases, configurations and visible coverage gaps. */
final class ComparativeBenchmarkCatalog {
    static final SearchHeuristic SEARCH_BUDGET =
        new SearchHeuristic(4, 80, 1, 2, 40, 8);

    private ComparativeBenchmarkCatalog() {
    }

    static List<Case> searchCases() {
        return List.of(
            Case.create(
                "target-add-zero",
                Track.TARGET_DIRECTED_SEARCH,
                "identity",
                "x + 0",
                "x",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "target-multiply-one",
                Track.TARGET_DIRECTED_SEARCH,
                "identity",
                "x * 1",
                "x",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "target-composed-identities",
                Track.TARGET_DIRECTED_SEARCH,
                "identity-composition",
                "(x + 0) * 1",
                "x",
                List.of(),
                ExpectedVerdict.TARGET_REACHED));
    }

    static List<Case> validationCases() {
        return List.of(
            Case.create(
                "polynomial-square-confirmed",
                Track.EQUALITY_VALIDATION,
                "polynomial",
                "(x + 1)^2",
                "x^2 + 2*x + 1",
                List.of(),
                ExpectedVerdict.CONFIRMED),
            Case.create(
                "polynomial-shift-refuted",
                Track.EQUALITY_VALIDATION,
                "polynomial",
                "x + 1",
                "x + 2",
                List.of(),
                ExpectedVerdict.REFUTED));
    }

    /**
     * Target-free simplification corpus.
     *
     * <p>The stored target expression is a pinned reference form. It is the
     * shared judge's answer key and is never handed to a competitor: the parity
     * manifest for this track sets {@code targetVisible=false}.</p>
     *
     * <p>The corpus deliberately contains a held-out capability gap. Exact
     * polynomial long division exists only in an opt-in experimental pack and
     * is not part of the measured default inventory.</p>
     */
    static List<Case> simplificationCases() {
        return List.of(
            Case.create(
                "simplify-identity-collapse",
                Track.SIMPLIFICATION_COMPETITION,
                "identity",
                "(x + 0) * 1",
                "x",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-annihilator",
                Track.SIMPLIFICATION_COMPETITION,
                "annihilator",
                "x * 0 + y",
                "y",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-square-folding",
                Track.SIMPLIFICATION_COMPETITION,
                "power-folding",
                "(a + b) * (a + b)",
                "(a + b) ^ 2",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-like-terms",
                Track.SIMPLIFICATION_COMPETITION,
                "linear-combination",
                "x + x",
                "2 * x",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-constant-fraction",
                Track.SIMPLIFICATION_COMPETITION,
                "rational-reduction",
                "(2 * x + 4) / 2",
                "x + 2",
                List.of(),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-factor-cancellation",
                Track.SIMPLIFICATION_COMPETITION,
                "rational-cancellation",
                "(x^2 - 1) / (x - 1)",
                "x + 1",
                List.of("x - 1 != 0"),
                ExpectedVerdict.TARGET_REACHED),
            Case.create(
                "simplify-cubic-cancellation",
                Track.SIMPLIFICATION_COMPETITION,
                "polynomial-division",
                "(x^3 - 1) / (x - 1)",
                "x ^ 2 + x + 1",
                List.of("x - 1 != 0"),
                ExpectedVerdict.TARGET_REACHED));
    }

    static InformationParityManifest searchParity(List<Case> cases) {
        return InformationParityManifest.create(
            "target-directed-shared-budget/v1",
            Track.TARGET_DIRECTED_SEARCH,
            true,
            false,
            false,
            false,
            false,
            false,
            corpusHash(cases),
            searchInventoryHash(),
            searchBudgetHash(),
            notApplicable("research-brief"),
            notApplicable("qualification-split"),
            List.of("TARGET_REACHABILITY"));
    }

    static InformationParityManifest validationParity(List<Case> cases) {
        return InformationParityManifest.create(
            "shared-polynomial-equality/v1",
            Track.EQUALITY_VALIDATION,
            true,
            false,
            false,
            false,
            false,
            false,
            corpusHash(cases),
            SolverIr.sha256(
                "fragment=real-polynomial-equality/v1"
                    + "\nassumptions=none"
                    + "\nrequestedEvidence=SYMBOLIC_CERTIFICATE"),
            SolverIr.sha256(
                "configuredWork=1"
                    + "\nmandatoryEvaluations=1"
                    + "\nprocessTimeoutMillis=20000"),
            notApplicable("research-brief"),
            notApplicable("qualification-split"),
            List.of("EQUALITY_DECISION"));
    }

    static InformationParityManifest simplificationParity(List<Case> cases) {
        return InformationParityManifest.create(
            "target-free-simplification/v1",
            Track.SIMPLIFICATION_COMPETITION,
            false,
            false,
            false,
            false,
            false,
            false,
            corpusHash(cases),
            SolverIr.sha256(
                "regelsuche-inventory=" + searchInventoryHash()
                    + "\nexternal-inventory=CAS_NATIVE_SIMPLIFIER"),
            SolverIr.sha256(
                "regelsuche-budget=" + searchBudgetHash()
                    + "\nexternal-timeoutMillis=20000"),
            notApplicable("research-brief"),
            notApplicable("qualification-split"),
            List.of("PINNED_REFERENCE_FORM_REACHABILITY"));
    }

    static Configuration searchConfiguration(
        SearchSystem system,
        InformationParityManifest parity
    ) {
        return Configuration.create(
            "search-" + system.id(),
            Track.TARGET_DIRECTED_SEARCH,
            SystemKind.REGELSUCHE,
            List.of(Role.SEARCH),
            parity.contentHash(),
            system.id(),
            system.version(),
            SolverIr.sha256(
                "search-strategy=" + system.strategy().getClass().getName()),
            SolverIr.sha256("NO_MODEL"),
            SolverIr.sha256(
                "java=21\nsearch-kernel=regelsuche-search/v1"),
            true,
            system.limitations());
    }

    static Configuration validationConfiguration(
        ValidationSystem system,
        InformationParityManifest parity
    ) {
        return Configuration.create(
            "validation-" + system.backend().descriptor().backendId(),
            Track.EQUALITY_VALIDATION,
            system.kind(),
            system.roles(),
            parity.contentHash(),
            system.backend().descriptor().backendId(),
            system.backend().descriptor().backendVersion(),
            SolverIr.sha256("direct-equality-validation/v1"),
            SolverIr.sha256("NO_MODEL"),
            SolverIr.sha256(system.environmentIdentity()),
            system.backend().descriptor().deterministic(),
            system.limitations());
    }

    static Configuration simplificationConfiguration(
        SimplificationSystem system,
        InformationParityManifest parity
    ) {
        return Configuration.create(
            "simplify-" + system.id(),
            Track.SIMPLIFICATION_COMPETITION,
            system.kind(),
            List.of(Role.EQUALITY_REWRITE),
            parity.contentHash(),
            system.id(),
            system.version(),
            SolverIr.sha256(
                "target-free-simplification/v1\nimplementation="
                    + system.implementationIdentity()),
            SolverIr.sha256("NO_MODEL"),
            SolverIr.sha256(system.environmentIdentity()),
            true,
            system.limitations());
    }

    static List<CoverageGap> coverageGaps() {
        return List.of(
            CoverageGap.create(
                Track.HIDDEN_RULE_REDISCOVERY,
                "The initial slice does not yet replay the leak-free #227 manifest.",
                List.of(
                    "PINNED_HIDDEN_RULE_MANIFEST",
                    "NEGATIVE_HOLDOUT_EXECUTIONS",
                    "POST_HOC_REFERENCE_RELATION")),
            CoverageGap.create(
                Track.OPEN_TARGET_DISCOVERY,
                "No external target-free conjecture generator has yet been run under information parity.",
                List.of(
                    "TARGET_FREE_EXTERNAL_BASELINE",
                    "ZERO_TO_MANY_CANDIDATE_OUTPUTS",
                    "PROJECT_NOVELTY_AND_FALSIFICATION")),
            CoverageGap.create(
                Track.SIMPLIFICATION_COMPETITION,
                "The target-free simplification corpus is small and single-domain; equality saturation and an independent assumption-aware output validator remain unmeasured. A deterministic seeded randomized-valid rewrite control is now retained.",
                List.of(
                    "EQUALITY_SATURATION_COMPETITOR_WITH_EXACT_SIDE_CONDITION_PROVENANCE",
                    "MULTI_DOMAIN_SIMPLIFICATION_CORPUS",
                    "INDEPENDENT_ASSUMPTION_AWARE_OUTPUT_VALIDATION")),
            CoverageGap.create(
                Track.CROSS_FAMILY_TRANSFER,
                "The fully held-out #222 transfer split is not part of the first executable slice.",
                List.of(
                    "FAMILY_BLIND_FORMATION",
                    "HELD_OUT_TRANSFER_RESULTS",
                    "PAIRED_UTILITY")),
            CoverageGap.create(
                Track.AUTONOMOUS_CAMPAIGN,
                "Controller baselines have not yet consumed the pinned #355 research brief and budget ledger.",
                List.of(
                    "COMMON_RESEARCH_BRIEF",
                    "COMMON_RESOURCE_LEDGER",
                    "COMPLETE_RECEIPT_DAG")),
            CoverageGap.create(
                Track.DISCOVERY_COMPONENT_ABLATION,
                "At least three production discovery-component ablations remain to be measured.",
                List.of(
                    "THREE_COMPONENT_ABLATIONS",
                    "IDENTICAL_INPUT_EVIDENCE",
                    "SEPARATE_CORRECTNESS_AND_UTILITY")),
            CoverageGap.create(
                Track.CONTROLLER_ABLATION,
                "At least two autonomous-controller ablations remain to be measured.",
                List.of(
                    "TWO_CONTROLLER_ABLATIONS",
                    "BALANCED_RESOURCE_ACCOUNTING",
                    "DETERMINISTIC_NEXT_PLAN")));
    }

    private static String corpusHash(List<Case> cases) {
        return SolverIr.sha256(
            cases.stream().map(Case::contentHash).sorted().toList().toString());
    }

    private static String searchInventoryHash() {
        List<String> ruleIds = new AstRewriteTransformationEngine().rules()
            .stream()
            .map(rule -> rule.id())
            .sorted()
            .toList();
        return SolverIr.sha256(ruleIds.toString());
    }

    private static String searchBudgetHash() {
        return SolverIr.sha256(
            "maxDepth=" + SEARCH_BUDGET.maxDepth()
                + "\nmaxVisitedExpressions="
                    + SEARCH_BUDGET.maxVisitedExpressions()
                + "\nmaxExpandingSteps="
                    + SEARCH_BUDGET.maxExpandingSteps()
                + "\nmaxCandidatesPerState="
                    + SEARCH_BUDGET.maxCandidatesPerState()
                + "\nbeamWidth=" + SEARCH_BUDGET.beamWidth());
    }

    private static String notApplicable(String name) {
        return SolverIr.sha256("NOT_APPLICABLE:" + name);
    }
}
