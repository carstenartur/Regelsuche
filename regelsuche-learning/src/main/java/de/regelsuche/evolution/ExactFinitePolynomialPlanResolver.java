package de.regelsuche.evolution;

import de.regelsuche.evolution.SchematicProofPlan.Hole;
import de.regelsuche.evolution.SchematicProofPlan.HoleBudget;
import de.regelsuche.evolution.SchematicProofPlan.HoleKind;
import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlan.InformationBoundary;
import de.regelsuche.evolution.SchematicProofPlan.InitialObligationStatus;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.evolution.SchematicProofPlan.ObligationKind;
import de.regelsuche.evolution.SchematicProofPlan.Step;
import de.regelsuche.evolution.SchematicProofPlan.StepAction;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.evolution.SchematicProofPlanResolution.ResolutionState;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates and replays the narrow schematic plan for one exact finite polynomial
 * ansatz search.
 *
 * <p>The result retains checked plan-relative references, but remains neither
 * formal proof nor production authorization nor independently dereferenced
 * evidence.</p>
 */
public final class ExactFinitePolynomialPlanResolver {
    public static final String RESOLVER_ID =
        "regelsuche.exact-finite-polynomial-plan-resolver/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        RESOLVER_ID
            + "|solver=" + ExactFinitePolynomialHoleSolver.REVISION_HASH
            + "|plan=" + SchematicProofPlan.SCHEMA
            + "|resolution=" + SchematicProofPlanResolution.SCHEMA
            + "|topology=finite-domains-solve-discharge-emit"
            + "|evidence=solution-binding-and-equivalence-outcome");
    public static final String EQUIVALENCE_OBLIGATION_ID =
        "exact-ansatz-equivalence";
    private static final String SOLVE_STEP_ID = "solve-finite-holes";

    private final ExactFinitePolynomialHoleSolver solver =
        new ExactFinitePolynomialHoleSolver();

    public SchematicProofPlan createPlan(
        String planId,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        SchematicProofPlan.Limits planLimits
    ) {
        List<HoleDomain> normalizedDomains = normalizeDomains(domains);
        SchematicProofPlan.Limits limits = Objects.requireNonNull(
            planLimits,
            "planLimits");
        String scopeHash = formationScopeHash(
            planId,
            sourceExpression,
            ansatzTemplate,
            normalizedDomains,
            retainedSolutionLimit,
            limits);
        List<String> holeIds = normalizedDomains.stream()
            .map(HoleDomain::holeId)
            .toList();
        List<Hole> holes = normalizedDomains.stream()
            .map(ExactFinitePolynomialPlanResolver::toPlanHole)
            .toList();
        Obligation obligation = new Obligation(
            EQUIVALENCE_OBLIGATION_ID,
            ObligationKind.EQUIVALENT,
            SOLVE_STEP_ID,
            holeIds,
            List.of(),
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            InitialObligationStatus.OPEN);
        return SchematicProofPlan.create(
            planId,
            InformationBoundary.TARGET_FREE_FORMATION,
            scopeHash,
            planSteps(holeIds),
            holes,
            List.of(obligation),
            limits);
    }

    public ExactFinitePolynomialPlanRun resolve(
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit
    ) {
        Objects.requireNonNull(plan, "plan");
        List<HoleDomain> normalizedDomains = normalizeDomains(domains);
        validatePlan(
            plan,
            sourceExpression,
            ansatzTemplate,
            normalizedDomains,
            retainedSolutionLimit);
        SearchResult solverResult = solver.solve(
            sourceExpression,
            ansatzTemplate,
            normalizedDomains,
            retainedSolutionLimit);
        List<ExactFinitePolynomialResolvedCandidate> candidates =
            solverResult.solutions().stream()
                .map(solution -> resolveCandidate(
                    plan,
                    solverResult,
                    solution))
                .toList();
        return ExactFinitePolynomialPlanRun.create(
            plan.contentHash(),
            solverResult,
            candidates);
    }

    public boolean replay(
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        ExactFinitePolynomialPlanRun expected
    ) {
        Objects.requireNonNull(expected, "expected");
        try {
            return expected.equals(resolve(
                plan,
                sourceExpression,
                ansatzTemplate,
                domains,
                retainedSolutionLimit));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return false;
        }
    }

    public String formationScopeHash(
        String planId,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        SchematicProofPlan.Limits planLimits
    ) {
        String normalizedPlanId = SchematicProofPlan.requireId(
            planId,
            "planId");
        SchematicProofPlan.Limits limits = Objects.requireNonNull(
            planLimits,
            "planLimits");
        String source = normalizeText(sourceExpression, "sourceExpression");
        String template = normalizeText(ansatzTemplate, "ansatzTemplate");
        List<HoleDomain> normalizedDomains = normalizeDomains(domains);
        if (retainedSolutionLimit < 1) {
            throw new IllegalArgumentException(
                "retainedSolutionLimit must be positive");
        }
        StringBuilder material = new StringBuilder();
        append(material, RESOLVER_ID);
        append(material, REVISION_HASH);
        append(material, ExactFinitePolynomialHoleSolver.SOLVER_ID);
        append(material, ExactFinitePolynomialHoleSolver.REVISION_HASH);
        append(material, normalizedPlanId);
        append(material, Integer.toString(limits.maxSteps()));
        append(material, Integer.toString(limits.maxHoles()));
        append(material, Integer.toString(limits.maxObligations()));
        append(material, Integer.toString(limits.maxCanonicalBytes()));
        append(material, source);
        append(material, template);
        append(material, Integer.toString(retainedSolutionLimit));
        for (HoleDomain domain : normalizedDomains) {
            append(material, domain.holeId());
            append(material, domain.kind().name());
            for (ExactRational value : domain.values()) {
                append(material, value.canonicalText());
            }
        }
        return SchematicProofPlan.hash(material.toString());
    }

    private ExactFinitePolynomialResolvedCandidate resolveCandidate(
        SchematicProofPlan plan,
        SearchResult solverResult,
        Solution solution
    ) {
        Map<String, Hole> holes = new LinkedHashMap<>();
        plan.holes().forEach(hole -> holes.put(hole.id(), hole));
        List<HoleBinding> bindings = solution.bindings().stream()
            .map(binding -> toHoleBinding(
                plan,
                solverResult,
                solution,
                holes.get(binding.holeId()),
                binding))
            .toList();
        Obligation obligation = plan.obligations().getFirst();
        ObligationOutcome outcome = new ObligationOutcome(
            obligation.id(),
            OutcomeStatus.CONFIRMED,
            obligation.checkerCapability(),
            obligation.checkerRevisionHash(),
            solverResult.contentHash(),
            "EXACT_FINITE_POLYNOMIAL_EQUIVALENCE_CONFIRMED");
        SchematicProofPlanResolution resolution =
            SchematicProofPlanResolution.create(
                plan,
                bindings,
                List.of(outcome));
        if (resolution.state() != ResolutionState.COMPLETE_REFERENCES
                || !resolution.isStructurallyCompleteFor(plan)) {
            throw new IllegalStateException(
                "exact solver did not produce a complete plan resolution");
        }
        return ExactFinitePolynomialResolvedCandidate.create(
            solution,
            resolution,
            solverResult.contentHash());
    }

    private static HoleBinding toHoleBinding(
        SchematicProofPlan plan,
        SearchResult solverResult,
        Solution solution,
        Hole hole,
        Binding binding
    ) {
        if (hole == null) {
            throw new IllegalStateException(
                "solver returned a binding for an unknown plan hole");
        }
        return new HoleBinding(
            binding.holeId(),
            hole.sort(),
            binding.value().canonicalText(),
            bindingEvidenceHash(
                plan.contentHash(),
                solverResult.contentHash(),
                solution.contentHash(),
                binding.holeId(),
                binding.value().canonicalText()));
    }

    static String bindingEvidenceHash(
        String planHash,
        String solverResultHash,
        String solutionHash,
        String holeId,
        String canonicalValue
    ) {
        StringBuilder material = new StringBuilder();
        append(material, RESOLVER_ID);
        append(material, REVISION_HASH);
        append(material, planHash);
        append(material, solverResultHash);
        append(material, solutionHash);
        append(material, holeId);
        append(material, canonicalValue);
        return SchematicProofPlan.hash(material.toString());
    }

    static String candidateHash(
        String planHash,
        String solverResultHash,
        String solutionHash,
        String resolutionHash
    ) {
        StringBuilder material = new StringBuilder();
        append(material, RESOLVER_ID);
        append(material, REVISION_HASH);
        append(material, planHash);
        append(material, solverResultHash);
        append(material, solutionHash);
        append(material, resolutionHash);
        return SchematicProofPlan.hash(material.toString());
    }

    static String planRunHash(
        String planHash,
        SearchResult solverResult,
        ExactFinitePolynomialPlanRun.Status status,
        List<ExactFinitePolynomialResolvedCandidate> candidates
    ) {
        StringBuilder material = new StringBuilder();
        append(material, RESOLVER_ID);
        append(material, REVISION_HASH);
        append(material, planHash);
        append(material, solverResult.contentHash());
        append(material, status.name());
        candidates.stream()
            .sorted(Comparator.comparing(
                ExactFinitePolynomialResolvedCandidate::contentHash))
            .forEach(candidate -> append(material, candidate.contentHash()));
        return SchematicProofPlan.hash(material.toString());
    }

    private static List<Step> planSteps(List<String> holeIds) {
        return List.of(
            new Step(
                "form-finite-domains",
                StepAction.FORM_CANDIDATES,
                holeIds,
                List.of()),
            new Step(
                SOLVE_STEP_ID,
                StepAction.SOLVE_HOLES,
                holeIds,
                List.of(EQUIVALENCE_OBLIGATION_ID)),
            new Step(
                "check-exact-equivalence",
                StepAction.DISCHARGE_OBLIGATIONS,
                List.of(),
                List.of(EQUIVALENCE_OBLIGATION_ID)),
            new Step(
                "emit-ansatz-candidate",
                StepAction.EMIT_CANDIDATE,
                List.of(),
                List.of(EQUIVALENCE_OBLIGATION_ID)));
    }

    private void validatePlan(
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit
    ) {
        String expectedScope = formationScopeHash(
            plan.planId(),
            sourceExpression,
            ansatzTemplate,
            domains,
            retainedSolutionLimit,
            plan.limits());
        if (!expectedScope.equals(plan.formationScopeHash())) {
            throw new IllegalArgumentException(
                "plan formation scope differs from solver input");
        }
        if (plan.informationBoundary()
                != InformationBoundary.TARGET_FREE_FORMATION) {
            throw new IllegalArgumentException(
                "finite polynomial resolution requires target-free formation");
        }
        if (!plan.steps().equals(planSteps(plan.holeIds()))) {
            throw new IllegalArgumentException(
                "plan topology differs from the finite resolver contract");
        }
        if (plan.obligations().size() != 1) {
            throw new IllegalArgumentException(
                "v1 resolver requires one equivalence obligation");
        }
        Obligation obligation = plan.obligations().getFirst();
        if (!EQUIVALENCE_OBLIGATION_ID.equals(obligation.id())
                || obligation.kind() != ObligationKind.EQUIVALENT
                || !SOLVE_STEP_ID.equals(obligation.issuerStepId())
                || !obligation.assumptions().isEmpty()
                || !obligation.dependentHoleIds().equals(plan.holeIds())
                || !ExactFinitePolynomialHoleSolver.SOLVER_ID.equals(
                    obligation.checkerCapability())
                || !ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                    obligation.checkerRevisionHash())) {
            throw new IllegalArgumentException(
                "plan does not contain the exact finite equivalence contract");
        }
        validateHoleDomains(plan.holes(), domains);
    }

    private static void validateHoleDomains(
        List<Hole> holes,
        List<HoleDomain> domains
    ) {
        List<Hole> expected = domains.stream()
            .map(ExactFinitePolynomialPlanResolver::toPlanHole)
            .toList();
        if (!holes.equals(expected)) {
            throw new IllegalArgumentException(
                "plan holes differ from their finite solver domains");
        }
    }

    private static Hole toPlanHole(HoleDomain domain) {
        boolean sign = domain.kind()
            == ExactFinitePolynomialHoleSolver.HoleKind.SIGN;
        int maxBytes = domain.values().stream()
            .map(ExactRational::canonicalText)
            .mapToInt(value ->
                value.getBytes(StandardCharsets.UTF_8).length)
            .max()
            .orElseThrow();
        int maxBits = domain.values().stream()
            .mapToInt(ExactFinitePolynomialPlanResolver::scalarBits)
            .max()
            .orElseThrow();
        return new Hole(
            domain.holeId(),
            sign ? HoleKind.SIGN : HoleKind.COEFFICIENT,
            sign ? HoleSort.SIGN : HoleSort.EXACT_RATIONAL,
            sign
                ? "regelsuche.finite-sign/v1"
                : ExactRationalDomain.DOMAIN_ID,
            "finite-enumeration/v1",
            new HoleBudget(
                domain.values().size(),
                maxBytes,
                Math.max(1, maxBits),
                0));
    }

    private static int scalarBits(ExactRational value) {
        return Math.max(
            value.numerator().abs().bitLength(),
            value.denominator().bitLength());
    }

    private static List<HoleDomain> normalizeDomains(
        List<HoleDomain> domains
    ) {
        Objects.requireNonNull(domains, "domains");
        if (domains.isEmpty()) {
            throw new IllegalArgumentException(
                "finite domains must not be empty");
        }
        List<HoleDomain> result = domains.stream()
            .map(domain -> Objects.requireNonNull(domain, "domain"))
            .sorted(Comparator.comparing(HoleDomain::holeId))
            .toList();
        if (result.stream().map(HoleDomain::holeId).distinct().count()
                != result.size()) {
            throw new IllegalArgumentException(
                "finite domain IDs must be unique");
        }
        return List.copyOf(result);
    }

    private static String normalizeText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }
}
