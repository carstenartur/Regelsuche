package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.parse.ExpressionFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Retrospective control on the published mixed stream, with the original routing and shared-context checker. */
@EnabledIfSystemProperty(named = "mixed.ablation.output", matches = ".+")
class MixedCheckerAblationTest {
    @Test
    void withholdLearnedProvidersAfterPaidRouting() throws Exception {
        Path research = Path.of(System.getProperty("mixed.research"));
        var frozen = HornerTransferStudyTest.readFrozenInputs(research);
        var genome = TraceStrategyTransferExample.inventory();
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(frozen.snapshot()).model(), genome.contentHash());
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var inputs = HornerTransferCases.mixedPanel("efb9439d38f8f0fa4e20ce5ffbedc34fa5248519");
        var archive = research.resolve("prospective-interval-transfer/development");
        var json = HornerTransferStudyTest.JSON;
        var previous = json.readTree(archive.resolve("summary.json").toFile()).required("arms").required("FAST_DROP");
        long previousSetup = previous.required("acquisition").asLong() + previous.required("load").asLong()
            + previous.required("preparation").asLong();
        var original = json.readTree(research.resolve("prospective-tactic-transfer/development/results.json").toFile())
            .required("mixed").required("CHECKER");
        var results = new TreeMap<String, Object>();
        for (var arm : List.of(HornerTransferStudyTest.Arm.FAST, HornerTransferStudyTest.Arm.FAST_DROP)) {
            var solver = inventory.newTaskSolverWithDomainReceipts(model, true);
            var setup = new HornerTransferStudyTest.Setup(solver, frozen.acquisition(), model.loadWork(), solver.preparationWork());
            var rows = HornerTransferStudyTest.run(arm, setup, inputs, new TreeMap<>(), ExpressionFormatter::format);
            var summary = HornerTransferStudyTest.summarize(setup, rows, List.of(524_288L));
            var digest = MessageDigest.getInstance("SHA-256");
            for (var row : rows) {
                digest.update(json.writeValueAsBytes(row));
                digest.update((byte) '\n');
            }
            var hash = HexFormat.of().formatHex(digest.digest());
            if (arm == HornerTransferStudyTest.Arm.FAST) {
                assertEquals(original.required("ledgerSha256").asText(), hash);
            } else {
                long setupDifference = setup.total() - previousSetup;
                try (var input = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(archive.resolve("FAST_DROP.jsonl.gz"))), StandardCharsets.UTF_8))) {
                    for (var row : rows) {
                        var old = (ObjectNode) json.readTree(input.readLine());
                        var current = (ObjectNode) json.readTree(json.writeValueAsBytes(row));
                        assertEquals(old.remove("completedAt").asLong() + setupDifference, current.remove("completedAt").asLong());
                        assertEquals(old, current);
                    }
                    assertNull(input.readLine());
                }
            }
            results.put(arm.name(), Map.of("summary", summary, "ledgerSha256", hash,
                "learnedSteps", rows.stream().mapToLong(HornerTransferStudyTest.Row::learnedSteps).sum(),
                "query", rows.stream().mapToLong(HornerTransferStudyTest.Row::query).sum(),
                "replay", rows.stream().mapToLong(HornerTransferStudyTest.Row::replay).sum(),
                "audit", rows.stream().mapToLong(HornerTransferStudyTest.Row::audit).sum()));
        }
        HornerTransferStudyTest.writeJson(Path.of(System.getProperty("mixed.ablation.output")), results);
    }
}
