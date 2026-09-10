package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearnedPatternRuleAuthorizationServiceTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_REVISION =
        "89abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_A = "sha256:" + "1".repeat(64);
    private static final String HASH_B = "sha256:" + "2".repeat(64);
    private static final String HASH_C = "sha256:" + "3".repeat(64);
    private static final String HASH_D = "sha256:" + "4".repeat(64);
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void authorizesOnlyAfterAllFourCurrentRootsMatchTheExactSubject(
        @TempDir Path temporary
    ) throws IOException {
        EvolutionGenome genome = genome();
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternRuleAuthorizationService.EvidenceFiles files =
            writeEvidence(temporary, genome, REVISION, EXPIRES);

        LearnedPatternRuleAuthorizationService.Authorization authorization =
            service.authorize(
                genome,
                "difference-squares",
                REVISION,
                files,
                AS_OF);

        assertTrue(authorization.promotion().proof().proved());
        assertEquals(4, authorization.evidenceRoots().size());
        assertEquals(EXPIRES, authorization.receipt().validUntil());
        assertEquals(
            authorization.promotion().receipt().contentHash(),
            authorization.receipt().promotionReceiptHash());
        assertEquals(
            authorization.evidenceRoots().get(
                LearnedPatternRuleAuthorizationService.EvidenceRole.HOLDOUT_EVALUATION)
                .contentHash(),
            authorization.promotion().receipt().holdoutEvaluationHash());
        assertTrue(authorization.receipt().toCanonicalJson()
            .contains(authorization.receipt().contentHash()));
        authorization.receipt().requireUsableAt(
            AS_OF,
            REVISION,
            authorization.receipt().promotedRuleHash());
    }

    @Test
    void rejectsExpiredFutureAndWrongRevisionEvidence(@TempDir Path temporary)
            throws IOException {
        EvolutionGenome genome = genome();
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();

        var expired = writeEvidence(
            temporary.resolve("expired"),
            genome,
            REVISION,
            Instant.parse("2026-09-09T23:59:59Z"));
        IllegalArgumentException expiredFailure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, expired, AS_OF));
        assertTrue(expiredFailure.getMessage().contains("expired"));

        var futureDirectory = temporary.resolve("future");
        Files.createDirectories(futureDirectory);
        var future = writeEvidence(
            futureDirectory,
            genome,
            REVISION,
            EXPIRES,
            Instant.parse("2026-09-11T00:00:00Z"));
        IllegalArgumentException futureFailure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, future, AS_OF));
        assertTrue(futureFailure.getMessage().contains("not yet valid"));

        var wrongRevision = writeEvidence(
            temporary.resolve("revision"), genome, OTHER_REVISION, EXPIRES);
        IllegalArgumentException revisionFailure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, wrongRevision, AS_OF));
        assertTrue(revisionFailure.getMessage().contains("revision mismatch"));
    }

    @Test
    void rejectsSubjectRoleStatusAndContentHashMismatches(@TempDir Path temporary)
            throws IOException {
        EvolutionGenome genome = genome();
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        Path root = temporary.resolve("roots");
        Files.createDirectories(root);
        LearnedPatternRuleAuthorizationService.EvidenceFiles files =
            writeEvidence(root, genome, REVISION, EXPIRES);

        Path holdout = files.holdoutEvaluation();
        String original = Files.readString(holdout, StandardCharsets.UTF_8);
        Files.writeString(
            holdout,
            original.replace(
                "\"geneId\":\"difference-squares\"",
                "\"geneId\":\"another-gene\""),
            StandardCharsets.UTF_8);
        IllegalArgumentException subjectFailure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, files, AS_OF));
        assertTrue(subjectFailure.getMessage().contains("contentHash mismatch")
            || subjectFailure.getMessage().contains("subject mismatch"));

        Files.writeString(holdout, original, StandardCharsets.UTF_8);
        Path semantic = files.semanticValidation();
        String semanticOriginal = Files.readString(semantic, StandardCharsets.UTF_8);
        Files.writeString(
            semantic,
            semanticOriginal.replace(
                "\"role\":\"SEMANTIC_VALIDATION\"",
                "\"role\":\"HOLDOUT_EVALUATION\""),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, files, AS_OF));

        Files.writeString(semantic, semanticOriginal, StandardCharsets.UTF_8);
        Files.writeString(
            semantic,
            semanticOriginal.replace(
                "\"status\":\"PASSED\"",
                "\"status\":\"FAILED\""),
            StandardCharsets.UTF_8);
        IllegalArgumentException statusFailure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, files, AS_OF));
        assertTrue(statusFailure.getMessage().contains("non-passing"));
    }

    @Test
    void rejectsMissingSymlinkedAndDuplicateKeyRoots(@TempDir Path temporary)
            throws IOException {
        EvolutionGenome genome = genome();
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        var files = writeEvidence(temporary.resolve("base"), genome, REVISION, EXPIRES);

        Files.delete(files.leakageAudit());
        assertThrows(IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, files, AS_OF));

        var duplicateFiles = writeEvidence(
            temporary.resolve("duplicate"), genome, REVISION, EXPIRES);
        Path semantic = duplicateFiles.semanticValidation();
        String value = Files.readString(semantic, StandardCharsets.UTF_8);
        Files.writeString(
            semantic,
            value.replaceFirst(
                "\\{",
                "{\"schema\":\""
                    + LearnedPatternRuleAuthorizationService.EVIDENCE_ROOT_SCHEMA
                    + "\","),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> service.authorize(
                genome, "difference-squares", REVISION, duplicateFiles, AS_OF));
    }

    @Test
    void authorizationReceiptExpiresAndIsBoundToRuleAndRevision(
        @TempDir Path temporary
    ) throws IOException {
        EvolutionGenome genome = genome();
        LearnedPatternRuleAuthorizationService.Authorization authorization =
            new LearnedPatternRuleAuthorizationService().authorize(
                genome,
                "difference-squares",
                REVISION,
                writeEvidence(temporary, genome, REVISION, EXPIRES),
                AS_OF);

        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                EXPIRES,
                REVISION,
                authorization.receipt().promotedRuleHash()));
        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                AS_OF,
                OTHER_REVISION,
                authorization.receipt().promotedRuleHash()));
        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                AS_OF,
                REVISION,
                HASH_D));
    }

    private static LearnedPatternRuleAuthorizationService.EvidenceFiles writeEvidence(
        Path directory,
        EvolutionGenome genome,
        String revision,
        Instant expiresAt
    ) throws IOException {
        return writeEvidence(directory, genome, revision, expiresAt, ISSUED);
    }

    private static LearnedPatternRuleAuthorizationService.EvidenceFiles writeEvidence(
        Path directory,
        EvolutionGenome genome,
        String revision,
        Instant expiresAt,
        Instant issuedAt
    ) throws IOException {
        Files.createDirectories(directory);
        Path semantic = directory.resolve("semantic-validation.json");
        Path counterexample = directory.resolve("counterexample-search.json");
        Path holdout = directory.resolve("holdout-evaluation.json");
        Path leakage = directory.resolve("leakage-audit.json");
        writeRoot(semantic,
            LearnedPatternRuleAuthorizationService.EvidenceRole.SEMANTIC_VALIDATION,
            genome, revision, issuedAt, expiresAt, HASH_A);
        writeRoot(counterexample,
            LearnedPatternRuleAuthorizationService.EvidenceRole.COUNTEREXAMPLE_SEARCH,
            genome, revision, issuedAt, expiresAt, HASH_B);
        writeRoot(holdout,
            LearnedPatternRuleAuthorizationService.EvidenceRole.HOLDOUT_EVALUATION,
            genome, revision, issuedAt, expiresAt, HASH_C);
        writeRoot(leakage,
            LearnedPatternRuleAuthorizationService.EvidenceRole.LEAKAGE_AUDIT,
            genome, revision, issuedAt, expiresAt, HASH_D);
        return new LearnedPatternRuleAuthorizationService.EvidenceFiles(
            semantic, counterexample, holdout, leakage);
    }

    private static void writeRoot(
        Path file,
        LearnedPatternRuleAuthorizationService.EvidenceRole role,
        EvolutionGenome genome,
        String revision,
        Instant issuedAt,
        Instant expiresAt,
        String artifactHash
    ) throws IOException {
        var root = LearnedPatternRuleAuthorizationService.EvidenceRoot.create(
            role,
            genome.contentHash(),
            "difference-squares",
            revision,
            issuedAt,
            expiresAt,
            artifactHash);
        Files.writeString(file, root.toCanonicalJson(), StandardCharsets.UTF_8);
    }

    private static EvolutionGenome genome() {
        return EvolutionGenome.create(
            EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            new EvolutionGenome.TrainingScope(
                EvolutionGenome.SourceSplit.TRAIN,
                HASH_A,
                HASH_B,
                HASH_C,
                HASH_D),
            List.of(new EvolutionGenome.RewriteGene(
                "difference-squares",
                "?A^2-?B^2",
                "(?A-?B)*(?A+?B)",
                RewriteKind.FACTOR,
                true,
                -1,
                4,
                8,
                List.of(),
                List.of(
                    EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
                    EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                    EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
                    EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION))),
            List.of(new EvolutionGenome.FeatureWeight(
                EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                1_000)),
            EvolutionGenome.GuardPolicy.strictDefault(),
            EvolutionGenome.ResourceBudget.conservativeDefault(),
            List.of("symbolic-polynomial"),
            List.of());
    }
}
