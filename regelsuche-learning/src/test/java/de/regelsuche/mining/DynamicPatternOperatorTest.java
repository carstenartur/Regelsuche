package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicPatternOperatorTest {

    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();

    @Test
    void firesOnFactorizationPattern() {
        DynamicPatternOperator op = compileOrFail(
            "test-factorization",
            "A * B + A * C",
            "A * (B + C)"
        );

        List<Transformation> candidates = op.generateCandidates("u * x + u * y");

        assertEquals(1, candidates.size());
        Transformation t = candidates.getFirst();
        assertEquals(op.ruleId(), t.rule());
        assertNotNull(t.transformedExpression());
        assertFalse(t.transformedExpression().isBlank());
    }

    @Test
    void doesNotFireWhenPatternDoesNotMatch() {
        DynamicPatternOperator op = compileOrFail(
            "test-factorization",
            "A * B + A * C",
            "A * (B + C)"
        );

        List<Transformation> candidates = op.generateCandidates("u * x + v * y");

        assertTrue(candidates.isEmpty(), "Expected no match when factors differ");
    }

    @Test
    void suppressesCanonicalReorderingNoOp() {
        DynamicPatternOperator op = compileOrFail(
            "test-id-guard",
            "A + B",
            "B + A"
        );

        assertTrue(op.generateCandidates("a + b").isEmpty());
    }

    @Test
    void allowsCanonicalEquivalentStrictSimplification() {
        DynamicPatternOperator op = compileOrFail(
            "test-neutral-simplification",
            "(A + 0) * 1",
            "A"
        );

        List<Transformation> candidates = op.generateCandidates("(x + 0) * 1");

        assertEquals(1, candidates.size());
        assertEquals("x", candidates.getFirst().transformedExpression());
    }

    @Test
    void appliesRepeatedPlaceholderMacroAcrossAssociativeGrouping() {
        DynamicPatternOperator op = compileOrFail(
            "test-quartic-normalization",
            "A * A * A * A",
            "A^4"
        );

        List<Transformation> candidates = op.generateCandidates(
            "((x + 1) * (x + 1)) * ((x + 1) * (x + 1))");

        assertEquals(1, candidates.size());
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        assertEquals(
            canonicalizer.stableHash("(x + 1)^4"),
            canonicalizer.stableHash(candidates.getFirst().transformedExpression()));
        assertTrue(op.generateCandidates(
            "((x + 1) * (x + 1)) * ((x + 1) * (x + 2))").isEmpty());
    }

    @Test
    void ruleIdContainsDynamicPrefix() {
        DynamicPatternOperator op = compileOrFail(
            "hyp-001",
            "A * B + A * C",
            "A * (B + C)"
        );

        assertTrue(op.ruleId().startsWith(DynamicPatternOperator.RULE_ID_PREFIX),
            "Rule ID should start with the dynamic prefix");
        assertTrue(op.ruleId().contains("hyp"), "Rule ID should include hypothesis ID fragment");
    }

    @Test
    void provenanceHashIsStableAndNonBlank() {
        DynamicPatternOperator op1 = compileOrFail(
            "hyp-stable",
            "A + B",
            "B + A"
        );
        DynamicPatternOperator op2 = compileOrFail(
            "hyp-stable",
            "A + B",
            "B + A"
        );

        assertFalse(op1.provenanceHash().isBlank());
        assertEquals(op1.provenanceHash(), op2.provenanceHash(),
            "Same hypothesis compiled twice must yield the same provenance hash");
    }

    @Test
    void differentHypothesesHaveDifferentProvenanceHashes() {
        DynamicPatternOperator op1 = compileOrFail("h1", "A * B + A * C", "A * (B + C)");
        DynamicPatternOperator op2 = compileOrFail("h2", "A + B", "B + A");

        assertFalse(op1.provenanceHash().equals(op2.provenanceHash()),
            "Different hypotheses must have distinct provenance hashes");
    }

    @Test
    void returnsEmptyForBlankInput() {
        DynamicPatternOperator op = compileOrFail("test", "A * B + A * C", "A * (B + C)");

        assertTrue(op.generateCandidates("").isEmpty());
        assertTrue(op.generateCandidates("  ").isEmpty());
    }

    @Test
    void firesOnTelescopingPattern() {
        DynamicPatternOperator op = compileOrFail(
            "test-telescoping",
            "A / (B * (B + 1))",
            "A / B - A / (B + 1)"
        );

        List<Transformation> candidates = op.generateCandidates("2 / (n * (n + 1))");

        assertFalse(candidates.isEmpty(), "Expected pattern to fire on telescoping expression");
    }

    @Test
    void applicationKeyContainsRevision() {
        DynamicPatternOperator op = compileOrFail(
            "test-rev",
            "A * B + A * C",
            "A * (B + C)",
            "rev-007"
        );

        List<Transformation> candidates = op.generateCandidates("u * x + u * y");

        assertFalse(candidates.isEmpty());
        String applicationKey = candidates.getFirst().applicationKey();
        assertTrue(applicationKey.contains("rev-007"),
            "Application key should carry the hypothesis revision for edge provenance: " + applicationKey);
    }

    private DynamicPatternOperator compileOrFail(
        String hypothesisId,
        String leftPattern,
        String rightPattern
    ) {
        return compileOrFail(hypothesisId, leftPattern, rightPattern, "v1");
    }

    private DynamicPatternOperator compileOrFail(
        String hypothesisId,
        String leftPattern,
        String rightPattern,
        String revision
    ) {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile(hypothesisId, revision, leftPattern, rightPattern);
        assertTrue(result.isSuccess(), "Expected compilation to succeed but got: " + result.rejectionReason());
        return result.operator().orElseThrow();
    }
}
