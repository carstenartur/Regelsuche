package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRewriteArchitectureDecisionTest {
    private static final String REVISION = "a".repeat(40);

    @Test
    @Timeout(240)
    void derivesAFreezeableDecisionFromTheExecutedPublicHistoricalReports(@TempDir Path output) throws Exception {
        var corpus = HistoricalRediscoveryCorpus.load();
        var atlas = new HistoricalRediscoveryAtlas();
        var evidence = HistoricalRediscoveryReportPipeline.execute(output.resolve("atlas"), corpus, atlas, atlas.run(corpus));
        Path decisionDir = output.resolve("decision");
        String[] command = {"rewrite-architecture-decision", REVISION,
            evidence.verifiedRun().manifestPath().toString(), evidence.artifacts().json().toString(),
            evidence.pruningArtifact().toString(), evidence.productionArtifact().toString(),
            evidence.equalWorkArtifact().toString(), decisionDir.toString()};
        HistoricalRewriteArchitectureDecision.main(command);

        String json = Files.readString(decisionDir.resolve(HistoricalRewriteArchitectureDecision.FILE_NAME));
        var verified = HistoricalRewriteArchitectureDecision.readFrozenDecision(json);
        assertEquals(json, verified.canonicalJson());
        assertEquals(6, verified.dispositions().size());
        assertEquals("REQUIRED_NEXT_EVALUATION", verified.dispositions().get("TARGET_FREE_REPRESENTATION_DISCOVERY"));
        var report = HistoricalRewriteArchitectureDecision.derive(REVISION, evidence.verifiedRun().manifestPath(),
            evidence.artifacts().json(), evidence.pruningArtifact(), evidence.productionArtifact(), evidence.equalWorkArtifact());
        assertEquals(report.contentHash(), verified.contentHash());
        assertEquals(evidence.verifiedRun().manifest().contentHash(), report.sources().atlasRunContentHash());
        assertEquals(evidence.pruningContentHash(), report.sources().witnessDiagnosticContentHash());
        assertEquals(evidence.productionReport().contentHash(), report.sources().productionComparisonContentHash());
        assertEquals(evidence.equalWorkReport().contentHash(), report.sources().equalWorkComparisonContentHash());
        assertEquals(corpus.cases().size(), report.distribution().caseCount());

        // A canonical WORKTREE diagnostic is useful locally; it has no frozen repository identity.
        command[1] = "WORKTREE";
        HistoricalRewriteArchitectureDecision.main(command);
        String worktree = Files.readString(decisionDir.resolve(HistoricalRewriteArchitectureDecision.FILE_NAME));
        assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.readFrozenDecision(worktree));
        assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.readFrozenDecision(json + "\n"));
        assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.readFrozenDecision(
            json.replace("NO_PRODUCTION_WITNESS_COUNT_POSITIVE", "invalid-code")));
        assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.readFrozenDecision(
            json.replace(verified.contentHash(), "sha256:" + "0".repeat(64))));

        // The atlas run binds the exact source bytes, even when the modified JSON has the same fields.
        Path foreignAtlas = output.resolve("modified-atlas.json");
        Files.writeString(foreignAtlas, Files.readString(evidence.artifacts().json()) + "\n");
        command[3] = foreignAtlas.toString();
        var mismatch = assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.main(command));
        assertTrue(mismatch.getMessage().contains("atlas-run payload identity"), mismatch::getMessage);
        command[3] = evidence.artifacts().json().toString();
        Files.writeString(evidence.productionArtifact(), Files.readString(evidence.productionArtifact())
            .replace(evidence.productionReport().contentHash(), "sha256:" + "0".repeat(64)));
        var corrupt = assertThrows(IllegalArgumentException.class, () -> HistoricalRewriteArchitectureDecision.main(command));
        assertTrue(corrupt.getMessage().contains("production comparison contentHash"), corrupt::getMessage);
    }
}
