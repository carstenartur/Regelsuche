package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Public hash-only view of one private held-out reveal bundle.
 *
 * <p>This artifact can be committed beside the public held-out commitment. It
 * contains no expression or assumption text and binds the exact split-manifest
 * references to the private reveal-bundle root.</p>
 */
public record EvolutionRewriteProgramHeldOutSplitReferences(
    String schema,
    String studyId,
    Split split,
    String revealBundleHash,
    List<EvolutionSplitManifest.CaseReference> cases,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-held-out-split-references/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramHeldOutSplitReferences {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported held-out split-reference schema");
        }
        requireId(studyId, "studyId");
        Objects.requireNonNull(split, "split");
        EvolutionGenome.requireSha256(revealBundleHash, "revealBundleHash");
        cases = canonicalCases(cases);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            split,
            revealBundleHash,
            cases,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "held-out split-reference contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramHeldOutSplitReferences create(
        EvolutionRewriteProgramHeldOutRevealBundle bundle
    ) {
        Objects.requireNonNull(bundle, "bundle");
        List<EvolutionSplitManifest.CaseReference> references =
            canonicalCases(bundle.splitReferences());
        String hash = EvolutionGenome.hash(render(
            bundle.studyId(),
            bundle.split(),
            bundle.contentHash(),
            references,
            null));
        return new EvolutionRewriteProgramHeldOutSplitReferences(
            SCHEMA,
            bundle.studyId(),
            bundle.split(),
            bundle.contentHash(),
            references,
            hash);
    }

    public void requireMatches(
        EvolutionRewriteProgramHeldOutRevealBundle bundle
    ) {
        Objects.requireNonNull(bundle, "bundle");
        EvolutionRewriteProgramHeldOutSplitReferences actual = create(bundle);
        if (!equals(actual)) {
            throw new IllegalArgumentException(
                "held-out split references do not match reveal bundle");
        }
    }

    public void requireMatches(EvolutionSplitManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (!studyId.equals(manifest.studyId())) {
            throw new IllegalArgumentException(
                "held-out split references use another study");
        }
        List<EvolutionSplitManifest.CaseReference> expected = switch (split) {
            case VALIDATION -> manifest.validationCases();
            case FINAL_TEST -> manifest.finalTestCases();
        };
        if (!cases.equals(canonicalCases(expected))) {
            throw new IllegalArgumentException(
                "held-out split references differ from split manifest");
        }
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            split,
            revealBundleHash,
            cases,
            contentHash);
    }

    private static List<EvolutionSplitManifest.CaseReference> canonicalCases(
        List<EvolutionSplitManifest.CaseReference> values
    ) {
        Objects.requireNonNull(values, "cases");
        List<EvolutionSplitManifest.CaseReference> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "case reference"))
            .sorted(Comparator.comparing(
                EvolutionSplitManifest.CaseReference::caseId))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "held-out split references must contain cases");
        }
        requireUnique(result, "case ID",
            EvolutionSplitManifest.CaseReference::caseId);
        requireUnique(result, "input identity",
            EvolutionSplitManifest.CaseReference::inputHash);
        requireUnique(result, "hidden-target identity",
            EvolutionSplitManifest.CaseReference::hiddenTargetHash);
        requireUnique(result, "exact signature",
            EvolutionSplitManifest.CaseReference::exactSignatureHash);
        requireUnique(result, "alpha signature",
            EvolutionSplitManifest.CaseReference::alphaSignatureHash);
        return List.copyOf(result);
    }

    private static void requireUnique(
        List<EvolutionSplitManifest.CaseReference> cases,
        String label,
        java.util.function.Function<
            EvolutionSplitManifest.CaseReference,
            String> identity
    ) {
        if (new HashSet<>(cases.stream().map(identity).toList()).size()
                != cases.size()) {
            throw new IllegalArgumentException(
                "held-out split references contain duplicate " + label);
        }
    }

    private static String render(
        String studyId,
        Split split,
        String revealBundleHash,
        List<EvolutionSplitManifest.CaseReference> cases,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("split", split.name())
            .property("revealBundleHash", revealBundleHash)
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyId", item.familyId())
                    .property("exactSignatureHash",
                        item.exactSignatureHash())
                    .property("alphaSignatureHash",
                        item.alphaSignatureHash())
                    .property("inputHash", item.inputHash())
                    .property("hiddenTargetHash",
                        item.hiddenTargetHash()))));
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
}
