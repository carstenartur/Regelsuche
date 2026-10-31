package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;

/** Bounded rational polynomial arithmetic shared by synthesis and checking, never a proof itself. */
final class IterationPolynomial {
    static final int MAX_DEGREE = 8, MAX_TERMS = 256;
    static final class Unavailable extends RuntimeException {
        Unavailable(String reason) { super(reason); }
    }
    static final class Work {
        final OptimizationBudget budget;
        final CancellationToken cancellation;
        final long start = System.nanoTime(), timeout;
        long used;
        Work(OptimizationBudget budget, CancellationToken cancellation) {
            this.budget = Objects.requireNonNull(budget); this.cancellation = Objects.requireNonNull(cancellation);
            timeout = budget.timeoutMillis() > Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE : budget.timeoutMillis()*1_000_000;
        }
        void charge(long amount) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
            if (amount < 0 || amount > budget.maximumWork()-used || System.nanoTime()-start >= timeout)
                throw new Unavailable("WORK_LIMIT");
            used += amount;
        }
        void coefficient(ExactRational c) {
            charge(1);
            if(c.numerator().bitLength()>FiniteIteration.MAX_COEFFICIENT_BITS || c.denominator().bitLength()>FiniteIteration.MAX_COEFFICIENT_BITS)
                throw new Unavailable("COEFFICIENT_LIMIT");
        }
    }
    record Monomial(List<SymbolId> symbols) {
        Monomial { symbols = List.copyOf(symbols); }
        int degree(SymbolId symbol) { int result=0; for(var s:symbols) if(s.equals(symbol)) result++; return result; }
        Monomial without(SymbolId symbol) { return new Monomial(symbols.stream().filter(s -> !s.equals(symbol)).toList()); }
    }
    private static final Comparator<SymbolId> ORDER = Comparator.comparing(SymbolId::namespace).thenComparingLong(SymbolId::ordinal);
    final Map<Monomial,ExactRational> terms;
    final Work work;
    private IterationPolynomial(Map<Monomial,ExactRational> terms, Work work) { this.terms=Collections.unmodifiableMap(terms); this.work=work; }
    static IterationPolynomial zero(Work w) { return new IterationPolynomial(new LinkedHashMap<>(),w); }
    static IterationPolynomial constant(BigInteger n, Work w) { return single(new Monomial(List.of()),ExactRational.integer(n),w); }
    static IterationPolynomial variable(SymbolId s, Work w) { return single(new Monomial(List.of(s)),ExactRational.ONE,w); }
    static IterationPolynomial single(Monomial m, ExactRational c, Work w) {
        var map = new LinkedHashMap<Monomial,ExactRational>(); put(map,m,c,w); return new IterationPolynomial(map,w);
    }
    private static void put(Map<Monomial,ExactRational> map, Monomial m, ExactRational c, Work w) {
        w.coefficient(c);
        if(m.symbols().size()>MAX_DEGREE+1) throw new Unavailable("DEGREE_LIMIT");
        if(c.signum()==0) map.remove(m); else map.put(m,c);
        if(map.size()>Math.min(MAX_TERMS,w.budget.maximumStates())) throw new Unavailable("TERM_LIMIT");
    }
    IterationPolynomial add(IterationPolynomial other) {
        var map = new LinkedHashMap<>(terms); work.charge(terms.size());
        for(var entry:other.terms.entrySet()) put(map,entry.getKey(),map.getOrDefault(entry.getKey(),ExactRational.ZERO).add(entry.getValue()),work);
        return new IterationPolynomial(map,work);
    }
    IterationPolynomial scale(ExactRational factor) {
        work.coefficient(factor);
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var entry:terms.entrySet()) put(map,entry.getKey(),entry.getValue().multiply(factor),work);
        return new IterationPolynomial(map,work);
    }
    IterationPolynomial subtract(IterationPolynomial other) { return add(other.scale(ExactRational.NEGATIVE_ONE)); }
    IterationPolynomial multiply(IterationPolynomial other) {
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var a:terms.entrySet()) for(var b:other.terms.entrySet()) {
            work.charge(1);
            if(a.getKey().symbols().size()+b.getKey().symbols().size()>MAX_DEGREE+1) throw new Unavailable("DEGREE_LIMIT");
            var symbols = new ArrayList<>(a.getKey().symbols()); symbols.addAll(b.getKey().symbols()); symbols.sort(ORDER);
            var m = new Monomial(symbols);
            put(map,m,map.getOrDefault(m,ExactRational.ZERO).add(a.getValue().multiply(b.getValue())),work);
        }
        return new IterationPolynomial(map,work);
    }
    IterationPolynomial substitute(SymbolId symbol, IterationPolynomial value) {
        var result=zero(work);
        for(var entry:terms.entrySet()) {
            var term=single(entry.getKey().without(symbol),entry.getValue(),work);
            for(int n=entry.getKey().degree(symbol);n>0;n--) term=term.multiply(value);
            result=result.add(term);
        }
        return result;
    }
    IterationPolynomial at(SymbolId symbol, BigInteger value) { return substitute(symbol,constant(value,work)); }
    IterationPolynomial shifted(SymbolId symbol, BigInteger step) { return substitute(symbol,variable(symbol,work).add(constant(step,work))); }
    boolean independent(SymbolId symbol) { work.charge(terms.size()); return terms.keySet().stream().noneMatch(m -> m.degree(symbol)>0); }
    boolean same(IterationPolynomial other) { work.charge(terms.size()+other.terms.size()); return terms.equals(other.terms); }
    IterationPolynomial integral(IntegerArithmetic arithmetic) {
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var e:terms.entrySet()) {
            work.charge(1);
            if(!e.getValue().isInteger()) throw new Unavailable("FRACTIONAL_RESULT");
            put(map,e.getKey(),ExactRational.integer(arithmetic.normalize(e.getValue().numerator())),work);
        }
        return new IterationPolynomial(map,work);
    }
    static IterationPolynomial parse(Expr expression, Set<SymbolId> allowed, boolean integers, int degree, Work w) {
        return parse(expression,allowed,integers,degree,w,1,new int[]{0});
    }
    private static IterationPolynomial parse(Expr expression, Set<SymbolId> allowed, boolean integers, int degree, Work w, int depth, int[] nodes) {
        w.charge(1);
        if(depth>FiniteIteration.MAX_DEPTH || ++nodes[0]>FiniteIteration.MAX_NODES) throw new Unavailable("EXPRESSION_LIMIT");
        IterationPolynomial result;
        if(expression instanceof NumberExpr number) {
            w.coefficient(number.value());
            if(integers && !number.value().isInteger()) throw new Unavailable("FRACTIONAL_SOURCE");
            result=single(new Monomial(List.of()),number.value(),w);
        } else if(expression instanceof VariableExpr variable) {
            if(variable.symbol().isEmpty() || !allowed.contains(variable.symbol().orElseThrow())) throw new Unavailable("UNBOUND_SYMBOL");
            result=variable(variable.symbol().orElseThrow(),w);
        } else if(expression instanceof BinaryExpr binary) {
            if(binary.operator()!=BinaryOperator.ADD && binary.operator()!=BinaryOperator.SUB && binary.operator()!=BinaryOperator.MUL) throw new Unavailable("UNSUPPORTED_OPERATOR");
            var left=parse(binary.left(),allowed,integers,degree,w,depth+1,nodes);
            var right=parse(binary.right(),allowed,integers,degree,w,depth+1,nodes);
            result=switch(binary.operator()) { case ADD -> left.add(right); case SUB -> left.subtract(right); case MUL -> left.multiply(right); default -> throw new AssertionError(); };
        } else throw new Unavailable("UNSUPPORTED_EXPRESSION");
        for(var m:result.terms.keySet()) { w.charge(1); if(m.symbols().size()>degree) throw new Unavailable("DEGREE_LIMIT"); }
        return result;
    }
    Expr expression() {
        var summands=new ArrayList<Expr>();
        for(var entry:terms.entrySet()) {
            work.charge(1);
            Expr term=null;
            if(!entry.getValue().equals(ExactRational.ONE) || entry.getKey().symbols().isEmpty()) term=new NumberExpr(entry.getValue());
            for(var symbol:entry.getKey().symbols()) term=term==null ? VariableExpr.scoped(symbol) : new BinaryExpr(term,BinaryOperator.MUL,VariableExpr.scoped(symbol));
            summands.add(term);
        }
        return balanced(summands,0,summands.size());
    }
    private Expr balanced(List<Expr> expressions,int from,int to) {
        work.charge(1);
        if(from==to) return new NumberExpr(0);
        if(to-from==1) return expressions.get(from);
        int middle=(from+to)/2;
        return new BinaryExpr(balanced(expressions,from,middle),BinaryOperator.ADD,balanced(expressions,middle,to));
    }
    static Set<SymbolId> symbols(FiniteIteration source, boolean state) {
        var result=new HashSet<>(source.parameters()); result.add(source.index());
        if(state && source instanceof FiniteIteration.Recurrence recurrence) result.add(recurrence.state());
        return result;
    }
    record Source(IterationPolynomial term, IterationPolynomial initial) {}
    static Source source(FiniteIteration source, Work w) {
        w.charge(1);
        if(source instanceof FiniteIteration.Sum sum)
            return new Source(parse(sum.term(),symbols(source,false),true,MAX_DEGREE,w),zero(w));
        var recurrence=(FiniteIteration.Recurrence)source;
        var update=parse(recurrence.update(),symbols(source,true),true,MAX_DEGREE,w);
        var term=update.subtract(variable(recurrence.state(),w));
        if(!term.independent(recurrence.state())) throw new Unavailable("NONLINEAR_STATE_UPDATE");
        return new Source(term,parse(recurrence.initial(),source.parameters(),true,MAX_DEGREE,w));
    }
    static long operations(Expr expression, Work w) { return operations(expression,w,1,new int[]{0}); }
    private static long operations(Expr e, Work w, int depth,int[] nodes) {
        w.charge(1);
        if(depth>FiniteIteration.MAX_DEPTH || ++nodes[0]>FiniteIteration.MAX_NODES) throw new Unavailable("EXPRESSION_LIMIT");
        return e instanceof BinaryExpr b ? 1+operations(b.left(),w,depth+1,nodes)+operations(b.right(),w,depth+1,nodes) : 0;
    }
    static FiniteIterationOptimizer.Cost cost(FiniteIteration source, Expr emitted, Work w) {
        long perIteration=source instanceof FiniteIteration.Sum s ? 1+operations(s.term(),w) : operations(((FiniteIteration.Recurrence)source).update(),w);
        long initial=source instanceof FiniteIteration.Recurrence r ? operations(r.initial(),w) : 0;
        return new FiniteIterationOptimizer.Cost(source.range().count().multiply(BigInteger.valueOf(perIteration)).add(BigInteger.valueOf(initial)),operations(emitted,w));
    }
}
