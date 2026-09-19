package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.PatternExpr;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Opt-in syntax hypotheses only. No mathematical verification or promotion. */
public final class TypedPatternGeneralizer {
    public static final int MAXIMUM_EXAMPLES = 32;
    public static final int MAXIMUM_NODES = 1024;
    public static final int MAXIMUM_DEPTH = 64;

    public record Example(Expr source, Expr target, List<String> assumptions) {
        public Example(Expr source, Expr target) { this(source, target, List.of()); }
    }

    public record Candidate(PatternExpr source, PatternExpr target,
            List<Example> examples, List<Map<String, Expr>> bindings) {}

    public Optional<Candidate> generalize(List<Example> examples) {
        return Optional.empty();
    }
}
