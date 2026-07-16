package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import java.util.List;
import java.util.Objects;

/** Reconstructs expected obligations instead of duplicating field-level checks. */
public final class SolverObligationVerifier {
    private final SolverObligationFactory factory = new SolverObligationFactory();

    public boolean matchesEquality(
        Obligation actual,
        String sourceId,
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    ) {
        if (actual == null || sourceId == null || sourceId.isBlank()) {
            return false;
        }
        try {
            Obligation expected = factory.relation(
                actual.obligationId(),
                Relation.EQUALS,
                leftExpression,
                rightExpression,
                assumptions,
                actual.requestedEvidence(),
                actual.provenance());
            return sourceId.equals(actual.provenance().sourceId())
                && expected.contentHash().equals(actual.contentHash());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean resultBelongsTo(
        Obligation obligation,
        SolverResult result
    ) {
        return obligation != null
            && result != null
            && Objects.equals(result.obligationHash(), obligation.contentHash())
            && Objects.equals(result.goalHash(), obligation.goalHash())
            && Objects.equals(result.assumptionsHash(), obligation.assumptionsHash());
    }
}
