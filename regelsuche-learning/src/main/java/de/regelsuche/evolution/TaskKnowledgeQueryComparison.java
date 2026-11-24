package de.regelsuche.evolution;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fixed public cold/repeated/distinct comparison; every arm pays acquisition and independent auditing. */
public final class TaskKnowledgeQueryComparison {
    public static final String REVISION = "regelsuche.task-query-reuse/v2-second-observation";
    public enum Profile { PERSISTENT, RETAINED_PATH }
    public enum Scenario {
        COLD_ONE(1), REPEATED_SIXTEEN(16), DISTINCT_SIXTEEN(16);
        private final int blocks;
        Scenario(int blocks) { this.blocks = blocks; }
        public int blocks() { return blocks; }
    }
    public record Acquisition(CumulativeTaskKnowledge.Decision decision, long work, String generationId) { }
    public record Edge(String source, String target, String rule, boolean accepted, long verificationWork) { }
    public record Row(int block, MixedTaskKnowledgeWorkload.Case input, boolean reused, boolean solved,
            long score, String output, long queryWork, long retentionWork, long verificationWork, long replayWork,
            long auditWork, List<Edge> witness, boolean verified, String failure, KnowledgeCostGate.Cost paidIncludingAudit) {
        public Row { witness = List.copyOf(witness); }
    }
    public record Arm(int repetition, Scenario scenario, Profile profile, List<Acquisition> acquisition,
            String generationId, long setupWork, KnowledgeCostGate.Cost setupCost, List<Row> queries,
            TaskKnowledgeQuerySession.Statistics statistics, long knownWork, boolean accountingComplete,
            String failure, KnowledgeCostGate.Cost paidIncludingSetupAndAudit) {
        public Arm { acquisition = List.copyOf(acquisition); queries = List.copyOf(queries); }
    }
    public record Comparison(int repetition, Scenario scenario, boolean qualityNonRegressing,
            long observedWorkSavings, long observedWallSavingsNanos, long observedCpuSavingsNanos,
            boolean observedBothResourcePayback) { }
    public record Report(String revision, List<Arm> arms, List<Comparison> comparisons,
            KnowledgeCostGate.Cost studyPaidBeforeSerialization) {
        public Report { arms = List.copyOf(arms); comparisons = List.copyOf(comparisons); }
    }
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private final TaskKnowledgeSelfImprover.Meter meter = TaskKnowledgeSelfImprover.systemMeter();

    /** The output directory must be new. Default registered repetition count is three. */
    public Report run(Path directory, int repetitions) throws Exception {
        if (repetitions < 1 || repetitions > 9) throw new IllegalArgumentException("bounded positive repetitions required");
        var begin = meter.now();
        Files.createDirectory(directory);
        var arms = new ArrayList<Arm>();
        var comparisons = new ArrayList<Comparison>();
        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (var scenario : Scenario.values()) {
                var pair = new ArrayList<Arm>();
                for (int index = 0; index < 2; index++) {
                    var profile = ((repetition + scenario.ordinal() + index) & 1) == 0 ? Profile.PERSISTENT : Profile.RETAINED_PATH;
                    var arm = arm(repetition, scenario, profile, directory.resolve("r" + repetition + "-" + scenario + "-" + profile));
                    arms.add(arm); pair.add(arm);
                }
                var previous = pair.stream().filter(arm -> arm.profile() == Profile.PERSISTENT).findFirst().orElseThrow();
                var candidate = pair.stream().filter(arm -> arm.profile() == Profile.RETAINED_PATH).findFirst().orElseThrow();
                comparisons.add(compare(previous, candidate));
            }
        }
        return new Report(REVISION, arms, comparisons, elapsed(begin));
    }

    private Arm arm(int repetition, Scenario scenario, Profile profile, Path directory) {
        var begin = meter.now();
        var acquisition = new ArrayList<Acquisition>();
        var rows = new ArrayList<Row>();
        long setupWork = 0;
        String generation = "", failure = "";
        boolean complete = true;
        KnowledgeCostGate.Cost setupCost = new KnowledgeCostGate.Cost(0, 0);
        TaskKnowledgeQuerySession.Statistics statistics = null;
        try {
            var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
            for (var batch : MixedTaskKnowledgeWorkload.seedBatches()) {
                var update = knowledge.learnBatch(batch.lessons(), batch.validation(), 1_000_000);
                setupWork = Math.addExact(setupWork, update.work().total());
                knowledge = update.next();
                acquisition.add(new Acquisition(update.reason(), update.work().total(), knowledge.id()));
            }
            var snapshot = knowledge.save(directory);
            try (var preparation = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 4, 4_000_000)) {
                var prepared = preparation.prepare(snapshot);
                setupWork = Math.addExact(setupWork, prepared.totalWork());
                generation = prepared.knowledge().id();
                try (var queries = new TaskKnowledgeQuerySession(prepared.knowledge(),
                        profile == Profile.PERSISTENT ? 0 : 32, profile == Profile.PERSISTENT ? 0 : 4_000_000)) {
                    setupCost = elapsed(begin);
                    for (int block = 0; block < scenario.blocks(); block++) {
                        for (var input : cases(scenario, block)) {
                            var row = observe(queries, block, input);
                            rows.add(row); complete &= row.verified();
                        }
                    }
                    statistics = queries.statistics();
                    complete &= statistics.accountingComplete();
                }
            }
        } catch (Exception error) { complete = false; failure = error.toString(); }
        long knownWork = setupWork;
        for (var row : rows) knownWork = Math.addExact(knownWork, Math.addExact(row.queryWork(), row.auditWork()));
        var paid = elapsed(begin);
        return new Arm(repetition, scenario, profile, acquisition, generation, setupWork, setupCost, rows, statistics,
            knownWork, complete && paid.cpuNanos() >= 0, failure, paid);
    }

    private Row observe(TaskKnowledgeQuerySession session, int block, MixedTaskKnowledgeWorkload.Case input) {
        var begin = meter.now();
        long[] auditWork = {0};
        long queryWork = 0, retentionWork = 0, verification = 0, replay = 0, score = Long.MAX_VALUE;
        boolean reused = false, solved = false, verified = false;
        String output = "", failure = "";
        var edges = new ArrayList<Edge>();
        try {
            var task = input.task();
            var query = session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            queryWork = query.totalWork(); retentionWork = query.retentionWork();
            reused = query.reused(); solved = query.solved();
            var evidence = query.execution().solution().orElseThrow().evidence();
            verification = evidence.search().metrics().verificationWork(); replay = evidence.replayWork();
            var exact = new ExactPolynomialAnalysis(units -> auditWork[0] = Math.addExact(auditWork[0], units));
            String current = ExpressionFormatter.format(task.source());
            for (var edge : evidence.witness()) {
                String source = ExpressionFormatter.format(edge.source().expression());
                String target = ExpressionFormatter.format(edge.target().expression());
                if (!source.equals(current) || !edge.verification().accepted()) throw new IllegalStateException("invalid witness continuity");
                exact.requireEquivalent(source, target); current = target;
                edges.add(new Edge(source, target, edge.move().ruleId(), edge.verification().accepted(), edge.verification().work()));
            }
            output = ExpressionFormatter.format(evidence.incumbent().expression());
            if (!current.equals(output)) throw new IllegalStateException("unbound endpoint");
            exact.requireEquivalent(input.source(), output);
            score = nodes(evidence.incumbent().expression()); auditWork[0] = Math.addExact(auditWork[0], score);
            if (score != evidence.outputScore() || solved != (score <= input.maximumNodes()
                    && queryWork <= task.budget().totalWork() && evidence.withinBudget()))
                throw new IllegalStateException("score/budget inconsistency");
            verified = evidence.accountingComplete();
        } catch (RuntimeException error) {
            failure = error.toString();
            if (error instanceof TaskKnowledgeQuerySession.QueryFailure stopped) queryWork = stopped.knownWork();
        }
        return new Row(block, input, reused, solved, score, output, queryWork, retentionWork, verification, replay,
            auditWork[0], edges, verified, failure, elapsed(begin));
    }

    private static List<MixedTaskKnowledgeWorkload.Case> cases(Scenario scenario, int block) {
        if (scenario != Scenario.DISTINCT_SIXTEEN) return MixedTaskKnowledgeWorkload.evaluation();
        return MixedTaskKnowledgeWorkload.evaluation().stream().map(input -> new MixedTaskKnowledgeWorkload.Case(input.role(),
            VARIABLE.matcher(input.source()).replaceAll(match -> match.group() + "_q" + block), input.maximumNodes())).toList();
    }
    private static long nodes(Expr expression) {
        return switch (expression) {
            case BinaryExpr binary -> 1 + nodes(binary.left()) + nodes(binary.right());
            case FunctionExpr function -> 1 + function.arguments().stream().mapToLong(TaskKnowledgeQueryComparison::nodes).sum();
            case NumberExpr ignored -> 1;
            case VariableExpr ignored -> 1;
        };
    }
    private static Comparison compare(Arm previous, Arm candidate) {
        boolean quality = previous.accountingComplete() && candidate.accountingComplete()
            && previous.queries().size() == candidate.queries().size();
        for (int i = 0; quality && i < previous.queries().size(); i++) {
            var before = previous.queries().get(i); var after = candidate.queries().get(i);
            quality = before.verified() && after.verified() && before.input().equals(after.input())
                && (!before.solved() || after.solved()) && after.score() <= before.score();
        }
        long wall = previous.paidIncludingSetupAndAudit().wallNanos() - candidate.paidIncludingSetupAndAudit().wallNanos();
        long cpu = previous.paidIncludingSetupAndAudit().cpuNanos() - candidate.paidIncludingSetupAndAudit().cpuNanos();
        return new Comparison(previous.repetition(), previous.scenario(), quality, previous.knownWork() - candidate.knownWork(),
            wall, cpu, quality && wall > 0 && cpu > 0);
    }
    private KnowledgeCostGate.Cost elapsed(KnowledgeCostGate.Cost start) {
        var end = meter.now();
        return new KnowledgeCostGate.Cost(end.wallNanos() - start.wallNanos(),
            start.cpuNanos() < 0 || end.cpuNanos() < start.cpuNanos() ? -1 : end.cpuNanos() - start.cpuNanos());
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("new snapshot directory [repetitions=3]");
        var report = new TaskKnowledgeQueryComparison().run(Path.of(args[0]), args.length == 2 ? Integer.parseInt(args[1]) : 3);
        System.out.println(LearnedSchedulingArtifacts.json(report));
    }
}
