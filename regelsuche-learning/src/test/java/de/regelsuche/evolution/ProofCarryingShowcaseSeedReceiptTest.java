package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseSeedReceiptTest {
    @Test
    void derivesOneDeterministicSeedAfterTheFrozenBoundary() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcasePublicRandomnessReceipt randomness =
            ProofCarryingShowcaseTestFixtures.randomness(
                plan, candidate);

        ProofCarryingShowcaseSeedReceipt first =
            ProofCarryingShowcaseSeedReceipt.create(
                plan, candidate, randomness);
        ProofCarryingShowcaseSeedReceipt second =
            ProofCarryingShowcaseSeedReceipt.create(
                plan, candidate, randomness);

        assertEquals(first, second);
        assertEquals(
            ProofCarryingShowcaseSeedReceipt.STATUS,
            first.status());
        assertEquals(
            first,
            ProofCarryingShowcaseSeedReceipt.fromCanonicalJson(
                first.toCanonicalJson()));
        assertEquals(
            candidate,
            ProofCarryingShowcaseCandidateFreeze.fromCanonicalJson(
                candidate.toCanonicalJson()));
        assertEquals(
            randomness,
            ProofCarryingShowcasePublicRandomnessReceipt
                .fromCanonicalJson(
                    randomness.toCanonicalJson()));
    }

    @Test
    void rejectsEarlyRandomnessAndCandidateSubstitution() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcasePublicRandomnessReceipt tooEarly =
            ProofCarryingShowcasePublicRandomnessReceipt.create(
                plan,
                candidate,
                99_999_999L,
                candidate.randomnessNotBeforeUnixTime(),
                "ab".repeat(32),
                "cd".repeat(96),
                "ef".repeat(96),
                ProofCarryingShowcaseTestFixtures.hash("chain-info"),
                "drand-client/verified-fixture",
                ProofCarryingShowcaseTestFixtures.hash("client"),
                ProofCarryingShowcaseTestFixtures.hash("verification"),
                "fixture.drand.invalid");

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseSeedReceipt.create(
                plan, candidate, tooEarly));

        ProofCarryingShowcaseCandidateFreeze substituted =
            ProofCarryingShowcaseCandidateFreeze.create(
                plan,
                "2".repeat(40),
                ProofCarryingShowcaseTestFixtures.hash("training-2"),
                ProofCarryingShowcaseTestFixtures.hash("selection-2"),
                ProofCarryingShowcaseTestFixtures.hash("candidate-2"),
                ProofCarryingShowcaseTestFixtures.hash(
                    "candidate-alpha-2"),
                ProofCarryingShowcaseTestFixtures.hash("program-2"),
                ProofCarryingShowcaseTestFixtures.hash("inventory"),
                ProofCarryingShowcaseTestFixtures.hash("budget"),
                ProofCarryingShowcaseTestFixtures.hash("protocol"),
                List.of(
                    ProofCarryingShowcaseTestFixtures.hash("seed-a"),
                    ProofCarryingShowcaseTestFixtures.hash("seed-b")),
                7,
                true,
                true,
                3,
                2_000_000_000L);
        ProofCarryingShowcasePublicRandomnessReceipt original =
            ProofCarryingShowcaseTestFixtures.randomness(
                plan, candidate);
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseSeedReceipt.create(
                plan, substituted, original));
    }

    @Test
    void substitutedRandomnessChangesTheSeedAndUnknownFieldsFailClosed() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseSeedReceipt first =
            ProofCarryingShowcaseSeedReceipt.create(
                plan,
                candidate,
                ProofCarryingShowcaseTestFixtures.randomness(
                    plan, candidate, "ab".repeat(32)));
        ProofCarryingShowcaseSeedReceipt second =
            ProofCarryingShowcaseSeedReceipt.create(
                plan,
                candidate,
                ProofCarryingShowcaseTestFixtures.randomness(
                    plan, candidate, "01".repeat(32)));

        assertNotEquals(first.derivedSeed(), second.derivedSeed());
        assertNotEquals(first.contentHash(), second.contentHash());

        String unknown = first.toCanonicalJson().replaceFirst(
            "\\{",
            "{\"unexpected\":true,");
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseSeedReceipt
                .fromCanonicalJson(unknown));
    }
}
