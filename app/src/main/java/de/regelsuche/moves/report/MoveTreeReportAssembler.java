package de.regelsuche.moves.report;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.RewriteMoveDeriver;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.moves.macro.MacroMoveEnumerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Assembles a {@link MoveTreeReport} from a successful transformation path,
 * deriving a {@link RewriteMove} for every applied step and enumerating the
 * depth-1 candidate moves and macro moves at the start expression.
 */
public final class MoveTreeReportAssembler {

    private final RewriteMoveDeriver deriver;
    private final ExpressionCanonicalizer canonicalizer;
    private final Depth1MoveEnumerator depth1Enumerator;
    private final MacroMoveEnumerator macroEnumerator;

    public MoveTreeReportAssembler() {
        this(new RewriteMoveDeriver(), new ExpressionCanonicalizer(),
                new Depth1MoveEnumerator(), new MacroMoveEnumerator());
    }

    public MoveTreeReportAssembler(
            RewriteMoveDeriver deriver,
            ExpressionCanonicalizer canonicalizer,
            Depth1MoveEnumerator depth1Enumerator,
            MacroMoveEnumerator macroEnumerator) {
        this.deriver = deriver == null ? new RewriteMoveDeriver() : deriver;
        this.canonicalizer = canonicalizer == null ? new ExpressionCanonicalizer() : canonicalizer;
        this.depth1Enumerator = depth1Enumerator == null ? new Depth1MoveEnumerator() : depth1Enumerator;
        this.macroEnumerator = macroEnumerator == null ? new MacroMoveEnumerator() : macroEnumerator;
    }

    /**
     * @param scenarioId the scenario id
     * @param steps      ordered steps of the successful path
     * @param macros     active macros from the inventory (may be empty)
     * @param scorer     scores an expression for node ranking (may be {@code null})
     * @return the assembled move-tree report
     */
    public MoveTreeReport assemble(
            String scenarioId,
            List<PathStep> steps,
            List<ReusableRule> macros,
            ToDoubleFunction<String> scorer) {
        List<PathStep> safeSteps = steps == null ? List.of() : steps;
        ToDoubleFunction<String> safeScorer = scorer == null ? expression -> 0.0 : scorer;

        Map<String, MoveTreeReport.MoveNode> nodes = new LinkedHashMap<>();
        List<MoveTreeReport.MoveEdge> edges = new ArrayList<>();
        List<RewriteMove> pathMoves = new ArrayList<>();
        List<MoveTreeReport.UnresolvedParameterEntry> unresolved = new ArrayList<>();
        List<MoveOrdinal> ordinalPath = new ArrayList<>();

        int depth = 0;
        for (PathStep step : safeSteps) {
            String fromId = canonical(step.before());
            String toId = canonical(step.after());
            nodes.computeIfAbsent(fromId, id -> node(id, step.before(), depthOf(id, nodes), safeScorer));
            RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                    step.before(),
                    step.after(),
                    step.ruleId(),
                    step.operatorId(),
                    step.assumptions(),
                    step.source(),
                    List.of()));
            pathMoves.add(move);
            ordinalPath.add(move.ordinal());
            int toDepth = depth + 1;
            nodes.computeIfAbsent(toId, id -> node(id, step.after(), toDepth, safeScorer));
            edges.add(new MoveTreeReport.MoveEdge(
                    fromId,
                    toId,
                    move,
                    List.copyOf(ordinalPath),
                    true,
                    move.isMacro(),
                    ""));
            if (move.hasUnresolvedParameters()) {
                unresolved.add(new MoveTreeReport.UnresolvedParameterEntry(
                        move.moveId(), move.kind().name(), move.ruleId()));
            }
            depth++;
        }

        String startExpression = safeSteps.isEmpty() ? "" : safeSteps.getFirst().before();
        List<Depth1MoveEnumerator.CandidateMove> depth1 = depth1Enumerator.enumerate(startExpression);
        List<RewriteMove> macroMoves = macroEnumerator.enumerate(
                macros == null ? List.of() : macros, startExpression);
        for (RewriteMove macroMove : macroMoves) {
            if (macroMove.hasUnresolvedParameters()) {
                unresolved.add(new MoveTreeReport.UnresolvedParameterEntry(
                        macroMove.moveId(), macroMove.kind().name(), macroMove.ruleId()));
            }
        }

        return new MoveTreeReport(
                scenarioId,
                List.copyOf(nodes.values()),
                List.copyOf(edges),
                List.copyOf(pathMoves),
                depth1,
                macroMoves,
                List.copyOf(unresolved));
    }

    private int depthOf(String id, Map<String, MoveTreeReport.MoveNode> nodes) {
        MoveTreeReport.MoveNode existing = nodes.get(id);
        return existing == null ? 0 : existing.depth();
    }

    private MoveTreeReport.MoveNode node(String id, String expression, int depth, ToDoubleFunction<String> scorer) {
        return new MoveTreeReport.MoveNode(id, expression, canonical(expression), depth, scorer.applyAsDouble(expression));
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return expression.trim().replaceAll("\\s+", "");
        }
    }

    /**
     * A single step of a successful path.
     *
     * @param before     source expression
     * @param after      target expression
     * @param ruleId     applied rule id
     * @param operatorId applied operator id
     * @param assumptions assumptions/evidence for the step
     * @param source     provenance of the step
     */
    public record PathStep(
            String before,
            String after,
            String ruleId,
            String operatorId,
            List<String> assumptions,
            String source) {
        public PathStep {
            before = before == null ? "" : before;
            after = after == null ? "" : after;
            ruleId = ruleId == null ? "" : ruleId;
            operatorId = operatorId == null ? "" : operatorId;
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            source = source == null ? "" : source;
        }

        public PathStep(String before, String after, String ruleId) {
            this(before, after, ruleId, "", List.of(), "");
        }
    }
}
