package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Retrospective replay of three published panels. All comparisons use their original per-case budgets. */
@EnabledIfSystemProperty(named = "retained.development.output", matches = ".+")
class RetainedTacticDevelopmentTest {
    private static final String TACTIC_SEED = "c7141702e091ff94b5ed2e3c5af5b5d2d66136dd";

    record Panel(String name, long deadline, List<PairedTacticCases.Input> inputs) {}

    static List<Panel> panels() {
        // The legacy label slots identify the MIXED stratum only in the diagnostic ledger.
        // Its original node goal, empty assumptions and query budget are retained.
        var mixed = HornerTransferCases.mixedPanel(IntervalTransferCases.MIXED_SEED).stream()
            .map(c -> new PairedTacticCases.Input(PairedTacticCases.Family.A, c.index(),
                new PairedTacticCases.Stratum(c.distributed(), c.topology().ordinal(), 8_192),
                c.source(), c.nodeBound())).toList();
        return List.of(new Panel("MIXED", 524_288, mixed), new Panel("A", 1_048_576,
            PairedTacticCases.panel(PairedTacticCases.Family.A, TACTIC_SEED)), new Panel("B", 1_048_576,
            PairedTacticCases.panel(PairedTacticCases.Family.B, TACTIC_SEED)));
    }

    @Test
    void comparePublishedPanels() throws Exception {
        var research = Path.of(System.getProperty("retained.research"));
        var out = Files.createDirectory(Path.of(System.getProperty("retained.development.output")));
        var materials = RetainedTacticStudyTest.materials(research);
        int rotation = Integer.parseInt(System.getProperty("retained.rotation", "0"));
        if (rotation < 0 || rotation > 1) throw new IllegalArgumentException("expected rotation zero or one");
        var arms = List.of(RetainedTacticStudyTest.Arm.values());
        for (var panel : panels()) {
            var dir = Files.createDirectory(out.resolve(panel.name()));
            var summaries = new TreeMap<String,PairedTacticStudyTest.Summary>();
            var paths = new TreeMap<String,List<PairedTacticStudyTest.Step>>();
            for (var arm : rotation == 0 ? arms : arms.reversed()) {
                var engine = RetainedTacticStudyTest.engine(arm, materials, Set.of());
                long clock = engine.setup().total();
                int byDeadline = 0;
                int totalSolved = 0;
                try (var writer = Files.newBufferedWriter(dir.resolve(arm.name() + ".jsonl"), StandardCharsets.UTF_8)) {
                    for (var input : panel.inputs()) {
                        var row = PairedTacticStudyTest.run(engine, input, clock, paths);
                        writer.write(JSON.writeValueAsString(row));
                        writer.newLine();
                        clock = row.completedAt();
                        if (row.solved()) {
                            totalSolved++;
                            if (clock <= panel.deadline()) byDeadline++;
                        }
                    }
                }
                summaries.put(arm.name(), new PairedTacticStudyTest.Summary(engine.setup(), byDeadline, totalSolved, clock));
            }
            assertEquals(summaries.get("RETAINED").setup(), summaries.get("DROP_ALL").setup());
            writeJson(dir.resolve("paths.json"), paths);
            writeJson(dir.resolve("summary.json"), new TreeMap<>(Map.of("deadline", panel.deadline(), "arms", summaries)));
        }
    }
}
