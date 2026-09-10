package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedPreparationGuardFactsTest {
    @Test
    void reusesGuardFactsAcrossDifferentPrincipalIdentitiesOnlyWhenSemanticsMatch() {
        PatternExpr pattern = PatternExpr.var("A");
        PatternMatchAnalyzer.Analysis analysis = new PatternMatchAnalyzer().analyze(
            pattern,
            new ExpressionParser().parseTerm("x"),
            RecognitionProfile.exact());
        RewriteApplicabilitySchema first = schema(
            "guarded_first",
            "guarded-first/v1",
            pattern,
            RequiredAssumptionTemplate.nonZero(PatternExpr.var("A")));
        RewriteApplicabilitySchema second = schema(
            "guarded_second",
            "guarded-second/v1",
            pattern,
            RequiredAssumptionTemplate.nonZero(PatternExpr.var("A")));
        AssumptionSignature available =
            AssumptionSignature.ofExpressions(List.of("x != 0"));
        SharedPreparationGuardFacts cache = new SharedPreparationGuardFacts();

        SharedPreparationGuardFacts.Fact firstFact =
            cache.evaluate(first, analysis, available);
        SharedPreparationGuardFacts.Fact secondFact =
            cache.evaluate(second, analysis, available);
        SharedPreparationGuardFacts.Fact snapshotFact =
            cache.evaluateSnapshot(
                second,
                PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
                available);

        assertSame(firstFact, secondFact);
        assertSame(firstFact, snapshotFact);
        assertTrue(firstFact.satisfied());
        assertEquals(List.of("x != 0"), firstFact.requiredAssumptions());
        assertEquals(3, cache.work().requests());
        assertEquals(1, cache.work().uniqueFacts());
        assertEquals(2, cache.work().cacheHits());

        SharedPreparationGuardFacts.Fact missing = cache.evaluate(
            second,
            analysis,
            AssumptionSignature.ofExpressions(List.of()));
        assertNotSame(firstFact, missing);
        assertEquals(SharedPreparationGuardFacts.Status.UNKNOWN,
            missing.status());
        assertEquals(2, cache.work().uniqueFacts());
    }

    @Test
    void doesNotShareDifferentRequiredAssumptionKinds() {
        PatternExpr pattern = PatternExpr.var("A");
        PatternMatchAnalyzer.Analysis analysis = new PatternMatchAnalyzer().analyze(
            pattern,
            new ExpressionParser().parseTerm("x"),
            RecognitionProfile.exact());
        RewriteApplicabilitySchema nonZero = schema(
            "non_zero_principal",
            "non-zero/v1",
            pattern,
            RequiredAssumptionTemplate.nonZero(PatternExpr.var("A")));
        RewriteApplicabilitySchema positive = schema(
            "positive_principal",
            "positive/v1",
            pattern,
            RequiredAssumptionTemplate.positive(PatternExpr.var("A")));
        SharedPreparationGuardFacts cache = new SharedPreparationGuardFacts();

        SharedPreparationGuardFacts.Fact nonZeroFact = cache.evaluate(
            nonZero,
            analysis,
            AssumptionSignature.ofExpressions(List.of("x != 0", "x > 0")));
        SharedPreparationGuardFacts.Fact positiveFact = cache.evaluate(
            positive,
            analysis,
            AssumptionSignature.ofExpressions(List.of("x != 0", "x > 0")));

        assertTrue(nonZeroFact.satisfied());
        assertTrue(positiveFact.satisfied());
        assertNotSame(nonZeroFact, positiveFact);
        assertEquals(2, cache.work().uniqueFacts());
        assertEquals(0, cache.work().cacheHits());
    }

    private static RewriteApplicabilitySchema schema(
        String ruleId,
        String schemaId,
        PatternExpr pattern,
        RequiredAssumptionTemplate requirement
    ) {
        PatternRewriteRule executor = new PatternRewriteRule(
            ruleId,
            pattern,
            pattern);
        return new RewriteApplicabilitySchema(
            schemaId,
            executor,
            pattern,
            RecognitionProfile.exact(),
            List.of(requirement));
    }
}
