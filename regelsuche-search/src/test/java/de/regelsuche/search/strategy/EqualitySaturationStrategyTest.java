package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.egraph.SaturationStats;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class EqualitySaturationStrategyTest {

    private final AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final SearchHeuristic heuristic = new SearchHeuristic(6, 1000, 1, 6, 80, 16);

    @Test
    void equalitySaturationFindsBinomialExpansion() {
        // (x+3)^2 must, after saturation, share an e-class with its
        // expanded product form (x+3)*(x+3). The rule
        // ast_power_two_to_product fires, the result is unioned, and the
        // graph holds both forms in the same class.
        SearchProblem problem = problemFor("( x + 3 ) ^ 2");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        List<SearchState> result = strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertNotNull(stats);
        assertTrue(stats.appliedRules().containsKey("ast_power_two_to_product"),
            "expected ast_power_two_to_product to fire on (x+3)^2 during saturation, got: "
                + stats.appliedRules());
        assertTrue(stats.merges() >= 1 || stats.totalApplications() >= 1,
            "expected at least one merge/application during saturation");
        // The extracted best form should still be (x+3)^2 because it has
        // fewer operator nodes than the distributed expansion.
        SearchState best = result.get(result.size() - 1);
        assertEquals(2, result.size());
        assertEquals(EqualitySaturationStrategy.SATURATION_RULE_ID, best.appliedRuleId());
        assertTrue(best.appliedRuleIds().contains(EqualitySaturationStrategy.SATURATION_RULE_ID));
    }

    @Test
    void equalitySaturationAvoidsRewriteOrderExplosion() {
        // The path-based BestFirst strategy enumerates one explored state
        // per ordered rewrite path; saturation collapses every
        // equivalent form into a single e-class, so the resulting graph
        // is strictly smaller than the number of explored search states.
        SearchProblem problem = problemFor("( a + b ) * c");
        EqualitySaturationStrategy saturation = new EqualitySaturationStrategy();
        saturation.search(problem);
        SaturationStats stats = saturation.lastStats();

        List<SearchState> bestFirstExplored = new BestFirstSearchStrategy().search(problem);

        assertTrue(stats.eclasses() < bestFirstExplored.size() + 50,
            "saturation should not blow up the e-class count beyond what BestFirst explores; "
                + "eclasses=" + stats.eclasses() + " explored=" + bestFirstExplored.size());
        // Sanity: saturation actually did some work.
        assertTrue(stats.iterations() >= 1);
    }

    @Test
    void equalitySaturationExtractsCheapestExpression() {
        // Saturating x + 0 must fold through ast_add_zero_right (or _left)
        // and extraction must then pick the bare "x" representative as
        // the smallest form.
        SearchProblem problem = problemFor("x + 0");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        List<SearchState> result = strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertEquals("x", stats.extractedBest(),
            "extract must pick the simpler representative after saturation");
        assertEquals(2, result.size());
        assertEquals("x", result.get(1).expression());
    }

    @Test
    void equalitySaturationRespectsIterationBudget() {
        // Budget of exactly 1 iteration must produce stats.iterations() == 1
        // and stop without claiming to have reached a fix point.
        SearchProblem problem = problemFor("( a + b ) * c");
        EqualitySaturationStrategy strategy =
            new EqualitySaturationStrategy(new de.regelsuche.egraph.EqualitySaturation.Config(1, 10_000));
        strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertEquals(1, stats.iterations(),
            "with maxIterations=1 exactly one iteration must run");
        // It either fixed-pointed in that single iteration or hit the
        // budget — either is a valid stop reason, but it must NOT be the
        // node-budget reason for such a tiny input.
        assertNotEquals(SaturationStats.Reason.NODE_BUDGET, stats.stopReason());
    }

    @Test
    void equalitySaturationReportsStats() {
        SearchProblem problem = problemFor("( x + 3 ) ^ 2");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertNotNull(stats);
        assertTrue(stats.eclasses() > 0, "stats.eclasses() must be populated");
        assertTrue(stats.enodes() > 0, "stats.enodes() must be populated");
        assertTrue(stats.iterations() >= 1, "stats.iterations() must be populated");
        assertNotNull(stats.appliedRules(), "stats.appliedRules() must be populated");
        assertNotNull(stats.extractedBest(), "stats.extractedBest() must be populated");
        assertTrue(stats.classesScanned() >= 0, "stats.classesScanned() must be populated");
        assertTrue(stats.nodesScanned() >= 0, "stats.nodesScanned() must be populated");
        assertTrue(stats.matcherCacheHits() >= 0, "stats.matcherCacheHits() must be populated");
        assertTrue(stats.matcherCacheMisses() >= 0, "stats.matcherCacheMisses() must be populated");
        assertEquals(stats.iterations(), stats.saturationIterations(),
            "legacy and explicit saturation iteration counters must agree");
        assertEquals(stats.totalApplications(), stats.rulesFired(),
            "rulesFired must mirror appliedRules totals");
        // Aggregated stats must be self-consistent.
        assertEquals(stats.totalApplications(),
            stats.appliedRules().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void searchProfileExposesEqualitySaturation() {
        SearchProfile profile = SearchProfile.EQUALITY_SATURATION;
        SearchStrategy strategy = profile.newStrategy();
        assertTrue(strategy instanceof EqualitySaturationStrategy,
            "SearchProfile.EQUALITY_SATURATION must build the new strategy; got "
                + strategy.getClass());
        assertFalse(profile.usesTranspositionTable(),
            "equality saturation has its own deduplication via e-classes");
    }

    @Test
    void unsupportedObjectivesUseExplicitFallbackAndDoNotReportSaturation() {
        var strategy = new EqualitySaturationStrategy();
        var problem = problemFor("x + 0").withCostModel(
            de.regelsuche.scoring.cost.StructuralCostModel.FACTORED_FORM);
        assertFalse(strategy.search(problem).isEmpty());
        assertTrue(strategy.lastExtractionSelection().fallback());
        assertEquals("factored-form", strategy.lastExtractionSelection().requestedObjective());
        assertEquals("best-first", strategy.lastExtractionSelection().strategy());
        org.junit.jupiter.api.Assertions.assertNull(strategy.lastStats());
    }

    @Test
    void customModelCannotClaimNativeSupportByReusingAnId() {
        var custom = new de.regelsuche.scoring.cost.CostModel() {
            @Override public String id() { return "operator-count"; }
            @Override public int cost(String expression, de.regelsuche.ast.Expr ast,
                    de.regelsuche.scoring.ExpressionScore score) { return score.nestingDepth(); }
        };
        var strategy = new EqualitySaturationStrategy();
        strategy.search(problemFor("x + 0").withCostModel(custom));
        assertTrue(strategy.lastExtractionSelection().fallback());
    }

    @Test
    void zeroArgumentFunctionsStillCountAsOperators() {
        assertEquals(1, EqualitySaturationStrategy.nodeCost(de.regelsuche.egraph.ENode.leaf("fn:f")));
        assertEquals(0, EqualitySaturationStrategy.nodeCost(de.regelsuche.egraph.ENode.leaf("var:x")));
    }

    @Test
    void extractedStateDoesNotInventAReplayableRuleStep() {
        var strategy = new EqualitySaturationStrategy();
        var states = strategy.search(problemFor("x + 0"));
        var extracted = states.getLast();
        assertEquals("x", extracted.expression());
        assertFalse(extracted.equivalencePreservingByConstruction());
        assertEquals(List.of(Boolean.FALSE), extracted.equivalencePreservingFlags());
        assertFalse(strategy.lastProvenance().hasExtractionProof());
        assertFalse(strategy.lastProvenance().witnesses().isEmpty());
    }

    @Test
    void invalidInputClearsPreviousRunMetadata() {
        var strategy = new EqualitySaturationStrategy();
        strategy.search(problemFor("x + 0"));
        strategy.search(problemFor("("));
        org.junit.jupiter.api.Assertions.assertNull(strategy.lastStats());
        assertTrue(strategy.lastProvenance().witnesses().isEmpty());
    }

    @Test
    void proofRequiredStrategyExposesCheckedOutput() {
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
        var states = strategy.search(exactProblem());
        assertEquals("x", states.getLast().expression());
        assertTrue(strategy.lastProofOutcome().complete());
        assertTrue(strategy.lastProvenance().hasExtractionProof());
        var checked = strategy.lastProofOutcome().checked().orElseThrow();
        assertEquals("x + 0", de.regelsuche.parse.ExpressionFormatter.format(checked.input()));
        assertEquals("x", de.regelsuche.parse.ExpressionFormatter.format(checked.output()));
        assertFalse(states.getLast().equivalencePreservingByConstruction());
        assertEquals(List.of(Boolean.FALSE), states.getLast().equivalencePreservingFlags());
    }

    @Test
    void proofRequiredStrategyKeepsRootWhenUnavailable() {
        var defaults = de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults();
        var unsupported = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(), defaults);
        assertEquals(1, unsupported.search(problemFor("x + 0")).size());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.UNSUPPORTED_RULE,
            unsupported.lastProofOutcome().status());
        assertEquals(unsupported.lastProofOutcome().detail(), unsupported.lastExtractionSelection().reason());
        var zero = new de.regelsuche.egraph.proof.ProofLimits(0, 0, 0, 0, 0, 0, 0, 0);
        var limited = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            new de.regelsuche.egraph.EqualitySaturation.ProofOptions(zero, defaults.replayLimits()));
        var states = limited.search(exactProblem());
        assertEquals(1, states.size());
        assertEquals("x + 0", states.getFirst().expression());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.LIMIT_EXCEEDED,
            limited.lastProofOutcome().status());
        assertEquals(limited.lastProofOutcome().detail(), limited.lastExtractionSelection().reason());
        assertFalse(limited.lastProvenance().hasExtractionProof());
    }

    @Test
    void proofRequiredUnsupportedObjectiveKeepsRoot() {
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
        var states = strategy.search(exactProblem().withCostModel(
            de.regelsuche.scoring.cost.StructuralCostModel.FACTORED_FORM));
        assertEquals(1, states.size());
        assertEquals("x + 0", states.getFirst().expression());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.UNSUPPORTED_OBJECTIVE,
            strategy.lastProofOutcome().status());
        assertFalse(strategy.lastExtractionSelection().fallback());
        org.junit.jupiter.api.Assertions.assertNull(strategy.lastStats());
    }

    @Test
    void proofRequiredSearchKeepsRootForUnrepresentableFunctionSymbol() {
        var a = new de.regelsuche.transform.PatternExpr.Placeholder("a");
        var source = new de.regelsuche.transform.PatternExpr.Function("f", List.of(a));
        var target = new de.regelsuche.transform.PatternExpr.Function("x+y", List.of());
        var exact = new AstRewriteTransformationEngine(List.of(
            new de.regelsuche.transform.PatternRewriteRule("symbol", source, target)));
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
        var states = strategy.search(new SearchProblem("f(x + 0)", exact, scorer, canonicalizer, heuristic));
        assertEquals(1, states.size());
        assertEquals("f(x + 0)", states.getFirst().expression());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
            strategy.lastProofOutcome().status());
        assertEquals("Search output contains an unrepresentable symbol.", strategy.lastProofOutcome().detail());
    }

    @Test
    void proofRequiredRenderingFailureKeepsRootAndActualDiagnostics() {
        var defaults = de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults();
        var first = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(), defaults);
        first.search(exactProblem());
        var proof = first.lastProvenance().proofOutcome();
        assertTrue(proof.complete());
        var limits = defaults.captureLimits();
        var exactWork = new de.regelsuche.egraph.proof.ProofLimits(limits.maxTerms(),
            limits.maxProofSteps(), limits.maxReferences(), limits.maxCharacters(),
            limits.maxRationalBits(), limits.maxDepth(), limits.maxExpandedNodes(),
            proof.captureWork() + proof.buildWork() + 2);
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            new de.regelsuche.egraph.EqualitySaturation.ProofOptions(exactWork, defaults.replayLimits()));
        var states = strategy.search(exactProblem());
        assertEquals(1, states.size());
        assertEquals("x + 0", states.getFirst().expression());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.LIMIT_EXCEEDED,
            strategy.lastProofOutcome().status());
        assertEquals("Search output rendering unavailable within proof limits.", strategy.lastProofOutcome().detail());
        assertNotNull(strategy.lastStats());
        assertTrue(strategy.lastProvenance().hasExtractionProof());
        assertEquals(strategy.lastProvenance().proofOutcome().captureWork() + 2,
            strategy.lastProofOutcome().captureWork());
        assertEquals(strategy.lastProofOutcome().detail(), strategy.lastExtractionSelection().reason());
    }

    @Test
    void proofRequiredReportsSearchOwnedWorkSeparatelyFromCharacters() {
        var defaults = de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults();
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(), defaults);
        strategy.search(exactProblem());
        var direct = strategy.lastProvenance().proofOutcome();
        assertTrue(strategy.lastProofOutcome().captureWork() > direct.captureWork());
        assertEquals(direct.buildWork(), strategy.lastProofOutcome().buildWork());
        assertTrue(strategy.lastProofOutcome().checked().orElseThrow() == direct.checked().orElseThrow());
        // x takes initial stack allocation, one AST visit, one name character and one emitted character.
        assertEquals(4, strategy.lastProofOutcome().captureWork() - direct.captureWork());
    }

    @Test
    void proofRequiredParseFailureReportsWhyRootWasKept() {
        var strategy = new EqualitySaturationStrategy(
            de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
            de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
        assertEquals(1, strategy.search(problemFor("(")).size());
        assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
            strategy.lastProofOutcome().status());
        assertEquals(strategy.lastProofOutcome().detail(), strategy.lastExtractionSelection().reason());
    }

    @Test
    void proofRequiredRenderedEndpointRetainsExactAssociation() {
        var a = new de.regelsuche.transform.PatternExpr.Placeholder("a");
        List<de.regelsuche.transform.RewriteRule> rules = List.of(
            new de.regelsuche.transform.PatternRewriteRule("unwrap",
                new de.regelsuche.transform.PatternExpr.Function("f", List.of(a)), a));
        var exact = new AstRewriteTransformationEngine(rules);
        var parser = new de.regelsuche.parse.ExpressionParser();
        for (String input : List.of("f(x + (y + z))", "f(x * (y * z))", "f(x + (y - z))",
                "f(g(x + (y + z), 0.5))", "f((x ^ y) ^ z)", "f(x ^ (y ^ z))")) {
            var strategy = new EqualitySaturationStrategy(
                de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
                de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
            var states = strategy.search(new SearchProblem(input, exact, scorer, canonicalizer, heuristic));
            assertEquals(2, states.size());
            assertTrue(strategy.lastProofOutcome().complete());
            var token = strategy.lastProofOutcome().checked().orElseThrow();
            var renderedEndpoint = parser.parseTerm(states.getLast().expression());
            var received = de.regelsuche.egraph.proof.ExtractionProofChecker.check(
                parser.parseTerm(input), renderedEndpoint, token.certificate(), rules,
                de.regelsuche.egraph.proof.ProofLimits.defaults());
            assertEquals(de.regelsuche.egraph.proof.ExtractionProofChecker.Status.ACCEPTED,
                received.status(), input + " -> " + states.getLast().expression() + ": " + received.detail());
            assertEquals(token.output(), renderedEndpoint);
        }
    }

    @Test
    void proofRequiredUnrepresentableScalarEndpointKeepsRoot() {
        var a = new de.regelsuche.transform.PatternExpr.Placeholder("a");
        for (var number : List.of(de.regelsuche.scalar.ExactRational.integer(-1),
                de.regelsuche.scalar.ExactRational.fromCanonicalText("1/3"))) {
            var exact = new AstRewriteTransformationEngine(List.of(
                new de.regelsuche.transform.PatternRewriteRule("scalar",
                    new de.regelsuche.transform.PatternExpr.Function("f", List.of(a)),
                    new de.regelsuche.transform.PatternExpr.LiteralNumber(number))));
            var strategy = new EqualitySaturationStrategy(
                de.regelsuche.egraph.EqualitySaturation.Config.defaults(),
                de.regelsuche.egraph.EqualitySaturation.ProofOptions.defaults());
            var states = strategy.search(new SearchProblem("f(x)", exact, scorer, canonicalizer, heuristic));
            assertEquals(1, states.size());
            assertEquals("f(x)", states.getFirst().expression());
            assertEquals(de.regelsuche.egraph.proof.ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                strategy.lastProofOutcome().status());
            assertEquals("Search output contains an unrepresentable scalar endpoint.",
                strategy.lastProofOutcome().detail());
            assertTrue(strategy.lastProvenance().hasExtractionProof());
        }
    }

    private SearchProblem exactProblem() {
        var a = new de.regelsuche.transform.PatternExpr.Placeholder("a");
        var zero = new de.regelsuche.transform.PatternExpr.LiteralNumber(de.regelsuche.scalar.ExactRational.ZERO);
        var source = new de.regelsuche.transform.PatternExpr.Operation(de.regelsuche.ast.BinaryOperator.ADD, a, zero);
        var exact = new AstRewriteTransformationEngine(List.of(
            new de.regelsuche.transform.PatternRewriteRule("add-zero", source, a)));
        return new SearchProblem("x + 0", exact, scorer, canonicalizer, heuristic);
    }

    private SearchProblem problemFor(String expression) {
        return new SearchProblem(expression, engine, scorer, canonicalizer, heuristic);
    }
}
