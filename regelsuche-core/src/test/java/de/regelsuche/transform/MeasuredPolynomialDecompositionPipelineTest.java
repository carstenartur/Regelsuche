package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.transform.MeasuredPolynomialDecompositionPipeline.Result;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class MeasuredPolynomialDecompositionPipelineTest {
    private final PolynomialDecompositionSynthesisOperator operator =
        new PolynomialDecompositionSynthesisOperator();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void issuesPrimitiveEvidenceFromTheExecutedSourceRequestCandidateRenderingAndReplacement() {
        ExactParsedTerm parsed = parser.parseExactTerm("x^4 + 4");
        Result result = run(parsed, position(parsed, List.of()), new Authority(1_000_000, ""));
        var steps = result.primitiveExpansion();
        assertEquals(6, steps.size());
        assertEquals(result.sourceEvidenceHash().orElseThrow(),
            steps.getFirst().evidenceHash());
        assertEquals(result.certificateHash(),
            steps.getLast().evidenceHash());
        assertEquals(result.report().orElseThrow().candidates().getFirst().verificationCertificateHash(),
            steps.get(2).evidenceHash());
        assertEquals(List.of("EXACT_SOURCE_EVIDENCE", "EXACT_INTEGER_FACTORIZATION_REQUEST",
            "VERIFIER_SELECTED_CANDIDATE", "EXACT_FACTOR_RENDERING", "EXACT_REPARSE",
            "SPECIALIZED_OCCURRENCE_REPLACEMENT"),
            steps.stream().map(MeasuredPolynomialDecompositionPipeline.PrimitiveStep::stageId).toList());
        Result refused = run(parsed, position(parsed, List.of()), new Authority(1_000_000, "nested.rewritten-"));
        assertTrue(refused.primitiveExpansion().isEmpty());
    }

    @Test
    void measuresTheOriginalIntegerEngineAndCandidateUnderOneCumulativeAuthority() {
        ExactParsedTerm parsed = parser.parseExactTerm("x^4 + 4");
        Authority authority = new Authority(1_000_000, "");
        authority.consume("projection.preparation", 7);
        Result result = run(parsed, position(parsed, List.of()), authority);

        assertEquals("GENERATED", result.status().toString());
        var report = result.report().orElseThrow();
        FactorizationRequest<BigInteger> request = result.request().orElseThrow();
        assertEquals(BinaryQuarticFactorizationEngine.ENGINE_ID, report.engineId());
        assertEquals(BigInteger.ONE, request.source().coefficient(4, 0));
        assertEquals(BigInteger.valueOf(4), request.source().coefficient(0, 4));
        assertTrue(request.source().isHomogeneousOfDegree(4));
        assertEquals(OptionalInt.of(0), result.selectedCandidateIndex());
        var historical = operator.factorExpression(parsed.source()).candidates().getFirst();
        assertEquals(Optional.of(historical.transformedExpression()), result.transformedExpression());
        assertEquals(Optional.of(historical.applicationKey()), result.applicationKey());
        assertEquals(result.rawWork().totalWorkUnits() + 7, authority.ledger().totalWorkUnits());
        assertEquals(0, result.rawWork().units("projection.preparation"));
        assertEquals(3, result.rawWork().units("engine.dispatch"));
        report.work().stages().forEach((stage, units) -> assertEquals(units, result.rawWork().units(stage)));
        assertTrue(result.rawWork().units("projection.subtree-node-visits") > 0);
        assertTrue(result.rawWork().units("exact-parsed-view.ast-visits") > 0);
        assertTrue(result.rawWork().stages().keySet().stream().anyMatch(stage -> stage.startsWith("render.")));
        assertTrue(result.rawWork().units("transform.exact-reparse-source-code-units") > 0);
        assertTrue(result.certificateHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void retainsFiniteNegativeOutcomesWithoutInventingRequestsOrCandidates() {
        for (var fixture : Map.of(
                "x^4 + 1/y", "UNSUPPORTED_SEMANTIC_VIEW",
                "x^4 + x^2*y^2 + 2*y^4", "NO_FACTORIZATION_FOUND",
                "x^4 + 4*y^4 + 1", "UNSUPPORTED_FACTORIZATION_REQUEST",
                "x - x", "NO_FACTORIZATION_FOUND").entrySet()) {
            ExactParsedTerm parsed = parser.parseExactTerm(fixture.getKey());
            Authority authority = new Authority(1_000_000, "");
            Result result = run(parsed, position(parsed, List.of()), authority);
            assertEquals(fixture.getValue(), result.status().toString(), fixture.getKey());
            assertEquals(Optional.empty(), result.transformedExpression());
            assertEquals(Optional.empty(), result.rewrittenRootSource());
            assertTrue(result.primitiveExpansion().isEmpty());
            assertEquals(authority.ledger(), result.rawWork());
            assertEquals(result.request().isPresent(), result.report().isPresent());
            if (fixture.getKey().equals("x^4 + 1/y") || fixture.getKey().equals("x - x")) {
                assertTrue(result.request().isEmpty());
            } else {
                assertTrue(result.report().isPresent());
            }
        }
    }

    @Test
    void replacesOnlyTheSelectedOccurrenceAndPreservesExactUntouchedSource() {
        String source = "  9007199254740993 + (x^4 + 4)  ";
        ExactParsedTerm parsed = parser.parseExactTerm(source);
        Result result = run(parsed, position(parsed, List.of(1)), new Authority(1_000_000, ""));

        assertEquals("GENERATED", result.status().toString());
        String transformed = result.transformedExpression().orElseThrow();
        assertEquals(Optional.of("  9007199254740993 + (" + transformed + ")  "),
            result.rewrittenRootSource());
        assertEquals(Optional.of("(x^4 + 4)"), result.sourceOccurrenceExpression());
        assertEquals(List.of(1), result.path());
        var replacement = result.replacement().orElseThrow();
        assertEquals(1, replacement.copiedAncestors());
        assertSame(((BinaryExpr) parsed.expression()).left(),
            ((BinaryExpr) replacement.rewrittenRoot().orElseThrow()).left());
        assertTrue(result.sourceEvidenceHash().orElseThrow()
            .matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void keepsDistinctExactAtomLiteralsWithoutRounding() {
        ExactParsedTerm parsed = parser.parseExactTerm(
            "(x + 9007199254740993)^4 + 4*(x + 9007199254740992)^4");
        Result result = run(parsed, position(parsed, List.of()), new Authority(1_000_000, ""));

        assertEquals("GENERATED", result.status().toString());
        assertEquals(2, result.request().orElseThrow().source().ring().variableCount());
        String transformed = result.transformedExpression().orElseThrow();
        assertTrue(transformed.contains("9007199254740993"));
        assertTrue(transformed.contains("9007199254740992"));
    }

    @Test
    void refusesBeforeSemanticWorkAndAfterVerificationWithoutResettingOrPublishingPartialOutput() {
        ExactParsedTerm parsed = parser.parseExactTerm("x^4 + 4");
        for (String deniedPrefix : List.of("projection.", "exact-parsed-view.",
                "exact-parsed-view.atom-format-code-units", "exact-parsed-view.homogenization-terms",
                "engine.dispatch", "render.", "render.code-units",
                "transform.exact-reparse-", "nested.replacement-", "nested.rewritten-")) {
            Authority authority = new Authority(1_000_000, deniedPrefix);
            Result result = run(parsed, position(parsed, List.of()), authority);
            assertEquals("BUDGET_INCONCLUSIVE", result.status().toString(), deniedPrefix);
            assertEquals(authority.ledger(), result.rawWork(), deniedPrefix);
            assertEquals(Optional.empty(), result.transformedExpression());
            assertEquals(Optional.empty(), result.rewrittenRootSource());
            assertFalse(result.rawWork().stages().keySet().stream().anyMatch(stage -> stage.startsWith(deniedPrefix)));
            if (deniedPrefix.startsWith("render") || deniedPrefix.startsWith("transform")
                    || deniedPrefix.startsWith("nested")) {
                assertTrue(result.report().orElseThrow().successful());
                assertTrue(result.rawWork().units("verify.product-comparisons") > 0);
            }
        }
    }

    @Test
    void theRemainingOpaqueAllowanceBoundsTheActualIntegerRequest() {
        ExactParsedTerm parsed = parser.parseExactTerm("x^4 + 4");
        Result beforeDispatch = run(parsed, position(parsed, List.of()), new Authority(1_000_000, "engine.dispatch"));
        Authority bounded = new Authority(beforeDispatch.rawWork().totalWorkUnits() + 4, "");
        Result result = run(parsed, position(parsed, List.of()), bounded);

        assertEquals("BUDGET_INCONCLUSIVE", result.status().name());
        assertEquals(1, result.request().orElseThrow().maxWorkUnits());
        assertEquals(3, result.rawWork().units("engine.dispatch"));
        assertTrue(result.report().orElseThrow().work().within(1));
        assertEquals(bounded.ledger(), result.rawWork());
        assertTrue(result.transformedExpression().isEmpty());
    }

    @Test
    void measuredStructuralAtomsKeepTheEstablishedExactFormattingRules() {
        for (String source : List.of("sin(t)^4 + 4*(x + 1)^4", "(x - (y - z))^4 + 4",
                "(x/(y+1))^4 + 4", "(-x)^4 + 4", "f(0.10, (a^b)^c, a^(b^c))^4 + 4")) {
            ExactParsedTerm parsed = parser.parseExactTerm(source);
            Result result = run(parsed, position(parsed, List.of()), new Authority(1_000_000, ""));
            var historical = operator.factorExpression(source);
            assertTrue(historical.generated(), historical.detailCode());
            assertTrue(result.generated(), result.detailCode());
            assertEquals(historical.candidates().getFirst().transformedExpression(),
                result.transformedExpression().orElseThrow(), source);
            assertEquals(historical.candidates().getFirst().applicationKey(), result.applicationKey().orElseThrow(), source);
        }
    }

    @Test
    void aSpentAuthorityCannotStartAnotherRequest() {
        ExactParsedTerm parsed = parser.parseExactTerm("x^4 + 4");
        Authority probe = new Authority(1_000_000, "");
        Result completed = run(parsed, position(parsed, List.of()), probe);
        Authority authority = new Authority(completed.rawWork().totalWorkUnits(), "");
        // Spend the finite authority in real admitted work before invoking the pipeline.
        authority.consume("projection.previous-occurrence", completed.rawWork().totalWorkUnits());
        Result result = run(parsed, position(parsed, List.of()), authority);
        assertEquals("BUDGET_INCONCLUSIVE", result.status().toString());
        assertEquals(PolynomialWorkLedger.empty(), result.rawWork());
        assertTrue(result.request().isEmpty());
        assertEquals(0, authority.remainingOpaqueWorkUnits());
    }

    @Test
    void replayIsDeterministicAndBindsOccurrenceAndExactRootSource() {
        ExactParsedTerm parsed = parser.parseExactTerm("(x^4 + 4) + (x^4 + 4)");
        Result left = run(parsed, position(parsed, List.of(0)), new Authority(1_000_000, ""));
        Result replay = run(parser.parseExactTerm(parsed.source()), position(parsed, List.of(0)),
            new Authority(1_000_000, ""));
        Result right = run(parsed, position(parsed, List.of(1)), new Authority(1_000_000, ""));
        assertEquals(left.canonicalMaterial(), replay.canonicalMaterial());
        assertEquals(left.rawWork(), replay.rawWork());
        assertFalse(left.certificateHash().equals(right.certificateHash()));
        assertFalse(left.rewrittenRootSource().equals(right.rewrittenRootSource()));

        ExactParsedTerm changedRoot = parser.parseExactTerm("(x^4 + 4) + (x^4 + 5)");
        Result changed = run(changedRoot, position(changedRoot, List.of(0)), new Authority(1_000_000, ""));
        assertEquals(left.transformedExpression(), changed.transformedExpression());
        assertFalse(left.rootSourceHash().equals(changed.rootSourceHash()));
        assertFalse(left.certificateHash().equals(changed.certificateHash()));

        Result stale = run(parsed, new TreePosition(List.of(0), "x ^ 4 + 5"), new Authority(1_000_000, ""));
        assertEquals("POSITION_STALE", stale.status().toString());
        assertTrue(stale.request().isEmpty());
        Result missing = run(parsed, new TreePosition(List.of(0, 0, 0, 0), "x"),
            new Authority(1_000_000, ""));
        assertEquals("POSITION_NOT_PRESENT", missing.status().toString());
        assertTrue(missing.request().isEmpty());
    }

    private static TreePosition position(ExactParsedTerm parsed, List<Integer> path) {
        var selected = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
        return new TreePosition(path, ExpressionFormatter.format(selected));
    }

    private Result run(ExactParsedTerm root, TreePosition position, PolynomialWorkAuthority authority) {
        return operator.factorExpression(root, position, authority);
    }

    private static final class Authority implements PolynomialWorkAuthority {
        private final long maximum;
        private final String deniedPrefix;
        private final Map<String, Long> stages = new LinkedHashMap<>();

        private Authority(long maximum, String deniedPrefix) {
            this.maximum = maximum;
            this.deniedPrefix = deniedPrefix;
        }

        @Override
        public void consume(PolynomialWorkLedger work) {
            if (work.totalWorkUnits() > remainingOpaqueWorkUnits()
                    || !deniedPrefix.isEmpty() && work.stages().keySet().stream()
                        .anyMatch(stage -> stage.startsWith(deniedPrefix))) {
                throw new PolynomialWorkAuthority.LimitReached();
            }
            work.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }

        @Override
        public long remainingOpaqueWorkUnits() { return maximum - ledger().totalWorkUnits(); }

        @Override
        public PolynomialWorkLedger opaqueInvocationOverhead() {
            return new PolynomialWorkLedger(Map.of("engine.dispatch", 3L));
        }

        PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }
}
