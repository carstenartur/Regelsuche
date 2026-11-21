package de.regelsuche.solver.ir;

import static de.regelsuche.solver.ir.ScopedProofDag.Node.*;
import static de.regelsuche.solver.ir.ScopedProofDag.Rule.*;
import de.regelsuche.solver.ir.ScopedProofDag.Node;
import de.regelsuche.solver.ir.SolverIr.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Source-only finite logical formation. Premises are inspected, the supplied endpoint is never queried. */
public final class ScopedProofSearch {
    public record Proposal(ScopedProofDag dag, int formationWork) { }
    public record Report(List<Proposal> proposals, int work, boolean exhausted) {
        public Report { proposals = List.copyOf(proposals); }
    }
    private record Fact(Expression formula, List<Node> proof) { }
    private final Obligation context;
    private final int limit;
    private int work;
    private final LinkedHashMap<Expression,Fact> facts = new LinkedHashMap<>();
    private final List<Proposal> proposals = new ArrayList<>();

    private ScopedProofSearch(Obligation context, int limit) { this.context = context; this.limit = limit; }
    public static Report discover(Obligation source, int budget) {
        if (!SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(source.schema()) || source.assumptions().size() > 32
                || source.declarations().size() > 32 || budget < 0 || budget > 65_536)
            throw new IllegalArgumentException("outside scoped proof formation bounds");
        var search = new ScopedProofSearch(source,budget);
        try { search.run(); return new Report(search.proposals,search.work,false); }
        catch (Stop exhausted) { return new Report(search.proposals,search.work,true); }
    }
    private void run() {
        for (int i = 0; i < context.assumptions().size(); i++) {
            charge(1);
            var premise = context.assumptions().get(i);
            if (premise.relation() == Relation.HOLDS) facts.putIfAbsent(premise.left(),new Fact(premise.left(),List.of(premise(i))));
        }
        var initial = List.copyOf(facts.values());
        for (var fact : initial) fromPremise(fact,initial);
        for (int i = 0; i < context.declarations().size(); i++) {
            var predicate = context.declarations().get(i);
            if (predicate.sort() != Sort.NAT_PREDICATE) continue;
            charge(1 + 2 * context.assumptions().size());
            var found = NaturalInduction.propose(context,predicate);
            if (found.isEmpty()) continue;
            var plan = found.get();
            int base = context.assumptions().indexOf(plan.base()), step = context.assumptions().indexOf(plan.step());
            retain(plan.conclusion().goal().left(),List.of(premise(base),premise(step),indexed(INDUCTION,i,0,1)));
        }
        for (int round = 0; round < 4; round++) {
            int before = facts.size();
            var previous = List.copyOf(facts.values());
            for (var implication : previous) apply(implication);
            if (before == facts.size()) return;
        }
    }
    private void fromPremise(Fact fact, List<Fact> initial) {
        if (fact.formula() instanceof Binder b && b.kind()==BinderKind.FORALL) specialize(fact,b);
        if (fact.formula() instanceof Call c) {
            switch (c.function()) {
                case "or" -> cases(fact,c,initial);
                case "eq" -> rewrite(fact,c,initial);
                case "not" -> contradiction(fact,c);
                default -> { }
            }
        }
        for (int i=0;i<context.declarations().size();i++) {
            charge(1);
            var d=context.declarations().get(i);
            if (d.sort()==Sort.NAT_PREDICATE) strongInduction(fact,d);
            if ((d.sort()==Sort.REAL || d.sort()==Sort.NATURAL) && fact.formula().referencedSymbols().contains(d.name())) {
                var closed = replace(fact.formula(),0,(e,depth)->e.equals(new Symbol(d.name())) ? new Bound(depth) : e);
                retain(new Binder(BinderKind.EXISTS,d.sort(),closed),
                    List.of(indexed(DECLARATION,i),fact.proof().getFirst(),of(WITNESS,0,1)));
            }
        }
    }
    private void cases(Fact input, Call disjunction, List<Fact> initial) {
        if (disjunction.arguments().size()!=2) return;
        var l=disjunction.arguments().getFirst(); var r=disjunction.arguments().getLast();
        if (l.equals(r)) {
            retain(l,List.of(input.proof().getFirst(),local(0),of(CASES,0,1,1)));
            return;
        }
        for (var f:initial) {
            charge(1);
            if (!(f.formula() instanceof Call arrow) || !arrow.function().equals("implies")
                    || arrow.arguments().size()!=2 || !arrow.arguments().getFirst().equals(l)) continue;
            var result=arrow.arguments().getLast();
            charge(initial.size());
            var g=initial.stream().filter(t -> t.formula().equals(call("implies",r,result))).findFirst();
            if (g.isPresent()) retain(result,List.of(input.proof().getFirst(),f.proof().getFirst(),local(0),of(APPLY,1,2),
                g.get().proof().getFirst(),of(APPLY,4,2),of(CASES,0,3,5)));
        }
    }
    private void rewrite(Fact equality,Call eq,List<Fact> initial) {
        if (eq.arguments().size()!=2 || !(eq.arguments().getFirst() instanceof Symbol left)) return;
        var right=eq.arguments().getLast();
        for (var f : initial) {
            charge(1);
            if (!f.formula().referencedSymbols().contains(left.name())) continue;
            var next=replace(f.formula(),0,(atom,depth)->atom.equals(left) ? right : atom);
            retain(next,List.of(equality.proof().getFirst(),f.proof().getFirst(),of(REWRITE,0,1)));
        }
    }
    private void contradiction(Fact input,Call negation) {
        if (negation.arguments().size()==1 && negation.arguments().getFirst() instanceof Call inner
                && inner.function().equals("not") && inner.arguments().size()==1)
            retain(inner.arguments().getFirst(),List.of(input.proof().getFirst(),local(0),of(BY_CONTRADICTION,0,1)));
    }
    private void strongInduction(Fact input,SymbolDeclaration d) {
        var p=new Symbol(d.name()); var n=new Bound(0); var pn=call("at",p,n);
        var smaller=new Binder(BinderKind.FORALL,Sort.NATURAL,call("implies",call("lt",n,new Bound(1)),pn));
        var condition=new Binder(BinderKind.FORALL,Sort.NATURAL,call("implies",smaller,pn));
        if (input.formula().equals(condition)) retain(new Binder(BinderKind.FORALL,Sort.NATURAL,pn),
            List.of(input.proof().getFirst(), local(1),of(SPECIALIZE,0,1),local(0),of(DECREASE,1,3),
                of(INTRO,4),of(INTRO,5),of(APPLY,2,6),of(STRONG_INDUCTION,7)));
    }
    private static Call call(String op, Expression... xs) { return new Call(op,List.of(xs)); }

    private void apply(Fact fact) {
        charge(1);
        if (!(fact.formula() instanceof Call c) || !c.function().equals("implies") || c.arguments().size() != 2) return;
        var antecedent = facts.get(c.arguments().getFirst());
        Expression result = c.arguments().getLast();
        if (antecedent == null || facts.containsKey(result)) return;
        var nodes = new ArrayList<>(fact.proof());
        int offset = nodes.size();
        if (offset + antecedent.proof().size() >= 128) return;
        for (var n : antecedent.proof()) {
            nodes.add(new Node(n.rule(),n.operand(),n.children().stream().map(i -> i + offset).toList()));
        }
        nodes.add(of(APPLY,offset-1,nodes.size()-1));
        retain(result,nodes);
    }
    private void specialize(Fact fact, Binder binder) {
        for (int i = 0; i < context.declarations().size(); i++) {
            charge(1);
            var declaration = context.declarations().get(i);
            if (declaration.sort() == binder.domain()) {
                Expression argument = new Symbol(declaration.name());
                retain(replace(binder.body(),0,(e,depth) -> e instanceof Bound b && b.index()==depth ? argument : e),
                    List.of(fact.proof().getFirst(),indexed(DECLARATION,i),of(SPECIALIZE,0,1)));
            }
        }
    }

    /** Traverses existing lexical levels. Formation only opens with a closed symbol or wraps a closed formula. */
    private Expression replace(Expression formula, int depth, java.util.function.BiFunction<Expression,Integer,Expression> atom) {
        charge(1);
        return switch (formula) {
            case Binder b -> new Binder(b.kind(),b.domain(),replace(b.body(),depth+1,atom));
            case Call c -> new Call(c.function(),c.arguments().stream().map(a -> replace(a,depth,atom)).toList());
            case Binary b -> new Binary(b.operator(),replace(b.left(),depth,atom),replace(b.right(),depth,atom));
            default -> atom.apply(formula,depth);
        };
    }
    private void retain(Expression formula, List<Node> nodes) {
        charge(nodes.size()+1);
        if (facts.containsKey(formula)) return;
        var request = Obligation.create(context.obligationId(),context.declarations(),context.theories(),context.assumptions(),
            Goal.holds(formula),context.requestedEvidence(),context.provenance());
        var dag = ScopedProofDag.create(request,nodes);
        facts.put(formula,new Fact(formula,dag.nodes()));
        proposals.add(new Proposal(dag,work));
    }
    private void charge(int amount) {
        if (amount > limit-work) throw new Stop();
        work+=amount;
    }
    private static final class Stop extends RuntimeException { }
}
