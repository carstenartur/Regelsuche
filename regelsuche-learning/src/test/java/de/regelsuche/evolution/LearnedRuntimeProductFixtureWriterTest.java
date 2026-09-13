package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.json.JsonReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearnedRuntimeProductFixtureWriterTest {
    private static final String SUBJECT = "0123456789abcdef0123456789abcdef01234567";
    private static final Instant AS_OF = Instant.parse("2026-09-13T00:00:00Z");

    @Test void publicInputsRetainUsableLeavesAndOriginalProgramReplay(@TempDir Path output) throws Exception {
        export(output);
        var inputs = new JsonReader(Files.readString(output.resolve("inputs.json"))).readObject();
        assertEquals("regelsuche.safe-runtime-public-authority-inputs/v1", inputs.get("schema"));
        assertEquals("public-normalization-authority-contract-v1", inputs.get("fixtureId"));
        assertEquals(true, inputs.get("explicitContractFixture"));
        assertEquals(SUBJECT, inputs.get("subjectRevision"));
        assertEquals("2026-09-01T00:00:00Z", inputs.get("issuedAt"));
        assertEquals("2026-09-10T00:00:00Z", inputs.get("authorizedAt"));
        assertEquals("2100-01-01T00:00:00Z", inputs.get("positiveExpiry"));
        assertEquals("2026-09-11T00:00:00Z", inputs.get("negativeExpiry"));
        assertEquals(Set.of(
            "schema", "fixtureId", "subjectRevision", "issuedAt", "authorizedAt",
            "positiveExpiry", "negativeExpiry", "explicitContractFixture",
            "promotedRuleIds"), inputs.keySet());
        Path valid = output.resolve("valid");
        var leaves = List.of(leaf(valid, "mul-one", AS_OF), leaf(valid, "add-zero", AS_OF));
        var promotedRuleIds = List.of(
            "learned.promoted.e6da03293d04583e.mul-one",
            "learned.promoted.e6da03293d04583e.add-zero");
        assertEquals(promotedRuleIds, inputs.get("promotedRuleIds"));
        assertEquals(promotedRuleIds, leaves.stream()
            .map(authorization -> authorization.promotion().rule().id())
            .toList());
        var authorized = program(valid, AS_OF);
        assertTrue(authorized.compiledProgram().engine().transform("(x * 1) + 0").stream()
            .anyMatch(step -> step.transformedExpression().equals("x")));
        assertEquals(Instant.parse("2100-01-01T00:00:00Z"), leaves.getFirst().receipt().validUntil());
        var manifest = new JsonReader(Files.readString(output.resolve("runtime-valid.json"))).readObject();
        assertEquals(Map.of(
            "schema", "regelsuche.learned-runtime-authority-manifest/v1",
            "repositoryRevision", SUBJECT,
            "patterns", List.of(
                Map.of("id", "mul-one", "root", "valid/leaf-mul-one"),
                Map.of("id", "add-zero", "root", "valid/leaf-add-zero")),
            "programs", List.of(Map.of(
                "id", "qualification-normalization-program",
                "root", "valid",
                "leafAuthorityIds", List.of("mul-one", "add-zero")))), manifest);
        assertFalse(Files.readString(output.resolve("inputs.json")).contains(output.toString()));
    }

    @Test void expiredControlWasAuthorizedButFailsAtItsActualExpiry(@TempDir Path output) throws Exception {
        export(output);
        Path expired = output.resolve("expired");
        var prior = leaf(expired, "mul-one", Instant.parse("2026-09-10T12:00:00Z"));
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"), prior.receipt().validUntil());
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"),
            program(expired, Instant.parse("2026-09-10T12:00:00Z")).receipt().validUntil());
        assertThrows(IllegalArgumentException.class,
            () -> leaf(expired, "mul-one", Instant.parse("2026-09-11T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> leaf(expired, "add-zero", AS_OF));
        assertThrows(IllegalArgumentException.class,
            () -> program(expired, Instant.parse("2026-09-11T00:00:00Z")));

        var manifest = new JsonReader(
            Files.readString(output.resolve("runtime-expired.json"))).readObject();
        assertEquals(Map.of(
            "schema", "regelsuche.learned-runtime-authority-manifest/v1",
            "repositoryRevision", SUBJECT,
            "patterns", List.of(
                Map.of("id", "mul-one", "root", "expired/leaf-mul-one"),
                Map.of("id", "add-zero", "root", "expired/leaf-add-zero")),
            "programs", List.of(Map.of(
                "id", "qualification-normalization-program",
                "root", "expired",
                "leafAuthorityIds", List.of("mul-one", "add-zero")))), manifest);
    }

    @Test void historicalProgramFixtureBytesStayFrozen(@TempDir Path output) throws Exception {
        LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.main(new String[]{output.toString()});
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(output)) {
            Comparator<Path> portableRelativeOrder = Comparator.comparing(path ->
                output.relativize(path).toString().replace('\\', '/'));
            for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(portableRelativeOrder).toList()) {
                digest.update(output.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
        }
        assertEquals("7c574c790f66b09881c0abc11a10889d15bd30153d975aa1644792cd68c9735b",
            HexFormat.of().formatHex(digest.digest()));
    }

    @Test void exporterAcceptsOnlyItsFixedPublicWindowContract(@TempDir Path output) {
        assertThrows(IllegalArgumentException.class,
            () -> LearnedRuntimeProductFixtureWriter.main(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> LearnedRuntimeProductFixtureWriter.main(
                new String[]{output.toString(), "2101-01-01T00:00:00Z"}));
    }

    private static void export(Path output) {
        assertDoesNotThrow(() -> LearnedRuntimeProductFixtureWriter.main(
            new String[]{output.toString()}),
            "the public fixed-window input exporter must be available");
    }

    private static LearnedRewriteProgramAuthorizationService.Authorization program(
        Path root,
        Instant asOf
    ) throws Exception {
        var leaves = List.of(leaf(root, "mul-one", asOf), leaf(root, "add-zero", asOf));
        var genome = new EvolutionGenomeCodec().read(root.resolve("genome.json"));
        var plan = new EvolutionRewriteProgramPlanCodec().read(root.resolve("program-plan.json"));
        var replay = LearnedRewriteProgramReplayEvidence.fromCanonicalJson(
            Files.readString(root.resolve("program-replay-evidence.json")));
        var receipt = LearnedRewriteProgramAuthorizationReceipt.fromCanonicalJson(
            Files.readString(root.resolve("program-authorization-receipt.json")));
        return new LearnedRewriteProgramAuthorizationService().replayStoredAuthorization(
            EvolutionRewriteProgramCandidate.create(genome, plan), leaves, replay, receipt, SUBJECT, asOf);
    }

    private static LearnedPatternRuleAuthorizationService.Authorization leaf(
        Path programRoot, String id, Instant asOf
    ) throws Exception {
        Path root = programRoot.resolve("leaf-" + id);
        var genome = new EvolutionGenomeCodec().read(root.resolve("genome.json"));
        return new LearnedPatternRuleAuthorizationService().verifyAuthorization(genome, id, SUBJECT,
            new LearnedPatternRuleAuthorizationService.EvidenceFiles(
                root.resolve("authorization-bundle.json"), root.resolve("split-manifest.json"),
                root.resolve("validation-selection.json"), root.resolve("final-test-evaluation.json"),
                root.resolve("counterexample-evidence.json")), root.resolve("authorization-receipt.json"), asOf);
    }
}
