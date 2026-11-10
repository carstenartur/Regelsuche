package de.regelsuche.evolution;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Preferred prospective learning evaluation; its public inputs are not a sealed holdout. */
public final class MixedTaskKnowledgeStudy {
    private final TaskKnowledgeSelfImprover.Meter meter;

    private MixedTaskKnowledgeStudy() { this(TaskKnowledgeSelfImprover.systemMeter()); }
    MixedTaskKnowledgeStudy(TaskKnowledgeSelfImprover.Meter meter) {
        this.meter = java.util.Objects.requireNonNull(meter);
    }
    KnowledgeCostGate.Cost now() { return meter.now(); }
    KnowledgeCostGate.Cost elapsed(KnowledgeCostGate.Cost start) {
        var end = now();
        return new KnowledgeCostGate.Cost(end.wallNanos() - start.wallNanos(),
            start.cpuNanos() < 0 || end.cpuNanos() < 0 ? -1 : end.cpuNanos() - start.cpuNanos());
    }
    /** Exact canonical split checks include seed acquisition, all later attempts, and their validation. */
    public static long checkSeparation() {
        var training = Stream.concat(MixedTaskKnowledgeWorkload.seedBatches().stream(),
            MixedTaskKnowledgeWorkload.improvementBatches().stream())
            .flatMap(b -> Stream.concat(b.lessons().stream(), b.validation().stream())).toList();
        var promotion = MixedTaskKnowledgeWorkload.promotionTasks();
        var evaluation = MixedTaskKnowledgeWorkload.evaluation().stream().map(MixedTaskKnowledgeWorkload.Case::task).toList();
        return Math.addExact(Math.addExact(CumulativeTaskKnowledge.disjoint(training, promotion),
            CumulativeTaskKnowledge.disjoint(training, evaluation)), CumulativeTaskKnowledge.disjoint(promotion, evaluation));
    }
    private Map<String, Object> arm(boolean improve, int blocks, Path directory) {
        var start = now();
        var report = new TreeMap<String, Object>();
        var acquisition = new ArrayList<Map<String, Object>>();
        var rows = new ArrayList<Map<String, Object>>();
        long knownWork = 0;
        boolean complete = true;
        report.put("profile", improve ? "MIXED_MEASURED_IMPROVEMENT" : "PREPARED_REUSE");
        report.put("acquisition", acquisition); report.put("queries", rows);
        try {
            var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
            for (var batch : MixedTaskKnowledgeWorkload.seedBatches()) {
                var update = knowledge.learnBatch(batch.lessons(), batch.validation(), 1_000_000);
                knownWork = Math.addExact(knownWork, update.work().total());
                knowledge = update.next();
                acquisition.add(Map.of("decision", update.reason(), "work", update.work(), "generationId", knowledge.id()));
            }
            Path snapshot = knowledge.save(directory);
            try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 4, 4_000_000)) {
                var loaded = session.prepare(snapshot);
                knowledge = loaded.knowledge();
                knownWork = Math.addExact(knownWork, loaded.preparationWork() + loaded.lookupWork());
                report.put("seedId", knowledge.id());
                if (improve) {
                    var policy = new TaskKnowledgeSelfImprover.Policy(5_000_000, 120_000_000_000L,
                        120_000_000_000L, 2, 3, (long) blocks * MixedTaskKnowledgeWorkload.evaluation().size());
                    var result = new TaskKnowledgeSelfImprover().improve(knowledge,
                        MixedTaskKnowledgeWorkload.improvementBatches(), policy);
                    knowledge = result.active();
                    knownWork = Math.addExact(knownWork, result.knownWork());
                    complete &= result.accountingComplete();
                    report.put("improvement", Map.of("attempts", result.attempts(), "knownWork", result.knownWork(),
                        "paid", result.paid(), "accountingComplete", result.accountingComplete(), "budgetExhausted", result.budgetExhausted()));
                    snapshot = knowledge.save(directory);
                    knownWork = Math.addExact(knownWork, session.retainVerified(knowledge).lookupWork());
                }
                report.put("activeId", knowledge.id());
                report.put("setupCost", elapsed(start));
                for (int block = 0; block < blocks; block++) {
                    var reused = session.prepare(snapshot);
                    knownWork = Math.addExact(knownWork, reused.preparationWork() + reused.lookupWork());
                    for (var entry : MixedTaskKnowledgeWorkload.evaluation()) {
                        var begin = now();
                        var task = entry.task();
                        var execution = reused.knowledge().solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                        knownWork = Math.addExact(knownWork, execution.totalWork());
                        long[] auditWork = {0};
                        var row = new TreeMap<String, Object>();
                        rows.add(row); row.put("block", block); row.put("case", entry);
                        row.put("nativeWork", execution.totalWork()); row.put("solved", execution.solved());
                        try {
                            var evidence = execution.solution().orElseThrow().evidence();
                            var exact = new ExactPolynomialAnalysis(units -> auditWork[0] = Math.addExact(auditWork[0], units));
                            String current = ExpressionFormatter.format(task.source());
                            for (var edge : evidence.witness()) {
                                String source = ExpressionFormatter.format(edge.source().expression());
                                String target = ExpressionFormatter.format(edge.target().expression());
                                if (!source.equals(current)) throw new IllegalStateException("disconnected witness");
                                exact.requireEquivalent(source, target); current = target;
                            }
                            String output = ExpressionFormatter.format(evidence.incumbent().expression());
                            if (!current.equals(output)) throw new IllegalStateException("unbound output");
                            exact.requireEquivalent(entry.source(), output);
                            long nodes = nodes(evidence.incumbent().expression());
                            auditWork[0] = Math.addExact(auditWork[0], nodes);
                            if (nodes != evidence.outputScore() || execution.solved() != (nodes <= entry.maximumNodes()
                                    && execution.totalWork() <= task.budget().totalWork() && evidence.withinBudget()))
                                throw new IllegalStateException("score/goal inconsistency");
                            complete &= evidence.accountingComplete();
                            row.put("output", output); row.put("score", nodes); row.put("verified", true);
                        } catch (RuntimeException failure) {
                            complete = false; row.put("verified", false); row.put("failure", failure.toString());
                        } finally {
                            knownWork = Math.addExact(knownWork, auditWork[0]);
                            row.put("auditWork", auditWork[0]); row.put("paidIncludingAudit", elapsed(begin));
                        }
                    }
                }
            }
        } catch (Exception failure) {
            complete = false; report.put("failure", failure.toString());
        } finally {
            var paid = elapsed(start);
            report.put("paidIncludingSetupAndAudit", paid); report.put("knownWork", knownWork);
            report.put("accountingComplete", complete && paid.cpuNanos() >= 0);
        }
        return report;
    }
    private static long nodes(Expr expression) {
        return switch (expression) {
            case BinaryExpr b -> 1 + nodes(b.left()) + nodes(b.right());
            case FunctionExpr f -> 1 + f.arguments().stream().mapToLong(MixedTaskKnowledgeStudy::nodes).sum();
            case NumberExpr ignored -> 1;
            case VariableExpr ignored -> 1;
        };
    }
    private static Map<String, Object> compare(int repetition, Map<String, Object> baseline, Map<String, Object> candidate) {
        var before = CheckedSchemaSupport.JSON.valueToTree(baseline);
        var after = CheckedSchemaSupport.JSON.valueToTree(candidate);
        boolean quality = before.path("accountingComplete").asBoolean() && after.path("accountingComplete").asBoolean()
            && before.path("queries").size() == after.path("queries").size();
        for (int i = 0; quality && i < before.path("queries").size(); i++) {
            var b = before.path("queries").get(i); var a = after.path("queries").get(i);
            quality &= b.path("verified").asBoolean() && a.path("verified").asBoolean()
                && b.path("case").equals(a.path("case")) && (!b.path("solved").asBoolean() || a.path("solved").asBoolean())
                && a.path("score").asLong(Long.MAX_VALUE) <= b.path("score").asLong(Long.MAX_VALUE);
        }
        long wall = before.path("paidIncludingSetupAndAudit").path("wallNanos").asLong()
            - after.path("paidIncludingSetupAndAudit").path("wallNanos").asLong();
        long cpu = before.path("paidIncludingSetupAndAudit").path("cpuNanos").asLong()
            - after.path("paidIncludingSetupAndAudit").path("cpuNanos").asLong();
        return Map.of("repetition", repetition, "qualityNonRegressing", quality,
            "observedWallSavingsNanos", wall, "observedCpuSavingsNanos", cpu,
            "observedBothResourcePayback", quality && wall > 0 && cpu > 0);
    }

    /** CLI: output directory [blocks=16] [repetitions=3]; stdout is one JSON report. */
    public static void main(String[] args) throws Exception {
        new MixedTaskKnowledgeStudy().run(args);
    }

    private void run(String[] args) throws Exception {
        var start = now();
        if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("directory [blocks] [repetitions]");
        Path directory = Path.of(args[0]);
        int blocks = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int repetitions = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        if (blocks < 1 || blocks > 128 || repetitions < 1 || repetitions > 9)
            throw new IllegalArgumentException("bounded positive blocks/repetitions required");
        Files.createDirectory(directory);
        var report = new TreeMap<String, Object>();
        report.put("revision", MixedTaskKnowledgeWorkload.REVISION);
        report.put("informationRegime", "PUBLIC_PROSPECTIVE_WITHIN_FAMILY_ALPHA_POLYNOMIAL_DISJOINT");
        report.put("blocks", blocks); report.put("repetitions", repetitions);
        report.put("improvementCostScope", TaskKnowledgeSelfImprover.CostScope.IMPROVEMENT_RUN.name());
        report.put("promotion", MixedTaskKnowledgeWorkload.promotion()); report.put("evaluation", MixedTaskKnowledgeWorkload.evaluation());
        report.put("separationWork", checkSeparation());
        var arms = new ArrayList<Map<String, Object>>();
        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (int index = 0; index < 2; index++) {
                boolean improve = ((repetition + index) & 1) == 1;
                var result = arm(improve, blocks, directory.resolve("r" + repetition + "-" + index));
                result.put("repetition", repetition); arms.add(result);
            }
        }
        report.put("arms", arms);
        var comparisons = new ArrayList<Map<String, Object>>();
        for (int repetition = 0; repetition < repetitions; repetition++) {
            var first = arms.get(2 * repetition); var second = arms.get(2 * repetition + 1);
            comparisons.add(compare(repetition, first.get("profile").equals("PREPARED_REUSE") ? first : second,
                first.get("profile").equals("PREPARED_REUSE") ? second : first));
        }
        report.put("comparisons", comparisons);
        report.put("studyPaidBeforeSerialization", elapsed(start));
        System.out.println(LearnedSchedulingArtifacts.json(report));
    }
}
