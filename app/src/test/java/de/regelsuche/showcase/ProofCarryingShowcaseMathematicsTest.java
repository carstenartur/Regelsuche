package de.regelsuche.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService.Status;
import de.regelsuche.evolution.ProofCarryingShowcaseCandidateFreeze;
import de.regelsuche.evolution.ProofCarryingShowcaseCaseGenerator;
import de.regelsuche.evolution.ProofCarryingShowcaseGeneratedCase;
import de.regelsuche.evolution.ProofCarryingShowcaseGeneratedFinalTest;
import de.regelsuche.evolution.ProofCarryingShowcasePlan;
import de.regelsuche.evolution.ProofCarryingShowcasePublicRandomnessReceipt;
import de.regelsuche.evolution.ProofCarryingShowcaseSeedReceipt;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseMathematicsTest {
    @Test
    void everyGeneratedCaseIsConfirmedByTheProductionExactEvaluator() {
        ProofCarryingShowcasePlan plan = ProofCarryingShowcasePlan.read(
            repositoryRoot().resolve(
                "research/showcase/proof-carrying-self-improvement/"
                    + "showcase-plan.json"));
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseCandidateFreeze.create(
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
        ProofCarryingShowcasePublicRandomnessReceipt randomness =
            ProofCarryingShowcasePublicRandomnessReceipt.create(
                plan,
                candidate,
                99_999_999L,
                candidate.randomnessNotBeforeUnixTime() + 1,
                "ab".repeat(32),
                "cd".repeat(96),
                "ef".repeat(96),
                hash("chain-info"),
                "drand-client/verified-fixture",
                hash("client"),
                hash("verification"),
                "fixture.drand.invalid");
        ProofCarryingShowcaseSeedReceipt seed =
            ProofCarryingShowcaseSeedReceipt.create(
                plan, candidate, randomness);
        ProofCarryingShowcaseGeneratedFinalTest generated =
            new ProofCarryingShowcaseCaseGenerator().generate(
                plan, seed);

        RationalFunctionNormalFormEquivalencePortAdapter evaluator =
            new RationalFunctionNormalFormEquivalencePortAdapter();
        for (ProofCarryingShowcaseGeneratedCase item
                : generated.cases()) {
            var evaluation = evaluator.evaluate(
                item.inputExpression(),
                item.targetExpression(),
                item.assumptions());
            assertEquals(
                Status.CONFIRMED,
                evaluation.status(),
                () -> item.caseId() + ": " + evaluation.detail());
            assertTrue(
                evaluation.missingAssumptions().isEmpty(),
                () -> item.caseId() + ": "
                    + evaluation.missingAssumptions());
            assertTrue(
                evaluation.unsupportedAssumptions().isEmpty(),
                () -> item.caseId() + ": "
                    + evaluation.unsupportedAssumptions());
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable", exception);
        }
    }

    private static Path repositoryRoot() {
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
