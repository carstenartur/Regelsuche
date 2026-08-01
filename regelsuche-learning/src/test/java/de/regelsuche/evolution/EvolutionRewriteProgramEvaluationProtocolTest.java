package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramEvaluationProtocolTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    @Test
    void officialProtocolIsCanonicalAndBoundToTheProtocolAwareEvaluator() {
        EvolutionRewriteProgramEvaluationProtocol first =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramEvaluationProtocol second =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
                .class.getName(),
            first.implementationClass());
        assertTrue(first.toCanonicalJson().contains(
            "\"baselineContract\":"
                + "\"ORDINARY_PLUS_FLAT_CANDIDATE_GENOME_RULES\""));
        assertTrue(first.toCanonicalJson().contains(
            "\"resourceAttributionContract\":"
                + "\"CONFIRMED_RETAINED_PATH_REQUIRES_PROGRAM_EDGE\""));

        EvolutionRewriteProgramEvaluationProtocol substituted =
            EvolutionRewriteProgramEvaluationProtocol.testingProtocol(
                "substituted_protocol_v1");
        assertNotEquals(first.contentHash(), substituted.contentHash());
    }

    @Test
    void protocolAwareEvaluatorWritesItsHashIntoEveryEvidenceRoot() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramFitnessEvaluator evaluator =
            new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(
                fixture.suite(), COMPONENTS);

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            evaluator.evaluate(fixture.candidate());

        assertEquals(
            evaluator.protocol().contentHash(),
            evidence.evaluationProtocolHash());
        assertEquals(
            fixture.study().trainEvaluationProtocolHash(),
            evidence.evaluationProtocolHash());
        assertTrue(evidence.toCanonicalJson().contains(
            "\"evaluationProtocolHash\":\""
                + evidence.evaluationProtocolHash() + "\""));
    }

    @Test
    void protocolBoundRunnerRejectsProtocolAndImplementationSubstitution() {
        Fixture fixture = fixture();
        ProtocolBoundEvolutionRewriteProgramPopulationRunner runner =
            new ProtocolBoundEvolutionRewriteProgramPopulationRunner();
        AtomicInteger calls = new AtomicInteger();

        EvolutionRewriteProgramFitnessEvaluator wrongProtocol = evaluator(
            EvolutionRewriteProgramEvaluationProtocol.testingProtocol(
                "wrong_protocol_v1"),
            calls,
            fixture);
        assertThrows(IllegalArgumentException.class, () -> runner.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            List.of(fixture.candidate()),
            fixture.catalog(),
            wrongProtocol));
        assertEquals(0, calls.get());

        EvolutionRewriteProgramFitnessEvaluator forgedImplementation = evaluator(
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1(),
            calls,
            fixture);
        assertThrows(IllegalArgumentException.class, () -> runner.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            List.of(fixture.candidate()),
            fixture.catalog(),
            forgedImplementation));
        assertEquals(0, calls.get());
    }

    @Test
    void schemasRetainProtocolVocabularyAndHashes() throws Exception {
        String protocol = readSchema(
            "regelsuche-evolution-rewrite-program-evaluation-protocol-v1.schema.json");
        String fitness = readSchema(
            "regelsuche-evolution-rewrite-program-train-fitness-v1.schema.json");
        String study = readSchema(
            "regelsuche-evolution-rewrite-program-study-plan-v1.schema.json");

        assertTrue(protocol.contains(
            "regelsuche.evolution-rewrite-program-evaluation-protocol/v1"));
        assertTrue(protocol.contains(
            "ORDINARY_PLUS_FLAT_CANDIDATE_GENOME_RULES"));
        assertTrue(protocol.contains(
            "CONFIRMED_RETAINED_PATH_REQUIRES_PROGRAM_EDGE"));
        assertTrue(protocol.contains("\"additionalProperties\": false"));
        assertTrue(fitness.contains("\"evaluationProtocolHash\""));
        assertTrue(study.contains("\"trainEvaluationProtocolHash\""));
    }

    private static EvolutionRewriteProgramFitnessEvaluator evaluator(
        EvolutionRewriteProgramEvaluationProtocol protocol,
        AtomicInteger calls,
        Fixture fixture
    ) {
        return new EvolutionRewriteProgramFitnessEvaluator() {
            @Override
            public EvolutionRewriteProgramEvaluationProtocol protocol() {
                return protocol;
            }

            @Override
            public EvolutionRewriteProgramTrainFitnessEvidence evaluate(
                EvolutionRewriteProgramCandidate candidate
            ) {
                calls.incrementAndGet();
                return EvolutionRewriteProgramTrainFitnessEvidence.create(
                    fixture.suite(),
                    protocol,
                    candidate,
                    List.of(),
                    java.util.Map.of(),
                    List.of());
            }
        };
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "protocol_binding_study_v1",
            hash("protocol-corpus"),
            hash("protocol-features"),
            List.of(caseRef("train_protocol_case", "train_protocol_family", "train")),
            List.of(caseRef(
                "validation_protocol_case",
                "validation_protocol_family",
                "validation")),
            List.of(caseRef("final_protocol_case", "final_protocol_family", "final")));
        EvolutionGenome genome = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
            List.of(EvolutionGenomeTestFixtures.addZero("add_zero", "A")),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
        EvolutionRewriteProgramPlan program =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("protocol_add_zero", List.of("add_zero")),
                4,
                4);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, program);
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "protocol_binding_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_protocol_case",
                    "train_protocol_family",
                    "x+0",
                    "x",
                    List.of())),
                new SearchHeuristic(2, 64, 1, 2, 80, 8));
        MutationCatalog catalog = new MutationCatalog(
            List.of(), List.of(), List.of(), List.of(), List.of());
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
                List.of(candidate),
                List.of(EvolutionRewriteProgramMutationKind.WRAP_REPEAT),
                new PopulationPolicy(2, 1, 1, 1, 1, 1, 20260801L),
                List.of(
                    new FitnessWeight(
                        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                    new FitnessWeight(
                        FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
                new StudyBudget(16, 16, 1, 1, 1));
        return new Fixture(manifest, suite, catalog, candidate, study);
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

    private static String readSchema(String fileName) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return Files.readString(root.resolve("docs").resolve("schemas")
            .resolve(fileName));
    }

    private record Fixture(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramTrainSuite suite,
        MutationCatalog catalog,
        EvolutionRewriteProgramCandidate candidate,
        EvolutionRewriteProgramStudyPlan study
    ) {
    }
}
