package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DynamicOperatorCompilerTest {

    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();

    @Test
    void compilesValidFactorizationHypothesis() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("factorization-001", "v1", "A * B + A * C", "A * (B + C)");

        assertTrue(result.isSuccess());
        DynamicPatternOperator op = result.operator().orElseThrow();
        assertNotNull(op);
        assertEquals("factorization-001", op.hypothesisId());
        assertEquals("v1", op.hypothesisRevision());
        assertFalse(op.ruleId().isBlank());
        assertTrue(op.ruleId().startsWith(DynamicPatternOperator.RULE_ID_PREFIX));
        assertEquals("A * B + A * C", op.leftPatternText());
        assertEquals("A * (B + C)", op.rightPatternText());
    }

    @Test
    void rejectsBlankHypothesisId() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("", "v1", "A + B", "B + A");

        assertFalse(result.isSuccess());
        assertFalse(result.rejectionReason().isBlank());
    }

    @Test
    void rejectsBlankLeftPattern() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("h1", "v1", "", "B + A");

        assertFalse(result.isSuccess());
        assertTrue(result.rejectionReason().contains("left-pattern-blank"),
            "Unexpected rejection reason: " + result.rejectionReason());
    }

    @Test
    void rejectsBlankRightPattern() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("h1", "v1", "A + B", "");

        assertFalse(result.isSuccess());
        assertTrue(result.rejectionReason().contains("right-pattern-blank"),
            "Unexpected rejection reason: " + result.rejectionReason());
    }

    @Test
    void rejectsLeftPatternWithNoPlaceholders() {
        // Pattern with no uppercase single-letter placeholders
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("h1", "v1", "2 + 3", "5");

        assertFalse(result.isSuccess());
        assertTrue(result.rejectionReason().contains("no-placeholders"),
            "Unexpected rejection reason: " + result.rejectionReason());
    }

    @Test
    void rejectsTrivialRewrite() {
        // Left pattern and right pattern are canonically identical
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("h1", "v1", "A + B", "A + B");

        assertFalse(result.isSuccess());
        assertTrue(result.rejectionReason().contains("trivial-rewrite"),
            "Unexpected rejection reason: " + result.rejectionReason());
    }

    @Test
    void rejectsUnboundRightPlaceholder() {
        // C appears in right pattern but not in left
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("h1", "v1", "A + B", "A + B + C");

        assertFalse(result.isSuccess());
        assertTrue(result.rejectionReason().contains("right-placeholder-unbound"),
            "Unexpected rejection reason: " + result.rejectionReason());
    }

    @Test
    void provenanceHashIsDeterministic() {
        DynamicOperatorCompiler.CompilationResult r1 =
            compiler.compile("hyp-prov", "rev1", "A * B + A * C", "A * (B + C)");
        DynamicOperatorCompiler.CompilationResult r2 =
            compiler.compile("hyp-prov", "rev1", "A * B + A * C", "A * (B + C)");

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertEquals(r1.operator().orElseThrow().provenanceHash(),
            r2.operator().orElseThrow().provenanceHash(),
            "Provenance hash must be deterministic");
    }

    @Test
    void provenanceHashDiffersForDifferentPatterns() {
        DynamicOperatorCompiler.CompilationResult r1 =
            compiler.compile("same-id", "v1", "A * B + A * C", "A * (B + C)");
        DynamicOperatorCompiler.CompilationResult r2 =
            compiler.compile("same-id", "v1", "A + B", "B + A");

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertFalse(r1.operator().orElseThrow().provenanceHash()
            .equals(r2.operator().orElseThrow().provenanceHash()),
            "Different patterns must yield different provenance hashes");
    }

    @Test
    void compiledOperatorCanGenerateCandidates() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("test-exec", "v1", "A * B + A * C", "A * (B + C)");

        assertTrue(result.isSuccess());
        DynamicPatternOperator op = result.operator().orElseThrow();

        var candidates = op.generateCandidates("u * a + u * b");
        assertFalse(candidates.isEmpty(), "Compiled operator must fire on matching expressions");
        assertEquals(op.ruleId(), candidates.getFirst().rule());
    }

    @Test
    void nullRevisionIsAccepted() {
        DynamicOperatorCompiler.CompilationResult result =
            compiler.compile("hyp-null-rev", null, "A + B", "B + A");

        // Null revision should be treated as empty, not cause a failure
        assertTrue(result.isSuccess(), "Null revision should be accepted: " + result.rejectionReason());
    }
}
