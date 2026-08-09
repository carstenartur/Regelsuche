package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AssessmentDecision;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRediscoveryAtlasTest {
    private final HistoricalRediscoveryAtlas atlas =
        new HistoricalRediscoveryAtlas();

    @Test
    @Timeout(120)
    void separatesInventoryBridgeSearchPolicyAndNegativeControlMechanisms() {
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(subset());
        Map<String, CaseResult> cases = byId(report);

        assertTrue(cases.get("difference-of-squares-powers")
            .production().oracle().reachable());
        assertTrue(cases.get("complete-square")
            .curatedControl().oracle().reachable());
        assertTrue(cases.get("sophie-germain")
            .genericBridge().guided().reached());

        CaseResult policy = cases.get("distribution-fitness-valley-control");
        assertTrue(policy.production().oracle().reachable());
        assertFalse(policy.production().scalar().reached(), policy.toString());
        assertTrue(policy.production().diversity().reached(), policy.toString());
        assertTrue(policy.production().guided().reached(), policy.toString());

        assertEquals(
            PrimaryStatus.NEGATIVE_CONTROL_CONFIRMED,
            cases.get("inconsistent-near-miss").status());
        assertEquals(
            AssessmentDecision.USEFUL_DIAGNOSTIC_STEP,
            report.assessment().decision());
    }

    @Test
    @Timeout(120)
    void reportSerializationIsStableAndArtifactsAreWritable(@TempDir Path directory)
            throws Exception {
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(subset());

        assertEquals(report.toJson(), report.toJson());
        assertTrue(report.toJson().startsWith(
            "{\"schema\":\"regelsuche.historical-rediscovery-atlas/v1\""));
        assertTrue(report.toMarkdown().contains(
            "Historical rediscovery and reachability atlas"));

        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(directory, report);
        assertEquals(
            report.toJson(),
            Files.readString(artifacts.json(), StandardCharsets.UTF_8));
        assertEquals(
            report.toMarkdown(),
            Files.readString(artifacts.markdown(), StandardCharsets.UTF_8));
    }

    private Corpus subset() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        Set<String> selected = Set.of(
            "complete-square",
            "difference-of-squares-powers",
            "sophie-germain",
            "distribution-fitness-valley-control",
            "inconsistent-near-miss"
        );
        List<HistoricalRediscoveryCorpus.Case> cases = full.cases().stream()
            .filter(value -> selected.contains(value.id()))
            .toList();
        assertEquals(selected.size(), cases.size());
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            cases
        );
    }

    private Map<String, CaseResult> byId(
        HistoricalRediscoveryAtlas.AtlasReport report
    ) {
        return report.cases().stream().collect(Collectors.toMap(
            result -> result.benchmarkCase().id(),
            Function.identity()
        ));
    }
}
