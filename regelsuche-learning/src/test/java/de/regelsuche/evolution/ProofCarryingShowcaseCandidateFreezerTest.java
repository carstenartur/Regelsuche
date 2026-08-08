package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.CaseMeasurement;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseCandidateFreezerTest {
    @Test
    void retainsAllTrainAlternativesAndFreezesTheDeterministicEligibleWinner() {
        ProofCarryingShowcasePlan showcase =
            ProofCarryingShowcaseTestFixtures.plan();
        Fixture fixture = fixture();
        Map<String, EvolutionRewriteProgramCandidate> registry =
            new HashMap<>();
        var population = new EvolutionRewriteProgramPopulationEngine().run(
            fixture.study(), fixture.manifest(), fixture.suite(),
            fixture.seeds(), fixture.catalog(), candidate -> {
                registry.put(candidate.contentHash(), candidate);
                return evidence(fixture.suite(), candidate,
                    fixture.seedHashes());
            });
        RetainedEvolutionRewriteProgramPopulationRun retained =
            RetainedEvolutionRewriteProgramPopulationRun.create(
                population,
                population.finalCandidateHashes().stream()
                    .map(registry::get).toList());
        var authority =
            ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun.create(
                retained, fixture.protocol(),
                ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
                    .class);

        var frozen = new ProofCarryingShowcaseCandidateFreezer().freeze(
            showcase, authority, fixture.study(), fixture.seeds(),
            "a".repeat(40), hash("inventory"), hash("work-budget"),
            2_000_000_000L);

        assertEquals(retained.finalCandidates().size(),
            frozen.selection().alternatives().size());
        var selected = frozen.selection().selectedAlternative();
        assertTrue(selected.eligibleForFreeze());
        assertFalse(selected.seedExactEquivalent());
        assertFalse(selected.seedAlphaEquivalent());
        assertTrue(selected.containsCompositionTopology());
        assertTrue(selected.containsDecisionTopology());
        assertTrue(selected.minimumStructuralPrimitivePathSteps() >= 3);
        assertEquals(showcase.contentHash(),
            frozen.candidateFreeze().planContentHash());
        assertEquals(authority.contentHash(),
            frozen.candidateFreeze().trainingRunHash());
        assertEquals(frozen.selection().contentHash(),
            frozen.candidateFreeze().selectionEvidenceHash());
        assertEquals(2_000_000_300L,
            frozen.candidateFreeze().randomnessNotBeforeUnixTime());
        assertFalse(frozen.candidateFreeze().toCanonicalJson()
            .contains("drandRound"));
        assertFalse(frozen.candidateFreeze().toCanonicalJson()
            .contains("generatedFinalTest"));
    }

    @Test
    void structuralAnalysisCountsCompositionDecisionAndPrimitiveDepth() {
        var sequence = new Sequence("facts_sequence", List.of(
            new Source("facts_a", List.of("add_zero")),
            new Source("facts_b", List.of("mul_one")),
            new Source("facts_c", List.of("add_zero"))));
        var facts = ProofCarryingShowcaseCandidateFreezer.analyze(
            new EvolutionRewriteProgramPlan.Require(
                "facts_require", sequence,
                Requirement.maxPrimitiveSteps(6)));

        assertEquals(5, facts.nodeCount());
        assertTrue(facts.containsCompositionTopology());
        assertTrue(facts.containsDecisionTopology());
        assertEquals(3, facts.minimumStructuralPrimitivePathSteps());
    }

    private static EvolutionRewriteProgramTrainFitnessEvidence evidence(
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramCandidate candidate,
        List<String> seedHashes
    ) {
        var facts = ProofCarryingShowcaseCandidateFreezer.analyze(
            candidate.plan().root());
        int utility = !seedHashes.contains(candidate.contentHash())
                && facts.containsCompositionTopology()
                && facts.containsDecisionTopology()
                && facts.minimumStructuralPrimitivePathSteps() >= 3
            ? 1000 : 0;
        TrainCase item = suite.cases().getFirst();
        CaseMeasurement measurement = new CaseMeasurement(
            item.caseId(), item.familyId(),
            "TARGET_NOT_REACHED", "TARGET_NOT_REACHED",
            false, false,
            PathCorrectness.NOT_EVALUATED,
            PathCorrectness.NOT_EVALUATED,
            -1, -1, 0, 0, 1, 1, 0, 0,
            false, false, false, false, false);
        return EvolutionRewriteProgramTrainFitnessEvidence.create(
            suite, candidate, List.of(measurement),
            Map.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, utility,
                FitnessComponent.CANDIDATE_COMPLEXITY,
                    Math.max(0, 1000 - candidate.plan().nodeCount() * 20)),
            List.of());
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "showcase_candidate_freeze_study_v1",
            hash("corpus"), hash("features"),
            List.of(ref("train_case", "train_family", "train")),
            List.of(ref("validation_case", "validation_family", "validation")),
            List.of(ref("final_case", "final_family", "final")));
        EvolutionGenome genome = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR, manifest.trainingScope(),
            List.of(
                EvolutionGenomeTestFixtures.addZero("add_zero", "A"),
                EvolutionGenomeTestFixtures.gene(
                    "mul_one", "?A*1", "?A")),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(
                    FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(24, 256, 16, 32, 80),
            List.of("core.ast-rewrite"), List.of());
        var seedPlan = EvolutionRewriteProgramPlan.create(
            genome,
            new EvolutionRewriteProgramPlan.Require(
                "seed_require",
                new Sequence("seed_sequence", List.of(
                    new Source("seed_a", List.of("add_zero")),
                    new Source("seed_b", List.of("mul_one")),
                    new Source("seed_c", List.of("add_zero")))),
                Requirement.maxPrimitiveSteps(6)),
            24, 12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, seedPlan));
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "showcase_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_case", "train_family", "x+0", "x", List.of())),
                new SearchHeuristic(6, 256, 1, 6, 80, 16));
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2), new RepeatBounds(3, 3)),
            List.of(
                Requirement.maxPrimitiveSteps(6),
                Requirement.equivalencePreservingByConstruction()),
            List.of(Priority.estimatedCostThenRule()),
            List.of(8), List.of("add_zero", "mul_one"));
        var protocol = EvolutionRewriteProgramEvaluationProtocol
            .informationParityExactRationalV1();
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(), manifest, suite, protocol, catalog,
                seeds,
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(8, 3, 2, 2, 4, 2, 20260808L),
                List.of(
                    new FitnessWeight(
                        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 800),
                    new FitnessWeight(
                        FitnessComponent.CANDIDATE_COMPLEXITY, 200)),
                new StudyBudget(2000, 2000, 1, 1, 2));
        return new Fixture(manifest, suite, catalog, seeds,
            seeds.stream().map(
                EvolutionRewriteProgramCandidate::contentHash).toList(),
            protocol, study);
    }

    private static EvolutionSplitManifest.CaseReference ref(
        String caseId, String familyId, String material
    ) {
        return new EvolutionSplitManifest.CaseReference(
            caseId, familyId,
            hash(material + "-exact"), hash(material + "-alpha"),
            hash(material + "-input"), hash(material + "-target"));
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record Fixture(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramTrainSuite suite,
        MutationCatalog catalog,
        List<EvolutionRewriteProgramCandidate> seeds,
        List<String> seedHashes,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        EvolutionRewriteProgramStudyPlan study
    ) {
    }
}
