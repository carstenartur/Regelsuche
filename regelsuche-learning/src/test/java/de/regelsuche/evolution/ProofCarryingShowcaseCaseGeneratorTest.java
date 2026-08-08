package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseCaseGeneratorTest {
    @Test
    void generatesOneDeterministicBalancedAndExactlyValidSuite() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseSeedReceipt seed =
            ProofCarryingShowcaseTestFixtures.seed(plan, candidate);
        ProofCarryingShowcaseCaseGenerator generator =
            new ProofCarryingShowcaseCaseGenerator();

        ProofCarryingShowcaseGeneratedFinalTest first =
            generator.generate(plan, seed);
        ProofCarryingShowcaseGeneratedFinalTest repeated =
            generator.generate(plan, seed);

        assertEquals(first, repeated);
        assertEquals(first.toCanonicalJson(), repeated.toCanonicalJson());
        assertEquals(24, first.caseCount());
        assertEquals(24, first.cases().size());
        assertEquals(3, first.familySummaries().size());
        first.familySummaries().forEach(summary -> {
            assertEquals(8, summary.caseCount());
            assertEquals(
                List.of(3, 4, 5, 6),
                summary.difficultyLevels());
        });
        assertEquals(
            ProofCarryingShowcaseGeneratedFinalTest.STATUS,
            first.status());
        assertEquals(
            first,
            ProofCarryingShowcaseGeneratedFinalTest
                .fromCanonicalJson(first.toCanonicalJson()));

        Set<String> caseIds = new HashSet<>();
        Set<String> caseHashes = new HashSet<>();
        Set<String> inputs = new HashSet<>();
        Set<String> structuralFingerprints = new HashSet<>();
        for (ProofCarryingShowcaseGeneratedCase item
                : first.cases()) {
            assertTrue(caseIds.add(item.caseId()));
            assertTrue(caseHashes.add(item.contentHash()));
            assertTrue(inputs.add(item.inputExpression()));
            assertTrue(structuralFingerprints.add(
                item.structuralFingerprint()));
        }
    }

    @Test
    void preservesThePublishedV1DevelopmentFixtureSurface() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseSeedReceipt seed =
            new ProofCarryingShowcaseSeedReceipt(
                ProofCarryingShowcaseSeedReceipt.SCHEMA,
                plan.showcaseId(),
                plan.contentHash(),
                ProofCarryingShowcaseTestFixtures.hash(
                    "fixture-candidate-a"),
                ProofCarryingShowcaseTestFixtures.hash(
                    "fixture-randomness-a"),
                plan.publicRandomness().chainHash(),
                99_000_000L,
                ProofCarryingShowcaseSeedReceipt
                    .DERIVATION_ALGORITHM,
                ProofCarryingShowcaseTestFixtures.hash(
                    "fixture-derived-seed-a"),
                ProofCarryingShowcaseSeedReceipt.STATUS,
                "sha256:1c4bb6e7ef3e37b539ba0359dba8daa71b30f7b18ff79fbf6c87e24dadf9cb72");

        ProofCarryingShowcaseGeneratedFinalTest generated =
            new ProofCarryingShowcaseCaseGenerator().generate(
                plan, seed);

        assertEquals(
            "sha256:28294f2993df4e8690cf0dad2cf1132bb203ce6217c51aa8747d9f07d5ea1eb1",
            generated.caseContentRoot());
    }

    @Test
    void seedSubstitutionChangesTheCompleteGeneratedSurface() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseCaseGenerator generator =
            new ProofCarryingShowcaseCaseGenerator();
        ProofCarryingShowcaseSeedReceipt firstSeed =
            ProofCarryingShowcaseSeedReceipt.create(
                plan,
                candidate,
                ProofCarryingShowcaseTestFixtures.randomness(
                    plan, candidate, "ab".repeat(32)));
        ProofCarryingShowcaseSeedReceipt secondSeed =
            ProofCarryingShowcaseSeedReceipt.create(
                plan,
                candidate,
                ProofCarryingShowcaseTestFixtures.randomness(
                    plan, candidate, "01".repeat(32)));

        ProofCarryingShowcaseGeneratedFinalTest first =
            generator.generate(plan, firstSeed);
        ProofCarryingShowcaseGeneratedFinalTest second =
            generator.generate(plan, secondSeed);

        assertNotEquals(
            first.caseContentRoot(),
            second.caseContentRoot());
        assertNotEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void generatedJsonRejectsUnknownFieldsAndCaseTampering() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseGeneratedFinalTest generated =
            new ProofCarryingShowcaseCaseGenerator().generate(
                plan,
                ProofCarryingShowcaseTestFixtures.seed(
                    plan, candidate));

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseGeneratedFinalTest
                .fromCanonicalJson(
                    generated.toCanonicalJson().replaceFirst(
                        "\\{",
                        "{\"unexpected\":true,")));
        String firstInput = generated.cases().getFirst()
            .inputExpression();
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseGeneratedFinalTest
                .fromCanonicalJson(
                    generated.toCanonicalJson().replace(
                        firstInput,
                        "(" + firstInput + ")+0")));
    }
}
