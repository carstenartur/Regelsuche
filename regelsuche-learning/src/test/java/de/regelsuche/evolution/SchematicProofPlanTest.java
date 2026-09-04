package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.SchematicProofPlan.Hole;
import de.regelsuche.evolution.SchematicProofPlan.HoleBudget;
import de.regelsuche.evolution.SchematicProofPlan.HoleKind;
import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlan.InformationBoundary;
import de.regelsuche.evolution.SchematicProofPlan.InitialObligationStatus;
import de.regelsuche.evolution.SchematicProofPlan.Limits;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.evolution.SchematicProofPlan.ObligationKind;
import de.regelsuche.evolution.SchematicProofPlan.Step;
import de.regelsuche.evolution.SchematicProofPlan.StepAction;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.evolution.SchematicProofPlanResolution.ResolutionState;
import de.regelsuche.scalar.ExactRationalDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchematicProofPlanTest {
    private static final Limits LIMITS = new Limits(16, 16, 16, 200_000);
    private static final String CHECKER_REVISION = hash("checker-revision");

    @Test
    void canonicalPlanIsIndependentOfDefinitionInputOrder() {
        SchematicProofPlan forward = plan(false);
        SchematicProofPlan reversed = plan(true);

        assertEquals(forward.contentHash(), reversed.contentHash());
        assertEquals(forward.toCanonicalJson(), reversed.toCanonicalJson());
        assertEquals(List.of("alpha", "sigma"), forward.holeIds());
        assertEquals(List.of("local-equivalence", "residual-zero"),
            forward.obligationIds());
        assertEquals("form-coefficients", forward.steps().getFirst().id());
        assertEquals(StepAction.EMIT_CANDIDATE,
            forward.steps().getLast().action());
        assertFalse(forward.toCanonicalJson().contains("\"referenceOutcome\""));
        assertFalse(forward.toCanonicalJson().contains("\"finalTest\""));
    }

    @Test
    void rejectsInvalidSortReferencesIssuerAndEmissionTopology() {
        assertThrows(IllegalArgumentException.class, () -> new Hole(
            "alpha", HoleKind.COEFFICIENT, HoleSort.TERM,
            ExactRationalDomain.DOMAIN_ID, "bounded-rational/v1", scalarBudget()));
        assertThrows(IllegalArgumentException.class, () -> new Step(
            "bad-emission", StepAction.EMIT_CANDIDATE, List.of("alpha"),
            List.of("local-equivalence")));

        SchematicProofPlan valid = plan(false);
        List<Step> unknownHole = new ArrayList<>(valid.steps());
        unknownHole.set(0, new Step("form-coefficients", StepAction.FORM_CANDIDATES,
            List.of("unknown-hole"), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, unknownHole, valid.obligations()));

        List<Obligation> mismatchedIssuer = new ArrayList<>(valid.obligations());
        Obligation first = mismatchedIssuer.getFirst();
        mismatchedIssuer.set(0, new Obligation(first.id(), first.kind(), "solve-holes",
            first.dependentHoleIds(), first.assumptions(), first.checkerCapability(),
            first.checkerRevisionHash(), first.initialStatus()));
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, valid.steps(), mismatchedIssuer));

        List<Step> nonFinalEmission = new ArrayList<>(valid.steps());
        Collections.swap(nonFinalEmission, nonFinalEmission.size() - 1,
            nonFinalEmission.size() - 2);
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, nonFinalEmission, valid.obligations()));

        List<Step> duplicateEmission = new ArrayList<>(valid.steps());
        duplicateEmission.add(new Step("second-emission",
            StepAction.EMIT_CANDIDATE, List.of(), valid.obligationIds()));
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, duplicateEmission, valid.obligations()));

        List<Step> noDischarge = valid.steps().stream()
            .filter(step -> step.action() != StepAction.DISCHARGE_OBLIGATIONS)
            .toList();
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, noDischarge, valid.obligations()));

        List<Step> earlyDischarge = new ArrayList<>(valid.steps());
        Collections.swap(earlyDischarge, 3, 4);
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, earlyDischarge, valid.obligations()));

        List<Step> duplicateDischarge = new ArrayList<>(valid.steps());
        duplicateDischarge.add(duplicateDischarge.size() - 1,
            new Step("second-discharge", StepAction.DISCHARGE_OBLIGATIONS,
                List.of(), valid.obligationIds()));
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, duplicateDischarge, valid.obligations()));

        List<Step> holesIntroducedByIssuer = List.of(
            new Step("compose-effects", StepAction.COMPOSE, valid.holeIds(),
                valid.obligationIds()),
            new Step("check-obligations", StepAction.DISCHARGE_OBLIGATIONS,
                List.of(), valid.obligationIds()),
            new Step("emit-candidate", StepAction.EMIT_CANDIDATE,
                List.of(), valid.obligationIds()));
        assertThrows(IllegalArgumentException.class,
            () -> createLike(valid, holesIntroducedByIssuer, valid.obligations()));
    }

    @Test
    void partialAndBlockedResolutionsRemainNonExecutable() {
        SchematicProofPlan plan = plan(false);
        SchematicProofPlanResolution partial = SchematicProofPlanResolution.create(
            plan, List.of(alphaBinding()),
            List.of(confirmed("local-equivalence", "one")));
        assertEquals(ResolutionState.PARTIAL, partial.state());
        assertFalse(partial.isStructurallyCompleteFor(plan));

        for (OutcomeStatus status : OutcomeStatus.values()) {
            if (status == OutcomeStatus.CONFIRMED) {
                continue;
            }
            SchematicProofPlanResolution blocked = SchematicProofPlanResolution.create(
                plan, List.of(alphaBinding(), sigmaBinding()),
                List.of(outcome("local-equivalence", status,
                        "blocked-" + status.name()),
                    confirmed("residual-zero", "two")));
            assertEquals(ResolutionState.BLOCKED, blocked.state(), status.name());
            assertFalse(blocked.isStructurallyCompleteFor(plan));
        }
    }

    @Test
    void completeConfirmedResolutionStillCarriesNoExecutableCandidate() {
        SchematicProofPlan plan = plan(false);
        SchematicProofPlanResolution resolution = SchematicProofPlanResolution.create(
            plan, List.of(sigmaBinding(), alphaBinding()),
            List.of(confirmed("residual-zero", "two"),
                confirmed("local-equivalence", "one")));

        assertEquals(ResolutionState.COMPLETE_REFERENCES, resolution.state());
        assertEquals(List.of("alpha", "sigma"), resolution.bindings().stream()
            .map(HoleBinding::holeId).toList());
        assertTrue(resolution.isStructurallyCompleteFor(plan));
        assertFalse(resolution.toCanonicalJson().contains("transformedExpression"));
        assertFalse(resolution.toCanonicalJson().contains("rewriteProgram"));
    }

    @Test
    void rejectsNonCanonicalScalarsAndCheckerSubstitution() {
        SchematicProofPlan plan = plan(false);
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan,
                List.of(new HoleBinding("alpha", HoleSort.EXACT_RATIONAL, "2/4",
                    hash("noncanonical"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan,
                List.of(new HoleBinding("sigma", HoleSort.SIGN, "0",
                    hash("bad-sign"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan, List.of(),
                List.of(new ObligationOutcome("local-equivalence",
                    OutcomeStatus.CONFIRMED, "different-checker", CHECKER_REVISION,
                    hash("execution"), "IDENTITY_CONFIRMED"))));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(narrowScalarPlan(),
                List.of(new HoleBinding("alpha", HoleSort.EXACT_RATIONAL,
                    "18446744073709551616", hash("large-number"))), List.of()));
    }

    @Test
    void validatesOccurrencePairsDepthDisjointnessAndPlanBinding() {
        SchematicProofPlan occurrencePlan = occurrencePlan(2);
        HoleBinding pair = new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
            "0.1|1.0", hash("pair-evidence"));
        SchematicProofPlanResolution complete = SchematicProofPlanResolution.create(
            occurrencePlan, List.of(pair),
            List.of(new ObligationOutcome("pair-disjoint", OutcomeStatus.CONFIRMED,
                "occurrence-checker", CHECKER_REVISION, hash("pair-execution"),
                "OCCURRENCES_DISJOINT")));
        assertEquals(ResolutionState.COMPLETE_REFERENCES, complete.state());

        SchematicProofPlanResolution numericOrder =
            SchematicProofPlanResolution.create(
                occurrencePlan,
                List.of(new HoleBinding(
                    "term-pair",
                    HoleSort.OCCURRENCE_PAIR,
                    "0.2|0.10",
                    hash("numeric-order"))),
                List.of());
        assertEquals(ResolutionState.PARTIAL, numericOrder.state());

        SchematicProofPlanResolution arbitraryPrecisionOrder =
            SchematicProofPlanResolution.create(
                occurrencePlan,
                List.of(new HoleBinding(
                    "term-pair",
                    HoleSort.OCCURRENCE_PAIR,
                    "0.9223372036854775808|0.18446744073709551616",
                    hash("arbitrary-precision-order"))),
                List.of());
        assertEquals(
            ResolutionState.PARTIAL,
            arbitraryPrecisionOrder.state());

        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
                    "1.0|0.1", hash("reversed-pair"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
                    "0.10|0.2", hash("lexically-misordered-pair"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding(
                    "term-pair",
                    HoleSort.OCCURRENCE_PAIR,
                    "0.18446744073709551616|0.9223372036854775808",
                    hash("arbitrary-precision-reversed"))),
                List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
                    "0|0.1", hash("ancestor-pair"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
                    "0.1|root", hash("root-pair"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan(1), List.of(pair), List.of()));
        assertFalse(complete.isStructurallyCompleteFor(occurrencePlan(1)));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(occurrencePlan,
                List.of(new HoleBinding("term-pair", HoleSort.OCCURRENCE_PAIR,
                    "00.1|1.0", hash("noncanonical-path"))), List.of()));

        SchematicProofPlan substituted = SchematicProofPlan.create(
            occurrencePlan.planId(), occurrencePlan.informationBoundary(),
            hash("different-scope"), occurrencePlan.steps(), occurrencePlan.holes(),
            occurrencePlan.obligations(), occurrencePlan.limits());
        assertFalse(complete.isStructurallyCompleteFor(substituted));
    }

    @Test
    void rejectsDuplicatesOversizedValuesAndTamperedArtifacts() {
        SchematicProofPlan plan = plan(false);
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan,
                List.of(alphaBinding(), alphaBinding()), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan, List.of(),
                List.of(confirmed("local-equivalence", "one"),
                    confirmed("local-equivalence", "two"))));
        assertThrows(IllegalArgumentException.class, () -> new HoleBinding(
            "alpha", HoleSort.EXACT_RATIONAL, " 1/2", hash("whitespace")));
        assertThrows(IllegalArgumentException.class, () ->
            SchematicProofPlanResolution.create(plan,
                List.of(new HoleBinding("alpha", HoleSort.EXACT_RATIONAL,
                    "1234567890123456789012345678901234567890",
                    hash("too-many-bytes"))), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new SchematicProofPlanResolution(
                SchematicProofPlanResolution.SCHEMA,
                hash("empty-hole-plan"),
                List.of(),
                List.of("required-obligation"),
                List.of(),
                List.of(),
                ResolutionState.PARTIAL,
                hash("empty-hole-resolution")));

        assertThrows(IllegalArgumentException.class, () -> new SchematicProofPlan(
            plan.schema(), plan.planId(), plan.informationBoundary(),
            plan.formationScopeHash(), plan.steps(), plan.holes(), plan.obligations(),
            plan.limits(), hash("wrong-plan")));
        SchematicProofPlanResolution partial = SchematicProofPlanResolution.create(
            plan, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () ->
            new SchematicProofPlanResolution(partial.schema(), partial.planHash(),
                partial.requiredHoleIds(), partial.requiredObligationIds(),
                partial.bindings(), partial.outcomes(),
                ResolutionState.COMPLETE_REFERENCES, partial.contentHash()));
    }

    private static SchematicProofPlan plan(boolean reverseDefinitions) {
        List<Hole> holes = new ArrayList<>(List.of(
            new Hole("alpha", HoleKind.COEFFICIENT, HoleSort.EXACT_RATIONAL,
                ExactRationalDomain.DOMAIN_ID, "bounded-rational/v1", scalarBudget()),
            new Hole("sigma", HoleKind.SIGN, HoleSort.SIGN, "regelsuche.sign/v1",
                "finite-sign/v1", scalarBudget())));
        List<Obligation> obligations = new ArrayList<>(List.of(
            obligation("local-equivalence", ObligationKind.EQUIVALENT),
            obligation("residual-zero", ObligationKind.RESIDUAL_SUM_IS_ZERO)));
        if (reverseDefinitions) {
            Collections.reverse(holes);
            Collections.reverse(obligations);
        }
        return SchematicProofPlan.create("residual-balance-plan",
            InformationBoundary.TARGET_FREE_FORMATION, hash("formation-scope"),
            steps(), holes, obligations, LIMITS);
    }

    private static Obligation obligation(String id, ObligationKind kind) {
        return new Obligation(id, kind, "compose-effects", List.of("alpha", "sigma"),
            List.of(), "polynomial-normal-form", CHECKER_REVISION,
            InitialObligationStatus.OPEN);
    }

    private static List<Step> steps() {
        List<String> holes = List.of("alpha", "sigma");
        List<String> obligations = List.of("local-equivalence", "residual-zero");
        return List.of(
            new Step("form-coefficients", StepAction.FORM_CANDIDATES,
                List.of("alpha"), List.of()),
            new Step("form-signs", StepAction.FORM_CANDIDATES,
                List.of("sigma"), List.of()),
            new Step("solve-holes", StepAction.SOLVE_HOLES, holes, List.of()),
            new Step("compose-effects", StepAction.COMPOSE, holes, obligations),
            new Step("check-obligations", StepAction.DISCHARGE_OBLIGATIONS,
                List.of(), obligations),
            new Step("emit-candidate", StepAction.EMIT_CANDIDATE,
                List.of(), obligations));
    }

    private static SchematicProofPlan occurrencePlan(int maxDepth) {
        Hole hole = new Hole("term-pair", HoleKind.DISJOINT_TERM_PAIR,
            HoleSort.OCCURRENCE_PAIR, "regelsuche.occurrence-pair/v1",
            "tree-position-pair/v1", new HoleBudget(16, 64, 0, maxDepth));
        Obligation obligation = new Obligation("pair-disjoint",
            ObligationKind.DISJOINT_OCCURRENCES, "compose-pair", List.of("term-pair"),
            List.of(), "occurrence-checker", CHECKER_REVISION,
            InitialObligationStatus.OPEN);
        return SchematicProofPlan.create("occurrence-pair-plan",
            InformationBoundary.TARGET_FREE_FORMATION,
            hash("occurrence-scope-" + maxDepth),
            List.of(
                new Step("form-pair", StepAction.FORM_CANDIDATES,
                    List.of("term-pair"), List.of()),
                new Step("compose-pair", StepAction.COMPOSE,
                    List.of("term-pair"), List.of("pair-disjoint")),
                new Step("check-pair", StepAction.DISCHARGE_OBLIGATIONS,
                    List.of(), List.of("pair-disjoint")),
                new Step("emit-pair", StepAction.EMIT_CANDIDATE,
                    List.of(), List.of("pair-disjoint"))),
            List.of(hole), List.of(obligation), LIMITS);
    }

    private static SchematicProofPlan narrowScalarPlan() {
        Hole hole = new Hole("alpha", HoleKind.COEFFICIENT, HoleSort.EXACT_RATIONAL,
            ExactRationalDomain.DOMAIN_ID, "bounded-rational/v1",
            new HoleBudget(4, 64, 8, 0));
        Obligation obligation = new Obligation("alpha-valid", ObligationKind.EQUIVALENT,
            "compose-alpha", List.of("alpha"), List.of(), "polynomial-normal-form",
            CHECKER_REVISION, InitialObligationStatus.OPEN);
        return SchematicProofPlan.create("narrow-scalar-plan",
            InformationBoundary.TARGET_FREE_FORMATION, hash("narrow-scope"),
            List.of(
                new Step("form-alpha", StepAction.FORM_CANDIDATES,
                    List.of("alpha"), List.of()),
                new Step("compose-alpha", StepAction.COMPOSE,
                    List.of("alpha"), List.of("alpha-valid")),
                new Step("check-alpha", StepAction.DISCHARGE_OBLIGATIONS,
                    List.of(), List.of("alpha-valid")),
                new Step("emit-alpha", StepAction.EMIT_CANDIDATE,
                    List.of(), List.of("alpha-valid"))),
            List.of(hole), List.of(obligation), LIMITS);
    }

    private static SchematicProofPlan createLike(
        SchematicProofPlan source,
        List<Step> replacementSteps,
        List<Obligation> replacementObligations
    ) {
        return SchematicProofPlan.create(source.planId(), source.informationBoundary(),
            source.formationScopeHash(), replacementSteps, source.holes(),
            replacementObligations, source.limits());
    }

    private static HoleBudget scalarBudget() {
        return new HoleBudget(16, 32, 64, 0);
    }

    private static HoleBinding alphaBinding() {
        return new HoleBinding("alpha", HoleSort.EXACT_RATIONAL, "1/2",
            hash("alpha-evidence"));
    }

    private static HoleBinding sigmaBinding() {
        return new HoleBinding("sigma", HoleSort.SIGN, "-1", hash("sigma-evidence"));
    }

    private static ObligationOutcome confirmed(String id, String discriminator) {
        return outcome(id, OutcomeStatus.CONFIRMED, discriminator);
    }

    private static ObligationOutcome outcome(
        String id,
        OutcomeStatus status,
        String discriminator
    ) {
        return new ObligationOutcome(id, status, "polynomial-normal-form",
            CHECKER_REVISION, hash("execution-" + discriminator),
            status == OutcomeStatus.CONFIRMED
                ? "IDENTITY_CONFIRMED"
                : "CHECK_" + status.name());
    }

    private static String hash(String value) {
        return SchematicProofPlan.hash(value);
    }
}
