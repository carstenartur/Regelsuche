package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseArchitectureTest {
    @Test
    void showcaseSemanticsLiveInJavaAndRunThroughJUnit() {
        Path root = ProofCarryingShowcaseTestFixtures.repositoryRoot();

        assertFalse(Files.exists(root.resolve(
            "scripts/verify-proof-carrying-showcase-contract.py")));
        assertFalse(Files.exists(root.resolve(
            "scripts/derive-proof-carrying-showcase-seed.py")));
        assertFalse(Files.exists(root.resolve(
            "scripts/generate-proof-carrying-showcase-cases.py")));
        assertFalse(Files.exists(root.resolve(
            "scripts/generate-proof-carrying-showcase-final-test.py")));
        assertFalse(Files.exists(root.resolve(
            "gradle/proof-carrying-showcase.init.gradle")));

        Path javaRoot = root.resolve(
            "regelsuche-learning/src/main/java/de/regelsuche/evolution");
        assertTrue(Files.isRegularFile(javaRoot.resolve(
            "ProofCarryingShowcasePlan.java")));
        assertTrue(Files.isRegularFile(javaRoot.resolve(
            "ProofCarryingShowcaseSeedReceipt.java")));
        assertTrue(Files.isRegularFile(javaRoot.resolve(
            "ProofCarryingShowcaseCaseGenerator.java")));
    }
}
