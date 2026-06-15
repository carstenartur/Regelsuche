package de.regelsuche.ide;

import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default logical grouping with one-to-one fallback and pluggable enumerator strategies. */
public final class DefaultLogicalMoveGrouper implements LogicalMoveGrouper {

    private final Map<String, EnumeratorLogicalMoveGroupingStrategy> strategiesByEnumeratorId;

    public DefaultLogicalMoveGrouper() {
        this(Map.of("complete-square", EnumeratorLogicalMoveGroupingStrategy.completeSquare()));
    }

    DefaultLogicalMoveGrouper(Map<String, EnumeratorLogicalMoveGroupingStrategy> strategiesByEnumeratorId) {
        this.strategiesByEnumeratorId = Map.copyOf(strategiesByEnumeratorId);
    }

    @Override
    public List<LogicalMoveMatch> group(TreePosition position, List<LocalCandidateMove> candidates) {
        if (position == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<LocalCandidateMove>> byEnumerator = new LinkedHashMap<>();
        for (LocalCandidateMove candidate : candidates) {
            byEnumerator.computeIfAbsent(candidate.move().enumeratorId(), id -> new ArrayList<>())
                    .add(candidate);
        }

        List<LogicalMoveMatch> matches = new ArrayList<>();
        for (Map.Entry<String, List<LocalCandidateMove>> entry : byEnumerator.entrySet()) {
            String enumeratorId = entry.getKey();
            List<LocalCandidateMove> enumeratorCandidates = entry.getValue();
            EnumeratorLogicalMoveGroupingStrategy strategy = strategiesByEnumeratorId.getOrDefault(
                    enumeratorId,
                    EnumeratorLogicalMoveGroupingStrategy.singleCandidate());
            matches.addAll(strategy.group(position, enumeratorId, enumeratorCandidates));
        }
        return List.copyOf(matches);
    }

    private interface EnumeratorLogicalMoveGroupingStrategy {

        List<LogicalMoveMatch> group(
                TreePosition position,
                String enumeratorId,
                List<LocalCandidateMove> enumeratorCandidates);

        static EnumeratorLogicalMoveGroupingStrategy singleCandidate() {
            return (position, enumeratorId, enumeratorCandidates) -> enumeratorCandidates.stream()
                    .map(candidate -> {
                        List<MoveParameter> bindings = candidate.move().parameter() == null
                                ? List.of()
                                : List.of(candidate.move().parameter());
                        return new LogicalMoveMatch(
                                position,
                                candidate.move().enumeratorId(),
                                candidate.move().kind().name(),
                                List.of(candidate),
                                bindings,
                                false);
                    })
                    .toList();
        }

        static EnumeratorLogicalMoveGroupingStrategy completeSquare() {
            return (position, enumeratorId, enumeratorCandidates) -> {
                if (enumeratorCandidates.isEmpty()) {
                    return List.of();
                }
                LocalCandidateMove first = enumeratorCandidates.getFirst();
                List<MoveParameter> bindings = enumeratorCandidates.stream()
                        .map(candidate -> candidate.move().parameter())
                        .filter(parameter -> parameter != null)
                        .sorted(MoveParameter.CANONICAL_ORDER)
                        .toList();
                return List.of(new LogicalMoveMatch(
                        position,
                        enumeratorId,
                        first.move().kind().name(),
                        enumeratorCandidates,
                        bindings,
                        true));
            };
        }
    }
}
