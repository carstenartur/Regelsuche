package de.regelsuche.egraph;

import de.regelsuche.ast.*;
import de.regelsuche.egraph.proof.ScalarExtractionProof;
import de.regelsuche.egraph.proof.ScalarGuardFacts;
import de.regelsuche.transform.PatternExpr;
import java.util.*;

/** Directed match plans over e-nodes and canonical child classes. Syntax extraction records an actual matched witness. */
final class NativeScalarRules {
    private static final FlatAcMatchPlan DOUBLE = FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.ADD,
        PatternExpr.var("term"),PatternExpr.var("term")));
    private NativeScalarRules() {}

    static void apply(NativeScalarSaturation run, String rule, List<EClassId> roots, NativeScalarSaturation.Account work) {
        if (rule.equals("ast_double_term")) { doubles(run,rule,roots,work); return; }
        for (var raw : roots) {
            if (!work.spend(1,run.budget.perRuleFuel())) return;
            work.roots++;
            var root = run.graph.find(raw);
            for (var node : List.copyOf(run.graph.classOrThrow(root).nodes())) {
                if (!work.spend(1,run.budget.perRuleFuel())) return;
                work.nodes++;
                switch (rule) {
                    case "ast_add_zero_left","ast_add_zero_right" -> neutral(run,rule,root,node,BinaryOperator.ADD,0,work);
                    case "ast_multiply_one_left","ast_multiply_one_right" -> neutral(run,rule,root,node,BinaryOperator.MUL,1,work);
                    case "ast_divide_one" -> ordered(run,rule,root,node,BinaryOperator.DIV,1,work);
                    case "ast_subtract_zero" -> ordered(run,rule,root,node,BinaryOperator.SUB,0,work);
                    case "ast_factor_common_left","ast_factor_common_right" -> factor(run,rule,root,node,work);
                    case "ast_cancel_division_factor" -> cancel(run,rule,root,node,work);
                    default -> throw new IllegalArgumentException("rule without native plan: " + rule);
                }
            }
        }
    }
    private static void doubles(NativeScalarSaturation run, String rule, List<EClassId> roots, NativeScalarSaturation.Account work) {
        int left = run.budget.perRuleFuel()-work.spent;
        var matched = DOUBLE.matchRoots(run.graph, roots,left);
        work.spend(matched.work(),run.budget.perRuleFuel());
        work.capped |= !matched.complete();
        work.roots += matched.candidateRoots();
        work.nodes += matched.nodesScanned();
        work.tuples += matched.tupleAttempts();
        work.duplicates += matched.duplicateBindings();
        for (var match : matched.matches()) {
            var subject = match.bindings().get("term");
            var two = run.graph.add(ENode.leaf("num:2"));
            var target = run.graph.add(new ENode("ac:MUL",List.of(two,subject)));
            Expr term = run.syntax(subject);
            run.apply(rule,match.root(),target,new BinaryExpr(term,BinaryOperator.ADD,term),
                new BinaryExpr(new NumberExpr(2),BinaryOperator.MUL,term),work);
        }
    }
    private static void neutral(NativeScalarSaturation run,String rule,EClassId root,ENode node,BinaryOperator op,int literal,
                                NativeScalarSaturation.Account work) {
        if (!node.symbol().equals("ac:"+op)) return;
        for (int i=0;i<node.children().size();i++) {
            if (!work.spend(1,run.budget.perRuleFuel())) return;
            work.tuples++;
            if (!hasLiteral(run,node.children().get(i),literal)) continue;
            var rest = new ArrayList<>(node.children()); rest.remove(i);
            var right = group(run,rest,op);
            Expr target = productSyntax(run,rest,op);
            // This ground source uses the actual literal witness even if another representative is cheaper.
            var operands = new ArrayList<Expr>(node.children().stream().map(run::syntax).toList());
            operands.set(i,new NumberExpr(literal));
            run.apply(rule,root,right,ScalarExtractionProof.join(operands,op),target,work);
        }
    }
    private static void ordered(NativeScalarSaturation run,String rule,EClassId root,ENode node,BinaryOperator op,int literal,
                                NativeScalarSaturation.Account work) {
        if (!node.symbol().equals("op:"+op) || !hasLiteral(run,node.children().get(1),literal)) return;
        var target = node.children().getFirst();
        var term = run.syntax(target);
        run.apply(rule,root,target,new BinaryExpr(term,op,new NumberExpr(literal)),term,work);
    }
    private static boolean hasLiteral(NativeScalarSaturation run,EClassId child,int literal) {
        return run.graph.classOrThrow(run.graph.find(child)).nodes().contains(ENode.leaf("num:"+literal));
    }
    private static void factor(NativeScalarSaturation run,String rule,EClassId root,ENode node,NativeScalarSaturation.Account work) {
        if (!node.symbol().equals("ac:ADD") || node.children().size()!=2) return;
        var left = List.copyOf(run.graph.classOrThrow(run.graph.find(node.children().get(0))).nodes());
        var right = List.copyOf(run.graph.classOrThrow(run.graph.find(node.children().get(1))).nodes());
        for (var a : left) for(var b : right) {
            if (!work.spend(1,run.budget.perRuleFuel())) return;
            if (!a.symbol().equals("ac:MUL") || !b.symbol().equals("ac:MUL")) continue;
            for (int i=0;i<a.children().size();i++) for (int j=0;j<b.children().size();j++) {
                if (!work.spend(1,run.budget.perRuleFuel())) return;
                work.tuples++;
                var common = run.graph.find(a.children().get(i));
                if (common.equals(run.graph.find(b.children().get(j)))) {
                    var ax=new ArrayList<>(a.children()); ax.remove(i);
                    var bx=new ArrayList<>(b.children()); bx.remove(j);
                    var sum=run.graph.add(new ENode("ac:ADD",List.of(group(run,ax,BinaryOperator.MUL),group(run,bx,BinaryOperator.MUL))));
                    var rhs=run.graph.add(new ENode("ac:MUL",List.of(common,sum)));
                    Expr before=new BinaryExpr(productSyntax(run,a.children(),BinaryOperator.MUL),BinaryOperator.ADD,productSyntax(run,b.children(),BinaryOperator.MUL));
                    Expr after=new BinaryExpr(run.syntax(common),BinaryOperator.MUL,
                        new BinaryExpr(productSyntax(run,ax,BinaryOperator.MUL),BinaryOperator.ADD,productSyntax(run,bx,BinaryOperator.MUL)));
                    run.apply(rule,root,rhs,before,after,work);
                }
            }
        }
    }

    private static void cancel(NativeScalarSaturation run,String rule,EClassId root,ENode node,NativeScalarSaturation.Account work) {
        if (!node.symbol().equals("op:DIV")) return;
        var numerator = run.graph.find(node.children().getFirst());
        var denominator = run.graph.find(node.children().get(1));
        for (var product : List.copyOf(run.graph.classOrThrow(numerator).nodes())) {
            if (!product.symbol().equals("ac:MUL")) continue;
            for (int i=0;i<product.children().size();i++) {
                if (!work.spend(1,run.budget.perRuleFuel())) return;
                work.tuples++;
                if (!run.graph.find(product.children().get(i)).equals(denominator)) continue;
                var factor = run.syntax(denominator);
                var truth = run.facts.get(denominator).evaluate(ScalarGuardFacts.Predicate.NONZERO);
                if (truth != ScalarGuardFacts.Truth.TRUE) {
                    work.rejected++;
                    run.guards.add(new NativeScalarSaturation.Guard(rule,factor,truth.name(),false));
                    break;
                }
                var rest = new ArrayList<>(product.children()); rest.remove(i);
                Expr before = new BinaryExpr(productSyntax(run,product.children(),BinaryOperator.MUL),BinaryOperator.DIV,factor);
                int previous = work.unions;
                run.apply(rule,root,group(run,rest,BinaryOperator.MUL),before,productSyntax(run,rest,BinaryOperator.MUL),work);
                run.guards.add(new NativeScalarSaturation.Guard(rule,factor,truth.name(),work.unions>previous));
                break;
            }
        }
    }
    private static EClassId group(NativeScalarSaturation run,List<EClassId> ids,BinaryOperator op) {
        return ids.size()==1 ? run.graph.find(ids.getFirst()) : run.graph.add(new ENode("ac:"+op,ids));
    }
    private static Expr productSyntax(NativeScalarSaturation run,List<EClassId> ids,BinaryOperator op) {
        return ScalarExtractionProof.join(ids.stream().map(run::syntax).toList(),op);
    }
}
