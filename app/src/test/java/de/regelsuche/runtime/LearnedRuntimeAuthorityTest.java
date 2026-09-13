package de.regelsuche.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionCorrectnessStatus;
import de.regelsuche.evolution.EvolutionFinalTestCaseEvidence;
import de.regelsuche.evolution.EvolutionFinalTestEvaluation;
import de.regelsuche.evolution.EvolutionFinalTestMeasurement;
import de.regelsuche.evolution.EvolutionFinalTestReservation;
import de.regelsuche.evolution.EvolutionFinalTestSuite;
import de.regelsuche.evolution.EvolutionGenome;
import de.regelsuche.evolution.EvolutionRewriteProgramCandidate;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan;
import de.regelsuche.evolution.EvolutionSplitManifest;
import de.regelsuche.evolution.EvolutionValidationCandidate;
import de.regelsuche.evolution.EvolutionValidationCaseEvidence;
import de.regelsuche.evolution.EvolutionValidationSearchConfiguration;
import de.regelsuche.evolution.EvolutionValidationSelection;
import de.regelsuche.evolution.LearnedPatternAuthorizationBundle;
import de.regelsuche.evolution.LearnedPatternCounterexampleEvidence;
import de.regelsuche.evolution.LearnedPatternRuleAuthorizationService;
import de.regelsuche.evolution.LearnedRewriteProgramAuthorizationService;
import de.regelsuche.evolution.LearnedRewriteProgramReplayEvidence;
import de.regelsuche.json.JsonReader;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

class LearnedRuntimeAuthorityTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final Instant ISSUED =
        Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AUTHORIZED =
        Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant VALID =
        Instant.parse("2026-09-12T00:00:00Z");
    private static final Instant EXPIRES =
        Instant.parse("2026-10-01T00:00:00Z");
    private static final String IMPLEMENTATION = "sha256:" + "c".repeat(64);

    @TempDir
    Path temporary;

    @Test
    @ResourceLock("system-properties")
    void absentConfiguredManifestLoadsAnEmptySnapshot() {
        String property = "regelsuche.runtime.learnedManifest";
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            LearnedRuntimeAuthority.Snapshot snapshot =
                LearnedRuntimeAuthority.fromConfiguredProperty().load();
            assertEquals(List.of(), snapshot.rules());
            assertEquals(List.of(), snapshot.sources());
            assertEquals(0L, snapshot.setupWorkUnits());
            assertEquals(
                LearnedRuntimeAuthorityIdentity.EXPECTED_EMPTY_IDENTITY,
                snapshot.identity());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    @ResourceLock("system-properties")
    void strictConfiguredManifestLoadsRelativeEmptyAuthority() throws Exception {
        Path manifest = temporary.resolve("authority.json");
        Files.writeString(manifest, """
            {
              "schema":"regelsuche.learned-runtime-authority-manifest/v1",
              "repositoryRevision":"%s",
              "patterns":[],
              "programs":[]
            }
            """.formatted(REVISION));

        withManifest(manifest, () -> {
            LearnedRuntimeAuthority.Snapshot snapshot =
                LearnedRuntimeAuthority.fromConfiguredProperty().load();
            assertEquals(REVISION, snapshot.identity().get(
                "repositoryRevision"));
            assertEquals(0L, snapshot.setupWorkUnits());
        });
    }

    @Test
    @ResourceLock("system-properties")
    void configuredManifestRejectsUnknownFieldsAndDuplicateKeys()
            throws Exception {
        Path unknown = temporary.resolve("unknown.json");
        Files.writeString(unknown, """
            {"schema":"regelsuche.learned-runtime-authority-manifest/v1",
             "repositoryRevision":"%s","patterns":[],"programs":[],
             "requestReceipt":"untrusted"}
            """.formatted(REVISION));
        Path duplicate = temporary.resolve("duplicate.json");
        Files.writeString(duplicate, """
            {"schema":"regelsuche.learned-runtime-authority-manifest/v1",
             "repositoryRevision":"%s","patterns":[],"patterns":[],
             "programs":[]}
            """.formatted(REVISION));

        withManifest(unknown, () -> assertTrue(assertThrows(
            IllegalArgumentException.class,
            LearnedRuntimeAuthority::fromConfiguredProperty)
            .getMessage().contains("field")));
        withManifest(duplicate, () -> assertThrows(
            IllegalArgumentException.class,
            LearnedRuntimeAuthority::fromConfiguredProperty));
    }

    @Test
    void replaysRetainedPatternAndProgramAuthoritiesOnEveryLoad()
            throws Exception {
        RetainedFixture fixture = retainedFixture(temporary.resolve("retained"));
        MutableClock clock = new MutableClock(VALID);
        LearnedRuntimeAuthority authority = new LearnedRuntimeAuthority(
            REVISION,
            fixture.patternRoots(),
            List.of(fixture.programRoot()),
            clock);

        LearnedRuntimeAuthority.Snapshot snapshot = authority.load();

        assertEquals(
            fixture.rules().stream()
                .map(RuleInventoryFingerprint::ruleContentHash)
                .toList(),
            snapshot.rules().stream()
                .map(RuleInventoryFingerprint::ruleContentHash)
                .toList());
        assertEquals(1, snapshot.sources().size());
        SafeRuntimeAdapter.DirectSource loadedSource =
            snapshot.sources().getFirst();
        assertEquals("normalize-program", loadedSource.id());
        assertEquals(
            fixture.programAuthorization().receipt().contentHash(),
            loadedSource.authorityHash());
        assertEquals(
            fixture.programAuthorization().compiledProgram().engine()
                .transform("(x * 1) + 0"),
            loadedSource.engine().transform("(x * 1) + 0"));
        assertFalse(loadedSource.engine().transform("(x * 1) + 0").isEmpty());
        assertEquals(3L, snapshot.setupWorkUnits());
        assertEquals(
            LearnedRuntimeAuthority.SETUP_WORK_REVISION,
            snapshot.identity().get("setupWorkRevision"));
        String retainedIdentity = RuntimeJson.canonical(snapshot.identity());
        assertFalse(retainedIdentity.contains(temporary.toString()));
        assertTrue(retainedIdentity.contains(
            fixture.programAuthorization().receipt().planHash()));
        assertTrue(retainedIdentity.contains(
            fixture.leaves().getFirst().receipt().applicabilitySchemaHash()));

        SafeRuntimeAdapter adapter = new SafeRuntimeAdapter(
            List.of(), List.of(), IMPLEMENTATION, authority);
        Map<String, Object> directRequest = request(
            "DIRECT_V1", "(x * 1) + 0");
        Map<String, Object> safeRequest = request(
            "SAFE_PREPARATION_V4", "(x * 1) + 0");
        String directArtifact = adapter.analyze(directRequest);
        String safeArtifact = adapter.analyze(safeRequest);
        Map<String, Object> direct = evidence(directArtifact);
        Map<String, Object> safe = evidence(safeArtifact);
        assertEquals(direct.get("inventory"), safe.get("inventory"));
        var historical = evidence(adapter.analyze(request("SAFE_PREPARATION_V3", "(x * 1) + 0")));
        assertEquals(direct.get("inventory"), historical.get("inventory"));
        Map<String, Object> exportedAuthority = object(
            direct.get("authority"));
        assertEquals(
            snapshot.identity(),
            exportedAuthority.get("learnedAuthorization"));
        Map<String, Object> work = object(direct.get("work"));
        assertTrue(((Number) work.get("setupUnits")).longValue()
            >= snapshot.setupWorkUnits());

        Map<String, Object> programOutcome = outcome(
            direct, "normalize-program");
        Map<String, Object> programCandidate = object(
            programOutcome.get("candidate"));
        assertNotNull(programCandidate);
        var expectedProgram = fixture.programAuthorization()
            .compiledProgram().engine().transform("(x * 1) + 0").getFirst();
        assertEquals(expectedProgram.transformedExpression(),
            programCandidate.get("expression"));
        assertEquals(expectedProgram.rule(), programCandidate.get("rule"));
        assertEquals(expectedProgram.primitiveRuleIds(),
            programCandidate.get("primitiveRuleIds"));
        assertEquals(
            expectedProgram.executionWork(),
            RecordedExecution.fromCanonicalJson(
                (String) programCandidate.get("execution")).work());
        Map<String, Object> directWork = object(
            programOutcome.get("directWork"));
        assertEquals("MEASURED_ORIGINAL_ENGINE", directWork.get("kind"));
        Map<String, Object> metrics = object(directWork.get("metrics"));
        assertTrue(((Number) metrics.get("programNodeVisits")).longValue() > 0);
        assertTrue(((Number) metrics.get("sourceInvocations")).longValue() > 0);
        assertNotNull(outcome(safe, "normalize-program").get("candidate"));
        assertEquals(programCandidate, outcome(safe, "normalize-program").get("candidate"));
        assertEquals(programCandidate, outcome(historical, "normalize-program").get("candidate"));
        assertEquals(safeArtifact, adapter.replay(new JsonReader(safeArtifact).readObject()));
        assertEquals(
            directArtifact,
            adapter.replay(new JsonReader(directArtifact).readObject()));

        Path counterexamples = fixture.patternRoots().getFirst().root()
            .resolve("counterexample-evidence.json");
        String original = Files.readString(counterexamples);
        Files.writeString(counterexamples, "{}");
        assertThrows(IllegalArgumentException.class, authority::load);
        assertThrows(IllegalArgumentException.class,
            () -> adapter.analyze(directRequest));
        assertThrows(IllegalArgumentException.class,
            () -> adapter.replay(new JsonReader(directArtifact).readObject()));
        Files.writeString(counterexamples, original);

        clock.set(EXPIRES);
        assertThrows(IllegalArgumentException.class,
            () -> adapter.analyze(safeRequest));
        assertThrows(IllegalArgumentException.class,
            () -> adapter.replay(new JsonReader(directArtifact).readObject()));
    }

    private static Map<String, Object> request(
        String profile,
        String source
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schema", SafeRuntimeRequest.SCHEMA);
        request.put("profile", profile);
        request.put("source", source);
        request.put("assumptions", List.of());
        request.put("includeSymPy", false);
        return request;
    }

    private static Map<String, Object> evidence(String artifact) {
        return object(new JsonReader(artifact).readObject().get("evidence"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> outcome(
        Map<String, Object> evidence,
        String id
    ) {
        for (Object raw : (List<?>) evidence.get("outcomes")) {
            Map<String, Object> outcome = object(raw);
            if (id.equals(outcome.get("id"))) {
                return outcome;
            }
        }
        throw new AssertionError("missing outcome for " + id);
    }

    private static void withManifest(Path manifest, ThrowingTask task)
            throws Exception {
        String property = LearnedRuntimeAuthority.MANIFEST_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, manifest.toString());
            task.run();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run() throws Exception;
    }

    private static RetainedFixture retainedFixture(Path output)
            throws Exception {
        Files.createDirectories(output);
        EvolutionSplitManifest split = split();
        EvolutionGenome genome = genome(split);
        EvolutionValidationSelection validation = validation(split, genome);
        EvolutionFinalTestEvaluation finalTest = finalTest(split, validation);
        Leaf multiply = authorizeLeaf(
            output.resolve("leaf-mul-one"), genome, split, validation,
            finalTest, "mul-one");
        Leaf add = authorizeLeaf(
            output.resolve("leaf-add-zero"), genome, split, validation,
            finalTest, "add-zero");

        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new EvolutionRewriteProgramPlan.Sequence(
                "two-step-normalization",
                List.of(
                    new EvolutionRewriteProgramPlan.Source(
                        "multiply-identity", List.of("mul-one")),
                    new EvolutionRewriteProgramPlan.Source(
                        "additive-identity", List.of("add-zero")))),
            5,
            3);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        LearnedRewriteProgramAuthorizationService service =
            new LearnedRewriteProgramAuthorizationService();
        List<LearnedPatternRuleAuthorizationService.Authorization> leaves =
            List.of(multiply.authorization(), add.authorization());
        LearnedRewriteProgramReplayEvidence replay = service.evaluateReplay(
            candidate,
            leaves,
            List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                "composite-normalization", "(x * 1) + 0")),
            REVISION,
            AUTHORIZED);
        var programAuthorization = service.authorize(
            candidate, leaves, replay, REVISION, AUTHORIZED);
        write(output.resolve("genome.json"), genome.toCanonicalJson());
        write(output.resolve("program-plan.json"), plan.toCanonicalJson());
        write(output.resolve("program-replay-evidence.json"),
            replay.toCanonicalJson());
        write(output.resolve("program-authorization-receipt.json"),
            programAuthorization.receipt().toCanonicalJson());

        return new RetainedFixture(
            List.of(
                new LearnedRuntimeAuthority.PatternRoot(
                    "add-zero", add.root()),
                new LearnedRuntimeAuthority.PatternRoot(
                    "mul-one", multiply.root())),
            new LearnedRuntimeAuthority.ProgramRoot(
                "normalize-program", output,
                List.of("mul-one", "add-zero")),
            leaves,
            List.of(
                add.authorization().promotion().rule(),
                multiply.authorization().promotion().rule()),
            programAuthorization);
    }

    private static Leaf authorizeLeaf(
        Path output,
        EvolutionGenome genome,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation finalTest,
        String geneId
    ) throws Exception {
        Files.createDirectories(output);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternCounterexampleEvidence counterexample =
            service.evaluateCounterexamples(genome, geneId, REVISION);
        LearnedPatternAuthorizationBundle bundle =
            LearnedPatternAuthorizationBundle.create(
                genome, geneId, REVISION, ISSUED, EXPIRES, split, validation,
                finalTest, counterexample);
        write(output.resolve("genome.json"), genome.toCanonicalJson());
        Path bundleFile = write(output.resolve("authorization-bundle.json"),
            bundle.toCanonicalJson());
        Path splitFile = write(output.resolve("split-manifest.json"),
            split.toCanonicalJson());
        Path validationFile = write(
            output.resolve("validation-selection.json"),
            validation.toCanonicalJson());
        Path finalFile = write(output.resolve("final-test-evaluation.json"),
            finalTest.toCanonicalJson());
        Path counterexampleFile = write(
            output.resolve("counterexample-evidence.json"),
            counterexample.toCanonicalJson());
        var authorization = service.authorize(
            genome,
            geneId,
            REVISION,
            new LearnedPatternRuleAuthorizationService.EvidenceFiles(
                bundleFile,
                splitFile,
                validationFile,
                finalFile,
                counterexampleFile),
            AUTHORIZED);
        write(output.resolve("authorization-receipt.json"),
            authorization.receipt().toCanonicalJson());
        return new Leaf(output, authorization);
    }

    private static EvolutionSplitManifest split() {
        return EvolutionSplitManifest.create(
            "runtime-authority-fixture",
            hash("fixture-corpus"),
            hash("fixture-features"),
            List.of(caseReference("train", "train-family")),
            List.of(caseReference("validation", "validation-family")),
            List.of(caseReference("final", "final-family")));
    }

    private static EvolutionSplitManifest.CaseReference caseReference(
        String id,
        String family
    ) {
        return new EvolutionSplitManifest.CaseReference(
            id,
            family,
            hash(id + "-exact"),
            hash(id + "-alpha"),
            hash(id + "-input"),
            hash(id + "-target"));
    }

    private static EvolutionGenome genome(EvolutionSplitManifest split) {
        return EvolutionGenome.create(
            EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            split.trainingScope(),
            List.of(
                gene("mul-one", "?A*1", "?A"),
                gene("add-zero", "?A+0", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(
                EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                1_000)),
            EvolutionGenome.GuardPolicy.strictDefault(),
            EvolutionGenome.ResourceBudget.conservativeDefault(),
            List.of("symbolic-polynomial"),
            List.of());
    }

    private static EvolutionGenome.RewriteGene gene(
        String id,
        String source,
        String target
    ) {
        return new EvolutionGenome.RewriteGene(
            id,
            source,
            target,
            RewriteKind.SIMPLIFY,
            true,
            -2,
            4,
            4,
            List.of(),
            List.of(
                EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
                EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
                EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION));
    }

    private static EvolutionValidationSelection validation(
        EvolutionSplitManifest split,
        EvolutionGenome genome
    ) {
        EvolutionValidationCaseEvidence evidence =
            new EvolutionValidationCaseEvidence(
                "validation",
                "validation-family",
                false,
                true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.CONFIRMED,
                "FRONTIER_EXHAUSTED",
                "TARGET_REACHED",
                -1,
                2,
                10,
                10,
                5,
                5,
                true,
                false,
                false,
                false);
        EvolutionValidationCandidate candidate =
            EvolutionValidationCandidate.create(
                genome.contentHash(),
                genome.alphaStructuralHash(),
                new EvolutionValidationSearchConfiguration(5, 200, 12),
                List.of(evidence),
                List.of());
        return EvolutionValidationSelection.create(
            hash("fixture-study-plan"),
            split.contentHash(),
            hash("fixture-train-population"),
            hash("fixture-validation-suite"),
            List.of("validation"),
            List.of(candidate));
    }

    private static EvolutionFinalTestEvaluation finalTest(
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation
    ) {
        EvolutionFinalTestSuite suite = EvolutionFinalTestSuite.create(
            validation.studyPlanHash(),
            split.contentHash(),
            hash("fixture-baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final", "final-family", hash("fixture-final-material"))));
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(validation, suite);
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(
                suite.cases().getFirst(),
                new EvolutionFinalTestMeasurement(
                    EvolutionFinalTestMeasurement.Status.COMPLETED,
                    false,
                    "FRONTIER_EXHAUSTED",
                    -1,
                    10,
                    5,
                    EvolutionCorrectnessStatus.NOT_EVALUATED,
                    ""),
                new EvolutionFinalTestMeasurement(
                    EvolutionFinalTestMeasurement.Status.COMPLETED,
                    true,
                    "TARGET_REACHED",
                    2,
                    10,
                    5,
                    EvolutionCorrectnessStatus.CONFIRMED,
                    hash("fixture-selected-result")));
        return EvolutionFinalTestEvaluation.create(
            reservation, suite, List.of(evidence));
    }

    private static Path write(Path path, String content) throws Exception {
        Files.writeString(path, content);
        return path;
    }

    private static String hash(String value) {
        return RuntimeJson.hash(value);
    }

    private record Leaf(
        Path root,
        LearnedPatternRuleAuthorizationService.Authorization authorization
    ) {
    }

    private record RetainedFixture(
        List<LearnedRuntimeAuthority.PatternRoot> patternRoots,
        LearnedRuntimeAuthority.ProgramRoot programRoot,
        List<LearnedPatternRuleAuthorizationService.Authorization> leaves,
        List<RewriteRule> rules,
        LearnedRewriteProgramAuthorizationService.Authorization
            programAuthorization
    ) {
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void set(Instant value) {
            current = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("fixture clock is UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    /** Avoids compiling the test against an API that does not exist yet. */
    private static final class LearnedRuntimeAuthorityIdentity {
        private static final Map<String, Object> EXPECTED_EMPTY_IDENTITY =
            Map.of(
                "schema", "regelsuche.learned-runtime-authority-snapshot/v1",
                "repositoryRevision", "",
                "setupWorkRevision",
                    "regelsuche.learned-runtime-authority-revalidation-work/v1",
                "patterns", List.of(),
                "programs", List.of());
    }
}
