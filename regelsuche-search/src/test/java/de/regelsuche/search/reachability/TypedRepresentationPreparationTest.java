package de.regelsuche.search.reachability;

import de.regelsuche.representation.RepresentationBridge;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.representation.RepresentationBridge.*;

/** Relation identity must survive the unified entry point even for identical payloads. */
class TypedRepresentationPreparationTest {
    @Test void preservesEveryTypedRelationAndRejectsCrossRelationReuse() {
        for (Relation relation : Relation.values()) {
            var bridge = new DeclaredRelation(relation);
            var result = UnifiedRulePreparationCoordinator.prepareRepresentation(
                "same payload", bridge, Set.of(relation), bridge, new Budget(10));
            assertTrue(result.accepted());
            assertEquals(relation, result.formation().relation().orElseThrow());
            assertEquals(relation, result.replay().orElseThrow().relation().orElseThrow());
            assertEquals(WorkLedger.of(10, 2), result.work());
            Relation different = relation == Relation.EXACT_EXPRESSION_EQUALITY
                ? Relation.MODEL_INTERPRETATION_CANDIDATE : Relation.EXACT_EXPRESSION_EQUALITY;
            var rejected = UnifiedRulePreparationCoordinator.prepareRepresentation(
                "same payload", bridge, Set.of(different), bridge, new Budget(10));
            assertFalse(rejected.accepted());
            assertTrue(rejected.replay().isEmpty());
        }
    }

    @Test void concretePrincipalUsesRemainingBudgetAndRetainsItsOwnConsequenceRelation() {
        var formation = new DeclaredRelation(Relation.LINEAR_MAP_REPRESENTATION_EQUIVALENCE);
        var principal = new DeclaredRelation(Relation.SPECTRAL_OR_SIMILARITY_RELATION);
        var exhausted = UnifiedRulePreparationCoordinator.prepareRepresentation("operator", formation,
            Set.of(formation.relation()), principal, new Budget(1));
        assertFalse(exhausted.accepted());
        assertEquals(Status.BUDGET_INCONCLUSIVE, exhausted.replay().orElseThrow().status());
        var accepted = UnifiedRulePreparationCoordinator.prepareRepresentation("operator", formation,
            Set.of(formation.relation()), principal, new Budget(2));
        assertTrue(accepted.accepted());
        assertEquals(formation.relation(), accepted.formation().relation().orElseThrow());
        assertEquals(principal.relation(), accepted.replay().orElseThrow().relation().orElseThrow());
    }

    private record DeclaredRelation(Relation relation) implements RepresentationBridge<String, String, String> {
        @Override public Result<String, String> analyze(String source, Budget budget) {
            if (budget.maxWorkUnits() == 0) {
                return Result.withoutRepresentation(Status.BUDGET_INCONCLUSIVE, WorkLedger.of(0, 0), "NO_WORK");
            }
            return Result.represented(source, relation + ":" + source, relation,
                WorkLedger.of(budget.maxWorkUnits(), 1), "TYPED_TEST_PRINCIPAL");
        }
        @Override public boolean verify(String source, Result<String, String> result) {
            return analyze(source, new Budget(result.work().configuredWorkUnits())).equals(result);
        }
    }
}
