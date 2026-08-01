package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed commitment to one concrete held-out rewrite-program suite.
 *
 * <p>The commitment freezes case identities and hashes before evaluated search
 * without exposing the concrete target, assumptions, difficulty annotation or
 * expected terminal class. A later reveal must be verified against both the
 * per-case reveal-entry hashes and {@link #sealedRevealHash()} before any held-
 * out evaluator may inspect it.</p>
 */
public record EvolutionRewriteProgramHeldOutCommitment(
    String schema,
    String studyId,
    Split split,
    List<CaseCommitment> cases,
    String sealedRevealHash,
    RevealPolicy revealPolicy,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-held-out-commitment/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramHeldOutCommitment {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program held-out commitment schema");
        }
        requireId(studyId, "studyId");
        Objects.requireNonNull(split, "split");
        cases = canonicalCases(cases);
        EvolutionGenome.requireSha256(sealedRevealHash, "sealedRevealHash");
        Objects.requireNonNull(revealPolicy, "revealPolicy");
        if (revealPolicy != split.requiredRevealPolicy()) {
            throw new IllegalArgumentException(
                "held-out reveal policy does not match split");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            split,
            cases,
            sealedRevealHash,
            revealPolicy,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "held-out commitment contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramHeldOutCommitment create(
        String studyId,
        Split split,
        List<CaseCommitment> cases,
        String sealedRevealHash
    ) {
        requireId(studyId, "studyId");
        Objects.requireNonNull(split, "split");
        List<CaseCommitment> canonical = canonicalCases(cases);
        EvolutionGenome.requireSha256(sealedRevealHash, "sealedRevealHash");
        RevealPolicy revealPolicy = split.requiredRevealPolicy();
        String hash = EvolutionGenome.hash(render(
            studyId,
            split,
            canonical,
            sealedRevealHash,
            revealPolicy,
            null));
        return new EvolutionRewriteProgramHeldOutCommitment(
            SCHEMA,
            studyId,
            split,
            canonical,
            sealedRevealHash,
            revealPolicy,
            hash);
    }

    /**
     * Verifies that this commitment is exactly the corresponding split surface
     * from the already-frozen split manifest.
     */
    public void requireMatches(EvolutionSplitManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (!studyId.equals(manifest.studyId())) {
            throw new IllegalArgumentException(
                "held-out commitment studyId differs from split manifest");
        }
        List<EvolutionSplitManifest.CaseReference> references = switch (split) {
            case VALIDATION -> manifest.validationCases();
            case FINAL_TEST -> manifest.finalTestCases();
        };
        if (references.size() != cases.size()) {
            throw new IllegalArgumentException(
                "held-out commitment case count differs from split manifest");
        }
        Map<String, EvolutionSplitManifest.CaseReference> byId = new HashMap<>();
        references.forEach(reference -> byId.put(reference.caseId(), reference));
        for (CaseCommitment commitment : cases) {
            EvolutionSplitManifest.CaseReference reference =
                byId.get(commitment.caseId());
            if (reference == null) {
                throw new IllegalArgumentException(
                    "held-out case is absent from split manifest: "
                        + commitment.caseId());
            }
            String expectedFamily = familyCommitment(reference.familyId());
            if (!expectedFamily.equals(commitment.familyCommitmentHash())
                    || !reference.inputHash().equals(commitment.inputHash())
                    || !reference.hiddenTargetHash().equals(
                        commitment.targetHash())
                    || !reference.exactSignatureHash().equals(
                        commitment.exactSignatureHash())
                    || !reference.alphaSignatureHash().equals(
                        commitment.alphaSignatureHash())) {
                throw new IllegalArgumentException(
                    "held-out case identity differs from split manifest: "
                        + commitment.caseId());
            }
        }
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            split,
            cases,
            sealedRevealHash,
            revealPolicy,
            contentHash);
    }

    public static String familyCommitment(String familyId) {
        requireId(familyId, "familyId");
        return EvolutionGenome.hash(
            "regelsuche.evolution-family-commitment/v1\nfamilyId=" + familyId);
    }

    private static List<CaseCommitment> canonicalCases(
        List<CaseCommitment> values
    ) {
        Objects.requireNonNull(values, "cases");
        List<CaseCommitment> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "case commitment"))
            .sorted(Comparator.comparing(CaseCommitment::caseId))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "held-out commitment must contain cases");
        }
        requireUnique(result, "case ID", CaseCommitment::caseId);
        requireUnique(result, "input hash", CaseCommitment::inputHash);
        requireUnique(result, "target hash", CaseCommitment::targetHash);
        requireUnique(
            result, "exact signature", CaseCommitment::exactSignatureHash);
        requireUnique(
            result, "alpha signature", CaseCommitment::alphaSignatureHash);
        requireUnique(
            result, "reveal entry", CaseCommitment::revealEntryHash);
        return List.copyOf(result);
    }

    private static void requireUnique(
        List<CaseCommitment> cases,
        String label,
        java.util.function.Function<CaseCommitment, String> identity
    ) {
        List<String> values = cases.stream().map(identity).toList();
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                "held-out commitment contains duplicate " + label);
        }
    }

    private static String render(
        String studyId,
        Split split,
        List<CaseCommitment> cases,
        String sealedRevealHash,
        RevealPolicy revealPolicy,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("split", split.name())
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyCommitmentHash",
                        item.familyCommitmentHash())
                    .property("inputHash", item.inputHash())
                    .property("targetHash", item.targetHash())
                    .property("assumptionsHash", item.assumptionsHash())
                    .property("exactSignatureHash",
                        item.exactSignatureHash())
                    .property("alphaSignatureHash",
                        item.alphaSignatureHash())
                    .property("difficultyTierHash",
                        item.difficultyTierHash())
                    .property("expectedTerminalClassHash",
                        item.expectedTerminalClassHash())
                    .property("revealEntryHash", item.revealEntryHash()))))
            .property("sealedRevealHash", sealedRevealHash)
            .property("revealPolicy", revealPolicy.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }

    public enum Split {
        VALIDATION,
        FINAL_TEST;

        RevealPolicy requiredRevealPolicy() {
            return switch (this) {
                case VALIDATION ->
                    RevealPolicy.AFTER_TRAIN_POPULATION_COMPLETE;
                case FINAL_TEST -> RevealPolicy
                    .ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION;
            };
        }
    }

    public enum RevealPolicy {
        AFTER_TRAIN_POPULATION_COMPLETE,
        ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION
    }

    public record CaseCommitment(
        String caseId,
        String familyCommitmentHash,
        String inputHash,
        String targetHash,
        String assumptionsHash,
        String exactSignatureHash,
        String alphaSignatureHash,
        String difficultyTierHash,
        String expectedTerminalClassHash,
        String revealEntryHash
    ) {
        public CaseCommitment {
            requireId(caseId, "caseId");
            EvolutionGenome.requireSha256(
                familyCommitmentHash, "familyCommitmentHash");
            EvolutionGenome.requireSha256(inputHash, "inputHash");
            EvolutionGenome.requireSha256(targetHash, "targetHash");
            EvolutionGenome.requireSha256(
                assumptionsHash, "assumptionsHash");
            EvolutionGenome.requireSha256(
                exactSignatureHash, "exactSignatureHash");
            EvolutionGenome.requireSha256(
                alphaSignatureHash, "alphaSignatureHash");
            EvolutionGenome.requireSha256(
                difficultyTierHash, "difficultyTierHash");
            EvolutionGenome.requireSha256(
                expectedTerminalClassHash,
                "expectedTerminalClassHash");
            EvolutionGenome.requireSha256(
                revealEntryHash, "revealEntryHash");
        }
    }
}
