package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearnedRewriteProgramReplayEvidenceTest {
    @Test
    void replayRetainsCompositeTopologyAndPrimitiveWork() {
        Fixture fixture = fixture();
        var compiled = new EvolutionRewriteProgramCompiler().compileAuthorized(
            fixture.candidate().genome(),
            fixture.candidate().plan(),
            fixture.authorizedRules());

        LearnedRewriteProgramReplayEvidence evidence =
            LearnedRewriteProgramReplayEvidence.capture(
                fixture.candidate(),
                compiled,
                List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                    "composite_case",
                    "(x * 1) + 0")));

        var replayCase = evidence.cases().getFirst();
        assertFalse(replayCase.complete(),
            "declared pruning must remain visible as incomplete enumeration");
        assertEquals(1, replayCase.candidates().size());
        var result = replayCase.candidates().getFirst();
        assertEquals("x", result.outputExpression());
        assertEquals(List.of("promoted_mul", "promoted_add"), result.ruleIds());
        assertEquals(2, result.executionWork().primitiveRewrites());
        assertEquals(2, result.primitiveRuleIds().size());
        assertEquals("regelsuche.rewrite-program-work/v2", replayCase.workRevision());
        assertTrue(replayCase.pathBudget().present());
        assertEquals(
            fixture.candidate().genome().budget().maxApplicationsPerPath(),
            replayCase.pathBudget().primitiveRewriteUnits());
        assertEquals(0, replayCase.pathBudget().exactTheoryWorkUnits());
        assertTrue(replayCase.workMetrics().candidateWork().canonicalWorkUnits()
            >= result.executionWork().canonicalWorkUnits(),
            "authorization replay must account at least the retained mathematical path work");
        assertTrue(replayCase.workMetrics().composedCandidates() > 0);
        assertTrue(replayCase.workMetrics().requirementEvaluations() > 0);
        assertTrue(replayCase.workMetrics().priorityCandidatesOrdered() > 0);
        assertTrue(replayCase.workMetrics().prunedCandidates() > 0);
        assertTrue(replayCase.workMetrics().repeatIterations() > 0);
        assertTrue(replayCase.workMetrics().alternativeSelections() > 0);
        assertTrue(replayCase.workMetrics().totalWorkUnitsV2()
            >= result.executionWork().canonicalWorkUnits());
    }

    @Test
    void replayJsonIsCanonicalAndTamperingFailsClosed() {
        Fixture fixture = fixture();
        var compiled = new EvolutionRewriteProgramCompiler().compileAuthorized(
            fixture.candidate().genome(),
            fixture.candidate().plan(),
            fixture.authorizedRules());
        LearnedRewriteProgramReplayEvidence evidence =
            LearnedRewriteProgramReplayEvidence.capture(
                fixture.candidate(),
                compiled,
                List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                    "roundtrip_case",
                    "(x * 1) + 0")));

        assertEquals(
            evidence,
            LearnedRewriteProgramReplayEvidence.fromCanonicalJson(
                evidence.toCanonicalJson()));
        String tampered = evidence.toCanonicalJson().replace(
            evidence.contentHash(),
            EvolutionGenome.hash("tampered-replay"));
        assertThrows(IllegalArgumentException.class, () ->
            LearnedRewriteProgramReplayEvidence.fromCanonicalJson(tampered));
    }

    @Test
    void replayIsBoundToExactProgramTopology() {
        Fixture fixture = fixture();
        var compiler = new EvolutionRewriteProgramCompiler();
        var compiled = compiler.compileAuthorized(
            fixture.candidate().genome(),
            fixture.candidate().plan(),
            fixture.authorizedRules());
        LearnedRewriteProgramReplayEvidence evidence =
            LearnedRewriteProgramReplayEvidence.capture(
                fixture.candidate(),
                compiled,
                List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                    "identity_case",
                    "(x * 1) + 0")));
        EvolutionRewriteProgramPlan substitutedPlan =
            EvolutionRewriteProgramPlan.create(
                fixture.candidate().genome(),
                new Sequence(
                    "substituted_sequence",
                    List.of(
                        new Source("sub_mul", List.of("mul_one")),
                        new Source("sub_add", List.of("add_zero")))),
                12,
                8);
        EvolutionRewriteProgramCandidate substituted =
            EvolutionRewriteProgramCandidate.create(
                fixture.candidate().genome(), substitutedPlan);

        assertThrows(IllegalArgumentException.class,
            () -> evidence.requireCandidate(substituted));
    }

    private static Fixture fixture() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A"),
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Prune(
                "keep_best",
                new Prioritize(
                    "rank_candidates",
                    new Require(
                        "bounded_equivalent",
                        new Choice(
                            "alternatives",
                            List.of(
                                new Sequence(
                                    "full_sequence",
                                    List.of(
                                        new Source(
                                            "mul_source",
                                            List.of("mul_one")),
                                        new Source(
                                            "add_source",
                                            List.of("add_zero")))),
                                new Repeat(
                                    "repeat_add",
                                    new Source(
                                        "repeat_source",
                                        List.of("add_zero")),
                                    1,
                                    2),
                                new FirstApplicable(
                                    "first_applicable",
                                    List.of(
                                        new Source(
                                            "first_add",
                                            List.of("add_zero")),
                                        new Source(
                                            "fallback_mul",
                                            List.of("mul_one")))))),
                        Requirement.maxPrimitiveSteps(2)),
                    Priority.estimatedCostThenRule()),
                1,
                "retain best deterministic candidate"),
            12,
            8);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        Map<String, RewriteRule> rules = Map.of(
            "mul_one", exactRule("promoted_mul", "?A*1", "?A"),
            "add_zero", exactRule("promoted_add", "?A+0", "?A"));
        return new Fixture(candidate, rules);
    }

    private static RewriteRule exactRule(
        String id,
        String source,
        String target
    ) {
        return new PatternRewriteRule(
            id,
            EvolutionGenomeCompiler.parsePattern(source),
            EvolutionGenomeCompiler.parsePattern(target),
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true);
    }

    private record Fixture(
        EvolutionRewriteProgramCandidate candidate,
        Map<String, RewriteRule> authorizedRules
    ) {
    }
}
