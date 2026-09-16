package de.regelsuche.evolution;

import de.regelsuche.ast.Expr;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.BindingLimits;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Observation;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bridges observed primitive paths to the model, and model constraints to actual dispatch. */
final class TraceBindingDispatch {
    private final TraceBindingModel.Session session;
    private final ExpressionParser parser = new ExpressionParser();
    private final Map<String, Expr> parsed = new HashMap<>();

    TraceBindingDispatch(TraceBindingModel model) {
        session = model.session();
    }

    static TraceBindingModel learn(TraceRewriteStrategyLearner.FrozenStrategy formation,
            List<Observation> baseline, BindingLimits limits) {
        var work = new TraceBindingModel.FormationWork(limits.maximumFormationWork());
        work.charge();
        var inventory = formation.inventory();
        var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
        var genes = new HashMap<String, String>();
        for (int i = 0; i < rules.size(); i++) {
            work.charge();
            genes.put(rules.get(i).id(), inventory.rewrites().get(i).geneId());
        }
        Set<List<String>> admitted = formation.observations().stream()
            .map(TraceRewriteStrategyLearner.Observation::geneSequence).filter(sequence -> sequence.size() >= 2)
            .collect(Collectors.toSet());
        var parser = new ExpressionParser();
        var traces = new ArrayList<TraceBindingModel.Trace>();
        for (var observation : baseline) {
            work.charge();
            var path = observation.search().bestState().transformations();
            if (path.size() < 2) continue;
            var sequence = new ArrayList<String>();
            var states = new ArrayList<Expr>();
            work.charge();
            states.add(parser.parseTerm(observation.input().expression()));
            for (var step : path) {
                work.charge();
                if (step.primitiveStepCount() != 1 || !genes.containsKey(step.rule())) {
                    throw new IllegalArgumentException("binding TRAIN requires inventoried atomic primitive paths");
                }
                sequence.add(genes.get(step.rule()));
                states.add(parser.parseTerm(step.transformedExpression()));
            }
            traces.add(new TraceBindingModel.Trace(observation.input().id(), sequence, states));
        }
        return TraceBindingModel.learn(traces, admitted, limits.maximumTemplates(),
            work, limits.maximumMatchingWorkPerExpansion());
    }

    Attempt begin(List<String> sequence, String source, Transformation first) {
        if (first.primitiveStepCount() != 1) throw new IllegalArgumentException("non-atomic dispatch prefix");
        var before = parse(source);
        var after = parse(first.transformedExpression());
        if (before == null || after == null) return new Attempt(List.of(), List.of(), sequence.size(), first.transformedExpression());
        var prefix = List.of(before, after);
        return new Attempt(session.matching(sequence, prefix), prefix, sequence.size(), first.transformedExpression());
    }

    long workUnits() { return session.workUnits(); }

    final class Attempt {
        private final List<TraceBindingModel.Template> templates;
        private final List<Expr> prefix;
        private final int primitiveSteps;
        private final String source;
        private Attempt(List<TraceBindingModel.Template> templates, List<Expr> prefix, int primitiveSteps, String source) {
            this.templates = templates;
            this.prefix = prefix;
            this.primitiveSteps = primitiveSteps;
            this.source = source;
        }
        boolean eligible() { return !templates.isEmpty(); }
        boolean accepts(Transformation suffix) {
            var states = new ArrayList<>(prefix);
            if (!appendStates(source, suffix, states, 0) || states.size() != primitiveSteps + 1) return false;
            for (var template : templates) if (session.matches(template, states)) return true;
            return false;
        }
    }

    private boolean appendStates(String source, Transformation step, List<Expr> states, int depth) {
        if (!session.inspect()) return false;
        if (depth > 8 || states.size() >= 9) throw new IllegalArgumentException("dispatch provenance bound exceeded");
        step.provenance().requireSource(source);
        if (step.provenance() instanceof TransformationProvenance.Sequence sequence) {
            for (var child : sequence.steps()) {
                if (!appendStates(source, child, states, depth + 1)) return false;
                source = child.transformedExpression();
            }
            return true;
        }
        if (step.primitiveStepCount() != 1 || step.executionWork().exactTheorySteps() != 0) {
            throw new IllegalArgumentException("dispatch continuation lost its atomic intermediate states");
        }
        var expression = parse(step.transformedExpression());
        if (expression == null) return false;
        states.add(expression);
        return true;
    }

    private Expr parse(String identityTransport) {
        if (!session.inspect()) return null;
        // Engine text encodes scoped SymbolIds; it is not a human display-name serialization.
        return parsed.computeIfAbsent(identityTransport, parser::parseTerm);
    }
}
