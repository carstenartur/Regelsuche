package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded rational polynomial arithmetic shared by synthesis and checking, never a proof itself. */
public final class IterationPolynomial {
    public static final int MAX_DEGREE = 8, MAX_TERMS = 256;
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String reason) { super(reason); }
    }
    public static final class Work {
        private final IterationLimits limits;
        private final BooleanSupplier cancellation;
        final int maximumDegree;
        final long start = System.nanoTime(), timeout;
        long used;
        public Work(IterationLimits limits, BooleanSupplier cancellation, int maximumDegree) {
            this.maximumDegree = maximumDegree;
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }

        public long used() { return used; }

        public void charge(long amount) {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
            if (amount < 0 || amount > limits.maximumWork() - used || System.nanoTime() - start >= timeout)
                throw new Unavailable("WORK_LIMIT");
            used += amount;
        }

        public void runtimeInteger(BigInteger value) {
            charge(1 + value.bitLength() / 64L);
            if (value.bitLength() > IterationLimits.MAX_RUNTIME_BITS) throw new Unavailable("RUNTIME_BIT_LIMIT");
        }

        public void coefficient(ExactRational c) {
            charge(1);
            if(c.numerator().bitLength()>FiniteIteration.MAX_COEFFICIENT_BITS || c.denominator().bitLength()>FiniteIteration.MAX_COEFFICIENT_BITS)
                throw new Unavailable("COEFFICIENT_LIMIT");
        }
    }
    public record Monomial(List<SymbolId> symbols) {
        public Monomial {
            var canonical = new ArrayList<>(symbols);
            canonical.sort(ORDER);
            symbols = List.copyOf(canonical);
        }
        public int degree(SymbolId symbol) { int result=0; for(var s:symbols) if(s.equals(symbol)) result++; return result; }
        public Monomial without(SymbolId symbol) { return new Monomial(symbols.stream().filter(s -> !s.equals(symbol)).toList()); }
    }
    private static final Comparator<SymbolId> ORDER = Comparator.comparing(SymbolId::namespace).thenComparingLong(SymbolId::ordinal);
    private final Map<Monomial,ExactRational> terms;
    private final Work work;
    public Map<Monomial,ExactRational> terms() { return terms; }
    public Work work() { return work; }
    private IterationPolynomial(Map<Monomial,ExactRational> terms, Work work) { this.terms=Collections.unmodifiableMap(terms); this.work=work; }
    public static IterationPolynomial zero(Work w) { return new IterationPolynomial(new LinkedHashMap<>(),w); }
    public static IterationPolynomial constant(BigInteger n, Work w) { return single(new Monomial(List.of()),ExactRational.integer(n),w); }
    public static IterationPolynomial variable(SymbolId s, Work w) { return single(new Monomial(List.of(s)),ExactRational.ONE,w); }
    public static IterationPolynomial single(Monomial m, ExactRational c, Work w) {
        var map = new LinkedHashMap<Monomial,ExactRational>(); put(map,m,c,w); return new IterationPolynomial(map,w);
    }
    private static void put(Map<Monomial,ExactRational> map, Monomial m, ExactRational c, Work w) {
        w.coefficient(c);
        if(m.symbols().size()>w.maximumDegree) throw new Unavailable("DEGREE_LIMIT");
        if(c.signum()==0) map.remove(m); else map.put(m,c);
        if(map.size()>Math.min(MAX_TERMS,w.limits.maximumTerms())) throw new Unavailable("TERM_LIMIT");
    }
    public IterationPolynomial add(IterationPolynomial other) {
        var map = new LinkedHashMap<>(terms); work.charge(terms.size());
        for(var entry:other.terms.entrySet()) put(map,entry.getKey(),map.getOrDefault(entry.getKey(),ExactRational.ZERO).add(entry.getValue()),work);
        return new IterationPolynomial(map,work);
    }
    public IterationPolynomial scale(ExactRational factor) {
        work.coefficient(factor);
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var entry:terms.entrySet()) put(map,entry.getKey(),entry.getValue().multiply(factor),work);
        return new IterationPolynomial(map,work);
    }
    public IterationPolynomial subtract(IterationPolynomial other) { return add(other.scale(ExactRational.NEGATIVE_ONE)); }
    public IterationPolynomial multiply(IterationPolynomial other) {
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var a:terms.entrySet()) for(var b:other.terms.entrySet()) {
            work.charge(1);
            if(a.getKey().symbols().size()+b.getKey().symbols().size()>work.maximumDegree) throw new Unavailable("DEGREE_LIMIT");
            var symbols = new ArrayList<>(a.getKey().symbols()); symbols.addAll(b.getKey().symbols()); symbols.sort(ORDER);
            var m = new Monomial(symbols);
            put(map,m,map.getOrDefault(m,ExactRational.ZERO).add(a.getValue().multiply(b.getValue())),work);
        }
        return new IterationPolynomial(map,work);
    }
    public IterationPolynomial substitute(SymbolId symbol, IterationPolynomial value) {
        var result=zero(work);
        for(var entry:terms.entrySet()) {
            var term=single(entry.getKey().without(symbol),entry.getValue(),work);
            for(int n=entry.getKey().degree(symbol);n>0;n--) term=term.multiply(value);
            result=result.add(term);
        }
        return result;
    }
    public IterationPolynomial at(SymbolId symbol, BigInteger value) { return substitute(symbol,constant(value,work)); }
    public IterationPolynomial shifted(SymbolId symbol, BigInteger step) { return substitute(symbol,variable(symbol,work).add(constant(step,work))); }
    public boolean independent(SymbolId symbol) { work.charge(terms.size()); return terms.keySet().stream().noneMatch(m -> m.degree(symbol)>0); }
    public boolean same(IterationPolynomial other) { work.charge(terms.size()+other.terms.size()); return terms.equals(other.terms); }
    public IterationPolynomial integral(IntegerArithmetic arithmetic) {
        var map = new LinkedHashMap<Monomial,ExactRational>();
        for(var e:terms.entrySet()) {
            work.charge(1);
            if(!e.getValue().isInteger()) throw new Unavailable("FRACTIONAL_RESULT");
            put(map,e.getKey(),ExactRational.integer(arithmetic.normalize(e.getValue().numerator())),work);
        }
        return new IterationPolynomial(map,work);
    }
    public static IterationPolynomial parse(Expr expression, Set<SymbolId> allowed, boolean integers, int degree, Work w) {
        return parse(expression,allowed,integers,false,degree,w,1,new int[]{0});
    }
    static IterationPolynomial parsePairSummand(PairReduction source, Work work) {
        var allowed = new HashSet<SymbolId>();
        for (var column : source.columns()) {
            work.charge(1);
            allowed.add(column.left());
            allowed.add(column.right());
        }
        return parse(source.summand(), allowed, true, true, PairReduction.MAX_DEGREE, work, 1, new int[]{0});
    }
    private static IterationPolynomial parse(Expr expression, Set<SymbolId> allowed, boolean integers,
            boolean powers, int degree, Work w, int depth, int[] nodes) {
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
            result = parseBinary(binary, allowed, integers, powers, degree, w, depth + 1, nodes);
        } else throw new Unavailable("UNSUPPORTED_EXPRESSION");
        for(var m:result.terms.keySet()) { w.charge(1); if(m.symbols().size()>degree) throw new Unavailable("DEGREE_LIMIT"); }
        return result;
    }

    private static IterationPolynomial parseBinary(BinaryExpr binary, Set<SymbolId> allowed, boolean integers,
            boolean powers, int degree, Work work, int childDepth, int[] nodes) {
        BinaryOperator operator = binary.operator();
        if (powers && operator == BinaryOperator.POW) {
            if (!(binary.right() instanceof NumberExpr exponent) || !exponent.value().isInteger()
                    || exponent.value().signum() < 0
                    || exponent.value().numerator().compareTo(BigInteger.valueOf(degree)) > 0) {
                throw new Unavailable("UNSUPPORTED_POWER");
            }
            var base = parse(binary.left(), allowed, integers, true, degree, work, childDepth, nodes);
            var result = constant(BigInteger.ONE, work);
            int count = exponent.value().numerator().intValueExact();
            for (int i = 0; i < count; i++) {
                result = result.multiply(base);
            }
            return result;
        }
        if (operator != BinaryOperator.ADD && operator != BinaryOperator.SUB && operator != BinaryOperator.MUL) {
            throw new Unavailable("UNSUPPORTED_OPERATOR");
        }
        var left = parse(binary.left(), allowed, integers, powers, degree, work, childDepth, nodes);
        var right = parse(binary.right(), allowed, integers, powers, degree, work, childDepth, nodes);
        return switch (operator) {
            case ADD -> left.add(right);
            case SUB -> left.subtract(right);
            case MUL -> left.multiply(right);
            default -> throw new AssertionError();
        };
    }

    public Expr expression() {
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
}
