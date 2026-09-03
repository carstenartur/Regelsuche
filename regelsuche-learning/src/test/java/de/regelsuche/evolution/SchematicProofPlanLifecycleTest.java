package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import de.regelsuche.scalar.ExactRationalDomain;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchematicProofPlanLifecycleTest {
    private static final String CHECKER_REVISION =
        SchematicProofPlan.hash("lifecycle-checker");
    private static final Limits LIMITS =
        new Limits(8, 4, 4, 32_768);

    @Test
    void v1RequiresAtLeastOneDeclaredHoleAndPositiveHoleLimit() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Limits(4, 0, 1, 4_096));
        assertThrows(
            IllegalArgumentException.class,
            () -> SchematicProofPlan.create(
                "hole-free-plan",
                InformationBoundary.TARGET_FREE_FORMATION,
                SchematicProofPlan.hash("hole-free-scope"),
                List.of(
                    new Step(
                        "check-only",
                        StepAction.DISCHARGE_OBLIGATIONS,
                        List.of(),
                        List.of("check-result")),
                    new Step(
                        "emit-only",
                        StepAction.EMIT_CANDIDATE,
                        List.of(),
                        List.of("check-result"))),
                List.of(),
                List.of(obligation(
                    "check-result",
                    "check-only",
                    List.of())),
                LIMITS));
    }

    @Test
    void composeCannotBeTheFirstStepThatMentionsAHole() {
        Hole hole = coefficientHole();
        Obligation obligation = obligation(
            "composition-valid",
            "compose-before-preparation",
            List.of("alpha"));

        assertThrows(
            IllegalArgumentException.class,
            () -> SchematicProofPlan.create(
                "late-hole-preparation",
                InformationBoundary.TARGET_FREE_FORMATION,
                SchematicProofPlan.hash("late-preparation-scope"),
                List.of(
                    new Step(
                        "compose-before-preparation",
                        StepAction.COMPOSE,
                        List.of("alpha"),
                        List.of("composition-valid")),
                    new Step(
                        "form-too-late",
                        StepAction.FORM_CANDIDATES,
                        List.of("alpha"),
                        List.of()),
                    new Step(
                        "check-composition",
                        StepAction.DISCHARGE_OBLIGATIONS,
                        List.of(),
                        List.of("composition-valid")),
                    new Step(
                        "emit-composition",
                        StepAction.EMIT_CANDIDATE,
                        List.of(),
                        List.of("composition-valid"))),
                List.of(hole),
                List.of(obligation),
                LIMITS));
    }

    @Test
    void solvingStepMayPrepareAHoleAndIssueItsObligation() {
        Hole hole = coefficientHole();
        Obligation obligation = obligation(
            "coefficient-valid",
            "solve-coefficient",
            List.of("alpha"));

        SchematicProofPlan plan = SchematicProofPlan.create(
            "same-step-solver-obligation",
            InformationBoundary.TARGET_FREE_FORMATION,
            SchematicProofPlan.hash("same-step-scope"),
            List.of(
                new Step(
                    "solve-coefficient",
                    StepAction.SOLVE_HOLES,
                    List.of("alpha"),
                    List.of("coefficient-valid")),
                new Step(
                    "check-coefficient",
                    StepAction.DISCHARGE_OBLIGATIONS,
                    List.of(),
                    List.of("coefficient-valid")),
                new Step(
                    "emit-coefficient",
                    StepAction.EMIT_CANDIDATE,
                    List.of(),
                    List.of("coefficient-valid"))),
            List.of(hole),
            List.of(obligation),
            LIMITS);

        assertEquals(
            List.of("alpha"),
            plan.holeIds());
        assertEquals(
            List.of("coefficient-valid"),
            plan.obligationIds());
    }

    @Test
    void dischargeStepCannotAlsoBeAnObligationIssuer() {
        Hole hole = coefficientHole();
        Obligation obligation = obligation(
            "late-issued",
            "check-and-issue",
            List.of("alpha"));

        assertThrows(
            IllegalArgumentException.class,
            () -> SchematicProofPlan.create(
                "discharge-as-issuer",
                InformationBoundary.TARGET_FREE_FORMATION,
                SchematicProofPlan.hash("discharge-issuer-scope"),
                List.of(
                    new Step(
                        "form-alpha",
                        StepAction.FORM_CANDIDATES,
                        List.of("alpha"),
                        List.of()),
                    new Step(
                        "check-and-issue",
                        StepAction.DISCHARGE_OBLIGATIONS,
                        List.of(),
                        List.of("late-issued")),
                    new Step(
                        "emit-late-issued",
                        StepAction.EMIT_CANDIDATE,
                        List.of(),
                        List.of("late-issued"))),
                List.of(hole),
                List.of(obligation),
                LIMITS));
    }

    private static Hole coefficientHole() {
        return new Hole(
            "alpha",
            HoleKind.COEFFICIENT,
            HoleSort.EXACT_RATIONAL,
            ExactRationalDomain.DOMAIN_ID,
            "bounded-rational/v1",
            new HoleBudget(16, 64, 64, 0));
    }

    private static Obligation obligation(
        String id,
        String issuerStepId,
        List<String> dependentHoleIds
    ) {
        return new Obligation(
            id,
            ObligationKind.EQUIVALENT,
            issuerStepId,
            dependentHoleIds,
            List.of(),
            "polynomial-normal-form",
            CHECKER_REVISION,
            InitialObligationStatus.OPEN);
    }
}
