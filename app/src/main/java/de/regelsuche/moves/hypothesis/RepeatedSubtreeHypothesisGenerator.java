package de.regelsuche.moves.hypothesis;

import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds subtrees occurring more than once and proposes them as substitution
 * atoms. The repeated subtree may be arbitrarily complex.
 *
 * <p>Example: {@code (sin(x)+cos(x))^2 + 2*(sin(x)+cos(x)) + 1} →
 * {@code A = sin(x) + cos(x)}.</p>
 */
public final class RepeatedSubtreeHypothesisGenerator implements ParameterHypothesisGenerator {

    @Override
    public String id() {
        return "repeated-subtree";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        if (!context.allows(RewriteMoveKind.SUBSTITUTE_INTRODUCE) || context.inputIndex().isEmpty()) {
            return List.of();
        }
        List<ParameterHypothesis> result = new ArrayList<>();
        for (TermOccurrence occurrence : context.inputIndex().get().repeatedComposites()) {
            String canonical = occurrence.canonicalValue();
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.SUBSTITUTE_INTRODUCE,
                    "atom",
                    canonical,
                    canonical,
                    HypothesisSource.REPEATED_SUBTREE,
                    0.7,
                    "subtree occurs " + occurrence.occurrenceCount() + " times",
                    List.of("occurrences=" + occurrence.occurrenceCount())));
        }
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }
}
