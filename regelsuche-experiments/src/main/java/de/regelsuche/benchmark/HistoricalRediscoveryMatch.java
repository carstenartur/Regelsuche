package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.discovery.representation.RepresentationCorrespondenceClassifier;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.strategy.SearchState;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Shared post-search representation criterion for the existing historical reports. */
final class HistoricalRediscoveryMatch {
    static final String REPRESENTATION_CRITERION = "POST_SEARCH_AC_REPRESENTATION_V1";
    private final RepresentationCorrespondenceClassifier representations =
        new RepresentationCorrespondenceClassifier();
    private final ExpressionParser parser = new ExpressionParser();

    Optional<SearchState> find(Case benchmarkCase, String target, List<SearchState> states) {
        if (sourceAlreadyMatches(benchmarkCase, target)) return Optional.empty();
        return states.stream()
            .filter(state -> matches(benchmarkCase, state, target))
            .min(Comparator.comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state -> String.join("->", state.appliedRuleIds())));
    }

    boolean sourceAlreadyMatches(Case benchmarkCase, String target) {
        if (benchmarkCase.targetRelation() == TargetRelation.VALUE_EQUIVALENT) {
            return sameRepresentation(benchmarkCase.source(), target);
        }
        try {
            return ExpressionFormatter.format(parser.parseTerm(benchmarkCase.source()))
                .equals(ExpressionFormatter.format(parser.parseTerm(target)));
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    boolean sameRepresentation(String expression, String target) {
        try {
            return representations.classify(expression, target)
                    == RepresentationCorrespondenceClassifier.Correspondence.SAME_REPRESENTATION_CLASS;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    private boolean matches(Case benchmarkCase, SearchState state, String target) {
        if (state.depth() == 0) return false;
        if (benchmarkCase.targetRelation() == TargetRelation.VALUE_EQUIVALENT) {
            return sameRepresentation(state.expression(), target);
        }
        try {
            return ExpressionFormatter.format(parser.parseTerm(state.expression())).equals(target);
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }
}
