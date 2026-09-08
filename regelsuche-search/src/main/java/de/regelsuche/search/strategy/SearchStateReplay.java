package de.regelsuche.search.strategy;

import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Portable common-state observation. Loading never reconstructs executable transformations. */
public final class SearchStateReplay {
    public static final String SCHEMA = "regelsuche.search-state-replay/v1";
    private SearchStateReplay() { }

    public static String toCanonicalJson(SearchState state) {
        Objects.requireNonNull(state, "state");
        var json = new JsonWriter().beginObject().property("schema", SCHEMA)
            .property("expression", state.expression()).property("depth", state.depth())
            .property("canonicalHash", state.canonicalHash())
            .stringArray("path", state.path()).stringArray("appliedRuleIds", state.appliedRuleIds())
            .stringArray("applicationKeys", state.appliedRuleApplications().stream().sorted().toList())
            .stringArray("assumptions", state.assumptions()).property("expandedStepCount", state.expandedStepCount())
            .property("parentExpression", state.parentExpression()).property("appliedRuleId", state.appliedRuleId())
            .property("appliedRuleKind", state.appliedRuleKind().name()).property("mayIncreaseComplexity", state.mayIncreaseComplexity())
            .property("estimatedCostDelta", state.estimatedCostDelta())
            .property("equivalencePreserving", state.equivalencePreservingByConstruction()).property("improvement", state.improvement())
            .stringArray("appliedRuleKinds", state.appliedRuleKinds().stream().map(Enum::name).toList())
            .array("equivalencePreservingFlags", array -> state.equivalencePreservingFlags().forEach(array::value))
            .object("score", score -> score.property("stringLength", state.score().stringLength())
                .property("astNodeCount", state.score().astNodeCount()).property("operatorCount", state.score().operatorCount())
                .property("nestingDepth", state.score().nestingDepth()).property("recognizedPatternBonus", state.score().recognizedPatternBonus()))
            .property("executionRetained", state.transformations() != null);
        state.recordedExecution().ifPresent(execution -> json.property("execution", execution.toCanonicalJson()));
        return json.endObject().toString();
    }

    public static SearchState verifyArtifact(Path file, SearchReplayArtifact.Reference reference,
            Supplier<SearchState> independentlyReplayedState) throws IOException {
        String expected = SearchReplayArtifact.load(file, reference);
        SearchState actual = Objects.requireNonNull(independentlyReplayedState, "replay source").get();
        if (actual.transformations() == null) throw new IllegalArgumentException("observational-only state cannot authorize replay");
        if (!expected.equals(toCanonicalJson(actual))) throw new IllegalArgumentException("search state replay differs from artifact");
        return actual;
    }
}
