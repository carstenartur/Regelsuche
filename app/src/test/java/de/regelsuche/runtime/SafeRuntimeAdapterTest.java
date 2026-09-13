package de.regelsuche.runtime;

import de.regelsuche.ast.Expr;
import de.regelsuche.evolution.*;
import de.regelsuche.json.JsonReader;
import de.regelsuche.rules.RationalRules;
import de.regelsuche.search.program.ProgrammedTransformationEngine;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeRuntimeAdapterTest {
    private static final String IMPLEMENTATION = "sha256:" + "a".repeat(64);

    @Test void directAndSafeUseIdenticalVisibleInventoryAndInitialAssumptions() {
        var rules = List.of(rule("ast_add_zero_right"), new OpaqueRule());
        var adapter = new SafeRuntimeAdapter(rules, List.of(new SafeRuntimeAdapter.DirectSource("backend", "trusted-backend/v1", source -> List.of())), IMPLEMENTATION);
        Map<String, Object> direct = evidence(adapter.analyze(request("DIRECT_V1", "x+0", List.of("b!=0", "b != 0"))));
        Map<String, Object> safe = evidence(adapter.analyze(request("SAFE_PREPARATION_V3", "x+0", List.of("b!=0"))));
        assertEquals(direct.get("inventory"), safe.get("inventory"));
        assertEquals(List.of("b != 0"), direct.get("retainedAssumptions"));
        assertEquals(candidates(direct), candidates(safe));
        assertEquals("x", candidates(direct).getFirst().get("expression"));
        assertTrue(direct.toString().contains("opaque"));
        assertTrue(safe.toString().contains("DIRECT_EXECUTOR_ONLY"));
    }

    @Test void nativeSafePreparationIsAblatedAndItsPrimitiveLineageReplays() {
        var adapter = new SafeRuntimeAdapter(List.of(rule("ast_square_difference_factor")), List.of(), IMPLEMENTATION);
        var direct = evidence(adapter.analyze(request("DIRECT_V1", "4*x^2-y^2", List.of())));
        String artifact = adapter.analyze(request("SAFE_PREPARATION_V3", "4*x^2-y^2", List.of()));
        var safe = evidence(artifact);
        assertTrue(candidates(direct).isEmpty());
        assertEquals("NO_MATCH", direct.get("status"));
        assertEquals("SUCCESS", safe.get("status"));
        var prepared = candidates(safe).getFirst();
        assertEquals(List.of("prepare_exact_monomial_square_structure", "ast_square_difference_factor"), prepared.get("primitiveRuleIds"));
        assertEquals(2, RecordedExecution.fromCanonicalJson((String) prepared.get("execution")).work().primitiveRewrites());
        assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));
        for (String corrupted : List.of(artifact.replace("SAFE_PREPARATION_V3", "DIRECT_V1"),
                artifact.replace("prepare_exact_monomial_square_structure", "invented-preparation"),
                artifact.replace(IMPLEMENTATION, "sha256:" + "b".repeat(64)),
                artifact.replace("\"maxDepth\":2", "\"maxDepth\":1"))) {
            assertThrows(IllegalArgumentException.class, () -> adapter.replay(new JsonReader(corrupted).readObject()));
        }
    }

    @Test void nestedGuardsKeepLocalBindingsUnderBothProfiles() {
        var adapter = new SafeRuntimeAdapter(List.of(new RationalRules.MultiplyFractionsRule()), List.of(), IMPLEMENTATION);
        for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
            var positive = evidence(adapter.analyze(request(profile, "1+(a/b)*(c/d)", List.of("b != 0", "d != 0"))));
            assertEquals("SUCCESS", positive.get("status"));
            assertEquals(List.of("b != 0", "d != 0"), candidates(positive).getFirst().get("retainedAssumptions"));
            assertTrue(positive.toString().contains("occurrencePath=$R"));
            var negative = evidence(adapter.analyze(request(profile, "1+(a/b)*(c/d)", List.of("b != 0"))));
            assertEquals("UNSUPPORTED", negative.get("status"));
            assertTrue(candidates(negative).isEmpty());
        }
    }

    @Test void guardedPreparationUsesOnlyExplicitSupportAndKeepsItDirectlyAvailable() {
        var adapter = new SafeRuntimeAdapter(List.of(new RationalRules.MultiplyFractionsRule(), rule("ast_add_zero_right")), List.of(), IMPLEMENTATION);
        var selected = request("SAFE_PREPARATION_V3", "((a/b)+0)*(c/d)", List.of("b != 0", "d != 0"));
        selected.put("preparationRuleIds", List.of("ast_add_zero_right"));
        var safe = evidence(adapter.analyze(selected));
        assertTrue(candidates(safe).stream().anyMatch(candidate -> candidate.get("primitiveRuleIds").equals(
            List.of("ast_add_zero_right", "rational_multiply_fractions"))), safe.toString());
        selected.put("profile", "DIRECT_V1");
        var direct = evidence(adapter.analyze(selected));
        assertEquals(safe.get("inventory"), direct.get("inventory"));
        assertTrue(candidates(direct).stream().anyMatch(candidate -> candidate.get("rule").equals("ast_add_zero_right")));
        assertTrue(candidates(direct).stream().noneMatch(candidate -> candidate.get("rule").equals("rational_multiply_fractions")));
        selected.put("profile", "SAFE_PREPARATION_V3");
        selected.put("assumptions", List.of("b != 0"));
        var negative = evidence(adapter.analyze(selected));
        assertTrue(candidates(negative).stream().noneMatch(candidate -> candidate.get("rule").equals("rational_multiply_fractions")));
        // V3 delegates preparation to the historical V2 root-pattern closure.
        // The adapter cannot authorize an additional nested context by itself.
        selected.put("assumptions", List.of("b != 0", "d != 0"));
        selected.put("source", "1+((a/b)+0)*(c/d)");
        var nestedPrepared = evidence(adapter.analyze(selected));
        assertTrue(candidates(nestedPrepared).stream().noneMatch(candidate -> candidate.get("rule").equals("rational_multiply_fractions")));
        assertTrue(nestedPrepared.toString().contains("SHARED_FROZEN_CLOSURE_EXHAUSTED"));
        selected.put("preparationRuleIds", List.of("invented"));
        assertThrows(IllegalArgumentException.class, () -> adapter.analyze(selected));
    }

    @Test void technicalNoMatchUnsupportedAndExhaustedWorkRemainDifferent() {
        var adapter = new SafeRuntimeAdapter(List.of(new OpaqueRule()), List.of(), IMPLEMENTATION);
        assertEquals("NO_MATCH", evidence(adapter.analyze(request("DIRECT_V1", "z", List.of()))).get("status"));
        assertEquals("UNSUPPORTED", evidence(adapter.analyze(request("SAFE_PREPARATION_V3", "z", List.of()))).get("status"));
        var failing = new SafeRuntimeAdapter(List.of(), List.of(new SafeRuntimeAdapter.DirectSource("throwing", "trusted/v1",
            source -> { throw new IllegalStateException("backend crashed"); })), IMPLEMENTATION);
        var failure = evidence(failing.analyze(request("SAFE_PREPARATION_V3", "z", List.of())));
        assertEquals("TECHNICAL_FAILURE", failure.get("status"));
        assertTrue(((Number) object(failure.get("work")).get("chargedUnits")).longValue() > 0);
        var exhausted = request("SAFE_PREPARATION_V3", "z", List.of());
        exhausted.put("maxWorkUnits", 0L);
        assertEquals("BUDGET_INCONCLUSIVE", evidence(adapter.analyze(exhausted)).get("status"));
        var answer = request("SAFE_PREPARATION_V3", "z", List.of());
        answer.put("target", "0");
        assertThrows(IllegalArgumentException.class, () -> adapter.analyze(answer));
    }

    @Test void unverifiedNegativePrincipalResultsRemainBudgetInconclusive() {
        var adapter = new SafeRuntimeAdapter(List.of(rule("ast_add_zero_right")), List.of(), IMPLEMENTATION);
        for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
            var request = request(profile, "x", List.of());
            request.put("maxWorkUnits", 6L);
            var result = evidence(adapter.analyze(request));
            assertEquals("BUDGET_INCONCLUSIVE", result.get("status"), result.toString());
            assertEquals("VERIFICATION_BUDGET_EXHAUSTED", object(result.get("authority")).get("verificationDetail"));
            assertEquals(0L, ((Number) object(result.get("work")).get("verificationUnits")).longValue());
            assertTrue(((Number) object(result.get("work")).get("refusedReservationUnits")).longValue() > 0);
            assertTrue(candidates(result).isEmpty());
        }
    }

    @Test void rawSourceNodesBoundTraversalBeforeCanonicalSimplification() {
        var rule = new CountingRule();
        var adapter = new SafeRuntimeAdapter(List.of(rule), List.of(), IMPLEMENTATION);
        // Canonicalization collapses this to x; the original engine visits all 41 nodes.
        String source = "x" + "+0".repeat(20);
        for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
            var request = request(profile, source, List.of());
            request.put("preparationBudget", Map.of("maxExpressionNodes", 1L));
            var capped = evidence(adapter.analyze(request));
            assertEquals("BUDGET_INCONCLUSIVE", capped.get("status"));
            assertTrue(capped.toString().contains("SOURCE_NODE_BUDGET_EXHAUSTED"));
            assertEquals(0, rule.matches);

            request.remove("preparationBudget");
            request.put("maxWorkUnits", 10L);
            var refused = evidence(adapter.analyze(request));
            assertEquals("BUDGET_INCONCLUSIVE", refused.get("status"));
            assertTrue(refused.toString().contains("DIRECT_EXECUTION_BUDGET_EXHAUSTED"));
            assertEquals(0, rule.matches);

            request.put("maxWorkUnits", 1_000L);
            var completed = evidence(adapter.analyze(request));
            assertEquals(profile.equals("DIRECT_V1") ? "NO_MATCH" : "UNSUPPORTED", completed.get("status"));
            assertEquals(82, rule.matches, "analysis and replay each visit the 41 original nodes");
            assertEquals(124L, ((Number) object(completed.get("work")).get("analysisUnits")).longValue());
            assertEquals(124L, ((Number) object(completed.get("work")).get("verificationUnits")).longValue());
            rule.matches = 0;
        }
    }

    @Test void principalReplayReservationCountsTheOriginalSourceNodes() {
        var adapter = new SafeRuntimeAdapter(List.of(rule("ast_add_zero_right")), List.of(), IMPLEMENTATION);
        for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
            var result = evidence(adapter.analyze(request(profile, "x+0", List.of())));
            assertEquals("SUCCESS", result.get("status"));
            assertEquals(10L, ((Number) object(result.get("authority")).get("independentReplayReservationUnits")).longValue());
        }
    }

    @Test void successorCannotRetainNestedPreparationWithoutCompleteWorkAndLineage() {
        var adapter = new SafeRuntimeAdapter(List.of(new RationalRules.MultiplyFractionsRule(), rule("ast_add_zero_right")), List.of(), IMPLEMENTATION);
        var request = request("SAFE_PREPARATION_V4", "1+((a/b)+0)*(c/d)", List.of("b != 0", "d != 0"));
        request.put("preparationRuleIds", List.of("ast_add_zero_right"));
        String artifact = adapter.analyze(request);
        var complete = evidence(artifact);
        var candidate = candidates(complete).stream().filter(value -> value.get("rule").equals("rational_multiply_fractions"))
            .findFirst().orElseThrow();
        var execution = RecordedExecution.fromCanonicalJson((String) candidate.get("execution"));
        assertEquals(2, execution.work().primitiveRewrites());
        assertEquals(List.of("ast_add_zero_right", "rational_multiply_fractions"), candidate.get("primitiveRuleIds"));
        var work = object(complete.get("work"));
        assertTrue(((Number) work.get("analysisUnits")).longValue() > 0);
        assertTrue(((Number) work.get("verificationUnits")).longValue() > 0);
        assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));

        request.put("maxPrimitiveRewrites", 1L);
        var pathBudget = evidence(adapter.analyze(request));
        assertEquals("BUDGET_INCONCLUSIVE", pathBudget.get("status"));
        assertTrue(candidates(pathBudget).stream().noneMatch(value -> value.get("rule").equals("rational_multiply_fractions")));
        request.remove("maxPrimitiveRewrites");
        request.put("maxWorkUnits", 8L);
        var exhausted = evidence(adapter.analyze(request));
        assertEquals("BUDGET_INCONCLUSIVE", exhausted.get("status"));
        assertTrue(candidates(exhausted).stream().noneMatch(value -> value.get("rule").equals("rational_multiply_fractions")));
        for (String tampered : List.of(artifact.replace("SAFE_PREPARATION_V4", "SAFE_PREPARATION_V3"),
                artifact.replace("\"chargedUnits\":" + work.get("chargedUnits"), "\"chargedUnits\":0"))) {
            assertThrows(IllegalArgumentException.class, () -> adapter.replay(new JsonReader(tampered).readObject()));
        }
    }

    @Test void originalProgramTopologyAndMeasuredInternalWorkSurviveTheBoundary() {
        var engine = new ProgrammedTransformationEngine(RewritePrograms.sequence("two-zero-removals",
            RewritePrograms.source("first", new AstRewriteTransformationEngine(List.of(rule("ast_add_zero_right")))),
            RewritePrograms.source("second", new AstRewriteTransformationEngine(List.of(rule("ast_add_zero_right"))))));
        var expected = engine.execute("x + 0 + 0");
        var adapter = new SafeRuntimeAdapter(List.of(), List.of(new SafeRuntimeAdapter.DirectSource("program", "approved-program/v1", engine)), IMPLEMENTATION);
        String artifact = adapter.analyze(request("DIRECT_V1", "x+0+0", List.of()));
        var result = evidence(artifact);
        assertFalse(candidates(result).isEmpty());
        assertTrue(artifact.contains("programNodeVisits"), "program mechanics must not disappear behind transform()");
        assertTrue(((Number) object(result.get("work")).get("analysisUnits")).longValue() >= expected.workMetrics().totalWorkUnitsV2());
        assertEquals(2, RecordedExecution.fromCanonicalJson((String) candidates(result).getFirst().get("execution")).work().primitiveRewrites());
        assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));
    }

    @Test void verifiedExactTheoryStaysWorkAwareAndCannotBeImportedAsAuthority() {
        var evidence = theoryEvidence();
        var adapter = new SafeRuntimeAdapter(List.of(), List.of(new SafeRuntimeAdapter.DirectSource("theory", evidence.evidenceHash(),
            new VerifiedFinitePolynomialTransformationEngine(evidence))), IMPLEMENTATION);
        String artifact = adapter.analyze(request("DIRECT_V1", "x*x", List.of()));
        var result = evidence(artifact);
        assertEquals("SUCCESS", result.get("status"), artifact);
        var execution = RecordedExecution.fromCanonicalJson((String) candidates(result).getFirst().get("execution"));
        assertEquals(1, execution.work().exactTheorySteps());
        assertTrue(execution.work().exactTheoryWorkUnits() > 0);
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(execution));
        assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));
        var exhausted = request("DIRECT_V1", "x*x", List.of()); exhausted.put("maxTheoryWorkUnits", 0L);
        assertEquals("BUDGET_INCONCLUSIVE", evidence(adapter.analyze(exhausted)).get("status"));
    }

    @Test void typedSystemKeepsRelationAndCompleteDownstreamReplay() {
        var adapter = new SafeRuntimeAdapter(List.of(), List.of(), IMPLEMENTATION);
        var request = request("SAFE_PREPARATION_V3", "", List.of("a != 0"));
        request.remove("source");
        request.put("representation", Map.of("schema", "regelsuche.matrix-preparation-request/v1",
            "equations", "2*(x+y)+3*(x-y)=5; (x+y)+4*(x-y)=6", "unknowns", List.of("x", "y")));
        String artifact = adapter.analyze(request);
        String typed = (String) object(evidence(artifact).get("authority")).get("typedArtifact");
        assertNotNull(typed, artifact);
        assertTrue(typed.contains("SOLUTION_SET_EQUIVALENCE"), typed);
        var solving = object(object(new JsonReader(typed).readObject().get("evidence")).get("solving"));
        assertEquals("UNIQUE", object(solving.get("rref")).get("classification"));
        assertEquals(List.of("9/10", "-1/2"), object(solving.get("rref")).get("particularSolution"));
        assertEquals(typed, de.regelsuche.math.algorithms.linalg.MatrixPreparationJson.replay(new JsonReader(typed).readObject()));
        assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));
        assertThrows(IllegalArgumentException.class, () -> adapter.replay(new JsonReader(artifact.replace("SOLUTION_SET_EQUIVALENCE", "EXACT_EXPRESSION_EQUALITY")).readObject()));
    }

    static Map<String, Object> request(String profile, String source, List<String> assumptions) {
        return new java.util.LinkedHashMap<>(Map.of("schema", "regelsuche.safe-runtime-request/v1", "profile", profile,
            "source", source, "assumptions", assumptions, "includeSymPy", false));
    }
    static Map<String, Object> evidence(String artifact) { return object(new JsonReader(artifact).readObject().get("evidence")); }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    static List<Map<String, Object>> candidates(Map<String, Object> evidence) {
        var result = new ArrayList<Map<String, Object>>();
        for (Object outcome : (List<?>) evidence.get("outcomes")) {
            if (object(outcome).containsKey("candidate")) result.add(object(object(outcome).get("candidate")));
        }
        return result;
    }
    static RewriteRule rule(String id) { return AstRewriteTransformationEngine.allBuiltInRules().stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow(); }
    private static class OpaqueRule implements RewriteRule {
        public String id() { return "opaque"; }
        public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        public boolean mayIncreaseComplexity() { return false; }
        public int estimatedCostDelta() { return -1; }
        public boolean isEquivalencePreservingByConstruction() { return true; }
        public boolean matches(Expr expression) { return false; }
        public Expr apply(Expr expression) { throw new AssertionError("not matched"); }
    }

    private static final class CountingRule extends OpaqueRule {
        int matches;
        @Override public boolean matches(Expr expression) { matches++; return false; }
    }

    private static ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence theoryEvidence() {
        String source = "x * x", ansatz = "(${unit}*x)^2";
        var domains = List.of(de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain.integerRange("unit", 1, 1));
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan("runtime-theory", source, ansatz, domains, 1, new SchematicProofPlan.Limits(8, 8, 4, 200_000));
        var run = resolver.resolve(plan, source, ansatz, domains, 1);
        var receipt = new ExactFinitePolynomialPlanReplayVerifier().verify(plan, source, ansatz, domains, 1, run);
        var bytes = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptRef = bytes.describeReceipt(receipt); var runRef = bytes.describePlanRun(run);
        var checkedReceipt = bytes.verifyReceipt(receiptRef, ignored -> new ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact(receiptRef.artifactId(), receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        var checkedRun = bytes.verifyPlanRun(runRef, ignored -> new ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact(runRef.artifactId(), run.toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        var confirmed = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier().verify(checkedReceipt), checkedRun,
            run, plan, source, ansatz, domains, 1);
        return new ExactFinitePolynomialPlanCandidateEvidenceVerifier().verify(confirmed, plan, run, run.candidates().getFirst().contentHash());
    }
}
