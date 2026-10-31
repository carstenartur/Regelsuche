package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;

/** Scoped, finite, integer-ring state updates; count is a nonnegative mathematical integer. */
public record LinearIteration(SymbolId index, Expr indexStart, BigInteger indexStep, Expr count,
        List<State> states, Set<SymbolId> parameters, List<Update> updates,
        UpdateOrder order, IntegerArithmetic arithmetic) {
    public static final int MAX_STATES=8, MAX_PARAMETERS=16, MAX_INDEX_DEGREE=8,
        MAX_PARAMETER_DEGREE=16, MAX_TERMS=256, MAX_NODES=2048, MAX_DEPTH=64, MAX_BITS=4096;
    public enum UpdateOrder { SIMULTANEOUS, SEQUENTIAL }
    public record State(SymbolId symbol, Expr initial) {
        public State { Objects.requireNonNull(symbol); Objects.requireNonNull(initial); }
    }
    public record Update(SymbolId state, Expr expression) {
        public Update { Objects.requireNonNull(state); Objects.requireNonNull(expression); }
    }
    public LinearIteration {
        Objects.requireNonNull(index); Objects.requireNonNull(indexStep);
        Objects.requireNonNull(order); Objects.requireNonNull(arithmetic);
        if(states.isEmpty() || states.size()>MAX_STATES || updates.size()!=states.size())
            throw new IllegalArgumentException("STATE_DIMENSION");
        if(parameters.size()>MAX_PARAMETERS) throw new IllegalArgumentException("PARAMETER_LIMIT");
        states=List.copyOf(states); parameters=Set.copyOf(parameters); updates=List.copyOf(updates);
        if(indexStep.signum()==0 || indexStep.abs().bitLength()>MAX_BITS) throw new IllegalArgumentException("INDEX_STEP");
        var symbols=new HashSet<>(parameters);
        if(!symbols.add(index)) throw new IllegalArgumentException("COLLIDING_BINDERS");
        var assigned=new HashSet<SymbolId>();
        for(var s:states) {
            if(!symbols.add(s.symbol())) throw new IllegalArgumentException("COLLIDING_BINDERS");
            assigned.add(s.symbol()); validate(s.initial(),parameters);
        }
        validate(indexStart,parameters); validate(count,parameters);
        for(var u:updates) {
            if(!assigned.remove(u.state())) throw new IllegalArgumentException("INVALID_ASSIGNMENT");
            validate(u.expression(),symbols);
        }
    }
    static void validate(Expr expression, Set<SymbolId> allowed) {
        record Node(Expr expression,int depth) {}
        var pending=new ArrayDeque<Node>(); pending.push(new Node(Objects.requireNonNull(expression),1));
        int nodes=0;
        while(!pending.isEmpty()) {
            var p=pending.pop();
            if(++nodes>MAX_NODES || p.depth()>MAX_DEPTH) throw new IllegalArgumentException("EXPRESSION_LIMIT");
            switch(p.expression()) {
                case VariableExpr v -> {
                    if(v.symbol().isEmpty() || (allowed!=null && !allowed.contains(v.symbol().orElseThrow())))
                        throw new IllegalArgumentException("UNBOUND_SYMBOL");
                }
                case NumberExpr n -> {
                    if(n.value().numerator().abs().bitLength()>MAX_BITS || n.value().denominator().bitLength()>MAX_BITS)
                        throw new IllegalArgumentException("COEFFICIENT_LIMIT");
                }
                case BinaryExpr b -> { pending.push(new Node(b.left(),p.depth()+1)); pending.push(new Node(b.right(),p.depth()+1)); }
                case FunctionExpr f -> {
                    if(f.name().length()>128 || f.arguments().size()+pending.size()+nodes>MAX_NODES)
                        throw new IllegalArgumentException("EXPRESSION_LIMIT");
                    for(var a:f.arguments()) pending.push(new Node(a,p.depth()+1));
                }
            }
        }
    }
}
