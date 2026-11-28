package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.Expr;
import de.regelsuche.evolution.HornerTransferStudyTest.Arm;
import de.regelsuche.evolution.HornerTransferStudyTest.Row;
import de.regelsuche.evolution.HornerTransferStudyTest.Step;
import de.regelsuche.evolution.HornerTransferStudyTest.Summary;
import de.regelsuche.parse.ExpressionFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class IntervalTransferStudyTest {
    private static final String PROTOCOL = "sha256:e2ed7fb16070265c7a2e2e838ed0933448a14a0662be0f46640c854e8bacd6f4";
    private static final List<Arm> ARMS = List.of(Arm.BASE, Arm.REGEN, Arm.DROP, Arm.FAST, Arm.FAST_DROP);

    @Test
    void generatesAConstantHeightOverConsecutiveOrientedWidths() {
        var right = new IntervalTransferCases.Stratum(3, IntervalTransferCases.Orientation.R);
        assertEquals("(s-p)*(a+35008)+(r-s)*(a+35008)+(q-r)*(a+35008)",
            IntervalTransferCases.source(5007, right, List.of(0, 3, 2, 1)));
        var left = new IntervalTransferCases.Stratum(2, IntervalTransferCases.Orientation.L);
        String example = IntervalTransferCases.source(5005, left, List.of(1, 2, 0));
        assertEquals("(a+35006)*(r-q)+(a+35006)*(p-r)", example);
        assertEquals(11, new IntervalTransferCases.Input(5005, left, example).nodeBound());
    }

    @Test
    @EnabledIfSystemProperty(named = "interval.phase", matches = "final|development")
    void evaluateFrozenStream() throws IOException {
        Path research = Path.of(System.getProperty("interval.research"));
        HornerTransferStudyTest.readChecked(research.resolve("prospective-interval-transfer/protocol.md"), PROTOCOL);
        var frozen = HornerTransferStudyTest.readFrozenInputs(research);
        boolean development = System.getProperty("interval.phase").equals("development");
        String seed = development ? IntervalTransferCases.MIXED_SEED : System.getProperty("interval.freeze");
        long primary = development ? 524_288L : 262_144L;
        var finalCases = development ? List.<IntervalTransferCases.Input>of() : IntervalTransferCases.panel(seed);
        if (!development) IntervalTransferCases.verifySplit(finalCases);
        List<? extends TransferStudyInput> inputs = development ? HornerTransferCases.mixedPanel(seed) : finalCases;
        var order = switch (Integer.parseInt(System.getProperty("interval.rotation"))) {
            case 0 -> ARMS;
            case 1 -> ARMS.reversed();
            default -> throw new IllegalArgumentException("rotation must be zero or one");
        };
        Path out = Files.createDirectory(Path.of(System.getProperty("interval.output")));
        var summaries = new EnumMap<Arm, Summary>(Arm.class);
        var elapsed = new EnumMap<Arm, Long>(Arm.class);
        Map<String, List<Step>> paths = new TreeMap<>();
        Function<Expr, String> format = development ? ExpressionFormatter::format : ExpressionFormatter::formatStructure;
        for (Arm arm : order) {
            long start = System.nanoTime();
            var setup = HornerTransferStudyTest.prepare(arm, frozen);
            var rows = HornerTransferStudyTest.run(arm, setup, inputs, paths, format);
            elapsed.put(arm, System.nanoTime() - start);
            var file = out.resolve(arm + ".jsonl");
            if (development) HornerTransferStudyTest.writeRows(file, rows);
            else writeIntervalRows(file, rows, finalCases);
            summaries.put(arm, HornerTransferStudyTest.summarize(setup, rows, List.of(primary)));
        }
        int base = summaries.get(Arm.BASE).solvedAt(primary);
        int regen = summaries.get(Arm.REGEN).solvedAt(primary);
        int fast = summaries.get(Arm.FAST).solvedAt(primary);
        var report = Map.of("freeze", seed, "protocol", PROTOCOL, "model", HornerTransferStudyTest.SNAPSHOT,
            "primaryBudget", primary, "transferPassed", regen > Math.max(base, summaries.get(Arm.DROP).solvedAt(primary)),
            "optimizedTransferPassed", fast > Math.max(base, Math.max(regen, summaries.get(Arm.FAST_DROP).solvedAt(primary))),
            "improvedOnRegenAtPrimary", fast > regen, "arms", summaries);
        HornerTransferStudyTest.writeJson(out.resolve("summary.json"), new TreeMap<>(report));
        HornerTransferStudyTest.writeJson(out.resolve("paths.json"), paths);
        HornerTransferStudyTest.writeJson(out.resolve("elapsed-nanos.json"), elapsed);
    }

    private static void writeIntervalRows(Path file, List<Row> rows, List<IntervalTransferCases.Input> inputs) throws IOException {
        try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
            for (Row row : rows) {
                var input = inputs.get(row.index());
                ObjectNode record = JSON.valueToTree(row);
                record.remove("distributed");
                record.put("cells", input.stratum().cells());
                record.put("orientation", input.stratum().orientation().name());
                record.put("nodeBound", input.nodeBound());
                writer.write(JSON.writeValueAsString(record));
                writer.newLine();
            }
        }
    }
}
