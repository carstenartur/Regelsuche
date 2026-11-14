package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import de.regelsuche.evolution.EvolutionRewriteProgramFreezeReceipt.FreezeStatus;
import de.regelsuche.evolution.EvolutionRewriteProgramFreezeReceipt.StageStatus;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagshipRewriteProgramFreezeAssemblerTest {
    private static final String COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    private static final JsonMapper JSON = new JsonMapper();

    @TempDir
    Path tempDir;

    @Test
    void assemblesOneDeterministicCompletePreExecutionContract() throws Exception {
        var first = assemble("validation_private_symbol", template("validation_private_symbol"));
        var second = assemble("validation_private_symbol", template("validation_private_symbol"));

        assertEquals(first.receipt(), second.receipt());
        assertEquals(
            first.receipt().toCanonicalJson(),
            second.receipt().toCanonicalJson());
        assertEquals(FreezeStatus.FROZEN_NOT_RUN, first.receipt().status());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().trainResultStatus());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().validationResultStatus());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().finalTestResultStatus());
        assertEquals(8, first.train().cases().size());
        assertEquals(2, first.seeds().size());
        assertEquals(
            EvolutionRewriteProgramBaselineAblationPlan.TrackKind.values().length,
            first.baselineAblation().tracks().size());
        assertEquals(
            EvolutionRewriteProgramPerformancePlan.MeasurementLayer.values().length,
            first.performance().requiredLayers().size());
        assertTrue(first.primitiveInventoryJson().contains("contentHash"));
        assertTrue(first.programGrammarJson().contains("contentHash"));
        assertTrue(first.mutationCatalogJson().contains("contentHash"));
        assertTrue(first.workBudgetPolicyJson().contains("contentHash"));
        assertTrue(first.schemaBundleJson().contains("contentHash"));
    }

    @Test
    void writesByteIdenticalPublicArtifactsWithoutPrivateCaseMaterial()
            throws Exception {
        Path validationPrivate = tempDir.resolve("validation-private.json");
        Path finalPrivate = tempDir.resolve("final-private.json");
        EvolutionRewriteProgramHeldOutRevealCodec codec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        codec.writePrivate(
            validationPrivate,
            validationBundle("validation_private_symbol"));
        codec.writePrivate(
            finalPrivate,
            finalTestBundle("final_private_symbol"));

        var first = FlagshipRewriteProgramFreezeAssembler.write(
            tempDir.resolve("freeze-a"),
            COMMIT,
            validationPrivate,
            finalPrivate, reviewFile(template("validation_private_symbol")), resource("flagship-synthetic-architecture-decision.json"));
        var second = FlagshipRewriteProgramFreezeAssembler.write(
            tempDir.resolve("freeze-b"),
            COMMIT,
            validationPrivate,
            finalPrivate, reviewFile(template("validation_private_symbol")), resource("flagship-synthetic-architecture-decision.json"));

        assertThrows(IllegalArgumentException.class, () -> FlagshipRewriteProgramFreezeAssembler.write(
            first.outputDirectory(), COMMIT, validationPrivate, finalPrivate,
            reviewFile(template("validation_private_symbol")), resource("flagship-synthetic-architecture-decision.json")));
        assertEquals(readDirectory(first.outputDirectory()),
            readDirectory(second.outputDirectory()));
        assertEquals(
            first.outputDirectory().resolve("freeze-receipt.json"),
            first.receiptPath());
        assertFalse(first.prerequisiteArtifacts().contains(first.receiptPath()));
        assertTrue(Files.isRegularFile(first.receiptPath()));
        assertEquals(
            assemble("validation_private_symbol", template("validation_private_symbol"))
                .receipt().contentHash(),
            first.receiptHash());

        var artifacts = readDirectory(first.outputDirectory());
        var receipt = (ObjectNode) JSON.readTree(artifacts.get("freeze-receipt.json"));
        String expectedHash = receipt.remove("contentHash").textValue();
        assertEquals(expectedHash, digest(receipt.toString()));
        assertEquals(receipt.path("preregistrationHash").textValue(), digest(artifacts.get("preregistration.json")));
        assertEquals(receipt.path("acceptanceThresholdsHash").textValue(), digest(artifacts.get("acceptance-thresholds.json")));
        var decision = (ObjectNode) JSON.readTree(artifacts.get("architecture-decision.json"));
        String decisionHash = decision.remove("contentHash").textValue();
        assertEquals(decisionHash, digest(decision.toString()));
        assertEquals(decisionHash, receipt.path("architectureDecisionHash").textValue());

        String publicMaterial = readDirectory(first.outputDirectory()).values()
            .stream().collect(Collectors.joining("\n"));
        assertFalse(publicMaterial.contains("validation_private_symbol"));
        assertFalse(publicMaterial.contains("final_private_symbol"));
        assertFalse(publicMaterial.contains("private_guard_alpha"));
        assertFalse(publicMaterial.contains("private_guard_beta"));
        assertTrue(publicMaterial.contains("FROZEN_NOT_RUN"));
        assertTrue(publicMaterial.contains("NOT_EVALUATED"));
    }

    @Test
    void privateSubstitutionChangesTheCompleteFreezeIdentity() throws Exception {
        var base = assemble("validation_private_symbol", template("validation_private_symbol"));
        var changed = assemble("changed_validation_symbol", template("changed_validation_symbol"));

        assertNotEquals(
            base.manifest().contentHash(),
            changed.manifest().contentHash());
        assertNotEquals(
            base.receipt().contentHash(),
            changed.receipt().contentHash());
    }

    @Test
    void rejectsInvalidRepositoryIdentityBeforeWriting() throws Exception {
        Path validationPrivate = tempDir.resolve("bad-validation-private.json");
        Path finalPrivate = tempDir.resolve("bad-final-private.json");
        EvolutionRewriteProgramHeldOutRevealCodec codec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        codec.writePrivate(
            validationPrivate,
            validationBundle("validation_private_symbol"));
        codec.writePrivate(
            finalPrivate,
            finalTestBundle("final_private_symbol"));
        Path output = tempDir.resolve("bad-output");

        assertThrows(
            IllegalArgumentException.class,
            () -> FlagshipRewriteProgramFreezeAssembler.write(
                output,
                "not-a-git-commit",
                validationPrivate,
                finalPrivate, reviewFile(template("validation_private_symbol")), resource("flagship-synthetic-architecture-decision.json")));
        assertFalse(Files.exists(output.resolve("freeze-receipt.json")));
    }

    @Test
    void architecturePoliciesBaselinesAndPrimaryEndpointChangeTheReceipt() throws Exception {
        String symbol = "validation_private_symbol";
        ObjectNode template = template(symbol);
        var original = assemble(symbol, template).receipt();
        for (String pointer : List.of("/components/MATCHER_CANDIDATE_ORDER/characterizationHash",
                "/components/RUNTIME_ENVIRONMENT/implementationHash",
                "/baselines/FIXED_PRIMITIVE_BEST_FIRST/translationLossHash",
                "/tranches/EXACT_VALUE_ARENA_SEARCH_QUOTIENT/excludedClaimHash",
                "/endpoint/policyRoots/ANTI_ALIAS_EXPANDED_COST",
                "/endpoint/comparisonBaselines/RANDOMIZED_VALID_CANDIDATE/configurationHash")) {
            ObjectNode changed = template.deepCopy();
            int slash = pointer.lastIndexOf('/');
            ((ObjectNode) changed.at(pointer.substring(0, slash))).put(pointer.substring(slash + 1), digest("different"));
            assertNotEquals(original.contentHash(), assemble(symbol, changed).receipt().contentHash(), pointer);
        }
        ObjectNode search = template.deepCopy();
        search.set("endpoint", JSON.readTree(resource("flagship-search-review-inputs.json").toFile()));
        var searchInputs = assemble(symbol, search);
        assertNotEquals(original.acceptanceThresholdsHash(), searchInputs.receipt().acceptanceThresholdsHash());
        assertTrue(searchInputs.thresholds() instanceof EvolutionRewriteProgramAcceptanceThresholds);
    }

    @Test
    void invalidOrSubstitutedPublicInputCannotProduceAReceipt() throws Exception {
        String symbol = "validation_private_symbol";
        ObjectNode original = template(symbol);
        for (String mandatory : List.of("architectureDecisionHash", "components", "baselines", "endpoint")) {
            ObjectNode missing = original.deepCopy();
            missing.remove(mandatory);
            assertThrows(IllegalArgumentException.class, () -> assemble(symbol, missing), mandatory);
        }
        ObjectNode missingAxis = original.deepCopy();
        ((ObjectNode) missingAxis.at("/endpoint/policyRoots")).remove("RECOGNITION_SCOPE");
        assertThrows(IllegalArgumentException.class, () -> assemble(symbol, missingAxis));
        for (String changed : List.of("architectureDecisionHash", "finalTestCommitmentHash", "validationCommitmentHash")) {
            ObjectNode substituted = original.deepCopy().put(changed, digest("substitution"));
            assertThrows(IllegalArgumentException.class, () -> assemble(symbol, substituted), changed);
        }
        ObjectNode result = original.deepCopy();
        ((ObjectNode) result.get("endpoint")).put("finalTestResultRoot", digest("forbidden"));
        assertThrows(IllegalArgumentException.class, () -> assemble(symbol, result));
        ObjectNode impossible = original.deepCopy();
        ((ObjectNode) impossible.at("/endpoint/thresholds")).put("minimumEligibleFinalCases", 4);
        assertThrows(IllegalArgumentException.class, () -> assemble(symbol, impossible));
        Path decision = resource("flagship-synthetic-architecture-decision.json");
        Path tampered = tempDir.resolve("tampered-decision.json");
        Files.writeString(tampered, Files.readString(decision).replace("synthetic-public-test", "different-test"));
        assertThrows(IllegalArgumentException.class, () -> FlagshipStudyPreregistration.readDecision(tampered));
    }

    private FlagshipRewriteProgramFreezeAssembler.FreezeInputs assemble(String symbol, ObjectNode review) throws Exception {
        return FlagshipRewriteProgramFreezeAssembler.assemble(COMMIT, validationBundle(symbol),
            finalTestBundle("final_private_symbol"), FlagshipStudyPreregistration.read(reviewFile(review)),
            FlagshipStudyPreregistration.readDecision(resource("flagship-synthetic-architecture-decision.json")));
    }

    private static ObjectNode template(String symbol) throws Exception {
        ObjectNode plan = (ObjectNode) JSON.readTree(resource("flagship-review-inputs.json").toFile());
        plan.put("validationCommitmentHash", validationBundle(symbol).commitment().contentHash());
        plan.put("finalTestCommitmentHash", finalTestBundle("final_private_symbol").commitment().contentHash());
        return plan;
    }

    private Path reviewFile(ObjectNode review) throws Exception {
        return Files.writeString(tempDir.resolve("review.json"), review.toString());
    }

    private static Path resource(String name) throws Exception {
        return Path.of(FlagshipRewriteProgramFreezeAssemblerTest.class.getResource(name).toURI());
    }

    private static String digest(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> readDirectory(Path directory)
            throws Exception {
        try (var files = Files.list(directory)) {
            Map<String, String> result = new TreeMap<>();
            for (Path file : files.sorted().toList()) {
                result.put(
                    file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8));
            }
            return result;
        }
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle validationBundle(
        String symbol
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_private_cubic_bridge",
                "private_cubic_bridge",
                "(" + symbol + "^3 - 1) / (" + symbol + " - 1)",
                symbol + "^2 + " + symbol + " + 1",
                List.of("private_guard_alpha != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED)));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle finalTestBundle(
        String symbol
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.FINAL_TEST,
            List.of(
                RevealCase.create(
                    "final_private_quartic_bridge",
                    "private_quartic_bridge",
                    "(" + symbol + "^4 - 1) / (" + symbol + "^2 - 1)",
                    symbol + "^2 + 1",
                    List.of("private_guard_beta != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_private_nested_reciprocal",
                    "private_nested_reciprocal",
                    "(1 / (" + symbol + " + 1) + 1 / (" + symbol
                        + " + 2)) / (1 / ((" + symbol + " + 1) * ("
                        + symbol + " + 2)))",
                    "2 * " + symbol + " + 3",
                    List.of(
                        symbol + " + 1 != 0",
                        symbol + " + 2 != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_private_square_normalization",
                    "private_square_normalization",
                    "(" + symbol + "^2 + 2 * " + symbol + " + 1) / ("
                        + symbol + " + 1)",
                    symbol + " + 1",
                    List.of(symbol + " + 1 != 0"),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED)));
    }
}
