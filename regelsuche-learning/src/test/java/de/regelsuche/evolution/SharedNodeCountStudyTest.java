package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Supplementary primitive-only technical control, outside the original ten-arm study. */
@EnabledIfSystemProperty(named = "shared.counts.output", matches = ".+")
class SharedNodeCountStudyTest {
    enum Arm { BASE, SHARED, SUBTREE }
    record Summary(long setup, int totalSolved, int byDeadline, long fullCost, long query,
            long verificationInSearch, long replay, long audit, int proofPaths, String rowSha256) {}

    @Test void comparePrimitiveRuns() throws Exception {
        int rotation = Integer.parseInt(System.getProperty("shared.counts.rotation", "0"));
        if (rotation < 0 || rotation > 1) throw new IllegalArgumentException("expected rotation zero or one");
        var panels = new ArrayList<>(RetainedTacticDevelopmentTest.panels());
        var published = new TreeMap<String,List<PairedTacticStudyTest.Row>>();
        String directory = System.getProperty("shared.counts.official");
        if (directory != null) {
            String seed = System.getProperty("retained.freeze");
            if (seed == null || !seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected official freeze SHA");
            for (String family : List.of("C", "D")) {
                var dir = Path.of(directory).resolve(family);
                var summary = JSON.readTree(dir.resolve("summary.json").toFile());
                assertEquals(seed, summary.required("freeze").asText());
                assertEquals(family, summary.required("family").asText());
                assertEquals(RetainedTacticStudyTest.DEADLINE, summary.required("deadline").asLong());
                var rows = new ArrayList<PairedTacticStudyTest.Row>();
                var inputs = new ArrayList<PairedTacticCases.Input>();
                var label = family.equals("C") ? PairedTacticCases.Family.A : PairedTacticCases.Family.B;
                try (var reader = Files.newBufferedReader(dir.resolve("BASE.jsonl"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        var row = JSON.readValue(line, PairedTacticStudyTest.Row.class);
                        assertEquals(rows.size(), row.index());
                        rows.add(row);
                        inputs.add(new PairedTacticCases.Input(label, row.index(),
                            new PairedTacticCases.Stratum(row.m(), row.orientation(), row.cap()), row.source(), row.target()));
                    }
                }
                assertEquals(512, inputs.size());
                panels.add(new RetainedTacticDevelopmentTest.Panel(family, RetainedTacticStudyTest.DEADLINE, inputs));
                published.put(family, rows);
            }
        }
        var reports = new TreeMap<String,TreeMap<Arm,Summary>>();
        for (var panel : panels) {
            var results = new TreeMap<Arm,Summary>();
            for (var arm : rotation == 0 ? List.of(Arm.BASE, Arm.SHARED) : List.of(Arm.SHARED, Arm.BASE))
                results.put(arm, measure(arm, panel, arm == Arm.BASE ? published.get(panel.name()) : null));
            reports.put(panel.name(), results);
        }
        writeJson(Path.of(System.getProperty("shared.counts.output")), reports);
    }

    static Summary measure(Arm arm, RetainedTacticDevelopmentTest.Panel panel,
            List<PairedTacticStudyTest.Row> official) throws IOException, NoSuchAlgorithmException {
        return measure(arm, panel, official, null);
    }

    static Summary measure(Arm arm, RetainedTacticDevelopmentTest.Panel panel,
            List<PairedTacticStudyTest.Row> official, Path ledger) throws IOException, NoSuchAlgorithmException {
        var solver = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newTaskSolver();
        var setup = new PairedTacticStudyTest.Setup(0, 0, 0, solver.preparationWork());
        var engine = new PairedTacticStudyTest.Engine(task -> {
            var result = switch (arm) {
                case BASE -> solver.solve(task);
                case SHARED -> solver.solveWithSharedNodeCounts(task);
                case SUBTREE -> solver.solveWithSubtreeNodeCounts(task);
            };
            return new PairedTacticStudyTest.Sample(0, result.evidence());
        }, setup);
        var paths = new TreeMap<String,List<PairedTacticStudyTest.Step>>();
        var rows = ledger == null ? null : new ArrayList<PairedTacticStudyTest.Row>();
        var hash = MessageDigest.getInstance("SHA-256");
        long clock = setup.total(), query = 0, verification = 0, replay = 0, audit = 0;
        int solved = 0, timely = 0;
        for (var input : panel.inputs()) {
            var row = PairedTacticStudyTest.run(engine, input, clock, paths);
            if (official != null) assertEquals(official.get(input.index()), row);
            if (rows != null) rows.add(row);
            assertTrue(row.learnedSchemas().isEmpty());
            clock = row.completedAt();
            query += row.query();
            verification += row.verificationWithinQuery();
            replay += row.replay();
            audit += row.audit();
            if (row.solved()) {
                solved++;
                if (clock <= panel.deadline()) timely++;
            }
            hash.update(JSON.writeValueAsBytes(row));
            hash.update((byte) '\n');
        }
        assertEquals(setup.total() + query + replay + audit, clock);
        if (ledger != null) {
            Files.createDirectory(ledger);
            PairedTacticStudyTest.writeLines(ledger.resolve(arm.name() + ".jsonl"), rows);
            writeJson(ledger.resolve("paths.json"), paths);
        }
        return new Summary(setup.total(), solved, timely, clock, query, verification, replay, audit,
            paths.size(), HexFormat.of().formatHex(hash.digest()));
    }
}
