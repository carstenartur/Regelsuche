package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ProofCarryingShowcaseTestFixtures {
    private ProofCarryingShowcaseTestFixtures() {
    }

    static ProofCarryingShowcasePlan plan() {
        return ProofCarryingShowcasePlan.read(
            repositoryRoot().resolve(
                "research/showcase/proof-carrying-self-improvement/"
                    + "showcase-plan.json"));
    }

    static ProofCarryingShowcaseCandidateFreeze candidate(
        ProofCarryingShowcasePlan plan
    ) {
        return ProofCarryingShowcaseCandidateFreeze.create(
            plan,
            "1".repeat(40),
            hash("training"),
            hash("selection"),
            hash("candidate"),
            hash("candidate-alpha"),
            hash("program"),
            hash("inventory"),
            hash("budget"),
            hash("protocol"),
            List.of(hash("seed-a"), hash("seed-b")),
            7,
            true,
            true,
            3,
            2_000_000_000L);
    }

    static ProofCarryingShowcasePublicRandomnessReceipt randomness(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate
    ) {
        return randomness(plan, candidate, "ab".repeat(32));
    }

    static ProofCarryingShowcasePublicRandomnessReceipt randomness(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        String randomness
    ) {
        return ProofCarryingShowcasePublicRandomnessReceipt.create(
            plan,
            candidate,
            99_999_999L,
            candidate.randomnessNotBeforeUnixTime() + 1,
            randomness,
            "cd".repeat(96),
            "ef".repeat(96),
            hash("chain-info"),
            "drand-client/verified-fixture",
            hash("client"),
            hash("verification"),
            "fixture.drand.invalid");
    }

    static ProofCarryingShowcaseSeedReceipt seed(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate
    ) {
        return ProofCarryingShowcaseSeedReceipt.create(
            plan,
            candidate,
            randomness(plan, candidate));
    }

    static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(current.resolve("gradlew"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("unable to locate repository root from "
            + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }
}
