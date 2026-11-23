package de.regelsuche.egraph;

import de.regelsuche.ast.*;
import de.regelsuche.egraph.proof.*;

import de.regelsuche.transform.*;
import de.regelsuche.value.ExprValueArena;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.ToIntFunction;

/** Bounded native scalar execution over EGraph, with a separate receiver check on each claimed extraction. */
public final class NativeScalarSaturation {
    public enum Schedule { ROUND_ROBIN, PRODUCTIVITY }
    public enum Matcher {
        WHOLE_NODE(FlatAcMatchPlan.VERSION, false),
        CONTEXTUAL(FlatAcMatchPlan.CONTEXTUAL_VERSION, true);

        private final String identifier;
        final boolean allowsRemainder;

        Matcher(String identifier, boolean allowsRemainder) {
            this.identifier = identifier;
            this.allowsRemainder = allowsRemainder;
        }

        public String identifier() {
            return identifier;
        }
    }

    public record Budget(int rounds, int nodes, int perRuleFuel, Schedule schedule) {
        public Budget {
            Objects.requireNonNull(schedule);
            if (rounds<1 || rounds>64 || nodes<1 || nodes>4096 || perRuleFuel<1 || perRuleFuel>100_000)
                throw new IllegalArgumentException("invalid native saturation budget");
        }
    }
    public record RuleWork(int roots, int nodes, int tuples, int matches, int duplicates, int rejectedGuards,
                           int cacheReuse, int unions, int fuelSpent) { }
    public record Guard(String rule, Expr factor, String result, boolean applied) { }
    public record Analysis(int eclass, ScalarGuardFacts conditions, ScalarShapeFacts shape) { }
    public record Extraction(String objective, Expr proposal, Expr expression, String status, int rawNodes, int proofSteps, Optional<ScalarExtractionProof.Bundle> proof) { }
    public record Result(ScalarExtractionProof.Context context, Budget budget, String terminal, int rounds, int graphNodes,
                         long analysisEvaluations, List<Analysis> analyses, Map<String,RuleWork> ledger, List<Guard> guards,
                         List<ScalarExtractionProof.Lemma> unionEvidence, List<Extraction> extractions) { }
    public record Observation(Result result, Map<String,Integer> nodesByKind,
                              Map<String,Integer> classesContainingKind, Map<String,Long> producerStages) { }

    static final class Account {
        int roots,nodes,tuples,matches,duplicates,rejected,reuse,unions,spent;
        boolean capped;
        boolean spend(int count, int limit) {
            if (count > limit-spent) { capped = true; return false; }
            spent += count;
            return true;
        }
        RuleWork freeze() { return new RuleWork(roots,nodes,tuples,matches,duplicates,rejected,reuse,unions,spent); }
    }

    final EGraph graph;
    final Budget budget;
    final Matcher matcher;
    final ScalarExtractionProof.Context context;
    final ScalarClassAnalysis facts;
    final List<Guard> guards = new ArrayList<>();
    final LinkedHashSet<ScalarExtractionProof.Lemma> unionEvidence = new LinkedHashSet<>();
    private final Map<String,Account> accounts = new TreeMap<>();
    private final Map<String,Long> scanned = new HashMap<>();
    private final EClassId root;
    private final Expr input;
    private final LongConsumer proofMeter;
    private final long sourcePreparationWork;
    private long schedulerWork;
    private long guardWork;
    private long proofWork;
    private int rounds;
    private String terminal = "ROUND_BUDGET";
    private NativeScalarSaturation(Expr input, ScalarExtractionProof.Context context, Budget budget,
                                   Matcher matcher, boolean observeWork) {
        this.input = input; this.context = context; this.budget = budget;
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.graph = EGraph.exactScalarAcV1(observeWork);
        this.facts = new ScalarClassAnalysis(graph,context.assumptions());
        var sourceCheck = new ExtractionProofBuilder(List.of(), ProofLimits.defaults());
        sourceCheck.addExpression(input);
        this.proofMeter = observeWork ? units -> proofWork += units : null;
        this.sourcePreparationWork = observeWork
                ? 1L + sourceCheck.workUsed() + context.inventory().size() + context.assumptions().size() : 0;
        graph.setInsertionLimit(budget.nodes());
        try (var arena = new ExprValueArena(budget.nodes())) {
            root = graph.addArenaValue(arena,arena.intern(input),FlatAcMatchPlan.MAX_ARITY);
        }
        context.inventory().stream().filter(e -> context.enables(e.id())).forEach(e -> accounts.put(e.id(),new Account()));
        recordAc(input,graph.extract(root,node -> 1));
    }

    static Result run(Expr input, Collection<? extends RewriteRule> rules, ScalarExtractionProof.Context context,
                      Budget budget, Matcher matcher) {
        return prepare(input, rules, context, budget, matcher, false).execute();
    }

    static Observation observe(Expr input, Collection<? extends RewriteRule> rules,
            ScalarExtractionProof.Context context, Budget budget) {
        var run = prepare(input, rules, context, budget, Matcher.CONTEXTUAL, true);
        var result = run.execute();
        var producerStages = run.producerStages();
        var nodes = new TreeMap<String,Integer>();
        var classes = new TreeMap<String,Integer>();
        for (var eclass : run.graph.classes()) {
            var kinds = new HashSet<String>();
            for (var node : eclass.nodes()) {
                String symbol = node.symbol();
                String kind = symbol.startsWith("ac:") || symbol.startsWith("op:")
                        ? symbol : symbol.substring(0, symbol.indexOf(':'));
                nodes.merge(kind, 1, Integer::sum);
                kinds.add(kind);
            }
            kinds.forEach(kind -> classes.merge(kind, 1, Integer::sum));
        }
        return new Observation(result, Collections.unmodifiableMap(nodes), Collections.unmodifiableMap(classes),
                producerStages);
    }

    private Map<String,Long> producerStages() {
        return Map.of("sourcePreparation", sourcePreparationWork, "scheduler", schedulerWork,
                "graphStructure", graph.observedStructureWork(), "representativeForest", graph.observedRepresentativeWork(),
                "extraction", graph.observedExtractionWork(), "guardChecks", guardWork, "proofProduction", proofWork);
    }

    private static NativeScalarSaturation prepare(Expr input, Collection<? extends RewriteRule> rules,
            ScalarExtractionProof.Context context, Budget budget, Matcher matcher, boolean observeWork) {
        if (!ScalarExtractionProof.Context.create(rules, context.assumptions(), context.sourceRevision()).equals(context)) {
            throw new IllegalArgumentException("receiver's scalar law or rule inventory differs");
        }
        return new NativeScalarSaturation(input, context, budget, matcher, observeWork);
    }

    private Result execute() {
        long previous = -1;
        try {
            for (int turn=0;turn<budget.rounds();turn++) {
                long entering = graph.version();
                rounds++;
                chargeScheduler(1);
                var dirty = previous < 0 ? graph.classes().stream().map(EClass::id).toList() : graph.affectedClassesChangedAfter(previous);
                facts.update(dirty);
                var keys = new ArrayList<>(accounts.keySet());
                if (budget.schedule() == Schedule.PRODUCTIVITY) keys.sort(this::compareProductivity);
                for (String id : keys) {
                    chargeScheduler(1);
                    Account work = accounts.get(id);
                    if (work.capped) continue;
                    Long last = scanned.get(id);
                    var roots = last == null ? graph.classes().stream().map(EClass::id).sorted().toList()
                        : graph.affectedClassesChangedAfter(last).stream().map(graph::find).distinct().sorted().toList();
                    chargeScheduler(roots.size());
                    work.reuse += Math.max(0,graph.classCount()-roots.size());
                    scanned.put(id,graph.version());
                    NativeScalarRules.apply(this,id,roots,work);
                    graph.rebuild();
                }
                previous = entering;
                if (entering==graph.version()) {
                    terminal = accounts.values().stream().anyMatch(a -> a.capped) ? "RULE_FUEL_BUDGET" : "NATIVE_FRAGMENT_FIXPOINT";
                    break;
                }
            }
        } catch (EGraph.NodeBudgetExceeded exhausted) { terminal = "NODE_BUDGET"; }
        var ledger = new TreeMap<String,RuleWork>();
        accounts.forEach((id,account) -> ledger.put(id,account.freeze()));
        var outputs = List.of(extract("NODE_COUNT", node -> 1), extract("DIVISION_PENALTY", node -> "op:DIV".equals(node.symbol())?16:1));
        facts.update(graph.affectedClassesChangedAfter(previous));
        return new Result(context,budget,terminal,rounds,graph.nodeCount(),facts.evaluations(),facts.snapshot(),Collections.unmodifiableMap(ledger),
            List.copyOf(guards),List.copyOf(unionEvidence),outputs);
    }

    private void chargeScheduler(long units) {
        if (proofMeter != null) schedulerWork += units;
    }

    private int compareProductivity(String left, String right) {
        chargeScheduler(1);
        int ordered = Integer.compare(accounts.get(right).unions, accounts.get(left).unions);
        return ordered == 0 ? left.compareTo(right) : ordered;
    }

    void chargeGuard() {
        if (proofMeter != null) guardWork++;
    }

    private Extraction extract(String label, ToIntFunction<ENode> cost) {
        Expr selected = graph.extract(root,cost);
        Optional<ScalarExtractionProof.Bundle> proof;
        try { proof = ScalarExtractionProof.buildObserved(input, selected, context, List.copyOf(unionEvidence),
                ProofLimits.defaults(), proofMeter); }
        catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return new Extraction(label,selected,input,"PROOF_BUDGET",nodes(input),0,Optional.empty());
        }
        Expr authorized = proof.isPresent() ? selected : input;
        return new Extraction(label,selected,authorized,proof.isPresent() ? "CHECKED_EQUALITY" : "DERIVATION_UNAVAILABLE",nodes(authorized),proof.map(b -> b.dag().steps().size()).orElse(0),proof);
    }
    private static int nodes(Expr expr) {
        return switch(expr) {
            case BinaryExpr b -> 1 + nodes(b.left()) + nodes(b.right());
            case FunctionExpr f -> 1 + f.arguments().stream().mapToInt(NativeScalarSaturation::nodes).sum();
            default -> 1;
        };
    }
    void recordAc(Expr a, Expr b) {
        if (!a.equals(b)) {
            var law = new ScalarExtractionProof.Lemma(ScalarExtractionProof.AC,a,b);
            if (ScalarExtractionProof.admitsObserved(context, law, proofMeter)) unionEvidence.add(law);
        }
    }
    void apply(String rule, EClassId left, EClassId right, Expr before, Expr after, Account account) {
        apply(left, right, before, after, new ScalarExtractionProof.Lemma(rule, before, after), account);
    }
    void applyWithinSum(EClassId left, EClassId right, List<Expr> remainder,
                        ScalarExtractionProof.Lemma witness, Account account) {
        Expr before = witness.before();
        Expr after = witness.after();
        if (!remainder.isEmpty()) {
            Expr unchanged = ScalarExtractionProof.join(remainder, BinaryOperator.ADD);
            before = new BinaryExpr(unchanged, BinaryOperator.ADD, before);
            after = new BinaryExpr(unchanged, BinaryOperator.ADD, after);
        }
        apply(left, right, before, after, witness, account);
    }
    private void apply(EClassId left, EClassId right, Expr before, Expr after,
                       ScalarExtractionProof.Lemma proposal, Account account) {
        account.matches++;
        if (graph.areEquivalent(left,right)) { account.duplicates++; return; }
        if (!ScalarExtractionProof.admitsObserved(context, proposal, proofMeter)) { account.rejected++; return; }
        recordAc(graph.extract(left,n -> 1),before);
        recordAc(graph.extract(right,n -> 1),after);
        unionEvidence.add(proposal);
        graph.union(left,right);
        account.unions++;
    }
    Expr syntax(EClassId id) { return graph.extract(id,node -> 1); }
}
