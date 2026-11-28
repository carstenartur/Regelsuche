package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static de.regelsuche.evolution.SharedNodeCountStudyTest.Arm.SHARED;
import static de.regelsuche.evolution.SharedNodeCountStudyTest.Arm.SUBTREE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Technical diagnostic independent of the five registered local-tactic arms. */
@EnabledIfSystemProperty(named = "subtree.output", matches = ".+")
class SubtreeNodeCountStudyTest {
    private static final String RETAINED_FREEZE = "27bb13a8c861578912ef968ab73b2d06cb95fce7";

    @Test void compareCheckedPrimitives() throws Exception {
        var panels = new ArrayList<>(RetainedTacticDevelopmentTest.panels());
        for (var family : List.of("C", "D")) {
            panels.add(new RetainedTacticDevelopmentTest.Panel(family, RetainedTacticStudyTest.DEADLINE,
                AdmissionTransferCases.panel(family, RETAINED_FREEZE)));
        }
        var official = new ArrayList<PairedTacticStudyTest.Row>();
        var runDirectory = System.getProperty("subtree.official");
        if (runDirectory != null) {
            var directory = Path.of(runDirectory).resolve("E");
            var summary = JSON.readTree(directory.resolve("summary.json").toFile());
            assertEquals("E", summary.required("family").asText());
            assertEquals(RetainedTacticStudyTest.DEADLINE, summary.required("deadline").asLong());
            assertEquals(System.getProperty("local.freeze"), summary.required("freeze").asText());
            var inputs = new ArrayList<PairedTacticCases.Input>();
            try (var reader = Files.newBufferedReader(directory.resolve("SHARED.jsonl"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var row = JSON.readValue(line, PairedTacticStudyTest.Row.class);
                    assertEquals(inputs.size(), row.index());
                    official.add(row);
                    inputs.add(new PairedTacticCases.Input(PairedTacticCases.Family.A, row.index(),
                        new PairedTacticCases.Stratum(row.m(), row.orientation(), row.cap()), row.source(), row.target()));
                }
            }
            assertEquals(256, inputs.size());
            panels.add(new RetainedTacticDevelopmentTest.Panel("E", RetainedTacticStudyTest.DEADLINE, inputs));
        }
        var previous = JSON.readTree(Path.of(System.getProperty("subtree.research"),
            "prospective-retained-tactics", "results", "standalone.json").toFile());
        var rotation = Integer.parseInt(System.getProperty("subtree.rotation", "0"));
        assertTrue(rotation == 0 || rotation == 1);
        var report = new TreeMap<String,TreeMap<SharedNodeCountStudyTest.Arm,SharedNodeCountStudyTest.Summary>>();
        for (var panel : panels) {
            var arms = new TreeMap<SharedNodeCountStudyTest.Arm,SharedNodeCountStudyTest.Summary>();
            for (var arm : rotation == 0 ? List.of(SHARED, SUBTREE) : List.of(SUBTREE, SHARED)) {
                boolean heldOut = panel.name().equals("E");
                var ledger = arm == SUBTREE && heldOut ? Path.of(System.getProperty("subtree.rows")) : null;
                var summary = SharedNodeCountStudyTest.measure(arm, panel,
                    arm == SHARED && heldOut ? official : null, ledger);
                if (arm == SHARED && !heldOut) {
                    assertEquals(previous.required(panel.name()).required("SHARED").toString(),
                        JSON.valueToTree(summary).toString());
                }
                arms.put(arm, summary);
            }
            report.put(panel.name(), arms);
        }
        writeJson(Path.of(System.getProperty("subtree.output")), report);
    }
}
