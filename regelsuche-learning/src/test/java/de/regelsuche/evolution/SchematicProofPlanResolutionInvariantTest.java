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
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.evolution.SchematicProofPlanResolution.ResolutionState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SchematicProofPlanResolutionInvariantTest {
    private static final String CHECKER_REVISION = hash("checker-revision");

    @Test
    void directConstructionRejectsAnEmptyRequiredHoleSet() {
        IllegalArgumentException rejected = assertThrows(
            IllegalArgumentException.class,
            () -> new SchematicProofPlanResolution(
                SchematicProofPlanResolution.SCHEMA,
                hash("plan"),
                List.of(),
                List.of("pair-disjoint"),
                List.of(),
                List.of(),
                ResolutionState.PARTIAL,
                hash("irrelevant-content")));

        assertEquals(
            "requiredHoleIds must not be empty",
            rejected.getMessage());
    }

    @Test
    void occurrencePairsUseNumericTreeOrderInsteadOfLexicalOrder() {
        SchematicProofPlan plan = occurrencePairPlan(2, 64);
        SchematicProofPlanResolution resolution =
            SchematicProofPlanResolution.create(
                plan,
                List.of(pairBinding("0.2|0.10")),
                List.of(confirmedOutcome()));

        assertEquals(
            ResolutionState.COMPLETE_REFERENCES,
            resolution.state());

        IllegalArgumentException reversed = assertThrows(
            IllegalArgumentException.class,
            () -> SchematicProofPlanResolution.create(
                plan,
                List.of(pairBinding("0.10|0.2")),
                List.of(confirmedOutcome())));
        assertEquals(
            "occurrence pair must be distinct and numerically ordered",
            reversed.getMessage());
    }

    @Test
    void numericOrderingDoesNotOverflowMachineIntegers() {
        SchematicProofPlan plan = occurrencePairPlan(2, 96);
        SchematicProofPlanResolution resolution =
            SchematicProofPlanResolution.create(
                plan,
                List.of(pairBinding(
                    "0.2147483648|0.9223372036854775808")),
                List.of(confirmedOutcome()));

        assertEquals(
            ResolutionState.COMPLETE_REFERENCES,
            resolution.state());
    }

    private static SchematicProofPlan occurrencePairPlan(
        int maxDepth,
        int maxBytes
    ) {
        Hole pair = new Hole(
            "term-pair",
            HoleKind.DISJOINT_TERM_PAIR,
            HoleSort.OCCURRENCE_PAIR,
            "regelsuche.occurrence-pair/v1",
            "tree-position-pair/v1",
            new HoleBudget(16, maxBytes, 0, maxDepth));
        Obligation obligation = new Obligation(
            "pair-disjoint",
            ObligationKind.DISJOINT_OCCURRENCES,
            "compose-pair",
            List.of("term-pair"),
            List.of(),
            "occurrence-checker",
            CHECKER_REVISION,
            InitialObligationStatus.OPEN);
        return SchematicProofPlan.create(
            "numeric-occurrence-pair-plan",
            InformationBoundary.TARGET_FREE_FORMATION,
            hash("numeric-occurrence-scope-" + maxBytes),
            List.of(
                new Step(
                    "form-pair",
                    StepAction.FORM_CANDIDATES,
                    List.of("term-pair"),
                    List.of()),
                new Step(
                    "compose-pair",
                    StepAction.COMPOSE,
                    List.of("term-pair"),
                    List.of("pair-disjoint")),
                new Step(
                    "check-pair",
                    StepAction.DISCHARGE_OBLIGATIONS,
                    List.of(),
                    List.of("pair-disjoint")),
                new Step(
                    "emit-pair",
                    StepAction.EMIT_CANDIDATE,
                    List.of(),
                    List.of("pair-disjoint"))),
            List.of(pair),
            List.of(obligation),
            new Limits(8, 8, 4, 200_000));
    }

    private static HoleBinding pairBinding(String value) {
        return new HoleBinding(
            "term-pair",
            HoleSort.OCCURRENCE_PAIR,
            value,
            hash("binding-" + value));
    }

    private static ObligationOutcome confirmedOutcome() {
        return new ObligationOutcome(
            "pair-disjoint",
            OutcomeStatus.CONFIRMED,
            "occurrence-checker",
            CHECKER_REVISION,
            hash("checker-execution"),
            "OCCURRENCES_DISJOINT");
    }

    private static String hash(String material) {
        return SchematicProofPlan.hash(material);
    }
}
