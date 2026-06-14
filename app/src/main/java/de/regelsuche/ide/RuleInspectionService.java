package de.regelsuche.ide;

import de.regelsuche.ide.RuleInspectionDto.Binding;
import de.regelsuche.ide.RuleInspectionDto.PositionResult;
import de.regelsuche.ide.RuleInspectionDto.RuleMatch;
import de.regelsuche.moves.MoveRealizer;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.apply.LocalRewriteApplier;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inspects an expression tree-locally and returns, for each subtree position,
 * the rule candidates that matched together with their bindings and a
 * before/after rewrite preview.
 *
 * <p>This is the backend for the Rule Authoring IDE: callers obtain a full
 * {@link RuleInspectionDto} and can then display, filter or drill into any
 * position without a round-trip to the server.</p>
 */
public final class RuleInspectionService {

    private final TreeLocalMoveEnumerator enumerator;
    private final LocalRewriteApplier localRewriteApplier;

    public RuleInspectionService() {
        this(new TreeLocalMoveEnumerator(), new LocalRewriteApplier());
    }

    public RuleInspectionService(TreeLocalMoveEnumerator enumerator, MoveRealizer realizer) {
        this(enumerator, new LocalRewriteApplier(realizer, null));
    }

    public RuleInspectionService(TreeLocalMoveEnumerator enumerator, LocalRewriteApplier localRewriteApplier) {
        this.enumerator = enumerator == null ? new TreeLocalMoveEnumerator() : enumerator;
        this.localRewriteApplier = localRewriteApplier == null ? new LocalRewriteApplier() : localRewriteApplier;
    }

    /**
     * Inspects the given {@code expression} and returns rule matches grouped by
     * tree position.
     *
     * @param expression infix expression to inspect
     * @return inspection result; positions list is empty for unparseable input
     */
    public RuleInspectionDto inspect(String expression) {
        return inspect(expression, null);
    }

    /**
     * Inspects the given {@code expression} and marks the requested position as
     * selected when present.
     *
     * @param expression infix expression to inspect
     * @param selectedPathKey optional path key that should be marked selected
     * @return inspection result; positions list is empty for unparseable input
     */
    public RuleInspectionDto inspect(String expression, String selectedPathKey) {
        if (expression == null || expression.isBlank()) {
            return new RuleInspectionDto(expression == null ? "" : expression, List.of());
        }

        List<LocalCandidateMove> allCandidates = enumerator.enumerate(expression);

        // Group candidates by position (preserving CANONICAL_ORDER via LinkedHashMap)
        Map<TreePosition, List<LocalCandidateMove>> byPosition = new LinkedHashMap<>();
        for (LocalCandidateMove candidate : allCandidates) {
            byPosition.computeIfAbsent(candidate.position(), p -> new ArrayList<>()).add(candidate);
        }

        List<PositionResult> positions = new ArrayList<>();
        for (Map.Entry<TreePosition, List<LocalCandidateMove>> entry : byPosition.entrySet()) {
            TreePosition pos = entry.getKey();
            List<LocalCandidateMove> atPosition = entry.getValue();

            List<RuleMatch> matches = new ArrayList<>();

            // Group candidates by enumeratorId: complete-square emits shift + residue as
            // two separate CandidateMoves that belong to one logical match; all other
            // enumerators emit one CandidateMove per independent logical match.
            Map<String, List<LocalCandidateMove>> byEnumerator = new LinkedHashMap<>();
            for (LocalCandidateMove candidate : atPosition) {
                byEnumerator.computeIfAbsent(candidate.move().enumeratorId(), id -> new ArrayList<>())
                        .add(candidate);
            }

            for (Map.Entry<String, List<LocalCandidateMove>> enumeratorEntry : byEnumerator.entrySet()) {
                String enumeratorId = enumeratorEntry.getKey();
                List<LocalCandidateMove> enumeratorCandidates = enumeratorEntry.getValue();
                CandidateMove first = enumeratorCandidates.get(0).move();

                if ("complete-square".equals(enumeratorId)) {
                    // Collapse all complete-square parameters into one RuleMatch so that
                    // both shift and residue appear together as a single logical match.
                    List<Binding> bindings = enumeratorCandidates.stream()
                            .filter(c -> c.move().parameter() != null)
                            .map(c -> new Binding(
                                    c.move().parameter().name(),
                                    c.move().parameter().value(),
                                    c.move().parameter().kind().name()))
                            .toList();
                    LocalRewriteApplier.LocalRewriteResult result = localRewriteApplier.apply(
                            expression,
                            pos,
                            enumeratorCandidates.stream().map(LocalCandidateMove::move).toList());
                    String matchId = stableMatchId(
                            enumeratorId,
                            first.kind().name(),
                            bindings,
                            result.subtreeAfter(),
                            result.expressionAfter());
                    matches.add(new RuleMatch(
                            matchId,
                            enumeratorId,
                            first.kind().name(),
                            bindings,
                            result.subtreeBefore(),
                            result.subtreeAfter(),
                            result.subtreeBefore(),
                            result.subtreeAfter(),
                            result.expressionAfter(),
                            applicable(result)));
                } else {
                    // Each candidate is an independent logical match. Match rewriteAfter by
                    // parameter value to correctly distinguish multiple realized moves of the
                    // same kind (e.g. several cancellation-candidate or repeated-subexpression
                    // moves at the same position).
                    for (LocalCandidateMove candidate : enumeratorCandidates) {
                        CandidateMove move = candidate.move();
                        LocalRewriteApplier.LocalRewriteResult result = localRewriteApplier.apply(expression, pos, move);
                        List<Binding> realizedBindings = bindings(result.bindings());
                        matches.add(new RuleMatch(
                                stableMatchId(
                                        move.enumeratorId(),
                                        move.kind().name(),
                                        realizedBindings,
                                        result.subtreeAfter(),
                                        result.expressionAfter()),
                                move.enumeratorId(),
                                move.kind().name(),
                                realizedBindings,
                                result.subtreeBefore(),
                                result.subtreeAfter(),
                                result.subtreeBefore(),
                                result.subtreeAfter(),
                                result.expressionAfter(),
                                applicable(result)));
                    }
                }
            }
            positions.add(new PositionResult(
                    pos.pathKey(),
                    pos.text(),
                    matches,
                    selectedPathKey != null && selectedPathKey.equals(pos.pathKey())));
        }

        return new RuleInspectionDto(expression, positions);
    }

    private static List<Binding> bindings(List<MoveParameter> parameters) {
        return parameters.stream()
                .map(parameter -> new Binding(parameter.name(), parameter.value(), parameter.kind().name()))
                .toList();
    }

    private static boolean applicable(LocalRewriteApplier.LocalRewriteResult result) {
        if (result == null) {
            return false;
        }
        return (result.expressionAfter() != null && !result.expressionAfter().isBlank())
                || (result.subtreeAfter() != null && !result.subtreeAfter().isBlank());
    }

    private static String stableMatchId(
            String enumeratorId,
            String kind,
            List<Binding> bindings,
            String subtreeAfter,
            String expressionAfter) {
        String bindingKey = bindings.stream()
                .map(binding -> segment(binding.name()) + segment(binding.value()) + segment(binding.kind()))
                .sorted()
                .reduce("", String::concat);
        return segment(enumeratorId)
                + segment(kind)
                + segment(bindingKey)
                + segment(subtreeAfter)
                + segment(expressionAfter);
    }

    private static String segment(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
}
