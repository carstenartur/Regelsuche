package de.regelsuche.experiments.autopilot;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousProductionMiningRunner.MiningBatch;
import de.regelsuche.mining.OpenTargetConjectureMiner.SupportScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Runs the registered observation-support control on the unchanged public campaign. */
final class MiningFanInComparison {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String PLAN = "6e3c4e733d308fa0457045b3bcfc1a7567a84538";

    private MiningFanInComparison() { }

    static void write(Path output) {
        var baseline = new AutonomousProductionMiningRunner().runPinned(1);
        var generation = baseline.generation();
        var report = JSON.createObjectNode();
        report.put("schema", "regelsuche.mining-fan-in-comparison/v1");
        report.put("planCommit", PLAN);
        // Each decision dispatches one batch. The runner checks its live input against a snapshot replay.
        report.put("minerInvocationsPerDispatch", 2);
        try {
            report.set("brief", JSON.readTree(generation.brief().toCanonicalJson()));
            report.set("generation", JSON.readTree(generation.toCanonicalJson()));
            report.set("generationReceipt", JSON.readTree(generation.receipt().toCanonicalJson()));
            report.set("seedCatalog", JSON.readTree(generation.seedCatalog().toCanonicalJson()));
            var production = report.putArray("production");
            var isolated = report.putArray("noFanIn");
            for (var batch : List.of(baseline.fullBatch(), baseline.rejectionBatch())) {
                production.add(evidence(batch));
                var inputs = generation.observations().stream()
                    .filter(item -> batch.decision().inputs().contains(item.branch())).toList();
                var control = AutonomousProductionMiningRunner.mineBatch(generation.brief(),
                    baseline.plan(), batch.decision(), inputs, SupportScope.SINGLE_OBSERVATION);
                isolated.add(evidence(control));
            }
            Path destination = output.toAbsolutePath();
            Files.createDirectories(destination.getParent());
            Path pending = Files.createTempFile(destination.getParent(), "fan-in-", ".json");
            try {
                Files.write(pending, JSON.writeValueAsBytes(report));
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ObjectNode evidence(MiningBatch batch) throws IOException {
        var row = JSON.createObjectNode();
        row.set("decision", JSON.readTree(batch.decision().toCanonicalJson()));
        row.set("mining", JSON.readTree(batch.evidence().toJson()));
        row.set("receipt", JSON.readTree(batch.binding().receipt().toCanonicalJson()));
        row.set("execution", JSON.readTree(batch.execution().toCanonicalJson()));
        return row;
    }
}
