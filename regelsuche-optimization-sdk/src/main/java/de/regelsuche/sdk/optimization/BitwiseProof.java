package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.math.BigInteger;
import java.util.*;

/** Exhaustive truth tables over up to eight opaque word inputs, for every bit position.
 * This is a universal bitvector proof (not numeric input sampling).
 */
final class BitwiseProof {
    private final VerificationWork work;
    private final Map<Expr,Expr> representatives=new HashMap<>();
    private final Set<Expr> constants=new LinkedHashSet<>();
    BitwiseProof(VerificationWork work) { this.work=work; }
    boolean equivalent(Expr left,Expr right,NumericKind kind) {
        var atoms=new LinkedHashSet<Expr>(); collect(left,atoms); collect(right,atoms);
        if (atoms.size()>8) return false;
        constants.add(kind==NumericKind.LONG?JavaExpressions.literal(0L):JavaExpressions.literal(0));
        constants.add(kind==NumericKind.LONG?JavaExpressions.literal(-1L):JavaExpressions.literal(-1));
        var distinct=new ArrayList<Expr>();
        for(var atom:atoms) {
            Expr representative=atom;
            var options=new ArrayList<>(constants); options.addAll(distinct);
            for(var option:options) {
                try {
                    if(new PolynomialProof(kind,false,false,work).equivalent(atom,option)) { representative=option; break; }
                } catch(PolynomialProof.OutsideFragment unknown) { /* Unproved atoms remain independent. */ }
            }
            representatives.put(atom,representative);
            if(representative.equals(atom)) distinct.add(atom);
        }
        var list=List.copyOf(distinct);
        for(int bit=0;bit<kind.bits();bit++) for(int assignment=0;assignment<(1<<list.size());assignment++) {
            work.charge(1);
            if(value(left,bit,assignment,list)!=value(right,bit,assignment,list)) return false;
        }
        return true;
    }
    private void collect(Expr e,Set<Expr> atoms) {
        work.charge(1);
        if(JavaExpressions.isLiteral(e)) { constants.add(e); return; }
        var op=JavaExpressions.operationOf(e).orElse(null);
        if(op==NumericOperation.AND || op==NumericOperation.OR || op==NumericOperation.XOR || op==NumericOperation.NOT)
            JavaExpressions.operands(e).forEach(child -> collect(child,atoms));
        else atoms.add(e);
    }
    private boolean value(Expr e,int bit,int assignment,List<Expr> atoms) {
        if(JavaExpressions.isLiteral(e)) return SemanticChecker.integer(JavaExpressions.literalValue(e)).testBit(bit);
        var op=JavaExpressions.operationOf(e).orElse(null); var args=JavaExpressions.operands(e);
        if(op==NumericOperation.NOT) return !value(args.getFirst(),bit,assignment,atoms);
        if(op==NumericOperation.AND) return value(args.getFirst(),bit,assignment,atoms)&value(args.get(1),bit,assignment,atoms);
        if(op==NumericOperation.OR) return value(args.getFirst(),bit,assignment,atoms)|value(args.get(1),bit,assignment,atoms);
        if(op==NumericOperation.XOR) return value(args.getFirst(),bit,assignment,atoms)^value(args.get(1),bit,assignment,atoms);
        var representative=representatives.getOrDefault(e,e);
        if(JavaExpressions.isLiteral(representative)) return SemanticChecker.integer(JavaExpressions.literalValue(representative)).testBit(bit);
        return (assignment&(1<<atoms.indexOf(representative)))!=0;
    }
}
