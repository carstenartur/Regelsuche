package de.regelsuche.evolution;

import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.hash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireHash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireRevision;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireText;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Temporal and identity manifest around self-verifying learned-rule evidence.
 * It deliberately contains no PASS field: qualification comes from the native
 * artifacts referenced by their content hashes.
 */
public record LearnedPatternAuthorizationBundle(
    String schema,
    String genomeHash,
    String geneId,
    String repositoryRevision,
    Instant issuedAt,
    Instant expiresAt,
    String splitManifestHash,
    String validationSelectionHash,
    String finalTestEvaluationHash,
    String counterexampleEvidenceHash,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.learned-pattern-rule-authorization-bundle/v1";

    public LearnedPatternAuthorizationBundle {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported learned-rule authorization bundle schema");
        }
        requireHash(genomeHash, "genomeHash");
        requireText(geneId, "geneId");
        requireRevision(repositoryRevision, "repositoryRevision");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!issuedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                "authorization bundle expiresAt must be after issuedAt");
        }
        requireHash(splitManifestHash, "splitManifestHash");
        requireHash(validationSelectionHash, "validationSelectionHash");
        requireHash(finalTestEvaluationHash, "finalTestEvaluationHash");
        requireHash(counterexampleEvidenceHash, "counterexampleEvidenceHash");
        requireHash(contentHash, "contentHash");
        String expected = hash(payload(
            schema,
            genomeHash,
            geneId,
            repositoryRevision,
            issuedAt,
            expiresAt,
            splitManifestHash,
            validationSelectionHash,
            finalTestEvaluationHash,
            counterexampleEvidenceHash));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "authorization bundle contentHash mismatch");
        }
    }

    public static LearnedPatternAuthorizationBundle create(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision,
        Instant issuedAt,
        Instant expiresAt,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation holdout,
        LearnedPatternCounterexampleEvidence counterexample
    ) {
        Objects.requireNonNull(genome, "genome");
        requireText(geneId, "geneId");
        requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(holdout, "holdout");
        Objects.requireNonNull(counterexample, "counterexample");
        String contentHash = hash(payload(
            SCHEMA,
            genome.contentHash(),
            geneId,
            repositoryRevision,
            issuedAt,
            expiresAt,
            split.contentHash(),
            validation.contentHash(),
            holdout.contentHash(),
            counterexample.contentHash()));
        return new LearnedPatternAuthorizationBundle(
            SCHEMA,
            genome.contentHash(),
            geneId,
            repositoryRevision,
            issuedAt,
            expiresAt,
            split.contentHash(),
            validation.contentHash(),
            holdout.contentHash(),
            counterexample.contentHash(),
            contentHash);
    }

    public static LearnedPatternAuthorizationBundle fromCanonicalJson(
        String json
    ) {
        return LearnedPatternAuthorizationJson.read(
            json, LearnedPatternAuthorizationBundle.class,
            "authorization bundle");
    }

    public String toCanonicalJson() {
        return LearnedPatternAuthorizationJson.write(this);
    }

    public void requireUsableAt(
        Instant asOf,
        String expectedGenomeHash,
        String expectedGeneId,
        String expectedRepositoryRevision
    ) {
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isBefore(issuedAt) || !asOf.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                "authorization evidence bundle is not valid at " + asOf);
        }
        if (!genomeHash.equals(expectedGenomeHash)
                || !geneId.equals(expectedGeneId)
                || !repositoryRevision.equals(expectedRepositoryRevision)) {
            throw new IllegalArgumentException(
                "authorization evidence bundle subject/revision mismatch");
        }
    }

    private static Map<String, Object> payload(
        String schema,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant issuedAt,
        Instant expiresAt,
        String splitManifestHash,
        String validationSelectionHash,
        String finalTestEvaluationHash,
        String counterexampleEvidenceHash
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("counterexampleEvidenceHash", counterexampleEvidenceHash);
        value.put("expiresAt", expiresAt);
        value.put("finalTestEvaluationHash", finalTestEvaluationHash);
        value.put("geneId", geneId);
        value.put("genomeHash", genomeHash);
        value.put("issuedAt", issuedAt);
        value.put("repositoryRevision", repositoryRevision);
        value.put("schema", schema);
        value.put("splitManifestHash", splitManifestHash);
        value.put("validationSelectionHash", validationSelectionHash);
        return value;
    }
}
