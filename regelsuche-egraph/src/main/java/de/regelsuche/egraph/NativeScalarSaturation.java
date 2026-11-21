package de.regelsuche.egraph;

import de.regelsuche.ast.*;
import de.regelsuche.egraph.proof.*;

import de.regelsuche.transform.*;
import de.regelsuche.value.ExprValueArena;
import java.util.*;
import java.util.function.ToIntFunction;

/** Bounded native scalar execution over EGraph, with a separate receiver check on each claimed extraction. */
public final class NativeScalarSaturation {
    public enum Schedule { ROUND_ROBIN, PRODUCTIVITY }
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

    final EGraph graph = EGraph.exactScalarAcV1();
    final Budget budget;
    final ScalarExtractionProof.Context context;
    final ScalarClassAnalysis facts;
    final List<Guard> guards = new ArrayList<>();
    final LinkedHashSet<ScalarExtractionProof.Lemma> unionEvidence = new LinkedHashSet<>();
    private final Map<String,Account> accounts = new TreeMap<>();
    private final Map<String,Long> scanned = new HashMap<>();
    private final EClassId root;
    private final Expr input;
    private int rounds;
    private String terminal = "ROUND_BUDGET";
    private NativeScalarSaturation(Expr input, ScalarExtractionProof.Context context, Budget budget) {
        this.input = input; this.context = context; this.budget = budget;
        this.facts = new ScalarClassAnalysis(graph,context.assumptions());
        new ExtractionProofBuilder(List.of(), ProofLimits.defaults()).addExpression(input);
        graph.setInsertionLimit(budget.nodes());
        try (var arena = new ExprValueArena(budget.nodes())) {
            root = graph.addArenaValue(arena,arena.intern(input),FlatAcMatchPlan.MAX_ARITY);
        }
        context.inventory().stream().filter(e -> context.enables(e.id())).forEach(e -> accounts.put(e.id(),new Account()));
        recordAc(input,graph.extract(root,node -> 1));
    }

    static Result run(Expr input, Collection<? extends RewriteRule> rules, ScalarExtractionProof.Context context, Budget budget) {
        if (!ScalarExtractionProof.Context.create(rules,context.assumptions(),context.sourceRevision()).equals(context))
            throw new IllegalArgumentException("receiver's scalar law or rule inventory differs");
        var run = new NativeScalarSaturation(input, context, budget);
        return run.execute();
    }

    private Result execute() {
        long previous = -1;
        try {
            for (int turn=0;turn<budget.rounds();turn++) {
                long entering = graph.version();
                rounds++;
                var dirty = previous < 0 ? graph.classes().stream().map(EClass::id).toList() : graph.affectedClassesChangedAfter(previous);
                facts.update(dirty);
                var keys = new ArrayList<>(accounts.keySet());
                if (budget.schedule()==Schedule.PRODUCTIVITY) keys.sort(Comparator.<String>comparingInt(id -> accounts.get(id).unions)
                    .reversed().thenComparing(id -> id));
                for (String id : keys) {
                    Account work = accounts.get(id);
                    if (work.capped) continue;
                    Long last = scanned.get(id);
                    var roots = last == null ? graph.classes().stream().map(EClass::id).sorted().toList()
                        : graph.affectedClassesChangedAfter(last).stream().map(graph::find).distinct().sorted().toList();
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

    private Extraction extract(String label, ToIntFunction<ENode> cost) {
        Expr selected = graph.extract(root,cost);
        Optional<ScalarExtractionProof.Bundle> proof;
        try { proof = ScalarExtractionProof.build(input,selected,context,List.copyOf(unionEvidence),ProofLimits.defaults()); }
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
            if (ScalarExtractionProof.admits(context,law)) unionEvidence.add(law);
        }
    }
    void apply(String rule, EClassId left, EClassId right, Expr before, Expr after, Account account) {
        account.matches++;
        if (graph.areEquivalent(left,right)) { account.duplicates++; return; }
        var proposal = new ScalarExtractionProof.Lemma(rule,before,after);
        if (!ScalarExtractionProof.admits(context,proposal)) { account.rejected++; return; }
        recordAc(graph.extract(left,n -> 1),before);
        recordAc(graph.extract(right,n -> 1),after);
        unionEvidence.add(proposal);
        graph.union(left,right);
        account.unions++;
    }
    Expr syntax(EClassId id) { return graph.extract(id,node -> 1); }
}
