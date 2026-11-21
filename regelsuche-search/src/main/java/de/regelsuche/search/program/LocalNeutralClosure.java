package de.regelsuche.search.program;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A source-only, strictly decreasing primitive pack executed by the existing typed interpreter. */
public final class LocalNeutralClosure {
    public static final String REVISION = "regelsuche.local-neutral-closure/v1";
    private static final Set<String> IDS = Set.of("ast_add_zero_right","ast_add_zero_left",
        "ast_multiply_one_right","ast_multiply_one_left","ast_subtract_zero","ast_divide_one");
    private static final List<RewriteRule> RULES = AstRewriteTransformationEngine.allBuiltInRules().stream()
        .filter(r -> IDS.contains(r.id())).toList();
    public record Limits(int primitiveDepth, int sourceCalls) {
        public Limits {
            if (primitiveDepth<1 || primitiveDepth>8 || sourceCalls<0 || sourceCalls>4096)
                throw new IllegalArgumentException("local closure bounds");
        }
    }
    public enum Status { COMPLETE_BOUNDED, SOURCE_CALL_LIMIT }
    public record SourceCall(String source, List<Transformation> generated) {
        public SourceCall { generated=List.copyOf(generated); }
    }
    public record Result(Status status, Limits limits, List<SourceCall> sourceCalls,
                         Optional<RewriteExecution> execution) {
        public Result { sourceCalls=List.copyOf(sourceCalls); }
        public int nativeCalls() { return sourceCalls.size(); }
        public long generatedCandidates() { return sourceCalls.stream().mapToLong(c -> c.generated().size()).sum(); }
    }
    private LocalNeutralClosure() { }
    public static List<RewriteRule> inventory() { return RULES; }
    public static String inventoryHash() { return RuleInventoryFingerprint.contentHash(RULES); }

    /** Complete only within the declared pack and primitive depth; the input is never a hidden target. */
    public static Result execute(String source, Limits limits) {
        Objects.requireNonNull(limits);
        if (source==null || source.isBlank() || source.length()>2048) throw new IllegalArgumentException("closure input size");
        if (rawSize(source)>63) throw new IllegalArgumentException("closure AST limit");
        if (RULES.size()!=IDS.size()) throw new IllegalStateException("closure inventory changed");
        var calls=new ArrayList<SourceCall>();
        var engine=new AstRewriteTransformationEngine(RULES,0,Integer.MAX_VALUE);
        TransformationEngine bounded = input -> {
            if (calls.size()>=limits.sourceCalls()) throw new CallLimit();
            var generated=engine.transform(input);
            calls.add(new SourceCall(input,generated));
            int before=rawSize(input);
            for(var step:generated) {
                if (!IDS.contains(step.rule()) || !step.assumptions().isEmpty()
                        || !step.equivalencePreservingByConstruction() || step.primitiveStepCount()!=1
                        || step.exactTheoryStepCount()!=0 || rawSize(step.transformedExpression())>=before)
                    throw new IllegalStateException("closure step exceeds its declared decreasing primitive pack: "
                        + step.rule()+" "+input+" -> "+step.transformedExpression()+" nodes="+before+"/"
                        + rawSize(step.transformedExpression())+" assumptions="+step.assumptions());
            }
            return generated;
        };
        var program=RewritePrograms.repeat(limits.primitiveDepth(),RewritePrograms.source(bounded)).named(REVISION);
        try {
            var execution=new RewriteProgramInterpreter().executeWithWorkBudget(program,source,
                new BudgetedRewriteProgramExecution.PathBudget(limits.primitiveDepth(),0));
            if (!execution.complete()) throw new IllegalStateException("unexpected incomplete neutral closure");
            return new Result(Status.COMPLETE_BOUNDED,limits,calls,Optional.of(execution));
        } catch(CallLimit limit) {
            return new Result(Status.SOURCE_CALL_LIMIT,limits,calls,Optional.empty());
        }
    }
    private static int rawSize(String source) {
        var pending=new ArrayDeque<Expr>();
        pending.add(new ExpressionParser().parseTerm(source));
        int count=0;
        while(!pending.isEmpty()) {
            if (++count>63) return count;
            switch(pending.removeFirst()) {
                case BinaryExpr binary -> { pending.add(binary.left()); pending.add(binary.right()); }
                case FunctionExpr function -> pending.addAll(function.arguments());
                default -> { }
            }
        }
        return count;
    }
    private static final class CallLimit extends RuntimeException { }
}
