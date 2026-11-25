package de.regelsuche.solver.ir;

import static de.regelsuche.solver.ir.ScopedProofDag.Node.*;
import static de.regelsuche.solver.ir.ScopedProofDag.Rule.*;
import de.regelsuche.solver.ir.ScopedProofDag.Node;
import de.regelsuche.solver.ir.SolverIr.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
        try (var cursor = open(source, budget)) {
            var proposals = new ArrayList<Proposal>();
            while (!cursor.done()) cursor.next(Long.MAX_VALUE).ifPresent(proposals::add);
            return new Report(proposals, cursor.work(), cursor.exhausted());
        }
    }

    public static Cursor open(Obligation source, int budget) {
        if (!SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(source.schema()) || source.assumptions().size() > 32
                || source.declarations().size() > 32 || budget < 0 || budget > 65_536)
            throw new IllegalArgumentException("outside scoped proof formation bounds");
        return new Cursor(new ScopedProofSearch(source,budget));
    }

    /** Each rule application is atomic. Its completed work (including an overrun of a slice) stays paid. */
    public static final class Cursor implements AutoCloseable {
        private enum Stage { PREMISES, FROM_PREMISE, INDUCTION, APPLY, ELIMINATE, APPLY_NEW, END }
        private final ScopedProofSearch search;
        private Stage stage = Stage.PREMISES;
        private List<Fact> roundFacts = List.of();
        private Iterator<Runnable> alternatives = Collections.emptyIterator();
        private int index, round, output;
        private boolean exhausted, closed;

        private Cursor(ScopedProofSearch search) { this.search = search; }
        public int work() { return search.work; }
        public boolean exhausted() { return exhausted; }
        public boolean done() { return closed || stage == Stage.END && output == search.proposals.size(); }

        public Optional<Proposal> next(long allowance) {
            if (allowance < 0) throw new IllegalArgumentException("negative formation allowance");
            if (allowance == 0 || done()) return Optional.empty();
            int before = work();
            try {
                while (stage != Stage.END && output == search.proposals.size() && work() - before < allowance)
                    advance();
            } catch (Stop stop) {
                stage = Stage.END;
                exhausted = true;
            }
            return output < search.proposals.size() ? Optional.of(search.proposals.get(output++)) : Optional.empty();
        }

        private void start(Stage next) {
            stage = next;
            index = 0;
            roundFacts = switch (next) {
                case FROM_PREMISE, APPLY, ELIMINATE, APPLY_NEW -> List.copyOf(search.facts.values());
                default -> List.of();
            };
        }

        private void advance() {
            if (alternatives.hasNext()) {
                alternatives.next().run();
                return;
            }
            switch (stage) {
                case PREMISES -> {
                    if (index < search.context.assumptions().size()) search.addPremise(index++);
                    else start(Stage.FROM_PREMISE);
                }
                case FROM_PREMISE -> {
                    if (index < roundFacts.size())
                        alternatives = search.premiseRules(roundFacts.get(index++), roundFacts).iterator();
                    else start(Stage.INDUCTION);
                }
                case INDUCTION -> {
                    if (index < search.context.declarations().size()) search.induct(index++);
                    else start(Stage.APPLY);
                }
                case APPLY -> {
                    if (index < roundFacts.size()) search.apply(roundFacts.get(index++));
                    else if (++round < 4 && roundFacts.size() != search.facts.size()) start(Stage.APPLY);
                    else { round = 0; start(Stage.ELIMINATE); }
                }
                case ELIMINATE -> {
                    if (index < roundFacts.size())
                        alternatives = search.eliminationRules(roundFacts.get(index++), roundFacts).iterator();
                    else start(roundFacts.size() == search.facts.size() ? Stage.END : Stage.APPLY_NEW);
                }
                case APPLY_NEW -> {
                    if (index < roundFacts.size()) search.apply(roundFacts.get(index++));
                    else start(++round < 4 ? Stage.ELIMINATE : Stage.END);
                }
                case END -> throw new IllegalStateException("formation ended");
            }
        }

        @Override public void close() {
            closed = true;
            alternatives = Collections.emptyIterator();
            roundFacts = List.of();
            search.facts.clear();
            search.proposals.clear();
        }
    }

    private void addPremise(int index) {
        charge(1);
        var predicate = context.assumptions().get(index);
        if (predicate.relation() == Relation.HOLDS)
            facts.putIfAbsent(predicate.left(),new Fact(predicate.left(),List.of(premise(index))));
    }

    private void induct(int index) {
        var predicate = context.declarations().get(index);
        if (predicate.sort() != Sort.NAT_PREDICATE) return;
        charge(1 + 2 * context.assumptions().size());
        var found = NaturalInduction.propose(context,predicate);
        if (found.isEmpty()) return;
        var plan = found.get();
        int base = context.assumptions().indexOf(plan.base()), step = context.assumptions().indexOf(plan.step());
        retain(plan.conclusion().goal().left(),List.of(premise(base),premise(step),indexed(INDUCTION,index,0,1)));
    }
    /** Alternatives run only when pulled. Each action can retain at most one proposal. */
    private Stream<Runnable> premiseRules(Fact fact, List<Fact> initial) {
        Stream<Runnable> direct = Stream.empty();
        if (fact.formula() instanceof Binder binder && binder.kind() == BinderKind.FORALL)
            direct = declarations(index -> specialize(fact, binder, index));
        if (fact.formula() instanceof Call call) {
            direct = switch (call.function()) {
                case "or" -> caseRules(fact, call, initial);
                case "eq" -> call.arguments().size() == 2 && call.arguments().getFirst() instanceof Symbol left
                    ? initial.stream().map(other -> () -> rewrite(fact, left, call.arguments().getLast(), other))
                    : Stream.empty();
                case "not" -> Stream.of(() -> contradiction(fact, call));
                default -> Stream.empty();
            };
        }
        return Stream.concat(direct, declarations(index -> fromDeclaration(fact, index)));
    }

    private Stream<Runnable> declarations(IntConsumer action) {
        return IntStream.range(0, context.declarations().size()).mapToObj(index -> () -> action.accept(index));
    }

    private void fromDeclaration(Fact fact, int index) {
        charge(1);
        var declaration = context.declarations().get(index);
        if (declaration.sort() == Sort.NAT_PREDICATE) strongInduction(fact, declaration);
        if ((declaration.sort() == Sort.REAL || declaration.sort() == Sort.NATURAL)
                && fact.formula().referencedSymbols().contains(declaration.name())) {
            var symbol = new Symbol(declaration.name());
            var closed = replace(fact.formula(),0,(e,depth) -> e.equals(symbol) ? new Bound(depth) : e);
            retain(new Binder(BinderKind.EXISTS,declaration.sort(),closed),
                List.of(indexed(DECLARATION,index),fact.proof().getFirst(),of(WITNESS,0,1)));
        }
    }

    private Stream<Runnable> caseRules(Fact input, Call disjunction, List<Fact> initial) {
        if (disjunction.arguments().size() != 2) return Stream.empty();
        var left = disjunction.arguments().getFirst();
        var right = disjunction.arguments().getLast();
        if (left.equals(right)) return Stream.of(() ->
            retain(left,List.of(input.proof().getFirst(),local(0),of(CASES,0,1,1))));
        return initial.stream().map(other -> () -> cases(input, left, right, other, initial));
    }

    private void cases(Fact input, Expression left, Expression right, Fact other, List<Fact> initial) {
        charge(1);
        if (!(other.formula() instanceof Call arrow) || !arrow.function().equals("implies")
                || arrow.arguments().size()!=2 || !arrow.arguments().getFirst().equals(left)) return;
        var result = arrow.arguments().getLast();
        charge(initial.size());
        var matching = initial.stream().filter(t -> t.formula().equals(call("implies",right,result))).findFirst();
        if (matching.isPresent()) retain(result,List.of(input.proof().getFirst(),other.proof().getFirst(),local(0),of(APPLY,1,2),
            matching.get().proof().getFirst(),of(APPLY,4,2),of(CASES,0,3,5)));
    }

    private void rewrite(Fact equality, Symbol left, Expression right, Fact other) {
        charge(1);
        if (!other.formula().referencedSymbols().contains(left.name())) return;
        var next = replace(other.formula(),0,(atom,depth) -> atom.equals(left) ? right : atom);
        retain(next,List.of(equality.proof().getFirst(),other.proof().getFirst(),of(REWRITE,0,1)));
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
        int argument = appendProof(nodes, antecedent);
        nodes.add(of(APPLY, offset - 1, argument));
        retain(result,nodes);
    }

    private Stream<Runnable> eliminationRules(Fact fact, List<Fact> available) {
        if (fact.formula() instanceof Call conjunction && conjunction.function().equals("and")
                && conjunction.arguments().size() == 2 && fact.proof().size() < 128)
            return Stream.of(() -> project(fact, conjunction.arguments().getFirst(), AND_LEFT),
                             () -> project(fact, conjunction.arguments().getLast(), AND_RIGHT));
        if (fact.formula() instanceof Binder exists && exists.kind() == BinderKind.EXISTS)
            return available.stream().map(candidate -> () -> eliminateExistential(fact, exists, candidate));
        return Stream.empty();
    }

    private void project(Fact fact, Expression side, ScopedProofDag.Rule rule) {
        var proof = new ArrayList<>(fact.proof());
        proof.add(of(rule, proof.size() - 1));
        retain(side, proof);
    }

    private void eliminateExistential(Fact fact, Binder exists, Fact candidate) {
        charge(1);
        if (!(candidate.formula() instanceof Binder universal) || universal.kind() != BinderKind.FORALL
                || universal.domain() != exists.domain() || !(universal.body() instanceof Call implication)
                || !implication.function().equals("implies") || implication.arguments().size() != 2
                || !implication.arguments().getFirst().equals(exists.body())) return;
        var conclusion = implication.arguments().getLast();
        if (facts.containsKey(conclusion) || usesBinder(conclusion, 0)
                || fact.proof().size() + candidate.proof().size() + 5 > 128) return;
        var nodes = new ArrayList<>(fact.proof());
        int source = nodes.size() - 1;
        int rule = appendProof(nodes, candidate);
        int witness = nodes.size();
        nodes.add(local(1));
        int specialized = nodes.size();
        nodes.add(of(SPECIALIZE, rule, witness));
        int hypothesis = nodes.size();
        nodes.add(local(0));
        int continuation = nodes.size();
        nodes.add(of(APPLY, specialized, hypothesis));
        nodes.add(of(EXISTS_ELIM, source, continuation));
        retain(conclusion, nodes);
    }

    /** A nested lexical binder increments the index of the eliminated witness. */
    private boolean usesBinder(Expression expression, int depth) {
        charge(1);
        return switch (expression) {
            case Bound bound -> bound.index() == depth;
            case Binder binder -> usesBinder(binder.body(), depth + 1);
            case Call call -> call.arguments().stream().anyMatch(argument -> usesBinder(argument, depth));
            case Binary binary -> usesBinder(binary.left(), depth) || usesBinder(binary.right(), depth);
            default -> false;
        };
    }

    private static int appendProof(List<Node> nodes, Fact fact) {
        int offset = nodes.size();
        for (var node : fact.proof()) {
            nodes.add(new Node(node.rule(), node.operand(), node.children().stream().map(i -> i + offset).toList()));
        }
        return nodes.size() - 1;
    }
    private void specialize(Fact fact, Binder binder, int index) {
        charge(1);
        var declaration = context.declarations().get(index);
        if (declaration.sort() == binder.domain()) {
            Expression argument = new Symbol(declaration.name());
            retain(replace(binder.body(),0,(e,depth) -> e instanceof Bound b && b.index()==depth ? argument : e),
                List.of(fact.proof().getFirst(),indexed(DECLARATION,index),of(SPECIALIZE,0,1)));
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
