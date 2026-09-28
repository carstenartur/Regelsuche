package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.List;

/** Explicit Expr execution; old typed/JSON entry points remain available separately. */
public final class NativeMoveSearch {
    public static final String REVISION = "regelsuche.native-expr-move-search/v1";
    public record Primitive(MoveProvider.Descriptor descriptor, AstRewriteTransport transport) {}
    public record Problem(Expr source, TypedMoveSearch.Context context, List<Primitive> providers,
            MoveSearch.Mode mode, MoveSearch.Scheduling scheduling, MoveSearch.Budget budget) {}
    public record Result(MoveSearch.Outcome outcome, Expr output, MoveSearch.Result legacy) {
        public MoveSearch.Result exportLegacy() { return legacy; }
    }
    public Result search(Problem problem, SearchContinuationContract continuation) {
        throw new UnsupportedOperationException("native frontier is not implemented");
    }
}
