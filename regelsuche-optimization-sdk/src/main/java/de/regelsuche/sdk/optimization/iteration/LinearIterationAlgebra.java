package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.LinearIteration.*;

/** Bounded integer polynomial primitives; shared arithmetic, never candidate search. */
final class LinearIterationAlgebra {
    private LinearIterationAlgebra() {}
    static final class Unavailable extends RuntimeException {
        Unavailable(String reason) { super(reason); }
    }
    static final class Work {
        final OptimizationBudget budget;
        final CancellationToken cancellation;
        final long start=System.nanoTime(),timeout;
        long used;
        Work(OptimizationBudget b,CancellationToken c) {
            budget=Objects.requireNonNull(b); cancellation=Objects.requireNonNull(c);
            timeout=b.timeoutMillis()>Long.MAX_VALUE/1_000_000 ? Long.MAX_VALUE:b.timeoutMillis()*1_000_000;
        }
        void charge() { charge(1); }
        void charge(long n) {
            if(cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
            if(n>budget.maximumWork()-used || System.nanoTime()-start>=timeout) throw new Unavailable("WORK_LIMIT");
            used+=n;
        }
        BigInteger checked(BigInteger n) { charge(); if(n.abs().bitLength()>MAX_BITS) throw new Unavailable("VALUE_LIMIT"); return n; }
        BigInteger add(BigInteger a,BigInteger b,IntegerArithmetic ring) {
            charge();
            if(ring==IntegerArithmetic.EXACT && a.signum()==b.signum() && a.signum()!=0
                    && Math.max(a.abs().bitLength(),b.abs().bitLength())>=MAX_BITS) throw new Unavailable("VALUE_LIMIT");
            return checked(ring.normalize(a.add(b)));
        }
        BigInteger multiply(BigInteger a,BigInteger b,IntegerArithmetic ring) {
            charge();
            if(ring==IntegerArithmetic.EXACT && a.signum()!=0 && b.signum()!=0
                    && (long)a.abs().bitLength()+b.abs().bitLength()>MAX_BITS) throw new Unavailable("VALUE_LIMIT");
            return checked(ring.normalize(a.multiply(b)));
        }
    }
    record Monomial(List<SymbolId> symbols) {
        Monomial { symbols=List.copyOf(symbols); }
        int degree(SymbolId s) { int n=0; for(var x:symbols) if(x.equals(s)) n++; return n; }
    }
    private static final Comparator<SymbolId> ORDER=Comparator.comparing(SymbolId::namespace).thenComparingLong(SymbolId::ordinal);
    static final class Poly {
        final Map<Monomial,BigInteger> terms;
        final LinearIteration source;
        final Work work;
        Poly(Map<Monomial,BigInteger> terms,LinearIteration source,Work work) { this.terms=terms; this.source=source; this.work=work; }
        void put(Map<Monomial,BigInteger> map,Monomial m,BigInteger coefficient) {
            work.checked(coefficient);
            int indexDegree=m.degree(source.index()), parameterDegree=0,stateDegree=0;
            for(var s:m.symbols()) { if(source.parameters().contains(s)) parameterDegree++; else if(!s.equals(source.index())) stateDegree++; }
            if(indexDegree>MAX_INDEX_DEGREE || parameterDegree>MAX_PARAMETER_DEGREE || stateDegree>1) throw new Unavailable("DEGREE_LIMIT");
            if(stateDegree>0 && indexDegree>0) throw new Unavailable("INDEX_DEPENDENT_STATE_COEFFICIENT");
            if(coefficient.signum()==0) map.remove(m); else map.put(m,coefficient);
            if(map.size()>Math.min(MAX_TERMS,work.budget.maximumStates())) throw new Unavailable("TERM_LIMIT");
        }
        Poly add(Poly other) {
            var out=new LinkedHashMap<>(terms); work.charge(terms.size());
            for(var e:other.terms.entrySet()) put(out,e.getKey(),work.add(out.getOrDefault(e.getKey(),BigInteger.ZERO),e.getValue(),source.arithmetic()));
            return new Poly(out,source,work);
        }
        Poly multiply(Poly other) {
            var out=new LinkedHashMap<Monomial,BigInteger>();
            for(var a:terms.entrySet()) for(var b:other.terms.entrySet()) {
                work.charge(); var symbols=new ArrayList<>(a.getKey().symbols()); symbols.addAll(b.getKey().symbols()); symbols.sort(ORDER);
                var m=new Monomial(symbols);
                put(out,m,work.add(out.getOrDefault(m,BigInteger.ZERO),work.multiply(a.getValue(),b.getValue(),source.arithmetic()),source.arithmetic()));
            }
            return new Poly(out,source,work);
        }
        Expr expression() {
            var pieces=new ArrayList<Expr>();
            for(var e:terms.entrySet()) {
                var factors=new ArrayList<Expr>(); factors.add(number(e.getValue()));
                for(var s:e.getKey().symbols()) factors.add(VariableExpr.scoped(s));
                pieces.add(tree(factors,BinaryOperator.MUL,number(BigInteger.ONE)));
            }
            var result=tree(pieces,BinaryOperator.ADD,number(BigInteger.ZERO));
            try { LinearIteration.validate(result,null); } catch(IllegalArgumentException ex) { throw new Unavailable(ex.getMessage()); }
            return result;
        }
    }
    static Expr number(BigInteger n) { return new NumberExpr(ExactRational.integer(n)); }
    static Expr tree(List<Expr> parts,BinaryOperator operator,Expr empty) {
        if(parts.isEmpty()) return empty;
        var level=new ArrayList<>(parts);
        while(level.size()>1) {
            var next=new ArrayList<Expr>();
            for(int i=0;i<level.size();i+=2) next.add(i+1==level.size()?level.get(i):new BinaryExpr(level.get(i),operator,level.get(i+1)));
            level=next;
        }
        return level.getFirst();
    }
    static Poly constant(LinearIteration s,Work w,BigInteger n) {
        var p=new Poly(new LinkedHashMap<>(),s,w); p.put(p.terms,new Monomial(List.of()),s.arithmetic().normalize(w.checked(n))); return p;
    }
    static Poly variable(LinearIteration s,Work w,SymbolId symbol) {
        var p=new Poly(new LinkedHashMap<>(),s,w); p.put(p.terms,new Monomial(List.of(symbol)),BigInteger.ONE); return p;
    }
    static Map<SymbolId,Poly> environment(LinearIteration source,Work w,boolean includeState) {
        var out=new HashMap<SymbolId,Poly>();
        for(var p:source.parameters()) out.put(p,variable(source,w,p));
        if(includeState) { out.put(source.index(),variable(source,w,source.index())); for(var s:source.states()) out.put(s.symbol(),variable(source,w,s.symbol())); }
        return out;
    }
    static Poly parse(Expr expr,Map<SymbolId,Poly> env,LinearIteration source,Work w) {
        w.charge();
        return switch(expr) {
            case NumberExpr n -> {
                if(!n.value().denominator().equals(BigInteger.ONE)) throw new Unavailable("NON_INTEGER");
                yield constant(source,w,n.value().numerator());
            }
            case VariableExpr v -> {
                var value=env.get(v.symbol().orElseThrow());
                if(value==null) throw new Unavailable("UNBOUND_SYMBOL"); yield value;
            }
            case BinaryExpr b -> {
                if(b.operator()!=BinaryOperator.ADD && b.operator()!=BinaryOperator.SUB && b.operator()!=BinaryOperator.MUL)
                    throw new Unavailable("UNSUPPORTED_OPERATOR");
                var left=parse(b.left(),env,source,w); var right=parse(b.right(),env,source,w);
                yield switch(b.operator()) {
                    case ADD -> left.add(right);
                    case SUB -> left.add(right.multiply(constant(source,w,BigInteger.ONE.negate())));
                    case MUL -> left.multiply(right);
                    default -> throw new Unavailable("UNSUPPORTED_OPERATOR");
                };
            }
            default -> throw new Unavailable("UNSUPPORTED_EXPRESSION");
        };
    }
    static List<Poly> basis(LinearIteration s,Work w,int degree) {
        var basis=new ArrayList<Poly>();
        for(var state:s.states()) basis.add(variable(s,w,state.symbol()));
        var power=constant(s,w,BigInteger.ONE); basis.add(power);
        for(int k=1;k<=degree;k++) { power=power.multiply(variable(s,w,s.index())); basis.add(power); }
        return basis;
    }
    static List<Expr> initial(LinearIteration s,Work w,int degree) {
        var env=environment(s,w,false); var values=new ArrayList<Expr>();
        for(var state:s.states()) values.add(parse(state.initial(),env,s,w).expression());
        var power=constant(s,w,BigInteger.ONE); values.add(power.expression());
        if(degree>0) {
            var start=parse(s.indexStart(),env,s,w);
            for(int k=1;k<=degree;k++) { power=power.multiply(start); values.add(power.expression()); }
        }
        return values;
    }
    static List<Expr> row(Poly value,int degree) {
        var s=value.source; var w=value.work; int n=s.states().size();
        var coefficients=new ArrayList<Poly>();
        for(int i=0;i<n+degree+1;i++) coefficients.add(constant(s,w,BigInteger.ZERO));
        for(var e:value.terms.entrySet()) {
            int column=n+e.getKey().degree(s.index());
            var remaining=new ArrayList<>(e.getKey().symbols()); remaining.removeIf(s.index()::equals);
            for(int k=0;k<n;k++) if(remaining.remove(s.states().get(k).symbol())) column=k;
            var p=new Poly(new LinkedHashMap<>(),s,w); p.put(p.terms,new Monomial(remaining),e.getValue());
            coefficients.set(column,coefficients.get(column).add(p));
        }
        return coefficients.stream().map(Poly::expression).toList();
    }
    static BigInteger eval(Expr expr,Map<SymbolId,BigInteger> bindings,IntegerArithmetic ring,Work w) {
        w.charge();
        return switch(expr) {
            case NumberExpr n -> {
                if(!n.value().denominator().equals(BigInteger.ONE)) throw new Unavailable("NON_INTEGER");
                yield ring.normalize(w.checked(n.value().numerator()));
            }
            case VariableExpr v -> {
                var n=bindings.get(v.symbol().orElseThrow()); if(n==null) throw new Unavailable("MISSING_PARAMETER");
                yield ring.normalize(w.checked(n));
            }
            case BinaryExpr b -> {
                var a=eval(b.left(),bindings,ring,w); var c=eval(b.right(),bindings,ring,w);
                yield switch(b.operator()) {
                    case ADD -> w.add(a,c,ring);
                    case SUB -> w.add(a,c.negate(),ring);
                    case MUL -> w.multiply(a,c,ring);
                    default -> throw new Unavailable("UNSUPPORTED_OPERATOR");
                };
            }
            default -> throw new Unavailable("UNSUPPORTED_EXPRESSION");
        };
    }
}
