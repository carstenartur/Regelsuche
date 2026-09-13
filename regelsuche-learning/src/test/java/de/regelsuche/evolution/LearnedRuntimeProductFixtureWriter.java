package de.regelsuche.evolution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Writes the fixed public learned-authority inputs for runtime qualification. */
public final class LearnedRuntimeProductFixtureWriter {
    static final String INPUT_SCHEMA =
        "regelsuche.safe-runtime-public-authority-inputs/v1";
    static final String FIXTURE_ID =
        "public-normalization-authority-contract-v1";
    static final String RUNTIME_MANIFEST_SCHEMA =
        "regelsuche.learned-runtime-authority-manifest/v1";
    static final String PROGRAM_ID = "qualification-normalization-program";
    static final Instant POSITIVE_EXPIRY =
        Instant.parse("2100-01-01T00:00:00Z");
    static final Instant NEGATIVE_EXPIRY =
        Instant.parse("2026-09-11T00:00:00Z");

    private LearnedRuntimeProductFixtureWriter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        reset(output);

        var valid = LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.export(
            output.resolve("valid"), POSITIVE_EXPIRY, true);
        var expired = LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.export(
            output.resolve("expired"), NEGATIVE_EXPIRY, true);
        if (!valid.promotedRuleIds().equals(expired.promotedRuleIds())) {
            throw new IllegalStateException(
                "fixed-window exports produced different promoted inventories");
        }

        write(output.resolve("runtime-valid.json"), runtimeManifest("valid"));
        write(output.resolve("runtime-expired.json"), runtimeManifest("expired"));
        write(output.resolve("inputs.json"), inputs(valid.promotedRuleIds()));
    }

    private static String runtimeManifest(String root) {
        return LearnedPatternAuthorizationJson.write(Map.of(
            "schema", RUNTIME_MANIFEST_SCHEMA,
            "repositoryRevision",
                LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.REVISION,
            "patterns", List.of(
                Map.of("id", "mul-one", "root", root + "/leaf-mul-one"),
                Map.of("id", "add-zero", "root", root + "/leaf-add-zero")),
            "programs", List.of(Map.of(
                "id", PROGRAM_ID,
                "root", root,
                "leafAuthorityIds", List.of("mul-one", "add-zero")))));
    }

    private static String inputs(List<String> promotedRuleIds) {
        return LearnedPatternAuthorizationJson.write(Map.of(
            "schema", INPUT_SCHEMA,
            "fixtureId", FIXTURE_ID,
            "subjectRevision",
                LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.REVISION,
            "issuedAt",
                LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.ISSUED.toString(),
            "authorizedAt",
                LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.AUTHORIZED.toString(),
            "positiveExpiry", POSITIVE_EXPIRY.toString(),
            "negativeExpiry", NEGATIVE_EXPIRY.toString(),
            "explicitContractFixture", true,
            "promotedRuleIds", promotedRuleIds));
    }

    private static void reset(Path output) throws IOException {
        if (Files.exists(output)) {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(output)) {
                        Files.delete(path);
                    }
                }
            }
        }
        Files.createDirectories(output);
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
