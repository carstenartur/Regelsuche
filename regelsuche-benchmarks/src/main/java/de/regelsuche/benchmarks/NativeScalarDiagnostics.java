package de.regelsuche.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.ThreadMXBean;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.egraph.NativeScalarSaturation.Budget;
import de.regelsuche.egraph.NativeScalarSaturation.Result;
import de.regelsuche.egraph.NativeScalarSaturation.RuleWork;
import de.regelsuche.egraph.NativeScalarSaturation.Schedule;
import de.regelsuche.egraph.proof.ExtractionCertificate;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import de.regelsuche.egraph.proof.ScalarExtractionProof;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.value.ExprValueArena;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

final class NativeScalarDiagnostics {
    private static final JsonMapper JSON = new JsonMapper();
    private static final long TIME_LIMIT_NANOS = Duration.ofMillis(50).toNanos();
    private static final String SCOPE = "Saturation, producer proof and graph observation on one thread. "
            + "A fixed-time row credits only a checked result available within the deadline.";
    private static final String WORK_SCOPE = "Structural producer work = matching fuel + class analysis evaluations "
            + "+ source, scheduler, graph, representative, extraction, guard and proof production counters. "
            + "Separate artifact replay, timing, and per-instruction machine costs are outside this sum.";

    private NativeScalarDiagnostics() {}

    static void write(String revision, Path output) throws IOException {
        byte[] panel = NativeScalarComparison.readPanel();
        var report = JSON.createObjectNode()
                .put("schema", "regelsuche.native-scalar-diagnostics/v1")
                .put("sourceRevision", revision)
                .put("panelHash", ScalarCertificateCodec.hash(panel))
                .put("timeLimitNanos", TIME_LIMIT_NANOS)
                .put("timedScope", SCOPE)
                .put("workScope", WORK_SCOPE);
        var runtime = Runtime.getRuntime();
        var allocation = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        boolean measureAllocation = allocation != null
                && allocation.isThreadAllocatedMemorySupported() && allocation.isThreadAllocatedMemoryEnabled();
        ThreadMXBean allocationMeter = measureAllocation ? allocation : null;
        report.putObject("environment")
                .put("javaVersion", System.getProperty("java.runtime.version"))
                .put("vm", System.getProperty("java.vm.name"))
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .put("architecture", System.getProperty("os.arch"))
                .put("processors", runtime.availableProcessors())
                .put("maxHeapBytes", runtime.maxMemory())
                .put("ociImageId", System.getenv("REGELSUCHE_SCALAR_DIAGNOSTICS_OCI_ID"))
                .put("threadAllocationMetered", measureAllocation);
        var rules = AstRewriteTransformationEngine.allBuiltInRules();
        var engine = new EqualitySaturation(rules);
        var parser = new ExpressionParser();
        var rows = report.putArray("rows");
        for (var row : JSON.readTree(panel)) {
            var input = parser.parseTerm(row.required("source").asText());
            var target = parser.parseTerm(row.required("target").asText());
            var premises = new ArrayList<Assumption>();
            for (JsonNode premise : row.required("assumptions")) {
                String text = premise.asText();
                premises.add(Assumption.nonZero(text.substring(0, text.length() - " != 0".length())));
            }
            var context = ScalarExtractionProof.Context.create(rules, premises, revision);
            String id = row.required("id").asText();
            for (var schedule : Schedule.values()) {
                var budget = NativeScalarComparison.budget(schedule);
                engine.saturateExactScalars(input, context, budget);
                var workRow = rows.addObject();
                var timeRow = rows.addObject();
                observe(workRow, id, input, target, context, budget, engine, allocationMeter, false);
                observe(timeRow, id, input, target, context, budget, engine, allocationMeter, true);
                recordFirstCheckedRounds(workRow, timeRow, input, target, context, budget, engine);
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        AtomicJsonFile.writeUtf8(output, report.toString() + "\n");
    }

    private static void observe(ObjectNode row, String id, Expr input, Expr target,
            ScalarExtractionProof.Context context, Budget budget, EqualitySaturation engine,
            ThreadMXBean allocation, boolean fixedTime) {
        long startBytes = allocatedBytes(allocation);
        long started = System.nanoTime();
        var observation = engine.observeExactScalars(input, context, budget);
        long elapsed = System.nanoTime() - started;
        long endBytes = allocatedBytes(allocation);
        var run = observation.result();
        boolean withinBudget = !fixedTime || elapsed <= TIME_LIMIT_NANOS;
        row.put("case", id)
                .put("schedule", budget.schedule().name())
                .put("regime", fixedTime ? "FIXED_TIME" : "FIXED_WORK")
                .put("contextHash", ScalarCertificateCodec.contextHash(context))
                .put("elapsedNanos", elapsed)
                .put("withinBudget", withinBudget)
                .put("rounds", run.rounds())
                .put("terminal", withinBudget ? run.terminal() : "TIME_BUDGET_INCONCLUSIVE");
        row.set("budget", JSON.valueToTree(budget));
        if (startBytes < 0 || endBytes < startBytes) {
            row.putNull("threadAllocatedBytes");
        } else {
            row.put("threadAllocatedBytes", endBytes - startBytes);
        }
        var graph = row.putObject("graph").put("nodeCount", run.graphNodes()).put("classCount", run.analyses().size());
        graph.set("nodesByKind", JSON.valueToTree(observation.nodesByKind()));
        graph.set("classesContainingKind", JSON.valueToTree(observation.classesContainingKind()));
        var ac = acOccurrences(input, null);
        var compression = row.putObject("inputAcCompression")
                .put("binaryOperatorOccurrences", ac.binary())
                .put("flattenedOperatorOccurrences", ac.flattened());
        if (ac.flattened() == 0) {
            compression.putNull("ratio");
        } else {
            compression.put("ratio", (double) ac.binary() / ac.flattened());
        }
        row.set("ruleLedger", JSON.valueToTree(run.ledger()));
        row.set("guards", JSON.valueToTree(run.guards().stream()
                .map(guard -> JSON.createObjectNode().put("rule", guard.rule())
                        .put("factor", ExpressionFormatter.format(guard.factor()))
                        .put("truth", guard.result()).put("applied", guard.applied())).toList()));
        long replay = proofs(row, input, context, run);
        var work = run.ledger().values();
        long matching = work.stream().mapToLong(RuleWork::fuelSpent).sum();
        logWork(row, observation.producerStages(), work, matching, run.analysisEvaluations(), replay);
        if (withinBudget) {
            row.put("reached", reached(run, target));
        } else {
            row.putNull("reached");
        }
    }

    private static void logWork(ObjectNode row, Map<String,Long> stages, Collection<RuleWork> work,
            long matching, long analysis, long replay) {
        var workReport = row.putObject("work")
                .put("matchedRuleApplications", work.stream().mapToLong(RuleWork::matches).sum())
                .put("ruleMerges", work.stream().mapToLong(RuleWork::unions).sum())
                .put("matchingFuel", matching)
                .put("analysisEvaluations", analysis)
                .put("proofReplayWork", replay)
                .put("totalMechanicalUnits", matching + analysis + stages.values().stream().mapToLong(Long::longValue).sum());
        workReport.set("producerStages", JSON.valueToTree(new TreeMap<>(stages)));
    }

    private static void recordFirstCheckedRounds(ObjectNode workRow, ObjectNode timeRow, Expr input, Expr target,
            ScalarExtractionProof.Context context, Budget budget, EqualitySaturation engine) {
        int terminalRound = workRow.required("rounds").asInt();
        int firstWorkRound = 0;
        int firstTimedRound = 0;
        for (int round = 1; round < terminalRound && (firstWorkRound == 0 || firstTimedRound == 0); round++) {
            var prefixBudget = new Budget(round, budget.nodes(), budget.perRuleFuel(), budget.schedule());
            long started = System.nanoTime();
            var prefix = engine.observeExactScalars(input, context, prefixBudget).result();
            long elapsed = System.nanoTime() - started;
            if (reached(prefix, target)) {
                if (firstWorkRound == 0) {
                    firstWorkRound = round;
                }
                if (firstTimedRound == 0 && elapsed <= TIME_LIMIT_NANOS) {
                    firstTimedRound = round;
                }
            }
        }
        putFirstRound(workRow, firstWorkRound);
        putFirstRound(timeRow, firstTimedRound);
    }

    private static void putFirstRound(ObjectNode row, int prefixRound) {
        if (prefixRound > 0) {
            row.put("firstCheckedRound", prefixRound);
        } else if (row.required("reached").asBoolean()) {
            row.put("firstCheckedRound", row.required("rounds").asInt());
        } else {
            row.putNull("firstCheckedRound");
        }
    }

    private static boolean reached(Result run, Expr target) {
        var chosen = run.extractions().getFirst();
        if (chosen.proof().isEmpty()) {
            return false;
        }
        try (var arena = new ExprValueArena(4096)) {
            return arena.intern(target) == arena.intern(chosen.expression());
        }
    }

    private static long proofs(ObjectNode row, Expr input, ScalarExtractionProof.Context context,
            Result run) {
        var extractions = row.putArray("extractions");
        long total = 0;
        for (var extraction : run.extractions()) {
            var entry = extractions.addObject().put("objective", extraction.objective())
                    .put("authorized", ExpressionFormatter.format(extraction.expression()))
                    .put("status", extraction.status())
                    .put("proofSteps", extraction.proofSteps());
            if (extraction.proof().isEmpty()) {
                entry.putNull("proofPremiseEdges").putNull("proofDepth")
                        .putNull("termNodes").putNull("termEdges").putNull("detachedReplayWork");
                continue;
            }
            var proof = extraction.proof().orElseThrow();
            var dag = proof.dag();
            long replay = ScalarExtractionProof.detachedReplayWork(input, extraction.expression(),
                    context, proof, ProofLimits.defaults()).orElseThrow();
            total += replay;
            entry.put("proofPremiseEdges", dag.steps().stream().mapToLong(step -> premises(step).count()).sum())
                    .put("proofDepth", proofDepth(dag))
                    .put("termNodes", dag.terms().size())
                    .put("termEdges", dag.terms().stream().mapToInt(term -> term.children().size()).sum())
                    .put("detachedReplayWork", replay);
        }
        return total;
    }

    private static int proofDepth(ExtractionCertificate dag) {
        var depths = new int[dag.steps().size()];
        for (int i = 0; i < depths.length; i++) {
            depths[i] = 1 + premises(dag.steps().get(i)).map(parent -> depths[parent]).max().orElse(0);
        }
        return depths[dag.conclusion()];
    }

    private static IntStream premises(ExtractionCertificate.Step step) {
        return switch (step) {
            case ExtractionCertificate.Symmetry symmetry -> IntStream.of(symmetry.premise());
            case ExtractionCertificate.Transitivity transitivity -> IntStream.of(transitivity.first(), transitivity.second());
            case ExtractionCertificate.Congruence congruence -> congruence.childProofs().stream().mapToInt(Integer::intValue);
            case ExtractionCertificate.Reflexivity ignored -> IntStream.empty();
            case ExtractionCertificate.PatternInstance ignored -> IntStream.empty();
        };
    }

    private record AcOccurrences(int binary, int flattened) {
        AcOccurrences plus(AcOccurrences other) {
            return new AcOccurrences(binary + other.binary, flattened + other.flattened);
        }
    }

    private static AcOccurrences acOccurrences(Expr expression, BinaryOperator parent) {
        if (expression instanceof BinaryExpr binary) {
            var operator = binary.operator();
            boolean ac = operator == BinaryOperator.ADD || operator == BinaryOperator.MUL;
            var children = acOccurrences(binary.left(), operator).plus(acOccurrences(binary.right(), operator));
            return children.plus(new AcOccurrences(ac ? 1 : 0, ac && operator != parent ? 1 : 0));
        }
        var occurrences = new AcOccurrences(0, 0);
        if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                occurrences = occurrences.plus(acOccurrences(argument, null));
            }
        }
        return occurrences;
    }

    private static long allocatedBytes(ThreadMXBean allocation) {
        return allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
