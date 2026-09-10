package de.regelsuche.evolution;

import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.hash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireHash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireRevision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Content-addressed production authorization for one canonical learned program. */
public record LearnedRewriteProgramAuthorizationReceipt(
    String schema,
    String authorizerId,
    String candidateHash,
    String genomeHash,
    String genomeAlphaStructuralHash,
    String planHash,
    String planAlphaStructuralHash,
    String repositoryRevision,
    Instant authorizedAt,
    Instant validUntil,
    String replayEvidenceHash,
    String applicabilitySemantics,
    Map<String, String> leafAuthorizationHashes,
    Map<String, String> leafApplicabilitySchemaHashes,
    List<String> workRevisions,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.learned-rewrite-program-authorization-receipt/v1";
    public static final String AUTHORIZER_ID =
        "regelsuche.learned-rewrite-program-authorizer/v1";
    public static final String APPLICABILITY_SEMANTICS =
        "CANONICAL_PROGRAM_RETURNS_AT_LEAST_ONE_CANDIDATE/v1";

    public LearnedRewriteProgramAuthorizationReceipt {
        if (!SCHEMA.equals(schema) || !AUTHORIZER_ID.equals(authorizerId)) {
            throw new IllegalArgumentException(
                "learned rewrite-program authorization identity is invalid");
        }
        for (String value : List.of(
                candidateHash,
                genomeHash,
                genomeAlphaStructuralHash,
                planHash,
                planAlphaStructuralHash,
                replayEvidenceHash,
                contentHash)) {
            requireHash(value, "rewrite-program authorization hash");
        }
        requireRevision(repositoryRevision, "repositoryRevision");
        authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt");
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (!authorizedAt.isBefore(validUntil)) {
            throw new IllegalArgumentException(
                "rewrite-program authorization must expire after authorizedAt");
        }
        if (!APPLICABILITY_SEMANTICS.equals(applicabilitySemantics)) {
            throw new IllegalArgumentException(
                "unsupported learned rewrite-program applicability semantics");
        }
        leafAuthorizationHashes = canonicalLeafHashes(
            leafAuthorizationHashes, "leafAuthorizationHashes");
        leafApplicabilitySchemaHashes = canonicalLeafHashes(
            leafApplicabilitySchemaHashes, "leafApplicabilitySchemaHashes");
        if (!leafAuthorizationHashes.keySet().equals(
                leafApplicabilitySchemaHashes.keySet())) {
            throw new IllegalArgumentException(
                "leaf authorization and applicability-schema subjects differ");
        }
        workRevisions = canonicalWorkRevisions(workRevisions);
        String expected = hash(payload(
            schema,
            authorizerId,
            candidateHash,
            genomeHash,
            genomeAlphaStructuralHash,
            planHash,
            planAlphaStructuralHash,
            repositoryRevision,
            authorizedAt,
            validUntil,
            replayEvidenceHash,
            applicabilitySemantics,
            leafAuthorizationHashes,
            leafApplicabilitySchemaHashes,
            workRevisions));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program authorization receipt contentHash mismatch");
        }
    }

    static LearnedRewriteProgramAuthorizationReceipt create(
        EvolutionRewriteProgramCandidate candidate,
        String repositoryRevision,
        Instant authorizedAt,
        Instant validUntil,
        LearnedRewriteProgramReplayEvidence replayEvidence,
        Map<String, String> leafAuthorizationHashes,
        Map<String, String> leafApplicabilitySchemaHashes
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(replayEvidence, "replayEvidence");
        replayEvidence.requireCandidate(candidate);
        Map<String, String> leaves = canonicalLeafHashes(
            leafAuthorizationHashes, "leafAuthorizationHashes");
        Map<String, String> applicability = canonicalLeafHashes(
            leafApplicabilitySchemaHashes, "leafApplicabilitySchemaHashes");
        if (!leaves.keySet().equals(applicability.keySet())) {
            throw new IllegalArgumentException(
                "leaf authorization and applicability-schema subjects differ");
        }
        List<String> revisions = canonicalWorkRevisions(
            replayEvidence.cases().stream()
                .map(LearnedRewriteProgramReplayEvidence.ReplayCase::workRevision)
                .distinct()
                .toList());
        Map<String, Object> payload = payload(
            SCHEMA,
            AUTHORIZER_ID,
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.genome().alphaStructuralHash(),
            candidate.plan().contentHash(),
            candidate.plan().alphaStructuralHash(),
            repositoryRevision,
            authorizedAt,
            validUntil,
            replayEvidence.contentHash(),
            APPLICABILITY_SEMANTICS,
            leaves,
            applicability,
            revisions);
        return new LearnedRewriteProgramAuthorizationReceipt(
            SCHEMA,
            AUTHORIZER_ID,
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.genome().alphaStructuralHash(),
            candidate.plan().contentHash(),
            candidate.plan().alphaStructuralHash(),
            repositoryRevision,
            authorizedAt,
            validUntil,
            replayEvidence.contentHash(),
            APPLICABILITY_SEMANTICS,
            leaves,
            applicability,
            revisions,
            hash(payload));
    }

    public static LearnedRewriteProgramAuthorizationReceipt fromCanonicalJson(
        String json
    ) {
        return LearnedPatternAuthorizationJson.read(
            json,
            LearnedRewriteProgramAuthorizationReceipt.class,
            "learned rewrite-program authorization receipt");
    }

    public String toCanonicalJson() {
        return LearnedPatternAuthorizationJson.write(this);
    }

    public void requireUsableAt(
        Instant asOf,
        String expectedRepositoryRevision,
        EvolutionRewriteProgramCandidate candidate
    ) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(candidate, "candidate");
        if (asOf.isBefore(authorizedAt) || !asOf.isBefore(validUntil)) {
            throw new IllegalArgumentException(
                "learned rewrite-program authorization is not valid at " + asOf);
        }
        if (!repositoryRevision.equals(expectedRepositoryRevision)
                || !candidateHash.equals(candidate.contentHash())
                || !genomeHash.equals(candidate.genome().contentHash())
                || !genomeAlphaStructuralHash.equals(
                    candidate.genome().alphaStructuralHash())
                || !planHash.equals(candidate.plan().contentHash())
                || !planAlphaStructuralHash.equals(
                    candidate.plan().alphaStructuralHash())) {
            throw new IllegalArgumentException(
                "learned rewrite-program authorization identity mismatch");
        }
    }

    private static Map<String, String> canonicalLeafHashes(
        Map<String, String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program authorization requires " + field);
        }
        TreeMap<String, String> retained = new TreeMap<>();
        values.forEach((geneId, value) -> {
            LearnedPatternAuthorizationJson.requireText(geneId, "leaf geneId");
            requireHash(value, field + " hash");
            if (retained.put(geneId, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate leaf subject in " + field + ": " + geneId);
            }
        });
        return Map.copyOf(retained);
    }

    private static List<String> canonicalWorkRevisions(List<String> values) {
        Objects.requireNonNull(values, "workRevisions");
        List<String> retained = values.stream()
            .map(value -> LearnedPatternAuthorizationJson.requireText(
                value, "workRevision"))
            .distinct()
            .sorted()
            .toList();
        if (retained.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program authorization requires a work revision");
        }
        return retained;
    }

    private static Map<String, Object> payload(
        String schema,
        String authorizerId,
        String candidateHash,
        String genomeHash,
        String genomeAlphaStructuralHash,
        String planHash,
        String planAlphaStructuralHash,
        String repositoryRevision,
        Instant authorizedAt,
        Instant validUntil,
        String replayEvidenceHash,
        String applicabilitySemantics,
        Map<String, String> leafAuthorizationHashes,
        Map<String, String> leafApplicabilitySchemaHashes,
        List<String> workRevisions
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("applicabilitySemantics", applicabilitySemantics);
        value.put("authorizedAt", authorizedAt);
        value.put("authorizerId", authorizerId);
        value.put("candidateHash", candidateHash);
        value.put("genomeAlphaStructuralHash", genomeAlphaStructuralHash);
        value.put("genomeHash", genomeHash);
        value.put("leafApplicabilitySchemaHashes", leafApplicabilitySchemaHashes);
        value.put("leafAuthorizationHashes", leafAuthorizationHashes);
        value.put("planAlphaStructuralHash", planAlphaStructuralHash);
        value.put("planHash", planHash);
        value.put("replayEvidenceHash", replayEvidenceHash);
        value.put("repositoryRevision", repositoryRevision);
        value.put("schema", schema);
        value.put("validUntil", validUntil);
        value.put("workRevisions", workRevisions);
        return value;
    }
}
