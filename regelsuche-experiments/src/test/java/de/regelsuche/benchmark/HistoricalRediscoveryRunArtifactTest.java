package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRediscoveryRunArtifactTest {
    private static final String TEST_SHA256 = "0".repeat(64);

    @Test
    void repeatedRunsAreByteIdenticalAndStrictlyVerifiable(
            @TempDir Path temporary) throws Exception {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");

        HistoricalRediscoveryRunArtifact.VerifiedRun firstRun = writeRun(first);
        HistoricalRediscoveryRunArtifact.VerifiedRun secondRun = writeRun(second);

        for (String fileName : List.of(
                "historical-rediscovery-atlas.json",
                "historical-rediscovery-atlas.md",
                HistoricalRediscoveryRunArtifact.MANIFEST_FILE_NAME)) {
            assertArrayEquals(
                Files.readAllBytes(first.resolve(fileName)),
                Files.readAllBytes(second.resolve(fileName)),
                fileName);
        }
        assertEquals(
            HistoricalRediscoveryRunArtifact.SCHEMA,
            firstRun.manifest().schema());
        assertEquals(
            HistoricalRediscoveryRunArtifact.EVIDENCE_STATUS,
            firstRun.manifest().evidenceStatus());
        assertEquals(2, firstRun.manifest().artifacts().size());
        assertEquals(
            firstRun.manifest().contentHash(),
            secondRun.manifest().contentHash());
        assertTrue(firstRun.manifest().contentHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertEquals(
            firstRun.manifest().toCanonicalJson(),
            Files.readString(
                firstRun.manifestPath(),
                StandardCharsets.UTF_8));
        assertEquals(
            firstRun.manifest(),
            HistoricalRediscoveryRunArtifact.verify(first).manifest());
    }

    @Test
    void manifestRejectsUnsupportedNestedSchemaIdentities(
            @TempDir Path directory) {
        HistoricalRediscoveryRunArtifact.Manifest manifest =
            writeRun(directory).manifest();

        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoricalRediscoveryRunArtifact.Manifest(
                manifest.schema(),
                manifest.evidenceStatus(),
                "unsupported-corpus/v2",
                manifest.corpusSha256(),
                manifest.atlasSchema(),
                manifest.inventoryRevision(),
                manifest.claimBoundary(),
                manifest.assessmentDecision(),
                manifest.caseCount(),
                manifest.artifacts(),
                manifest.commitProtocol(),
                manifest.contentHash()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoricalRediscoveryRunArtifact.Manifest(
                manifest.schema(),
                manifest.evidenceStatus(),
                manifest.corpusSchema(),
                manifest.corpusSha256(),
                "unsupported-atlas/v2",
                manifest.inventoryRevision(),
                manifest.claimBoundary(),
                manifest.assessmentDecision(),
                manifest.caseCount(),
                manifest.artifacts(),
                manifest.commitProtocol(),
                manifest.contentHash()));
    }

    @Test
    void beginInvalidatesAFormerManifestBeforePayloadReplacement(
            @TempDir Path directory) {
        writeRun(directory);
        assertTrue(Files.exists(directory.resolve(
            HistoricalRediscoveryRunArtifact.MANIFEST_FILE_NAME)));

        HistoricalRediscoveryRunArtifact.begin(directory);

        assertFalse(Files.exists(directory.resolve(
            HistoricalRediscoveryRunArtifact.MANIFEST_FILE_NAME)));
        assertTrue(Files.exists(directory.resolve(
            "historical-rediscovery-atlas.json")));
    }

    @Test
    void changedMissingAndUnexpectedPayloadsFailClosed(
            @TempDir Path temporary) throws Exception {
        Path changed = temporary.resolve("changed");
        writeRun(changed);
        Files.writeString(
            changed.resolve("historical-rediscovery-atlas.md"),
            "tampered",
            StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(changed));

        Path missing = temporary.resolve("missing");
        writeRun(missing);
        Files.delete(missing.resolve("historical-rediscovery-atlas.json"));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(missing));

        Path unexpected = temporary.resolve("unexpected");
        writeRun(unexpected);
        Files.writeString(unexpected.resolve("extra.txt"), "extra");
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(unexpected));
    }

    @Test
    void malformedDuplicateTrailingAndUnknownManifestJsonFailClosed(
            @TempDir Path temporary) throws Exception {
        Path duplicate = temporary.resolve("duplicate");
        writeRun(duplicate);
        replaceManifest(
            duplicate,
            json -> json.replaceFirst(
                "\\{",
                "{\"schema\":\"regelsuche.historical-rediscovery-run/v1\","));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(duplicate));

        Path trailing = temporary.resolve("trailing");
        writeRun(trailing);
        replaceManifest(trailing, json -> json + "{}");
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(trailing));

        Path unknown = temporary.resolve("unknown");
        writeRun(unknown);
        replaceManifest(
            unknown,
            json -> json.replaceFirst("\\{", "{\"unknown\":true,"));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryRunArtifact.verify(unknown));
    }

    @Test
    void commitRequiresExplicitPreviousManifestInvalidation(
            @TempDir Path directory) {
        HistoricalRediscoveryRunArtifact.VerifiedRun run = writeRun(directory);
        Fixture fixture = fixture();

        assertThrows(
            IllegalStateException.class,
            () -> HistoricalRediscoveryRunArtifact.commit(
                directory,
                fixture.corpus(),
                fixture.report(),
                new HistoricalRediscoveryAtlas.WrittenArtifacts(
                    directory.resolve("historical-rediscovery-atlas.json"),
                    directory.resolve("historical-rediscovery-atlas.md"))));
        assertTrue(Files.exists(run.manifestPath()));
    }

    private static HistoricalRediscoveryRunArtifact.VerifiedRun writeRun(
            Path directory) {
        Fixture fixture = fixture();
        HistoricalRediscoveryRunArtifact.begin(directory);
        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            new HistoricalRediscoveryAtlas().write(
                directory,
                fixture.report());
        return HistoricalRediscoveryRunArtifact.commit(
            directory,
            fixture.corpus(),
            fixture.report(),
            artifacts);
    }

    private static Fixture fixture() {
        Corpus corpus = new Corpus(
            HistoricalRediscoveryCorpus.SCHEMA,
            "FROZEN_DIAGNOSTIC_CORPUS",
            "test-inventory/v1",
            "Bounded test fixture; no discovery or novelty claim.",
            TEST_SHA256,
            List.of(new Case(
                "identity-control",
                "IDENTITY",
                "x",
                "x",
                Relation.EQUIVALENT,
                Role.HISTORICAL_POSITIVE,
                "CONTROL",
                "TEST_FIXTURE",
                TargetRelation.SYNTAX_EXACT,
                1,
                4,
                1,
                4,
                2,
                1,
                2)));
        HistoricalRediscoveryAtlas.AtlasReport report =
            new HistoricalRediscoveryAtlas().run(corpus);
        return new Fixture(corpus, report);
    }

    private static void replaceManifest(
        Path directory,
        java.util.function.UnaryOperator<String> replacement
    ) throws Exception {
        Path manifest = directory.resolve(
            HistoricalRediscoveryRunArtifact.MANIFEST_FILE_NAME);
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(
            manifest,
            replacement.apply(json),
            StandardCharsets.UTF_8);
    }

    private record Fixture(
        Corpus corpus,
        HistoricalRediscoveryAtlas.AtlasReport report
    ) {
    }
}
