package de.regelsuche.evolution;

import de.regelsuche.evolution.SchematicProofPlan.*;
import de.regelsuche.evolution.SchematicProofPlanResolution.*;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Limits;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Result;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Status;
import de.regelsuche.scalar.ExactRationalDomain;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A pre-solution, bounded rational coefficient plan. Public run records are data, not authority. */
public final class ExactLinearPolynomialPlanResolver {
    public static final String RESOLVER_ID = "regelsuche.exact-linear-polynomial-plan-resolver/v1";
    public static final String GRAMMAR = "exact-linear-coefficients/v1";
    public static final String FRAGMENT = "affine-rational-polynomial-holes-empty-assumptions/v1";
    public static final String SOLVER_REVISION_HASH = SchematicProofPlan.hash(
        ExactLinearPolynomialHoleSolver.SOLVER_ID + "|" + ExactLinearPolynomialHoleSolver.REVISION);
    public static final String REVISION_HASH = SchematicProofPlan.hash(RESOLVER_ID + "|" + GRAMMAR + "|" + FRAGMENT
        + "|" + SOLVER_REVISION_HASH + "|" + SchematicProofPlan.SCHEMA + "|" + SchematicProofPlanResolution.SCHEMA);
    private static final String OBLIGATION = "exact-linear-equivalence";
    private static final String SOLVE_STEP = "solve-linear-coefficients";
    private final ExactLinearPolynomialHoleSolver solver = new ExactLinearPolynomialHoleSolver();

    /** Does not call the solver or inspect a candidate/reference answer. */
    public SchematicProofPlan createPlan(String planId, Formation formation, SchematicProofPlan.Limits limits) {
        Objects.requireNonNull(formation, "formation");
        Objects.requireNonNull(limits, "planLimits");
        String id = SchematicProofPlan.requireId(planId, "planId");
        String scope = SchematicProofPlan.hash(ExactLinearPolynomialPlanJson.formationScope(id, formation, limits));
        List<String> ids = formation.holeIds();
        List<Hole> holes = ids.stream().map(hole -> new Hole(hole, HoleKind.COEFFICIENT, HoleSort.EXACT_RATIONAL,
            ExactRationalDomain.DOMAIN_ID, GRAMMAR,
            // One unique solution may be retained; this is not an enumerated candidate domain.
            new HoleBudget(1, 2 * formation.solverLimits().maxScalarBits() + 4,
                formation.solverLimits().maxScalarBits(), 0))).toList();
        return SchematicProofPlan.create(id, InformationBoundary.TARGET_FREE_FORMATION, scope,
            List.of(new Step("form-linear-template", StepAction.FORM_CANDIDATES, ids, List.of()),
                new Step(SOLVE_STEP, StepAction.SOLVE_HOLES, ids, List.of(OBLIGATION)),
                new Step("check-linear-identity", StepAction.DISCHARGE_OBLIGATIONS, List.of(), List.of(OBLIGATION)),
                new Step("emit-linear-candidate", StepAction.EMIT_CANDIDATE, List.of(), List.of(OBLIGATION))),
            holes, List.of(new Obligation(OBLIGATION, ObligationKind.EQUIVALENT, SOLVE_STEP, ids,
                formation.assumptions(), ExactLinearPolynomialHoleSolver.SOLVER_ID, SOLVER_REVISION_HASH,
                InitialObligationStatus.OPEN)), limits);
    }

    public Run resolve(SchematicProofPlan plan, Formation formation) {
        validatePlan(plan, formation);
        Result result = solver.solve(formation.sourceExpression(), formation.ansatzTemplate(), formation.holeIds(),
            formation.assumptions(), formation.solverLimits());
        Optional<SchematicProofPlanResolution> resolution = Optional.empty();
        if (result.status() == Status.UNIQUE) {
            var bindings = result.candidate().orElseThrow().bindings().entrySet().stream().map(entry -> new HoleBinding(
                entry.getKey(), HoleSort.EXACT_RATIONAL, entry.getValue().canonicalText(),
                SchematicProofPlan.hash(REVISION_HASH + "|" + plan.contentHash() + "|" + result.contentHash()
                    + "|" + entry.getKey() + "|" + entry.getValue().canonicalText()))).toList();
            var resolved = SchematicProofPlanResolution.create(plan, bindings, List.of(new ObligationOutcome(
                OBLIGATION, OutcomeStatus.CONFIRMED, ExactLinearPolynomialHoleSolver.SOLVER_ID, SOLVER_REVISION_HASH,
                result.contentHash(), "EXACT_LINEAR_IDENTITY_CONFIRMED")));
            if (!resolved.isStructurallyCompleteFor(plan)) {
                throw new IllegalStateException("linear solver did not resolve its complete plan");
            }
            resolution = Optional.of(resolved);
        }
        return new Run(plan.contentHash(), result, resolution);
    }

    void validatePlan(SchematicProofPlan plan, Formation formation) {
        Objects.requireNonNull(plan, "plan");
        if (!createPlan(plan.planId(), formation, plan.limits()).equals(plan)) {
            throw new IllegalArgumentException("linear plan differs from the independently supplied formation");
        }
    }

    /** Nonempty assumptions are retained so the native UNSUPPORTED outcome remains auditable. */
    public record Formation(String sourceExpression, String ansatzTemplate, List<String> holeIds,
                            List<String> assumptions, Limits solverLimits) {
        public Formation {
            sourceExpression = text(sourceExpression, "sourceExpression");
            ansatzTemplate = text(ansatzTemplate, "ansatzTemplate");
            holeIds = List.copyOf(Objects.requireNonNull(holeIds, "holeIds"));
            if (holeIds.isEmpty() || holeIds.size() > 12 || new HashSet<>(holeIds).size() != holeIds.size()
                    || holeIds.stream().anyMatch(id -> !id.matches("[a-z][a-z0-9_-]{2,63}"))) {
                throw new IllegalArgumentException("linear coefficient IDs must be unique and bounded");
            }
            holeIds = holeIds.stream().sorted().toList();
            assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
            if (assumptions.size() > 32 || assumptions.stream().anyMatch(value -> !text(value, "assumption").equals(value))) {
                throw new IllegalArgumentException("assumptions must be bounded and normalized");
            }
            Objects.requireNonNull(solverLimits, "solverLimits");
        }
    }

    public record Run(String planHash, Result solverResult, Optional<SchematicProofPlanResolution> resolution) {
        public static final String SCHEMA = "regelsuche.exact-linear-polynomial-plan-run/v1";
        public static final int MAX_CANONICAL_BYTES = 1_000_000;
        public Run {
            planHash = SchematicProofPlan.requireSha256(planHash, "planHash");
            Objects.requireNonNull(solverResult, "solverResult");
            Objects.requireNonNull(resolution, "resolution");
            if ((solverResult.status() == Status.UNIQUE) != resolution.isPresent()
                    || resolution.isPresent() && !planHash.equals(resolution.orElseThrow().planHash())) {
                throw new IllegalArgumentException("run resolution and solver status or plan disagree");
            }
        }
        public String contentHash() { return SchematicProofPlan.hash(ExactLinearPolynomialPlanJson.run(this, false)); }
        public String toCanonicalJson() { return ExactLinearPolynomialPlanJson.run(this, true); }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 16_384 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be bounded nonblank text without control characters");
        }
        // UTF-8 encoding replaces unpaired surrogates with '?', which would alias a different formation.
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException(field + " must contain well-formed Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " must contain well-formed Unicode");
            }
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
