package de.regelsuche.evolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Complete deterministic future showcase suite before any execution. */
public record ProofCarryingShowcaseGeneratedFinalTest(
    String schema,
    String showcaseId,
    String planContentHash,
    String candidateFreezeContentHash,
    String seedReceiptContentHash,
    String generatorId,
    String derivedSeed,
    String drandChainHash,
    long drandRound,
    int caseCount,
    List<FamilySummary> familySummaries,
    List<ProofCarryingShowcaseGeneratedCase> cases,
    String caseContentRoot,
    String caseIdentityPolicy,
    String manualReplacementOrPruning,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-generated-final-test/v1";
    public static final String STATUS =
        "FINAL_TEST_GENERATED_NOT_EXECUTED";

    public ProofCarryingShowcaseGeneratedFinalTest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported generated FINAL TEST schema");
        }
        if (!ProofCarryingShowcasePlan.SHOWCASE_ID.equals(showcaseId)) {
            throw new IllegalArgumentException(
                "generated FINAL TEST uses another showcase");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            planContentHash, "planContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateFreezeContentHash,
            "candidateFreezeContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            seedReceiptContentHash,
            "seedReceiptContentHash");
        if (!ProofCarryingShowcasePlan.GENERATOR_ID.equals(generatorId)) {
            throw new IllegalArgumentException(
                "generated FINAL TEST generator identity drift");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            derivedSeed, "derivedSeed");
        ProofCarryingShowcaseJsonSupport.requireHex64(
            drandChainHash, "drandChainHash");
        if (drandRound < 1) {
            throw new IllegalArgumentException(
                "generated FINAL TEST drand round must be positive");
        }
        Objects.requireNonNull(familySummaries, "familySummaries");
        familySummaries = List.copyOf(familySummaries);
        Objects.requireNonNull(cases, "cases");
        cases = List.copyOf(cases);
        if (caseCount != 24
                || cases.size() != caseCount
                || familySummaries.size() != 3) {
            throw new IllegalArgumentException(
                "generated FINAL TEST has the wrong surface size");
        }
        requireExpectedOrder(cases);
        requireUnique(cases, ProofCarryingShowcaseGeneratedCase::caseId,
            "caseId");
        requireUnique(cases,
            ProofCarryingShowcaseGeneratedCase::contentHash,
            "contentHash");
        requireUnique(cases,
            ProofCarryingShowcaseGeneratedCase::inputExpression,
            "inputExpression");
        requireUnique(cases,
            ProofCarryingShowcaseGeneratedCase::structuralFingerprint,
            "structuralFingerprint");
        requireFamilySummaries(familySummaries, cases);
        String expectedRoot = aggregateRoot(
            "regelsuche.proof-carrying-showcase-case-root/v1",
            cases.stream()
                .map(ProofCarryingShowcaseGeneratedCase::contentHash)
                .toList());
        if (!expectedRoot.equals(caseContentRoot)) {
            throw new IllegalArgumentException(
                "generated FINAL TEST case root mismatch");
        }
        if (!ProofCarryingShowcasePlan.CASE_IDENTITY.equals(
                caseIdentityPolicy)
                || !"FORBIDDEN".equals(manualReplacementOrPruning)
                || !STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "generated FINAL TEST policy or status drift");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                showcaseId,
                planContentHash,
                candidateFreezeContentHash,
                seedReceiptContentHash,
                generatorId,
                derivedSeed,
                drandChainHash,
                drandRound,
                caseCount,
                familySummaries,
                cases,
                caseContentRoot,
                caseIdentityPolicy,
                manualReplacementOrPruning,
                status));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "generated FINAL TEST contentHash mismatch");
        }
    }

    static ProofCarryingShowcaseGeneratedFinalTest create(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed,
        List<ProofCarryingShowcaseGeneratedCase> cases
    ) {
        seed.requireCompatible(plan);
        List<ProofCarryingShowcaseGeneratedCase> retained =
            List.copyOf(cases);
        retained.forEach(item -> item.requireCompatible(plan, seed));
        List<FamilySummary> summaries = new ArrayList<>();
        for (String familyId : ProofCarryingShowcasePlan.FAMILIES) {
            List<ProofCarryingShowcaseGeneratedCase> familyCases =
                retained.stream()
                    .filter(item -> familyId.equals(item.familyId()))
                    .toList();
            summaries.add(new FamilySummary(
                familyId,
                familyCases.size(),
                familyCases.stream()
                    .map(ProofCarryingShowcaseGeneratedCase::difficultyLevel)
                    .distinct()
                    .sorted()
                    .toList(),
                aggregateRoot(
                    "regelsuche.proof-carrying-showcase-family-root/v1",
                    familyCases.stream()
                        .map(ProofCarryingShowcaseGeneratedCase::contentHash)
                        .toList())));
        }
        String root = aggregateRoot(
            "regelsuche.proof-carrying-showcase-case-root/v1",
            retained.stream()
                .map(ProofCarryingShowcaseGeneratedCase::contentHash)
                .toList());
        Map<String, Object> payload = payload(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            seed.candidateFreezeContentHash(),
            seed.contentHash(),
            plan.challengeGenerator().generatorId(),
            seed.derivedSeed(),
            seed.drandChainHash(),
            seed.drandRound(),
            retained.size(),
            summaries,
            retained,
            root,
            plan.challengeGenerator().caseIdentity(),
            plan.challengeGenerator().manualReplacementOrPruning(),
            STATUS);
        return new ProofCarryingShowcaseGeneratedFinalTest(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            seed.candidateFreezeContentHash(),
            seed.contentHash(),
            plan.challengeGenerator().generatorId(),
            seed.derivedSeed(),
            seed.drandChainHash(),
            seed.drandRound(),
            retained.size(),
            summaries,
            retained,
            root,
            plan.challengeGenerator().caseIdentity(),
            plan.challengeGenerator().manualReplacementOrPruning(),
            STATUS,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseGeneratedFinalTest
            fromCanonicalJson(String json) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseGeneratedFinalTest.class,
            "generated showcase FINAL TEST");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed
    ) {
        seed.requireCompatible(plan);
        if (!showcaseId.equals(plan.showcaseId())
                || !planContentHash.equals(plan.contentHash())
                || !candidateFreezeContentHash.equals(
                    seed.candidateFreezeContentHash())
                || !seedReceiptContentHash.equals(seed.contentHash())
                || !derivedSeed.equals(seed.derivedSeed())
                || !drandChainHash.equals(seed.drandChainHash())
                || drandRound != seed.drandRound()) {
            throw new IllegalArgumentException(
                "generated FINAL TEST identity mismatch");
        }
        cases.forEach(item -> item.requireCompatible(plan, seed));
    }

    private static void requireExpectedOrder(
        List<ProofCarryingShowcaseGeneratedCase> cases
    ) {
        int index = 0;
        for (String familyId : ProofCarryingShowcasePlan.FAMILIES) {
            for (int difficulty : ProofCarryingShowcasePlan.DIFFICULTY_LEVELS) {
                for (int variant : List.of(0, 1)) {
                    ProofCarryingShowcaseGeneratedCase item =
                        cases.get(index++);
                    if (!familyId.equals(item.familyId())
                            || difficulty != item.difficultyLevel()
                            || variant != item.variant()) {
                        throw new IllegalArgumentException(
                            "generated FINAL TEST cases are reordered");
                    }
                }
            }
        }
    }

    private static <T> void requireUnique(
        List<ProofCarryingShowcaseGeneratedCase> cases,
        Function<ProofCarryingShowcaseGeneratedCase, T> mapper,
        String name
    ) {
        List<T> values = cases.stream().map(mapper).toList();
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                "generated FINAL TEST contains duplicate " + name);
        }
    }

    private static void requireFamilySummaries(
        List<FamilySummary> summaries,
        List<ProofCarryingShowcaseGeneratedCase> cases
    ) {
        if (!ProofCarryingShowcasePlan.FAMILIES.equals(
                summaries.stream()
                    .map(FamilySummary::familyId)
                    .toList())) {
            throw new IllegalArgumentException(
                "generated family-summary ordering drift");
        }
        Map<String, List<ProofCarryingShowcaseGeneratedCase>> byFamily =
            cases.stream().collect(Collectors.groupingBy(
                ProofCarryingShowcaseGeneratedCase::familyId));
        for (FamilySummary summary : summaries) {
            List<ProofCarryingShowcaseGeneratedCase> family =
                byFamily.getOrDefault(summary.familyId(), List.of());
            String expectedRoot = aggregateRoot(
                "regelsuche.proof-carrying-showcase-family-root/v1",
                family.stream()
                    .map(ProofCarryingShowcaseGeneratedCase::contentHash)
                    .toList());
            if (summary.caseCount() != family.size()
                    || !expectedRoot.equals(summary.caseContentRoot())) {
                throw new IllegalArgumentException(
                    "generated family summary differs from cases");
            }
        }
    }

    static String aggregateRoot(String domain, List<String> values) {
        String material = domain + "\n" + String.join("\n", values);
        return EvolutionGenome.hash(material);
    }

    private static Map<String, Object> payload(
        String schema,
        String showcaseId,
        String planContentHash,
        String candidateFreezeContentHash,
        String seedReceiptContentHash,
        String generatorId,
        String derivedSeed,
        String drandChainHash,
        long drandRound,
        int caseCount,
        List<FamilySummary> familySummaries,
        List<ProofCarryingShowcaseGeneratedCase> cases,
        String caseContentRoot,
        String caseIdentityPolicy,
        String manualReplacementOrPruning,
        String status
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "showcaseId", showcaseId,
            "planContentHash", planContentHash,
            "candidateFreezeContentHash",
                candidateFreezeContentHash,
            "seedReceiptContentHash", seedReceiptContentHash,
            "generatorId", generatorId,
            "derivedSeed", derivedSeed,
            "drandChainHash", drandChainHash,
            "drandRound", drandRound,
            "caseCount", caseCount,
            "familySummaries", familySummaries,
            "cases", cases,
            "caseContentRoot", caseContentRoot,
            "caseIdentityPolicy", caseIdentityPolicy,
            "manualReplacementOrPruning",
                manualReplacementOrPruning,
            "status", status);
    }

    public record FamilySummary(
        String familyId,
        int caseCount,
        List<Integer> difficultyLevels,
        String caseContentRoot
    ) {
        public FamilySummary {
            familyId = ProofCarryingShowcaseJsonSupport.requireText(
                familyId, "familyId");
            difficultyLevels = ProofCarryingShowcaseJsonSupport
                .immutableIntegers(
                    difficultyLevels,
                    "difficultyLevels");
            ProofCarryingShowcaseJsonSupport.requireSha256(
                caseContentRoot, "caseContentRoot");
            if (!ProofCarryingShowcasePlan.FAMILIES.contains(familyId)
                    || caseCount != 8
                    || !ProofCarryingShowcasePlan.DIFFICULTY_LEVELS.equals(
                        difficultyLevels)) {
                throw new IllegalArgumentException(
                    "generated family summary drift");
            }
        }
    }
}
