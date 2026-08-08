package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseCandidateFreezerTest {
    private final ProofCarryingShowcaseCandidateFreezer freezer =
        new ProofCarryingShowcaseCandidateFreezer();

    @Test
    void freezesOneNonSeedStructuredCandidateAndRetainsEveryAlternative() {
        Fixture fixture = fixture();
        Map<String, EvolutionRewriteProgramCandidate> registry =
            new HashMap<>();
        var run = new EvolutionRewriteProgramPopulationEngine().run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            candidate -> {
                registry.put(candidate.contentHash(), candidate);
                return scoredEvidence(
                    fixture.suite(), candidate, fixture.seedHashes());
            });
        List<EvolutionRewriteProgramCandidate> finalCandidates =
            run.finalCandidateHashes().stream()
                .map(registry::get)
                .toList();
        assertTrue(finalCandidates.stream().allMatch(
            java.util.Objects::nonNull));
        RetainedEvolutionRewriteProgramPopulationRun retained =
            RetainedEvolutionRewriteProgramPopulationRun.create(
                run, finalCandidates);
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun authority =
            authority(fixture, retained);

        var first = freezer.freeze(
            authority,
            fixture.study(),
            fixture.seeds(),
            "a".repeat(40),
            hash("showcase-primitive-inventory"),
            hash("showcase-work-budget"),
            2_000_000_000L);
        var second = freezer.freeze(
            authority,
            fixture.study(),
            fixture.seeds(),
            "a".repeat(40),
            hash("showcase-primitive-inventory"),
            hash("showcase-work-budget"),
            2_000_000_000L);

        assertEquals(
            first.selection().toCanonicalJson(),
            second.selection().toCanonicalJson());
        assertEquals(
            first.candidateFreeze().toCanonicalJson(),
            second.candidateFreeze().toCanonicalJson());
        assertEquals(
            retained.finalCandidates().size(),
            first.selection().alternatives().size());
        var selected = first.selection().selectedAlternative();
        assertTrue(selected.eligibleForFreeze());
        assertFalse(selected.seedExactEquivalent());
        assertFalse(selected.seedAlphaEquivalent());
        assertTrue(selected.containsCompositionTopology());
        assertTrue(selected.containsDecisionTopology());
        assertTrue(selected.minimumStructuralPrimitivePathSteps() >= 3);
        assertTrue(selected.trainBlockers().isEmpty());
        assertTrue(selected.freezeBlockers().isEmpty());
        assertEquals(
            first.selectedCandidate().candidateHash(),
            first.candidateFreeze().candidateContentHash());
        assertEquals(
            first.selection().contentHash(),
            first.candidateFreeze().selectionEvidenceHash());
        assertEquals(
            authority.contentHash(),
            first.candidateFreeze().trainingRunHash());
        assertEquals(
            authority.evaluationProtocolHash(),
            first.candidateFreeze().evaluationProtocolHash());
        assertEquals(
            2_000_000_300L,
            first.candidateFreeze().randomnessNotBeforeUnixTime());
        assertTrue(first.candidateFreeze().toCanonicalJson().contains(
            "\"status\":\"CANDIDATE_FROZEN_FINAL_TEST_UNSEEN\""));
        assertFalse(first.candidateFreeze().toCanonicalJson().contains(
            "drandRound"));
        assertFalse(first.candidateFreeze().toCanonicalJson().contains(
            "generatedFinalTest"));
    }

    @Test
    void structuralAnalysisIsConservativeAcrossChoiceAndWrappers() {
        var sourceA = new Source("facts_source_a", List.of("add_zero"));
        var sourceB = new Source("facts_source_b", List.of("mul_one"));
        var sourceC = new Source("facts_source_c", List.of("add_zero"));
        var sequence = new Sequence(
            "facts_sequence", List.of(sourceA, sourceB, sourceC));
        var required = new Require(
            "facts_require",
            sequence,
            Requirement.maxPrimitiveSteps(6));
        var prioritized = new Prioritize(
            "facts_prioritize",
            required,
            Priority.estimatedCostThenRule());

        var facts = ProofCarryingShowcaseCandidateFreezer.analyze(prioritized);

        assertEquals(6, facts.nodeCount());
        assertTrue(facts.containsCompositionTopology());
        assertTrue(facts.containsDecisionTopology());
        assertEquals(3, facts.minimumStructuralPrimitivePathSteps());
    }

    @Test
    void rejectsSeedSubstitutionAndTooEarlyFreezeTime() {
        Fixture fixture = fixture();
        Map<String, EvolutionRewriteProgramCandidate> registry =
            new HashMap<>();
        var run = new EvolutionRewriteProgramPopulationEngine().run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            candidate -> {
                registry.put(candidate.contentHash(), candidate);
                return scoredEvidence(
                    fixture.suite(), candidate, fixture.seedHashes());
            });
        RetainedEvolutionRewriteProgramPopulationRun retained =
            RetainedEvolutionRewriteProgramPopulationRun.create(
                run,
                run.finalCandidateHashes().stream()
                    .map(registry::get)
                    .toList());
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun authority =
            authority(fixture, retained);

        assertThrows(IllegalArgumentException.class, () -> freezer.freeze(
            authority,
            fixture.study(),
            List.of(),
            "a".repeat(40),
            hash("inventory"),
            hash("budget"),
            2_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> freezer.freeze(
            authority,
            fixture.study(),
            fixture.seeds(),
            "a".repeat(40),
            hash("inventory"),
            hash("budget"),
            0L));
    }

    @Test
    void strictSelectionAndFreezeSchemasRemainClaimBounded()
            throws Exception {
        Path schemas = repositoryRoot().resolve("docs").resolve("schemas");
        String selection = Files.readString(schemas.resolve(
            "regelsuche-proof-carrying-showcase-"
                + "candidate-selection-v1.schema.json"));
        String freeze = Files.readString(schemas.resolve(
            "regelsuche-proof-carrying-showcase-"
                + "candidate-freeze-v1.schema.json"));

        assertTrue(selection.contains(
            "TRAIN_SELECTION_FROZEN_FINAL_TEST_UNSEEN"));
        assertTrue(selection.contains("\"alternatives\""));
        assertTrue(selection.contains("\"trainBlockers\""));
        assertTrue(selection.contains("\"freezeBlockers\""));
        assertTrue(selection.contains("\"additionalProperties\": false"));
        assertTrue(freeze.contains(
            "CANDIDATE_FROZEN_FINAL_TEST_UNSEEN"));
        assertFalse(selection.contains("drandRound"));
        assertFalse(selection.contains("finalTestCases"));
    }

    private static ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            authority(
                Fixture fixture,
                RetainedEvolutionRewriteProgramPopulationRun retained
            ) {
        return ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun.create(
            retained,
            fixture.protocol(),
            ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
                .class);
    }

    private static EvolutionRewriteProgramTrainFitnessEvidence scoredEvidence(
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramCandidate candidate,
        List<String> seedHashes
    ) {
        var facts = ProofCarryingShowcaseCandidateFreezer.analyze(
            candidate.plan().root());
        boolean eligibleStructure =
            !seedHashes.contains(candidate.contentHash())
                && facts.containsCompositionTopology()
                && facts.containsDecisionTopology()
                && facts.minimumStructuralPrimitivePathSteps() >= 3;
        int utility = eligibleStructure ? 1000 : 0;
        int simplicity = Math.max(
            0, 1000 - candidate.plan().nodeCount() * 20);
        TrainCase trainCase = suite.cases().getFirst();
        CaseMeasurement measurement = new CaseMeasurement(
            trainCase.caseId(),
            trainCase.familyId(),
            "TARGET_NOT_REACHED",
            "TARGET_NOT_REACHED",
            false,
            false,
            PathCorrectness.NOT_EVALUATED,
            PathCorrectness.NOT_EVALUATED,
            -1,
            -1,
            0,
            0,
            1,
            1,
            0,
            0,
            false,
            false,
            false,
            false,
            false);
        return EvolutionRewriteProgramTrainFitnessEvidence.create(
            suite,
            candidate,
            List.of(measurement),
            Map.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, utility,
                FitnessComponent.CANDIDATE_COMPLEXITY, simplicity),
            List.of());
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "showcase_candidate_freeze_study_v1",
            hash("showcase-freeze-corpus"),
            hash("showcase-freeze-features"),
            List.of(caseRef(
                "train_showcase_freeze_case",
                "train_showcase_freeze_family",
                "train-showcase-freeze")),
            List.of(caseRef(
                "validation_showcase_freeze_case",
                "validation_showcase_freeze_family",
                "validation-showcase-freeze")),
            List.of(caseRef(
                "final_showcase_freeze_case",
                "final_showcase_freeze_family",
                "final-showcase-freeze")));
        EvolutionGenome genome = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
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
            List.of("core.ast-rewrite"),
            List.of());
        EvolutionRewriteProgramPlan seedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Require(
                    "showcase_seed_require",
                    new Sequence(
                        "showcase_seed_sequence",
                        List.of(
                            new Source(
                                "showcase_seed_source_a",
                                List.of("add_zero")),
                            new Source(
                                "showcase_seed_source_b",
                                List.of("mul_one")),
                            new Source(
                                "showcase_seed_source_c",
                                List.of("add_zero")))),
                    Requirement.maxPrimitiveSteps(6)),
                24,
                12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, seedPlan));
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "showcase_candidate_freeze_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_showcase_freeze_case",
                    "train_showcase_freeze_family",
                    "x+0",
                    "x",
                    List.of())),
                new SearchHeuristic(6, 256, 1, 6, 80, 16));
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2), new RepeatBounds(3, 3)),
            List.of(
                Requirement.maxPrimitiveSteps(6),
                Requirement.equivalencePreservingByConstruction()),
            List.of(Priority.estimatedCostThenRule()),
            List.of(8),
            List.of("add_zero", "mul_one"));
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                suite,
                protocol,
                catalog,
                seeds,
                Arrays.asList(
                    EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(8, 3, 2, 2, 4, 2, 20260808L),
                List.of(
                    new FitnessWeight(
                        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 800),
                    new FitnessWeight(
                        FitnessComponent.CANDIDATE_COMPLEXITY, 200)),
                new StudyBudget(2000, 2000, 1, 1, 2));
        return new Fixture(
            manifest,
            suite,
            catalog,
            seeds,
            seeds.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash)
                .toList(),
            protocol,
            study);
    }

    private static EvolutionSplitManifest.CaseReference caseRef(
        String caseId,
        String familyId,
        String material
    ) {
        return new EvolutionSplitManifest.CaseReference(
            caseId,
            familyId,
            hash(material + "-exact"),
            hash(material + "-alpha"),
            hash(material + "-input"),
            hash(material + "-target"));
    }

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null
                && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root;
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
