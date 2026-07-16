package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.SymbolDeclaration;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolverIrContractTest {
    private final SolverObligationFactory factory = new SolverObligationFactory();
    private final SolverIrJsonCodec codec = new SolverIrJsonCodec();

    @Test
    void canonicalHashIsStableAcrossAssumptionOrderAndJsonRoundTrip() {
        Obligation first = factory.equality(
            "factor-common",
            "A * B + A * C",
            "A * (B + C)",
            List.of("B != 0", "A > 0"),
            RequestedEvidence.DECISION,
            provenance());
        Obligation second = factory.equality(
            "factor-common",
            "A * B + A * C",
            "A * (B + C)",
            List.of("A > 0", "B != 0"),
            RequestedEvidence.DECISION,
            provenance());

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first.toCanonicalJson(),
            codec.readObligation(first.toCanonicalJson()).toCanonicalJson());
        assertEquals(List.of("A", "B", "C"), first.declarations().stream()
            .map(SymbolDeclaration::name).toList());
        assertEquals(Relation.GREATER_THAN, first.assumptions().getFirst().relation());
        assertEquals(Relation.NOT_EQUALS, first.assumptions().getLast().relation());
    }

    @Test
    void coreAstAdapterRoundTripsBoundedAlgebra() {
        CoreExpressionIrAdapter adapter = new CoreExpressionIrAdapter();
        var expression = adapter.parse("(x + 2) * sin(y) / 3");

        assertEquals("(x + 2) * sin(y) / 3", adapter.render(expression));
        assertEquals(expression, adapter.toIr(adapter.toCore(expression)));
    }

    @Test
    void undeclaredSymbolsAndMalformedJsonAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Obligation.create(
            "invalid",
            List.of(new SymbolDeclaration("x", Sort.REAL)),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(),
            new Goal(Relation.EQUALS, new Symbol("x"), new Symbol("y")),
            RequestedEvidence.DECISION,
            provenance()));

        assertThrows(IllegalArgumentException.class, () ->
            codec.readObligation("{\"schema\":\"regelsuche.solver-obligation/v1\"}"));
    }

    @Test
    void unsupportedOpaqueAssumptionIsNeverSilentlyStored() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> factory.equality(
                "opaque-assumption",
                "x",
                "x",
                List.of("x belongs to a mysterious domain"),
                RequestedEvidence.DECISION,
                provenance()));

        assertTrue(exception.getMessage().contains("unsupported structured assumption"));
    }

    private static SourceProvenance provenance() {
        return new SourceProvenance(
            "test",
            "solver-ir-contract",
            SolverIr.sha256("solver-ir-contract-revision"));
    }
}
