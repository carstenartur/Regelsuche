package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternNumber;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternInstantiator;
import de.regelsuche.mining.RulePatternMatcher;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** TRAIN-only trajectory abstraction. Templates constrain applicability, never authorize a rewrite. */
final class TraceBindingModel {
    static final String REVISION = "regelsuche.trace-binding-model/v1";

    record Trace(String id, List<String> sequence, List<Expr> states) {
        Trace {
            sequence = List.copyOf(sequence);
            states = List.copyOf(states);
            if (id == null || id.isBlank() || sequence.size() < 2 || sequence.size() > 8
                    || states.size() != sequence.size() + 1 || sequence.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("require an identified two-to-eight-step primitive trace");
            }
        }
        String evidenceHash() {
            return SchematicProofPlan.hash(new JsonWriter().beginObject().property("id", id)
                .stringArray("sequence", sequence)
                .stringArray("states", states.stream().map(ExpressionFormatter::format).toList()).endObject().toString());
        }
    }

    record Template(List<String> sequence, List<RulePatternNode> patterns,
            List<String> trainingIds, List<String> trainingHashes) {
        Template {
            sequence = List.copyOf(sequence);
            patterns = List.copyOf(patterns);
            trainingIds = List.copyOf(trainingIds);
            trainingHashes = List.copyOf(trainingHashes);
        }
        String structuralJson() {
            var instantiator = new RulePatternInstantiator();
            return new JsonWriter().beginObject().stringArray("sequence", sequence)
                .stringArray("states", patterns.stream().map(node ->
                    ExpressionFormatter.format(instantiator.instantiate(node, Map.of()))).toList())
                .endObject().toString();
        }
    }

    private final List<Template> templates;
    private final long formationWork;
    private final long maximumMatchingWork;
    private final String json;
    private final String hash;

    private TraceBindingModel(List<Template> templates, long formationWork, int maximumTemplates,
            long maximumFormationWork, long maximumMatchingWork) {
        this.templates = List.copyOf(templates);
        this.formationWork = formationWork;
        this.maximumMatchingWork = maximumMatchingWork;
        json = new JsonWriter().beginObject().property("schema", REVISION)
            .property("matcher", RulePatternMatcher.SEQUENCE_REVISION)
            .property("scope", "PAIRWISE_WHOLE_STATE_TRAIN_TRAJECTORIES;NO_PROOF_AUTHORITY")
            .property("maximumTemplates", maximumTemplates).property("maximumFormationWork", maximumFormationWork)
            .property("maximumMatchingWorkPerExpansion", maximumMatchingWork).property("formationWork", formationWork)
            .array("templates", array -> templates.forEach(template -> array.objectValue(item -> item
                .property("structure", template.structuralJson()).stringArray("trainingIds", template.trainingIds())
                .stringArray("trainingHashes", template.trainingHashes())))).endObject().toString();
        hash = SchematicProofPlan.hash(json);
    }

    static void requireLimits(int maximumTemplates, long maximumFormationWork, long maximumMatchingWork) {
        if (maximumTemplates < 1 || maximumTemplates > 32 || maximumFormationWork < 1
                || maximumFormationWork > 1_000_000 || maximumMatchingWork < 1 || maximumMatchingWork > 1_000_000) {
            throw new IllegalArgumentException("invalid binding formation/matching limits");
        }
    }

    static TraceBindingModel learn(List<Trace> traces, Set<List<String>> admitted, int maximumTemplates,
            long maximumFormationWork, long maximumMatchingWork) {
        return learn(traces, admitted, maximumTemplates, new FormationWork(maximumFormationWork), maximumMatchingWork);
    }

    static TraceBindingModel learn(List<Trace> traces, Set<List<String>> admitted, int maximumTemplates,
            FormationWork work, long maximumMatchingWork) {
        requireLimits(maximumTemplates, work.maximum, maximumMatchingWork);
        if (traces.size() > 16) throw new IllegalArgumentException("binding trace limit exceeded");
        var ordered = List.copyOf(traces).stream().sorted(Comparator.comparing(Trace::id)).toList();
        if (ordered.stream().map(Trace::id).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("duplicate binding TRAIN identifier");
        }
        var distinct = new TreeMap<String, Template>();
        for (int i = 0; i < ordered.size(); i++) {
            work.charge();
            var left = ordered.get(i);
            for (int j = i + 1; j < ordered.size(); j++) {
                work.charge();
                var right = ordered.get(j);
                if (!admitted.contains(left.sequence()) || !left.sequence().equals(right.sequence())) continue;
                // ONE environment for ALL state pairs is the cross-step binding contract.
                var pairs = new HashMap<ExpressionPair, PatternVariable>();
                var patterns = new ArrayList<RulePatternNode>();
                for (int k = 0; k < left.states().size(); k++) {
                    patterns.add(generalize(left.states().get(k), right.states().get(k), pairs, work, 0));
                }
                if (patterns.getFirst() instanceof PatternVariable || pairs.isEmpty()) continue;
                var initialNames = placeholderNames(patterns.getFirst(), work);
                boolean closed = true;
                for (int k = 1; k < patterns.size(); k++) {
                    closed &= initialNames.containsAll(placeholderNames(patterns.get(k), work));
                }
                if (!closed) continue;
                var candidate = new Template(left.sequence(), patterns,
                    List.of(left.id(), right.id()), List.of(left.evidenceHash(), right.evidenceHash()));
                String key = candidate.structuralJson();
                var previous = distinct.get(key);
                if (previous != null) {
                    var ids = new TreeSet<>(previous.trainingIds());
                    ids.addAll(candidate.trainingIds());
                    var hashes = new TreeSet<>(previous.trainingHashes());
                    hashes.addAll(candidate.trainingHashes());
                    candidate = new Template(candidate.sequence(), candidate.patterns(),
                        List.copyOf(ids), List.copyOf(hashes));
                }
                distinct.put(key, candidate);
                if (distinct.size() > maximumTemplates) throw new IllegalArgumentException("binding template limit exceeded");
            }
        }
        return new TraceBindingModel(List.copyOf(distinct.values()), work.used,
            maximumTemplates, work.maximum, maximumMatchingWork);
    }

    private static Set<String> placeholderNames(RulePatternNode root, FormationWork work) {
        var names = new TreeSet<String>();
        var pending = new java.util.ArrayDeque<RulePatternNode>();
        pending.push(root);
        while (!pending.isEmpty()) {
            work.charge();
            var node = pending.pop();
            if (node instanceof PatternVariable variable) names.add(variable.name());
            else if (node instanceof PatternBinary binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof PatternFunction function) {
                function.arguments().forEach(pending::push);
            }
        }
        return names;
    }

    private record ExpressionPair(Expr left, Expr right) {}

    private static RulePatternNode generalize(Expr left, Expr right,
            Map<ExpressionPair, PatternVariable> pairs, FormationWork work, int depth) {
        work.charge();
        if (depth > 64) throw new IllegalArgumentException("binding template depth limit exceeded");
        if (left instanceof BinaryExpr a && right instanceof BinaryExpr b && a.operator() == b.operator()) {
            return new PatternBinary(generalize(a.left(), b.left(), pairs, work, depth + 1), a.operator(),
                generalize(a.right(), b.right(), pairs, work, depth + 1));
        }
        if (left instanceof FunctionExpr a && right instanceof FunctionExpr b && a.name().equals(b.name())
                && a.arguments().size() == b.arguments().size()) {
            var arguments = new ArrayList<RulePatternNode>();
            for (int i = 0; i < a.arguments().size(); i++) {
                arguments.add(generalize(a.arguments().get(i), b.arguments().get(i), pairs, work, depth + 1));
            }
            return new PatternFunction(a.name(), arguments);
        }
        if (left instanceof NumberExpr a && left.equals(right) && a.value().isInteger()
                && a.value().numerator().bitLength() < 32) {
            return new PatternNumber(a.value().intValueExact());
        }
        var key = new ExpressionPair(left, right);
        return pairs.computeIfAbsent(key, ignored -> new PatternVariable("P" + pairs.size()));
    }

    List<Template> templates() { return templates; }
    long formationWork() { return formationWork; }
    String toCanonicalJson() { return json; }
    String contentHash() { return hash; }
    Session session() { return new Session(); }

    /** A single allowance covers every template, prefix and completed path in one expansion. */
    final class Session {
        private final RulePatternMatcher matcher = new RulePatternMatcher();
        private long work;
        private boolean exhausted;

        List<Template> matching(List<String> sequence, List<Expr> states) {
            var matching = new ArrayList<Template>();
            for (var template : templates) {
                if (!charge()) break;
                if (template.sequence().equals(sequence) && matches(template, states)) matching.add(template);
            }
            return List.copyOf(matching);
        }

        boolean matches(Template template, List<Expr> states) {
            if (!charge()) return false;
            if (states.size() < 2 || states.size() > template.patterns().size()) return false;
            var steps = new ArrayList<RulePatternMatcher.MatchStep>();
            for (int i = 0; i < states.size(); i++) {
                if (!charge()) return false;
                steps.add(new RulePatternMatcher.MatchStep(template.patterns().get(i), states.get(i)));
            }
            long remaining = maximumMatchingWork - work;
            if (remaining == 0) { exhausted = true; return false; }
            // Re-search all constraints: a prefix's first substitution must not lock later choices.
            var result = matcher.matchSequence(steps, Map.of(), remaining);
            work += result.workUnits();
            exhausted |= result.status() == RulePatternMatcher.MatchStatus.BUDGET_EXHAUSTED;
            return result.status() == RulePatternMatcher.MatchStatus.MATCH;
        }

        long workUnits() { return work; }
        boolean exhausted() { return exhausted; }
        boolean inspect() { return charge(); }
        private boolean charge() {
            if (work == maximumMatchingWork) { exhausted = true; return false; }
            work++;
            return true;
        }
    }

    static final class FormationWork {
        private final long maximum;
        private long used;
        FormationWork(long maximum) {
            if (maximum < 1 || maximum > 1_000_000) throw new IllegalArgumentException("invalid binding formation work");
            this.maximum = maximum;
        }
        void charge() {
            if (used == maximum) throw new IllegalArgumentException("binding formation work limit exceeded");
            used++;
        }
    }
}
