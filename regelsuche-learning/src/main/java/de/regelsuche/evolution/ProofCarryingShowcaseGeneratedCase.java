package de.regelsuche.evolution;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** One deterministic, content-addressed future showcase case. */
public record ProofCarryingShowcaseGeneratedCase(
    String schema,
    String caseId,
    String familyId,
    int difficultyLevel,
    int variant,
    String inputExpression,
    String targetExpression,
    List<String> assumptions,
    List<Integer> coefficientVector,
    List<String> blockKinds,
    String structuralFingerprint,
    String caseIdentityPolicy,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-generated-case/v1";
    private static final Pattern CASE_ID = Pattern.compile(
        "ft-(nrc|fcc|mrp)-d[3-6]-v[01]-[0-9a-f]{12}");
    private static final Set<String> BLOCK_KINDS = Set.of(
        "SHARED_DENOMINATOR_QUOTIENT",
        "FACTOR_CANCEL_SHARED_DENOMINATOR",
        "MIXED_DENOMINATOR_RATIO",
        "DIFFERENCE_OF_SQUARES_QUOTIENT");

    public ProofCarryingShowcaseGeneratedCase {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported generated showcase-case schema");
        }
        if (caseId == null || !CASE_ID.matcher(caseId).matches()) {
            throw new IllegalArgumentException(
                "invalid generated showcase caseId");
        }
        familyId = ProofCarryingShowcaseJsonSupport.requireText(
            familyId, "familyId");
        if (!ProofCarryingShowcasePlan.FAMILIES.contains(familyId)
                || difficultyLevel < 3
                || difficultyLevel > 6
                || (variant != 0 && variant != 1)) {
            throw new IllegalArgumentException(
                "generated showcase case structure is out of range");
        }
        inputExpression = ProofCarryingShowcaseJsonSupport.requireText(
            inputExpression, "inputExpression");
        targetExpression = ProofCarryingShowcaseJsonSupport.requireText(
            targetExpression, "targetExpression");
        if (inputExpression.length() > 20_000
                || targetExpression.length() > 20_000) {
            throw new IllegalArgumentException(
                "generated showcase expression is too large");
        }
        assumptions = ProofCarryingShowcaseJsonSupport
            .immutableStrings(
                assumptions,
                "assumptions",
                true,
                false);
        coefficientVector = ProofCarryingShowcaseJsonSupport
            .immutableIntegers(
                coefficientVector,
                "coefficientVector");
        if (coefficientVector.size() != difficultyLevel
                || coefficientVector.stream().anyMatch(
                    value -> value < 2 || value > 24)) {
            throw new IllegalArgumentException(
                "generated coefficient vector differs from difficulty");
        }
        blockKinds = ProofCarryingShowcaseJsonSupport
            .immutableStringList(
                blockKinds,
                "blockKinds",
                true);
        if (blockKinds.size() != difficultyLevel
                || blockKinds.stream().anyMatch(
                    kind -> !BLOCK_KINDS.contains(kind))) {
            throw new IllegalArgumentException(
                "generated block topology differs from difficulty");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            structuralFingerprint,
            "structuralFingerprint");
        String expectedStructure = structureHash(
            familyId,
            difficultyLevel,
            variant,
            coefficientVector,
            blockKinds);
        if (!expectedStructure.equals(structuralFingerprint)) {
            throw new IllegalArgumentException(
                "generated structural fingerprint mismatch");
        }
        if (!ProofCarryingShowcasePlan.CASE_IDENTITY.equals(
                caseIdentityPolicy)) {
            throw new IllegalArgumentException(
                "generated case identity policy drift");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                caseId,
                familyId,
                difficultyLevel,
                variant,
                inputExpression,
                targetExpression,
                assumptions,
                coefficientVector,
                blockKinds,
                structuralFingerprint,
                caseIdentityPolicy));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "generated showcase-case contentHash mismatch");
        }
    }

    static ProofCarryingShowcaseGeneratedCase create(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed,
        String familyId,
        int difficultyLevel,
        int variant,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        List<Integer> coefficientVector,
        List<String> blockKinds
    ) {
        seed.requireCompatible(plan);
        List<String> normalizedAssumptions =
            ProofCarryingShowcaseJsonSupport.immutableStrings(
                assumptions, "assumptions", true, false);
        List<Integer> coefficients =
            ProofCarryingShowcaseJsonSupport.immutableIntegers(
                coefficientVector, "coefficientVector");
        List<String> topology =
            ProofCarryingShowcaseJsonSupport.immutableStringList(
                blockKinds, "blockKinds", true);
        String structure = structureHash(
            familyId,
            difficultyLevel,
            variant,
            coefficients,
            topology);
        String caseId = caseId(
            plan,
            seed,
            familyId,
            difficultyLevel,
            variant,
            structure);
        Map<String, Object> payload = payload(
            SCHEMA,
            caseId,
            familyId,
            difficultyLevel,
            variant,
            inputExpression,
            targetExpression,
            normalizedAssumptions,
            coefficients,
            topology,
            structure,
            ProofCarryingShowcasePlan.CASE_IDENTITY);
        return new ProofCarryingShowcaseGeneratedCase(
            SCHEMA,
            caseId,
            familyId,
            difficultyLevel,
            variant,
            inputExpression,
            targetExpression,
            normalizedAssumptions,
            coefficients,
            topology,
            structure,
            ProofCarryingShowcasePlan.CASE_IDENTITY,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseGeneratedCase fromCanonicalJson(
        String json
    ) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseGeneratedCase.class,
            "generated showcase case");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    void requireCompatible(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed
    ) {
        if (!caseId.equals(caseId(
                plan,
                seed,
                familyId,
                difficultyLevel,
                variant,
                structuralFingerprint))) {
            throw new IllegalArgumentException(
                "generated case is not bound to the seed receipt");
        }
    }

    private static String structureHash(
        String familyId,
        int difficultyLevel,
        int variant,
        List<Integer> coefficientVector,
        List<String> blockKinds
    ) {
        return ProofCarryingShowcaseJsonSupport.hashPayload(
            ProofCarryingShowcaseJsonSupport.payload(
                "schema",
                    "regelsuche.proof-carrying-showcase-structure/v1",
                "familyId", familyId,
                "difficultyLevel", difficultyLevel,
                "variant", variant,
                "coefficientVector", coefficientVector,
                "blockKinds", blockKinds));
    }

    private static String caseId(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed,
        String familyId,
        int difficultyLevel,
        int variant,
        String structuralFingerprint
    ) {
        String material = String.join(
            "\n",
            "regelsuche.proof-carrying-showcase-case-id/v1",
            "showcaseId=" + plan.showcaseId(),
            "seedReceiptContentHash=" + seed.contentHash(),
            "familyId=" + familyId,
            "difficultyLevel=" + difficultyLevel,
            "variant=" + variant,
            "structuralFingerprint=" + structuralFingerprint);
        String hash = EvolutionGenome.hash(material)
            .substring("sha256:".length());
        return "ft-" + shortFamily(familyId)
            + "-d" + difficultyLevel
            + "-v" + variant
            + "-" + hash.substring(0, 12);
    }

    private static String shortFamily(String familyId) {
        return switch (familyId) {
            case "nested-rational-cancellation" -> "nrc";
            case "factor-cancel-collect" -> "fcc";
            case "multi-stage-rational-polynomial" -> "mrp";
            default -> throw new IllegalArgumentException(
                "unsupported showcase family " + familyId);
        };
    }

    private static Map<String, Object> payload(
        String schema,
        String caseId,
        String familyId,
        int difficultyLevel,
        int variant,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        List<Integer> coefficientVector,
        List<String> blockKinds,
        String structuralFingerprint,
        String caseIdentityPolicy
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "caseId", caseId,
            "familyId", familyId,
            "difficultyLevel", difficultyLevel,
            "variant", variant,
            "inputExpression", inputExpression,
            "targetExpression", targetExpression,
            "assumptions", assumptions,
            "coefficientVector", coefficientVector,
            "blockKinds", blockKinds,
            "structuralFingerprint", structuralFingerprint,
            "caseIdentityPolicy", caseIdentityPolicy);
    }

}
