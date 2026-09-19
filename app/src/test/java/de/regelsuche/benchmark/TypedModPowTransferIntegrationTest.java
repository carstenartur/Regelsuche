package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Development integration, not a frozen held-out learning experiment. */
class TypedModPowTransferIntegrationTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void learnsFromTargetBlindTrainWitnessesThenReplaysReorderedExtraOutputContext() {
        var model = learnedFromTrain();
        var program = program("program(modpow(b,t,m),7,modpow(b,s*t,m))");
        var proposals = new TypedOutputPattern(model).find(program, 64);
        assertTrue(proposals.complete());
        assertEquals(1, proposals.applications().size());
        var proposal = proposals.applications().getFirst();
        assertEquals(List.of(2, 0), proposal.positions());
        assertSame(program.arguments().get(1), proposal.target().arguments().get(1));
        assertEquals(program("program(modpow(b,t,m),7,modpow(modpow(b,t,m),s,m))"), proposal.target());
        var premises = assumptions("b", "s", "t", "m");
        var checked = ModPowCompositionReplay.verify(program, proposal.target(), premises).orElseThrow();
        assertSame(program, checked.source());
        assertSame(proposal.target(), checked.target());
        assertEquals(ModPowDagRediscoveryStudy.RULE_RIGHT, checked.primitiveRule());
        assertEquals(premises, checked.assumptions());
        assertThrows(UnsupportedOperationException.class, () -> checked.assumptions().add("invented premise"));
    }

    @Test
    void syntaxMatchingCannotSupplyTheMissingArithmeticPremises() {
        var program = program("program(modpow(b,s*t,m),modpow(b,t,m))");
        var proposals = new TypedOutputPattern(learnedFromTrain()).find(program, 64);
        assertEquals(1, proposals.applications().size());
        assertTrue(ModPowCompositionReplay.verify(program, proposals.applications().getFirst().target(), List.of()).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(program, proposals.applications().getFirst().target(),
            assumptions("a0", "q0", "e0", "n0")).isEmpty(), "TRAIN premises cannot authorize new variables");
    }

    @Test
    void unrelatedRetainedExponentDoesNotAcquireASharedComputation() {
        var program = program("program(modpow(b,s*t,m),modpow(b,r,m))");
        var proposals = new TypedOutputPattern(learnedFromTrain()).find(program, 64);
        assertTrue(proposals.complete());
        assertTrue(proposals.applications().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a integer", "q integer", "q >= 0", "e integer", "e >= 0", "n integer", "n > 0"})
    void eachNecessaryConcretePremiseIsRequired(String missing) {
        var premises = new ArrayList<>(assumptions("a", "q", "e", "n"));
        assertTrue(premises.remove(missing));
        assertTrue(ModPowCompositionReplay.verify(parser.parseTerm("modpow(a,q*e,n)"),
            parser.parseTerm("modpow(modpow(a,e,n),q,n)"), premises).isEmpty());
    }

    @Test
    void bothPrimitiveFactorOrdersReplayWithoutConsultingALearnedScore() {
        Expr source = parser.parseTerm("modpow(a,q*e,n)");
        var premises = assumptions("a", "q", "e", "n");
        var left = ModPowCompositionReplay.verify(source,
            parser.parseTerm("modpow(modpow(a,q,n),e,n)"), premises).orElseThrow();
        var right = ModPowCompositionReplay.verify(source,
            parser.parseTerm("modpow(modpow(a,e,n),q,n)"), premises).orElseThrow();
        assertEquals(ModPowDagRediscoveryStudy.RULE_LEFT, left.primitiveRule());
        assertEquals(ModPowDagRediscoveryStudy.RULE_RIGHT, right.primitiveRule());
    }

    @Test
    void replayRejectsChangedUnrelatedOutputModulusAndMoreThanOneComposition() {
        var premises = assumptions("a", "q", "e", "n");
        Expr source = parser.parseTerm("program(modpow(a,q*e,n),7)");
        assertTrue(ModPowCompositionReplay.verify(source,
            parser.parseTerm("program(modpow(modpow(a,e,n),q,n),8)"), premises).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(source,
            parser.parseTerm("program(modpow(modpow(a,e,n),q,m),7)"), premises).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(
            parser.parseTerm("program(modpow(a,q*e,n),modpow(a,q*e,n))"),
            parser.parseTerm("program(modpow(modpow(a,e,n),q,n),modpow(modpow(a,e,n),q,n))"), premises).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(source, source, premises).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(parser.parseTerm("f(a)"), parser.parseTerm("g(a)"), premises).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "modpow(modpow(a,e,m),q,n)",
        "modpow(modpow(a,e,n),q,m)",
        "modpow(modpow(a,e,m),q,m)"
    })
    void changedModuliAreRejectedEvenWhenBothArithmeticDomainsAreSatisfied(String changed) {
        var premises = new ArrayList<>(assumptions("a", "q", "e", "n"));
        premises.addAll(List.of("m integer", "m > 0"));
        Expr source = parser.parseTerm("modpow(a,q*e,n)");
        Expr target = parser.parseTerm(changed);
        assertTrue(ModPowDagRediscoveryStudy.domainContractSatisfied(source, premises));
        assertTrue(ModPowDagRediscoveryStudy.domainContractSatisfied(target, premises),
            "the modulus-equality control must reach the structural auditor");
        assertTrue(ModPowCompositionReplay.verify(source,
            parser.parseTerm("modpow(modpow(a,e,n),q,n)"), premises).isPresent());
        assertTrue(ModPowCompositionReplay.verify(source, target, premises).isEmpty());
    }

    @Test
    void structuralPreflightBoundsBothSidesBeforeRecursiveAudit() {
        Expr oversized = new FunctionExpr("wide", java.util.Collections.nCopies(
            ModPowCompositionReplay.MAXIMUM_NODES, new VariableExpr("x")));
        Expr small = new VariableExpr("x");
        assertThrows(IllegalArgumentException.class, () -> ModPowCompositionReplay.verify(oversized, small, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ModPowCompositionReplay.verify(small, oversized, List.of()));
        Expr deep = small;
        for (int i = 0; i <= ModPowCompositionReplay.MAXIMUM_DEPTH; i++) deep = new FunctionExpr("f", List.of(deep));
        Expr tooDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> ModPowCompositionReplay.verify(tooDeep, small, List.of()));
    }

    private TypedPatternGeneralizer.Candidate learnedFromTrain() {
        Expr source = parser.parseTerm("program(modpow(a,q*e,n),modpow(a,e,n))");
        var examples = new ArrayList<TypedPatternGeneralizer.Example>();
        for (int i = 0; i < ModPowDagRediscoveryStudy.FORMATION.size(); i++) {
            var run = ModPowDagRediscoveryStudy.runCase(ModPowDagRediscoveryStudy.FORMATION.get(i), false);
            assertTrue(run.completeBoundedRelation());
            assertEquals(2, run.acceptedProofReceipts());
            var names = Map.of("a", "a" + i, "q", "q" + i, "e", "e" + i, "n", "n" + i);
            Expr observedSource = rename(source, names);
            Expr observedTarget = rename(run.selectedProgram(), names);
            var premises = assumptions(names.get("a"), names.get("q"), names.get("e"), names.get("n"));
            assertTrue(ModPowCompositionReplay.verify(observedSource, observedTarget, premises).isPresent(),
                "each target-blind TRAIN witness must replay before formation");
            examples.add(new TypedPatternGeneralizer.Example(observedSource, observedTarget, premises));
        }
        return new TypedPatternGeneralizer().generalize(examples).orElseThrow();
    }

    private FunctionExpr program(String source) { return (FunctionExpr) parser.parseTerm(source); }

    private static List<String> assumptions(String a, String q, String e, String n) {
        return List.of(a + " integer", q + " integer", q + " >= 0", e + " integer", e + " >= 0", n + " integer", n + " > 0");
    }

    private static Expr rename(Expr expression, Map<String, String> names) {
        if (expression instanceof VariableExpr variable) return new VariableExpr(names.getOrDefault(variable.name(), variable.name()));
        if (expression instanceof BinaryExpr binary) return new BinaryExpr(rename(binary.left(), names), binary.operator(), rename(binary.right(), names));
        if (expression instanceof FunctionExpr function) return new FunctionExpr(function.name(), function.arguments().stream().map(child -> rename(child, names)).toList());
        return expression;
    }
}
