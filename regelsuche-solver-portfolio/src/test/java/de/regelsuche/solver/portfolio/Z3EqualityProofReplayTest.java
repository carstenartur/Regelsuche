package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Z3EqualityProofReplayTest {
    @Test
    void replaysCapturedDistributivityWithoutTrustingRewriteNames() throws IOException {
        String proof = captured("distributivity");
        var statement = statement("x*(y+z)", "x*y+x*z");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*(y+z)", "x*y-x*z"), proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(rewrite $x32)", "(unproved $x32)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(rewrite $x32)", "(rewrite (= (* x (+ y z)) (* x y)))")));
    }

    @Test
    void replaysExactNumeralCoercionsAndTerminatingQuotients() throws IOException {
        assertTrue(Z3EqualityProofReplay.verifies(statement("x+0", "x"), captured("add-zero")));
        assertTrue(Z3EqualityProofReplay.verifies(statement("0.5*x+0.25*x", "0.75*x"), captured("decimal-collection")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("0.5*x+0.25*x", "0.75*x"),
            captured("decimal-collection").replace("(/ 1.0 4.0)", "(/ 1.0 3.0)")));
    }

    @Test
    void checksCompoundCongruenceAgainstExactAntecedentAndArgumentPosition() {
        var statement = statement("x*z", "y*z", "x = y");
        String derivation = "(monotonicity (asserted (= x y)) (= (* x z) (* y z)))";
        String proof = contradiction(derivation, "(= (* x z) (* y z))");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(asserted (= x y))", "(asserted (= y x))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(monotonicity (asserted (= x y))", "(monotonicity (refl (= z z))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(* y z)", "(* z y)")));
    }

    @Test
    void replaysNativeCancellationOfEqualAddends() {
        // Captured from Z3 4.13.3.0: x = y and not (x+1 = y+1), with proof production enabled.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x25 (= x y)))
            (let (($x69 (not $x25)))
            (let ((@x72 (rewrite (= (not (= (+ x (to_real 1)) (+ y (to_real 1)))) $x69))))
            (let (($x32 (not (= (+ x (to_real 1)) (+ y (to_real 1))))))
            (let ((@x33 (asserted $x32)))
            (let ((@x26 (asserted $x25)))
            (unit-resolution @x26 (mp @x33 @x72 $x69) false)))))))))
            """;
        assertTrue(Z3EqualityProofReplay.verifies(statement("x+1", "y+1", "x = y"), proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x+1", "y+2", "x = y"),
            proof.replace("(+ y (to_real 1))", "(+ y (to_real 2))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x+1", "y+1", "y = x"), proof));
    }

    @Test
    void checksEquationDifferenceWithReversedOrientation() {
        String equality = "(= (+ x 1.0) (+ y 1.0))";
        String proof = contradiction("(mp (asserted (= y x)) (rewrite (= (= y x) "
            + equality + ")) " + equality + ")", equality);
        assertTrue(Z3EqualityProofReplay.verifies(statement("x+1", "y+1", "y = x"), proof));
        String invalid = proof.replace("(+ y 1.0)", "(+ y 2.0)");
        assertFalse(Z3EqualityProofReplay.verifies(statement("x+1", "y+2", "y = x"), invalid));
    }

    @Test
    void replaysNativeZeroProductWithThreeFactorsAndRejectsForgery() {
        // Captured from Z3 4.13.3.0 with proof production: x = 0 implies x*y*z = 0.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x51 (not (or (= z 0.0) (= y 0.0) (= x 0.0)))))
            (let (($x43 (not (= (* x y z) 0.0))))
            (let ((@x42 (mp (asserted (not (= (* x y z) (to_real 0)))) (rewrite (= (not (= (* x y z) (to_real 0))) $x43)) $x43)))
            (let ((@x58 (not-or-elim (mp @x42 (rewrite (= $x43 $x51)) $x51) (not (= x 0.0)))))
            (let (($x31 (= x 0.0)))
            (let ((@x29 (mp (asserted (= x (to_real 0))) (rewrite (= (= x (to_real 0)) $x31)) $x31)))
            (unit-resolution @x29 @x58 false)))))))))
            """;
        var statement = statement("x*y*z", "0", "x = 0");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(or (= z 0.0) (= y 0.0) (= x 0.0))", "(or (= y 0.0) (= x 0.0))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(= z 0.0)", "(= z 1.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(not-or-elim", "(unsupported-rule")));
    }

    @Test
    void replaysNativeFactoredEquationAndRequiresSelectedDisjunct() {
        // Captured from Z3 4.13.3.0: x = 1 implies x*y = y.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x55 (= x 1.0)))
            (let (($x70 (not $x55)))
            (let (($x6 (= (+ x (- 1.0)) 0.0)))
            (let (($x58 (not $x6)))
            (let (($x48 (not (or (= y 0.0) $x6))))
            (let (($x18 (not (= (* x y) y))))
            (let ((@x7 (asserted $x18)))
            (let ((@x72 (mp (not-or-elim (mp @x7 (rewrite (= $x18 $x48)) $x48) $x58) (rewrite (= $x58 $x70)) $x70)))
            (let ((@x53 (mp (asserted (= x (to_real 1))) (rewrite (= (= x (to_real 1)) $x55)) $x55)))
            (unit-resolution @x53 @x72 false))))))))))))
            """;
        assertTrue(Z3EqualityProofReplay.verifies(statement("x*y", "y", "x = 1"), proof));
        // The negative goal does not imply the negation of an unrelated factor.
        String forged = wrap("(unit-resolution (asserted (= z 0.0)) "
            + "(not-or-elim (mp (asserted (not (= (* x y) 0.0))) "
            + "(rewrite (= (not (= (* x y) 0.0)) (not (or (= x 0.0) (= y 0.0))))) "
            + "(not (or (= x 0.0) (= y 0.0)))) (not (= z 0.0))) false)");
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*y", "0", "z = 0"), forged));
    }

    @Test
    void replaysNativeOrderAntisymmetryAndMultiUnitResolution() {
        // Captured from Z3 4.13.3.0: x = 0 implies 0 = x.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x44 (>= x 0.0)))
            (let (($x57 (or (not (<= x 0.0)) (not $x44))))
            (let (($x58 (not $x57)))
            (let ((@x62 (trans (rewrite (= (= x 0.0) (and (<= x 0.0) $x44))) (rewrite (= (and (<= x 0.0) $x44) $x58)) (= (= x 0.0) $x58))))
            (let (($x31 (= x 0.0)))
            (let ((@x29 (mp (asserted (= x (to_real 0))) (rewrite (= (= x (to_real 0)) $x31)) $x31)))
            (let ((@x63 (mp @x29 @x62 $x58)))
            (let ((@x72 (trans (monotonicity @x62 (= (not $x31) (not $x58))) (rewrite (= (not $x58) $x57)) (= (not $x31) $x57))))
            (let (($x45 (not $x31)))
            (let (($x41 (not (= 0.0 x))))
            (let (($x33 (not (= (to_real 0) x))))
            (let ((@x35 (asserted $x33)))
            (let ((@x42 (mp (mp @x35 (rewrite (= $x33 $x41)) $x41) (rewrite (= $x41 $x45)) $x45)))
            (unit-resolution (mp @x42 @x72 $x57) (not-or-elim @x63 (<= x 0.0)) (not-or-elim @x63 $x44) false))))))))))))))))
            """;
        var statement = statement("0", "x", "x = 0");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(>= x 0.0)", "(<= x 0.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(not-or-elim @x63 $x44)", "")));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(not-or-elim @x63 $x44)", "(not-or-elim @x63 (<= x 0.0))")));
    }

    @Test
    void replaysNativeOrderNormalizationWithoutReversingDirection() {
        // Captured from Z3 4.13.3.0: x = y implies x - y = 0.
        String proof = """
            ((set-logic ALL)
            (proof
            (let ((?x35 (+ x (* (- 1.0) y))))
            (let (($x50 (>= ?x35 0.0)))
            (let (($x58 (or (not (<= ?x35 0.0)) (not $x50))))
            (let (($x59 (not $x58)))
            (let ((@x61 (rewrite (= (and (<= ?x35 0.0) $x50) $x59))))
            (let ((@x55 (monotonicity (rewrite (= (<= x y) (<= ?x35 0.0))) (rewrite (= (>= x y) $x50)) (= (and (<= x y) (>= x y)) (and (<= ?x35 0.0) $x50)))))
            (let ((@x65 (trans (rewrite (= (= x y) (and (<= x y) (>= x y)))) (trans @x55 @x61 (= (and (<= x y) (>= x y)) $x59)) (= (= x y) $x59))))
            (let (($x25 (= x y)))
            (let ((@x26 (asserted $x25)))
            (let ((@x66 (mp @x26 @x65 $x59)))
            (let ((@x72 (trans (rewrite (= (= ?x35 0.0) (and (<= ?x35 0.0) $x50))) @x61 (= (= ?x35 0.0) $x59))))
            (let ((@x79 (trans (monotonicity @x72 (= (not (= ?x35 0.0)) (not $x59))) (rewrite (= (not $x59) $x58)) (= (not (= ?x35 0.0)) $x58))))
            (let (($x41 (= ?x35 0.0)))
            (let (($x39 (not $x41)))
            (let ((@x42 (mp (asserted (not (= (- x y) (to_real 0)))) (rewrite (= (not (= (- x y) (to_real 0))) $x39)) $x39)))
            (unit-resolution (mp @x42 @x79 $x58) (not-or-elim @x66 (<= ?x35 0.0)) (not-or-elim @x66 $x50) false))))))))))))))))))
            """;
        var statement = statement("x-y", "0", "x = y");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(rewrite (= (<= x y) (<= ?x35 0.0)))", "(rewrite (= (<= x y) (>= ?x35 0.0)))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x-y", "1", "x = y"),
            proof.replace("(- x y) (to_real 0)", "(- x y) (to_real 1)")));
    }

    @Test
    void rejectsConditionalRewriteEvenWhenExternalSolverReportsUnsat() throws IOException {
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y"), captured("premise-eliminated")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z"),
            contradiction("(rewrite (= (* x z) (* y z)))", "(= (* x z) (* y z))")));
    }

    @Test
    void rejectsPolynomialNonidentityAsUniversalDisequality() {
        // x and x+1 are not identical polynomials; x and 0 may still be equal.
        String proof = wrap("(mp (asserted (not (= x 0.0))) "
            + "(rewrite (= (not (= x 0.0)) false)) false)");
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "0"), proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "0"),
            wrap("(mp (asserted (not (= x 0.0))) (rewrite (= (not (= x 0.0)) true)) false)")));
    }

    @Test
    void requiresEveryChangedArgumentAndRejectsUnrelatedAntecedents() {
        String equality = "(= (* x u) (* y v))";
        String proof = contradiction("(monotonicity (asserted (= x y)) (asserted (= u v)) " + equality + ")", equality);
        var statement = statement("x*u", "y*v", "x = y", "u = v");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(asserted (= u v))", "")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y", "u = v"),
            contradiction("(monotonicity (asserted (= x y)) (asserted (= u v)) (= (* x z) (* y z)))", "(= (* x z) (* y z))")));
    }

    @Test
    void rejectsPremisePaddingOnAnUnchangedCongruenceArgument() {
        String equality = "(= (* x z) (* y z))";
        String reflexiveWithPremise = "(trans (asserted (= z u)) "
            + "(symm (asserted (= z u)) (= u z)) (= z z))";
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y", "z = u"),
            contradiction("(monotonicity (asserted (= x y)) " + reflexiveWithPremise + " " + equality + ")", equality)));

        // Native coercion normalization can leave an explicit, dependency-free reflexive antecedent.
        assertTrue(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y"),
            contradiction("(monotonicity (asserted (= x y)) (refl (= z z)) " + equality + ")", equality)));
    }

    @Test
    void rejectsExtraPremisesForAnAlreadyCoveredCongruenceArgument() {
        String equality = "(= (* x z) (* y z))";
        String redundant = "(trans (asserted (= x u)) (asserted (= u y)) (= x y))";
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y", "x = u", "u = y"),
            contradiction("(monotonicity (asserted (= x y)) " + redundant + " " + equality + ")", equality)));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "y*z", "x = y", "x = u", "u = y"),
            contradiction("(monotonicity " + redundant + " (asserted (= x y)) " + equality + ")", equality)));

        String repeated = "(= (* x x) (* y y))";
        assertTrue(Z3EqualityProofReplay.verifies(statement("x*x", "y*y", "x = y"),
            contradiction("(monotonicity (asserted (= x y)) " + repeated + ")", repeated)));
    }

    @Test
    void doesNotReplaceAnAssertedPremiseByAnEquivalentPolynomial() {
        String equality = "(= (* (+ x 0.0) z) (* y z))";
        String proof = contradiction("(monotonicity (asserted (= (+ x 0.0) y)) " + equality + ")", equality);
        var statement = statement("(x+0)*z", "y*z", "x + 0 = y");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement,
            proof.replace("(asserted (= (+ x 0.0) y))", "(asserted (= x y))")));
    }

    @Test
    void preservesParallelLetScopingAndTypedNamespaces() {
        String body = "(unit-resolution (monotonicity (asserted (= x y)) (= ?x1 ?x2)) (asserted (not (= ?x1 ?x2))) false)";
        String proof = wrap("(let ((?x1 (* x z)) (?x2 (* y z))) " + body + ")");
        var statement = statement("x*z", "y*z", "x = y");
        assertTrue(Z3EqualityProofReplay.verifies(statement, proof));
        for (String invalid : List.of(
                proof.replace("(?x2 (* y z))", "(?x2 ?x1)"),
                proof.replace("(?x2 (* y z))", "(?x1 (* y z))"),
                proof.replace("(?x1 (* x z))", "(?x1 ?x1)"),
                proof.replace("(?x1 (* x z))", "($x1 (* x z))"),
                proof.replace("(?x1 (* x z))", "(?x1 (= x y))"),
                proof.replace("(?x1 (* x z))", "(@x1 (* x z))"),
                proof.replace("(?x1 (* x z))", "(?x1 (* x undeclared))"),
                wrap("(let ((?x1 (* x z))) (let ((?x1 (* x z)) (?x2 (* y z))) " + body + "))"))) {
            assertFalse(Z3EqualityProofReplay.verifies(statement, invalid));
        }
    }

    @Test
    void checksReflexivityAndBoundedNaturalPowers() {
        assertTrue(Z3EqualityProofReplay.verifies(statement("x*z", "x*z"),
            contradiction("(refl (= (* x z) (* x z)))", "(= (* x z) (* x z))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x*z", "z*x"),
            contradiction("(refl (= (* x z) (* z x)))", "(= (* x z) (* z x))")));
        assertTrue(Z3EqualityProofReplay.verifies(statement("x^2", "x*x"),
            contradiction("(rewrite (= (^ x 2.0) (* x x)))", "(= (^ x 2.0) (* x x))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x^2", "x*x"),
            contradiction("(rewrite (= (^ x 3.0) (* x x)))", "(= (^ x 2.0) (* x x))")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x^21", "x^21"),
            contradiction("(refl (= (^ x 21.0) (^ x 21.0)))", "(= (^ x 21.0) (^ x 21.0))")));
    }

    @Test
    void rejectsZerothPowersUnderNativeZ3Semantics() {
        // Z3's native power has no unconditional x^0 = 1 rewrite at x = 0.
        assertFalse(Z3EqualityProofReplay.verifies(statement("x^0", "1"),
            contradiction("(rewrite (= (^ x 0.0) 1.0))", "(= (^ x 0.0) 1.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"),
            withUnusedTerm("(^ x 0.0)")));
        assertTrue(Z3EqualityProofReplay.verifies(statement("x^1", "x"),
            contradiction("(rewrite (= (^ x 1.0) x))", "(= (^ x 1.0) x)")));
    }

    @Test
    void boundsConstantCoefficientGrowthBeforeNormalization() {
        // Unused bindings exercise admission without asking the polynomial backend to expand huge integers.
        for (String scalar : List.of("2.0", "0.1")) {
            String moderate = nestedPowers(scalar, 2);
            assertTrue(Z3EqualityProofReplay.verifies(statement("x", "x"), withUnusedTerm(moderate)));
            assertTrue(Z3EqualityProofReplay.verifies(statement("x", "x"), withUnusedTerm("(* " + moderate + " x)")));
            assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"), withUnusedTerm(nestedPowers(scalar, 3))));
            assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"),
                withUnusedTerm("(^ (* " + moderate + " x) 10.0)")));
        }
    }

    @Test
    void rejectsNonterminatingOrSymbolicDivisionAndInvalidCoercions() throws IOException {
        String proof = captured("decimal-collection");
        var statement = statement("0.5*x+0.25*x", "0.75*x");
        for (String term : List.of("(/ 1.0 0.0)", "(/ 1.0 3.0)", "(/ x 2.0)",
                "(to_real 0.5)", "(to_real x)", "1e1000000000", "(+ 0.5)", "(^ x x)")) {
            assertFalse(Z3EqualityProofReplay.verifies(statement, proof.replace("(/ 1.0 2.0)", term)));
        }
    }

    @Test
    void boundsSharedTermExpansionBeforeEqualityOrPolynomialWork() {
        assertTrue(Z3EqualityProofReplay.verifies(statement("x", "x"), sharedConstants(5)));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"), sharedConstants(30)));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"), sharedConstants(5) + " ".repeat(131_072)));
        String tooManyNodes = "(let (" + java.util.stream.IntStream.range(0, 3000)
            .mapToObj(i -> "(?x" + i + " 1.0)").collect(java.util.stream.Collectors.joining(" ")) + ") " + reflexiveBody() + ")";
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"), wrap(tooManyNodes)));
    }

    @Test
    void boundsTheNumberOfIndependentPolynomialLeaves() {
        assertTrue(Z3EqualityProofReplay.verifies(statement("x", "x"), manyRewrites(3)));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "x"), manyRewrites(129)));
    }

    @Test
    void rejectsUninterpretedRulesAndMalformedBooleanSteps() throws IOException {
        var statement = statement("x*(y+z)", "x*y+x*z");
        String proof = captured("distributivity");
        for (String invalid : List.of(
                proof.replace("(rewrite $x32)", "((_ th-lemma arith) $x32)"),
                proof.replace("(mp (asserted $x33) @x50 false)", "(mp (asserted $x33) @x50 true)"),
                proof.replace("(monotonicity @x43", "(monotonicity (symm @x43 (= true $x32))"),
                proof.replace("(rewrite (= (not true) false))", "(rewrite (= (not true) true))"),
                proof + "()", proof.substring(0, proof.length() - 1))) {
            assertFalse(Z3EqualityProofReplay.verifies(statement, invalid));
        }
    }

    @Test
    void replaysNativeNonzeroConstantCancellation() {
        // Captured from Z3 4.13.3.0: 2*x = 0 implies x = 0.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x41 (= x 0.0)))
            (let (($x51 (not $x41)))
            (let ((@x50 (mp (asserted (not (= x (to_real 0)))) (rewrite (= (not (= x (to_real 0))) $x51)) $x51)))
            (let ((?x34 (* 2.0 x)))
            (let (($x38 (= ?x34 0.0)))
            (let ((?x28 (to_real 0)))
            (let ((?x26 (* (to_real 2) x)))
            (let (($x29 (= ?x26 ?x28)))
            (let ((@x36 (rewrite (= ?x28 0.0))))
            (let ((@x45 (trans (monotonicity (rewrite (= ?x26 ?x34)) @x36 (= $x29 $x38)) (rewrite (= $x38 $x41)) (= $x29 $x41))))
            (let ((@x46 (mp (asserted $x29) @x45 $x41)))
            (unit-resolution @x46 @x50 false))))))))))))))
            """;
        assertTrue(Z3EqualityProofReplay.verifies(statement("x", "0", "2*x = 0"), proof));
        String zeroFactor = proof.replace("2.0 x", "0.0 x").replace("(to_real 2)", "(to_real 0)");
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "0", "0*x = 0"), zeroFactor));
    }

    @Test
    void replaysNativeAffineEquationRescaling() {
        // Captured from Z3 4.13.3.0: 2*x = 2 implies x = 1.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x39 (= x 1.0)))
            (let (($x50 (not $x39)))
            (let ((@x52 (mp (asserted (not (= x (to_real 1)))) (rewrite (= (not (= x (to_real 1))) $x50)) $x50)))
            (let ((?x32 (* 2.0 x)))
            (let (($x34 (= ?x32 2.0)))
            (let ((?x25 (to_real 2)))
            (let ((?x26 (* ?x25 x)))
            (let (($x27 (= ?x26 ?x25)))
            (let ((@x31 (rewrite (= ?x25 2.0))))
            (let ((@x43 (trans (monotonicity (rewrite (= ?x26 ?x32)) @x31 (= $x27 $x34)) (rewrite (= $x34 $x39)) (= $x27 $x39))))
            (let ((@x44 (mp (asserted $x27) @x43 $x39)))
            (unit-resolution @x44 @x52 false))))))))))))))
            """;
        assertTrue(Z3EqualityProofReplay.verifies(statement("x", "1", "2*x = 2"), proof));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "3", "2*x = 2"),
            proof.replace("1.0", "3.0").replace("(to_real 1)", "(to_real 3)")));
    }

    @Test
    void proportionalityIsForZeroEquationsOnlyAndNeverCancelsVariableFactors() {
        String target = "(= (+ x y) 0.0)";
        String source = "(= (* 0.5 (+ x y)) 0.0)";
        assertTrue(Z3EqualityProofReplay.verifies(statement("x+y", "0", "0.5*(x+y) = 0"),
            equationRewrite(source, target)));
        String reverse = "(= 0.0 (* 3.0 (+ x y)))";
        assertTrue(Z3EqualityProofReplay.verifies(statement("x+y", "0", "0 = 3*(x+y)"),
            equationRewrite(reverse, target)));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "0", "x*y = 0"),
            equationRewrite("(= (* x y) 0.0)", "(= x 0.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("x", "0", "2*x+4 = 0"),
            equationRewrite("(= (+ (* 2.0 x) 4.0) 0.0)", "(= x 0.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(statement("2*x", "x"),
            contradiction("(rewrite (= (* 2.0 x) x))", "(= (* 2.0 x) x)")));
    }

    @Test
    void replaysNativeAffineNonStrictOrder() {
        // Genuine Z3 4.13.3.0 proof: 2*x <= 2 implies x <= 1.
        String proof = """
            ((set-logic ALL)
            (proof
            (let (($x39 (<= x 1.0)))
            (let (($x50 (not $x39)))
            (let ((@x52 (mp (asserted (not (<= x (to_real 1)))) (rewrite (= (not (<= x (to_real 1))) $x50)) $x50)))
            (let ((?x32 (* 2.0 x)))
            (let (($x34 (<= ?x32 2.0)))
            (let ((?x25 (to_real 2)))
            (let ((?x26 (* ?x25 x)))
            (let (($x27 (<= ?x26 ?x25)))
            (let ((@x31 (rewrite (= ?x25 2.0))))
            (let ((@x43 (trans (monotonicity (rewrite (= ?x26 ?x32)) @x31 (= $x27 $x34)) (rewrite (= $x34 $x39)) (= $x27 $x39))))
            (let ((@x44 (mp (asserted $x27) @x43 $x39)))
            (unit-resolution @x44 @x52 false))))))))))))))
            """;
        var valid = orderStatement("x", "1", "2*x = 2", SolverIr.Relation.LESS_OR_EQUAL);
        assertTrue(Z3EqualityProofReplay.verifies(valid, proof));
        var wrongGoal = orderStatement("x", "1", "2*x = 2", SolverIr.Relation.GREATER_OR_EQUAL);
        assertFalse(Z3EqualityProofReplay.verifies(wrongGoal, proof));
        assertFalse(Z3EqualityProofReplay.verifies(valid, proof.replace("2.0 x", "3.0 x")));
    }

    @Test
    void admitsOnlyPositiveOrderRescaling() {
        String target = "(<= x 0.0)";
        assertTrue(Z3EqualityProofReplay.verifies(
            orderStatement("x", "0", "2*x = 0", SolverIr.Relation.LESS_OR_EQUAL),
            equationRewrite("(<= (* 2.0 x) 0.0)", target)));
        assertFalse(Z3EqualityProofReplay.verifies(
            orderStatement("x", "0", "-2*x = 0", SolverIr.Relation.LESS_OR_EQUAL),
            equationRewrite("(<= (* (- 2.0) x) 0.0)", target)));
        assertFalse(Z3EqualityProofReplay.verifies(
            orderStatement("x", "0", "0*x = 0", SolverIr.Relation.LESS_OR_EQUAL),
            equationRewrite("(<= (* 0.0 x) 0.0)", target)));
        assertFalse(Z3EqualityProofReplay.verifies(
            orderStatement("x", "0", "x*y = 0", SolverIr.Relation.LESS_OR_EQUAL),
            equationRewrite("(<= (* x y) 0.0)", target)));
        assertFalse(Z3EqualityProofReplay.verifies(
            orderStatement("x", "0", "2*x = 0", SolverIr.Relation.GREATER_OR_EQUAL),
            equationRewrite("(<= (* 2.0 x) 0.0)", "(>= x 0.0)")));
    }

    @Test
    void normalizesGreaterOrEqualWithoutReversingTheOrder() {
        var valid = orderStatement("x", "0", "0 = 2*x", SolverIr.Relation.GREATER_OR_EQUAL);
        assertTrue(Z3EqualityProofReplay.verifies(valid,
            equationRewrite("(<= 0.0 (* 2.0 x))", "(>= x 0.0)")));
        assertFalse(Z3EqualityProofReplay.verifies(valid,
            equationRewrite("(<= 0.0 (* 2.0 x))", "(<= x 0.0)")));
    }

    @Test
    void equationAndOrderRescalingHaveSeparateCacheAuthority() {
        var values = new Z3ProofValues(java.util.Set.of("x", "y"));
        var rewrites = new Z3PolynomialProofRewrites(statement("x+y", "0"), values);
        assertTrue(rewrites.verifies(rewrite(values, "(= (= 0.0 x) (= x 0.0))")));
        assertFalse(rewrites.verifies(rewrite(values, "(= (<= x 0.0) (<= 0.0 x))")));
        assertTrue(rewrites.verifies(rewrite(values,
            "(= (<= (+ x y) 0.0) (<= (* 0.5 (+ x y)) 0.0))")));
        assertFalse(rewrites.verifies(rewrite(values,
            "(= (<= (+ x y) 0.0) (<= (* (- 0.5) (+ x y)) 0.0))")));
        assertFalse(rewrites.verifies(rewrite(values,
            "(= (<= (* x y) 0.0) (<= x 0.0))")));
        assertFalse(rewrites.verifies(rewrite(values,
            "(= (<= (* 0.0 x) 0.0) (<= x 0.0))")));
    }

    private static Z3ProofValues.Equality rewrite(Z3ProofValues values, String formula) {
        return (Z3ProofValues.Equality) values.formula(Z3ProofSyntax.parse(formula));
    }

    private static Obligation orderStatement(String left, String right, String premise,
                                              SolverIr.Relation goalRelation) {
        var template = statement(left, right, premise);
        var source = template.assumptions().getFirst();
        var predicate = new SolverIr.Predicate(source.id(), SolverIr.Relation.LESS_OR_EQUAL,
            source.left(), source.right());
        var goal = new SolverIr.Goal(goalRelation, template.goal().left(), template.goal().right());
        return Obligation.create(template.obligationId(), template.declarations(), template.theories(),
            List.of(predicate), goal, template.requestedEvidence(), template.provenance());
    }

    private static String equationRewrite(String source, String target) {
        return contradiction("(mp (asserted " + source + ") (rewrite (= " + source + " "
            + target + ")) " + target + ")", target);
    }

    private static String nestedPowers(String term, int depth) {
        for (int i = 0; i < depth; i++) term = "(^ " + term + " 20.0)";
        return term;
    }

    private static String withUnusedTerm(String term) {
        return wrap("(let ((?x1 " + term + ")) " + reflexiveBody() + ")");
    }

    private static String sharedConstants(int depth) {
        var body = new StringBuilder("(let ((?x0 1.0)) ");
        for (int i = 1; i <= depth; i++) body.append("(let ((?x").append(i).append(" (* ?x")
            .append(i - 1).append(" ?x").append(i - 1).append("))) ");
        body.append(reflexiveBody()).append(")".repeat(depth + 1));
        return wrap(body.toString());
    }

    private static String manyRewrites(int count) {
        var bindings = new StringBuilder("(let (");
        for (int i = 1; i <= count; i++) bindings.append("(@x").append(i)
            .append(" (rewrite (= (+ x ").append(i).append(".0) (+ ").append(i).append(".0 x)))) ");
        return wrap(bindings.append(") ").append(reflexiveBody()).append(")").toString());
    }

    private static String reflexiveBody() {
        return "(unit-resolution (refl (= x x)) (asserted (not (= x x))) false)";
    }

    private static String captured(String name) throws IOException {
        try (var input = Z3EqualityProofReplayTest.class.getResourceAsStream("/z3-proof-replay/" + name + "-4.8.12.txt")) {
            if (input == null) throw new IOException("missing native proof fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Obligation statement(String left, String right, String... assumptions) {
        return new SolverObligationFactory().equality("native-ring-replay", left, right,
            List.of(assumptions), RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance("test", "native-ring-replay", SolverIr.sha256("native-ring-replay")));
    }

    private static String contradiction(String proof, String equality) {
        return wrap("(unit-resolution " + proof + " (asserted (not " + equality + ")) false)");
    }

    private static String wrap(String proof) { return "((set-logic ALL) (proof " + proof + "))"; }
}
