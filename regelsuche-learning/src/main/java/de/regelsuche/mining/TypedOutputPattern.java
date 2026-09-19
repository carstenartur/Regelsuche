package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.List;
import java.util.Map;

/** Test-first scaffold: syntax hypotheses only; no proof or promotion authority. */
public final class TypedOutputPattern {
    public static final int MAXIMUM_OUTPUTS = 8;
    public static final int MAXIMUM_ASSIGNMENTS = 4096;
    private final TypedPatternGeneralizer.Candidate hypothesis;

    public TypedOutputPattern(TypedPatternGeneralizer.Candidate hypothesis) {
        this.hypothesis = hypothesis;
    }

    public TypedPatternGeneralizer.Candidate hypothesis() { return hypothesis; }

    public record Application(FunctionExpr source, FunctionExpr target,
            List<Integer> positions, Map<String, Expr> bindings) {}

    public record Result(List<Application> applications, boolean complete,
            int assignmentAttempts, long matcherSteps) {}

    public Result find(FunctionExpr program, int maximumAssignments) {
        return new Result(List.of(), true, 0, 0);
    }
}
