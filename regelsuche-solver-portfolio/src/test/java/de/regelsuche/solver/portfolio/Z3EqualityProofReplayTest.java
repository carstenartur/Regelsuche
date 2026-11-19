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
