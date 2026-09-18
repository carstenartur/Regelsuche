package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.moves.MoveSearch;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModPowDagRediscoveryStudyTest {

    @Test
    void targetBlindDagCostRediscoveryPassesFrozenTestProfiles() {
        var study = ModPowDagRediscoveryStudy.run();

        assertTrue(study.green());
        assertEquals(2, study.formation().size());
        assertEquals(List.of("C31", "C61", "C93", "C123"),
            study.test().stream().map(ModPowDagRediscoveryStudy.CaseResult::id).toList());

        for (var result : study.test()) {
            assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, result.outcome());
            assertTrue(result.completeBoundedRelation());
            assertEquals(3, result.reachedStates());
            assertEquals(2, result.generatedSuccessors());
            assertEquals(2, result.acceptedProofReceipts());
            assertTrue(result.dagImproved());
            assertFalse(result.treeImproved(),
                "ordinary tree cost must not manufacture the reuse advantage");
            assertTrue(result.expectedSharedResidue(),
                "selected program must reuse the independently needed e-residue");
        }
    }

    @Test
    void frozenBitProfilesProduceThePredeclaredTreeVsDagSeparation() {
        for (var profile : ModPowDagRediscoveryStudy.TEST) {
            var result = ModPowDagRediscoveryStudy.runCase(profile, false);
            long expectedTree = profile.qBits() + 2L * profile.eBits();
            long expectedSharedDag = profile.qBits() + (long) profile.eBits();

            assertEquals(expectedTree, result.originalCost().tree());
            assertEquals(expectedTree, result.originalCost().dag());
            assertEquals(expectedTree, result.selectedCost().tree());
            assertEquals(expectedSharedDag, result.selectedCost().dag());
        }
    }

    @Test
    void negativeControlWithoutRepeatedEResidueGetsNoDagAdvantage() {
        var result = ModPowDagRediscoveryStudy.runCase(
            new ModPowDagRediscoveryStudy.BitProfile("NEG", 47, 46, 46), true);

        assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, result.outcome());
        assertTrue(result.completeBoundedRelation());
        assertFalse(result.dagImproved());
        assertFalse(result.treeImproved());
        assertFalse(result.expectedSharedResidue());
        assertEquals(result.originalCost(), result.selectedCost());
    }

    @Test
    void shapeAuditRejectsACompositionWithTheWrongOuterExponent() {
        Expr a = new VariableExpr("a");
        Expr q = new VariableExpr("q");
        Expr e = new VariableExpr("e");
        Expr n = new VariableExpr("n");
        Expr source = new FunctionExpr("modpow", List.of(
            a, new BinaryExpr(q, BinaryOperator.MUL, e), n));
        Expr wrong = new FunctionExpr("modpow", List.of(
            new FunctionExpr("modpow", List.of(a, e, n)),
            e,
            n));

        assertEquals(-1, ModPowDagRediscoveryStudy.compositionDifferenceCount(
            source, wrong, ModPowDagRediscoveryStudy.RULE_RIGHT));
    }

    @Test
    void modularExponentCompositionLawMatchesExactSmallIntegerSemantics() {
        for (int modulus = 2; modulus <= 31; modulus++) {
            BigInteger n = BigInteger.valueOf(modulus);
            for (int base = 0; base <= 12; base++) {
                BigInteger a = BigInteger.valueOf(base);
                for (int left = 0; left <= 7; left++) {
                    for (int right = 0; right <= 7; right++) {
                        BigInteger direct = a.modPow(
                            BigInteger.valueOf((long) left * right), n);
                        BigInteger composed = a.modPow(BigInteger.valueOf(left), n)
                            .modPow(BigInteger.valueOf(right), n);
                        assertEquals(direct, composed,
                            () -> "modPow composition mismatch for a=" + base
                                + ", u=" + left + ", v=" + right + ", n=" + modulus);
                    }
                }
            }
        }
    }
}
