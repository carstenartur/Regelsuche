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
    Map<String, String> leafAuthorizationHashes,
    List<String> workRevisions,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.learned-rewrite-program-authorization-receipt/v1";
    public static final String AUTHORIZER_ID =
        "regelsuche.learned-rewrite-program-authorizer/v1";

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
        leafAuthorizationHashes = canonicalLeafHashes(leafAuthorizationHashes);
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
            leafAuthorizationHashes,
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
        Map<String, String> leafAuthorizationHashes
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(replayEvidence, "replayEvidence");
        replayEvidence.requireCandidate(candidate);
        Map<String, String> leaves = canonicalLeafHashes(leafAuthorizationHashes);
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
            leaves,
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
            leaves,
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
        Map<String, String> values
    ) {
        Objects.requireNonNull(values, "leafAuthorizationHashes");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program authorization requires leaf authorizations");
        }
        TreeMap<String, String> retained = new TreeMap<>();
        values.forEach((geneId, value) -> {
            LearnedPatternAuthorizationJson.requireText(geneId, "leaf geneId");
            requireHash(value, "leaf authorization hash");
            if (retained.put(geneId, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate leaf authorization for " + geneId);
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
        Map<String, String> leafAuthorizationHashes,
        List<String> workRevisions
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("authorizedAt", authorizedAt);
        value.put("authorizerId", authorizerId);
        value.put("candidateHash", candidateHash);
        value.put("genomeAlphaStructuralHash", genomeAlphaStructuralHash);
        value.put("genomeHash", genomeHash);
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