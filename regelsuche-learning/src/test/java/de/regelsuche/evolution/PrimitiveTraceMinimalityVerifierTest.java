package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.evolution.PrimitiveTraceMinimalityVerifier.Limits;
import de.regelsuche.evolution.PrimitiveTraceMinimalityVerifier.Status;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class PrimitiveTraceMinimalityVerifierTest {
    private static EvolutionGenome inventory(boolean shortcut) {
        var template = TraceStrategyTransferExample.inventory();
        var one = template.rewrites().stream().filter(gene -> gene.geneId().equals("add-zero")).findFirst().orElseThrow();
        var ten = template.rewrites().stream().filter(gene -> gene.geneId().equals("multiply-one")).findFirst().orElseThrow()
            .withPatterns(wrap("?A", 10), "?A");
        return template.withRewrites(shortcut ? List.of(one, ten) : List.of(one));
    }

    private static String wrap(String expression, int count) {
        for (int i = 0; i < count; i++) expression = "(" + expression + "+0)";
        return expression;
    }

    private static List<Transformation> longPath(EvolutionGenome inventory, String source, int steps) {
        var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
        var engine = new PreparedAstRewriteTransformationEngine(rules, Integer.MAX_VALUE, 4096);
        List<Transformation> path = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            var step = engine.transform(source).stream().filter(value -> value.rule().endsWith("_add-zero")).findFirst().orElseThrow();
            path.add(step);
            source = step.transformedExpression();
        }
        return List.copyOf(path);
    }

    @Test
    void twentyObservedStepsAreReplacedByTheProvedTwoStepConnection() {
        var inventory = inventory(true);
        var source = wrap("x", 20);
        var verifier = new PrimitiveTraceMinimalityVerifier(inventory);
        var assessment = verifier.assess(source, longPath(inventory, source, 20), Limits.defaults());
        assertEquals(Status.SHORTER_PATH_FOUND, assessment.status());
        assertEquals(20, assessment.observedPrimitiveSteps());
        assertEquals(2, assessment.minimumPrimitiveSteps());
        assertEquals("x", assessment.shortestPath().getLast().transformedExpression());
        assertTrue(assessment.reusableMultistepTrace());
        assertTrue(assessment.shortestPath().stream().allMatch(step -> step.rule().endsWith("_multiply-one")));
        assertEquals(2, assessment.oracle().orElseThrow().witness().orElseThrow().primitiveSteps());
        var utility = new RuleUtilityAssessor().fromReference(assessment, 1);
        assertEquals(20, utility.observedPathSteps());
        assertEquals(2, utility.bestKnownPrimitiveSteps());
        assertEquals(1, utility.knownDepthCompression());
        assertEquals(20, utility.proofReplayWork(), "the retained original proof does not become free");
        assertTrue(utility.boundedMinimumProved());
        assertEquals(inventory.contentHash(), utility.reference().primitiveInventoryHash());
        assertEquals(assessment.contentHash(), utility.reference().assessmentHash());
        assertEquals(utility, de.regelsuche.inventory.RuleUtilityEvidence.fromJson(utility.toCanonicalJson()));
        verifier.verify(assessment);
    }

    @Test
    void twentyStepsAreCertifiedOnlyAfterEveryShorterPathHasBeenExcluded() {
        var inventory = inventory(false);
        var source = wrap("x", 20);
        var verifier = new PrimitiveTraceMinimalityVerifier(inventory);
        var path = longPath(inventory, source, 20);
        var assessment = verifier.assess(source, path, Limits.defaults());
        assertEquals(Status.SHORTEST_CONFIRMED, assessment.status());
        assertEquals(20, assessment.minimumPrimitiveSteps());
        assertEquals(path, assessment.shortestPath());
        assertTrue(assessment.oracle().orElseThrow().closureComplete());
        assertEquals(19, assessment.oracle().orElseThrow().budget().maxPrimitivePathWork());
        assertTrue(assessment.measuredWork() > 20);
        var utility = new RuleUtilityAssessor().fromReference(assessment, 1);
        assertEquals(19, utility.knownDepthCompression());
        assertTrue(utility.boundedMinimumProved());
        verifier.verify(assessment);
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveTraceMinimalityVerifier(inventory(true)).verify(assessment));
    }

    @Test
    void everyResourceCeilingLeavesMinimalityUnknownAndCannotApproveATrace() {
        var inventory = inventory(true);
        var source = wrap("x", 20);
        var path = longPath(inventory, source, 20);
        var verifier = new PrimitiveTraceMinimalityVerifier(inventory);
        for (var limits : List.of(new Limits(1, 65_536, 512, 1024, 500_000),
                new Limits(4096, 0, 512, 1024, 500_000), new Limits(4096, 65_536, 1, 1024, 500_000),
                new Limits(4096, 65_536, 512, 8, 500_000), new Limits(4096, 65_536, 512, 1024, 1))) {
            var assessment = verifier.assess(source, path, limits);
            assertEquals(Status.INCONCLUSIVE, assessment.status(), limits.toString());
            assertEquals(-1, assessment.minimumPrimitiveSteps());
            assertFalse(assessment.minimumProved());
            assertFalse(assessment.reusableMultistepTrace());
            assertTrue(assessment.shortestPath().isEmpty());
            var utility = new RuleUtilityAssessor().fromReference(assessment, -1);
            assertFalse(utility.boundedMinimumProved());
            assertEquals(assessment.observedReplayVerified() ? 20 : -1, utility.bestKnownPrimitiveSteps());
        }
    }

    @Test
    void aReplayedSevenStepUpperBoundSurvivesAnInconclusiveReferenceSearch() {
        var inventory = inventory(false);
        var source = wrap("x", 7);
        var assessed = new RuleUtilityAssessor().assess(inventory, source, longPath(inventory, source, 7),
            new Limits(4096, 0, 512, 1024, 500_000), 2);
        assertEquals(Status.INCONCLUSIVE, assessed.reference().status());
        assertTrue(assessed.reference().observedReplayVerified());
        assertFalse(assessed.utility().boundedMinimumProved());
        assertEquals(7, assessed.utility().bestKnownPrimitiveSteps());
        assertEquals(7, assessed.utility().proofReplayWork());
        assertEquals(6, assessed.utility().knownDepthCompression());
        assertEquals(assessed.utility(), de.regelsuche.inventory.RuleUtilityEvidence.fromJson(assessed.utility().toCanonicalJson()));
    }

    @Test
    void oneStepAndZeroStepConnectionsDoNotCreateNewMultistepRules() {
        var inventory = inventory(false);
        var verifier = new PrimitiveTraceMinimalityVerifier(inventory);
        var one = verifier.assess("x+0", longPath(inventory, "x+0", 1), Limits.defaults());
        assertEquals(1, one.minimumPrimitiveSteps(), "polynomial equivalence must not collapse distinct syntax to zero distance");
        assertFalse(one.reusableMultistepTrace());
        var zero = verifier.assess("x", List.of(), Limits.defaults());
        assertEquals(0, zero.minimumPrimitiveSteps());
        assertFalse(zero.reusableMultistepTrace());
        var grow = TraceStrategyTransferExample.inventory().rewrites().stream().filter(g -> g.geneId().equals("multiply-one"))
            .findFirst().orElseThrow().withPatterns("?A", "?A+0");
        var cyclicInventory = inventory.withRewrites(List.of(inventory.rewrites().getFirst(), grow));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
            new PrimitiveTraceMinimalityVerifier(cyclicInventory).assess("x", List.of(), Limits.defaults()))
            .getMessage().contains("REWRITE_CYCLE"), "the existing inventory preflight already rejects an explicit cycle");
    }

    @Test
    void forgedStepsAndPackagedMacrosCannotBecomePrimitiveWitnesses() {
        var inventory = inventory(false);
        var verifier = new PrimitiveTraceMinimalityVerifier(inventory);
        var source = wrap("x", 2);
        var path = longPath(inventory, source, 2);
        assertThrows(IllegalArgumentException.class, () -> verifier.assess("y", path, Limits.defaults()));
        var macro = new RewriteCandidate("packed", source, "x", path).toTransformation();
        assertThrows(IllegalArgumentException.class, () -> verifier.assess(source, List.of(macro), Limits.defaults()));
    }
}
