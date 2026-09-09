package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.moves.*;
import java.util.ArrayList;
import java.util.List;

/** Executes every preregistered row, including losses and unsupported controls, against one frozen model. */
public final class LearnedSchedulingStudy {
    public record Row(String caseId, String configuration, long budget, String status, MoveSearch.Result result, long wallNanos) {
        public String id() { return caseId + "-" + configuration + "-" + budget; }
        public boolean reached() { return result != null && result.reached(); }
    }
    public record Reference(String caseId, MoveSearch.Result base, MoveSearch.Result learned, boolean inclusion) {}
    public record Report(String protocol, LearnedSchedulingModel model, List<Row> rows, List<Reference> references, long trainingWallNanos) {
        public Report { rows = List.copyOf(rows); references = List.copyOf(references); }
    }
    public static Report run() {
        String protocol = LearnedSchedulingProtocol.canonicalJson();
        long trainingStart = System.nanoTime(); var model = LearnedSchedulingModel.train(); long trainingNanos = System.nanoTime() - trainingStart;
        rejectOverlap(model, LearnedSchedulingProtocol.cases());
        String before = LearnedSchedulingArtifacts.modelJson(model);
        var rows = new ArrayList<Row>();
        for (var example : LearnedSchedulingProtocol.cases()) for (long budget : LearnedSchedulingProtocol.WORK_BUDGETS)
            for (var configuration : LearnedSchedulingProtocol.configurations()) rows.add(runCase(model, example, configuration, budget));
        var references = new ArrayList<Reference>();
        for (var example : LearnedSchedulingProtocol.cases()) if (example.polynomial()) {
            var base = reference(model, example, LearnedSchedulingProtocol.configurations().getFirst());
            var learned = reference(model, example, LearnedSchedulingProtocol.configurations().get(2));
            var learnedExpressions = learned.reachedStates().stream().map(MoveState::expression).collect(java.util.stream.Collectors.toSet());
            boolean inclusion = base.completeBoundedRelation() && learned.completeBoundedRelation()
                && base.reachedStates().stream().allMatch(state -> learnedExpressions.contains(state.expression()));
            references.add(new Reference(example.id(), base, learned, inclusion));
        }
        if (!before.equals(LearnedSchedulingArtifacts.modelJson(model))) throw new IllegalStateException("evaluation mutated frozen knowledge or policy");
        return new Report(protocol, model, rows, references, trainingNanos);
    }
    public static void rejectOverlap(LearnedSchedulingModel model, List<LearnedSchedulingProtocol.Case> cases) {
        var exact = new ExactPolynomialAnalysis();
        var excluded = new java.util.HashSet<>(model.knowledge().excludedIdentities());
        for (var development : LearnedRuleBaselineExperiment.cases()) {
            try { excluded.add(exact.alphaIdentity(development.source())); } catch (IllegalArgumentException unsupported) { /* explicit unsupported controls */ }
        }
        for (var example : cases) if (example.polynomial() && excluded.contains(exact.alphaIdentity(example.source())))
            throw new IllegalArgumentException("TRAIN/development overlap: " + example.id());
    }
    public static Row runCase(LearnedSchedulingModel model, LearnedSchedulingProtocol.Case example,
            LearnedSchedulingProtocol.Configuration configuration, long budget) {
        if (!example.polynomial()) return new Row(example.id(), configuration.id(), budget, "UNSUPPORTED_POLYNOMIAL_FRAGMENT", null, 0);
        long start = System.nanoTime();
        var result = new MoveSearch().search(problem(model, example, configuration, budget, false));
        if (result.reached() && result.metrics().totalWork() > budget) throw new IllegalStateException("over-budget success");
        return new Row(example.id(), configuration.id(), budget, result.outcome().name(), result, System.nanoTime() - start);
    }
    private static MoveSearch.Result reference(LearnedSchedulingModel model, LearnedSchedulingProtocol.Case example,
            LearnedSchedulingProtocol.Configuration configuration) {
        return new MoveSearch().search(problem(model, example, configuration, 1000000, true));
    }
    private static MoveSearch.Problem problem(LearnedSchedulingModel model, LearnedSchedulingProtocol.Case example,
            LearnedSchedulingProtocol.Configuration configuration, long budget, boolean reference) {
        return new MoveSearch.Problem(LearnedSchedulingModel.format(example.source()),
            MoveContext.frozen(reference ? "REFERENCE_ENUMERATION_NO_TARGET" : LearnedSchedulingModel.format(example.target())),
            model.providers(configuration), model.policy(configuration), new PrimitiveReplayMoveVerifier(model.knowledge().inventory()),
            state -> new ExpressionScorer().score(state.expression()).weightedTotal(),
            reference ? MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE : MoveSearch.Mode.FAST, configuration.scheduling(),
            new MoveSearch.Budget(9, 9, 0, reference ? 4096 : 256, budget, 24), model.stateValue(configuration));
    }
    public static void main(String[] args) throws Exception {
        var report = run(); var output = java.nio.file.Path.of(args.length == 0 ? "build/reports/learned-scheduling" : args[0]);
        System.out.println(LearnedSchedulingArtifacts.write(report, output));
        System.out.println(LearnedSchedulingArtifacts.summaryJson(report));
    }
}
