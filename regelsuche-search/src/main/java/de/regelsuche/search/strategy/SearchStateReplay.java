package de.regelsuche.search.strategy;

import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Portable common-state observation. Loading never reconstructs executable transformations. */
public final class SearchStateReplay {
    public static final String SCHEMA = "regelsuche.search-state-replay/v2";
    private SearchStateReplay() { }

    public static String toCanonicalJson(SearchState state) {
        Objects.requireNonNull(state, "state");
        var json = new JsonWriter().beginObject().property("schema", SCHEMA)
            .property("scoringRevision", state.score() == null ? de.regelsuche.scoring.ScoreRevision.UNSPECIFIED : state.score().scoringRevision())
            .property("expression", state.expression()).property("depth", state.depth())
            .property("canonicalHash", state.canonicalHash())
            .stringArray("path", state.path()).stringArray("appliedRuleIds", state.appliedRuleIds())
            .stringArray("applicationKeys", state.appliedRuleApplications().stream().sorted().toList())
            .stringArray("assumptions", state.assumptions()).property("expandedStepCount", state.expandedStepCount())
            .property("parentExpression", state.parentExpression()).property("appliedRuleId", state.appliedRuleId());
        if (state.appliedRuleKind() == null) json.nullProperty("appliedRuleKind");
        else json.property("appliedRuleKind", state.appliedRuleKind().name());
        json.property("mayIncreaseComplexity", state.mayIncreaseComplexity())
            .property("estimatedCostDelta", state.estimatedCostDelta())
            .property("equivalencePreserving", state.equivalencePreservingByConstruction()).property("improvement", state.improvement())
            .stringArray("appliedRuleKinds", state.appliedRuleKinds().stream().map(Enum::name).toList())
            .array("equivalencePreservingFlags", array -> state.equivalencePreservingFlags().forEach(array::value));
        if (state.score() == null) json.nullProperty("score");
        else json.object("score", score -> score.property("stringLength", state.score().stringLength())
                .property("astNodeCount", state.score().astNodeCount()).property("operatorCount", state.score().operatorCount())
                .property("nestingDepth", state.score().nestingDepth()).property("recognizedPatternBonus", state.score().recognizedPatternBonus()));
        json.property("executionRetained", state.transformations() != null);
        state.recordedExecution().ifPresent(execution -> json.property("execution", execution.toCanonicalJson()));
        return json.endObject().toString();
    }

    public static SearchState verifyArtifact(Path file, SearchReplayArtifact.Reference reference,
            Supplier<SearchState> independentlyReplayedState) throws IOException {
        return verifyArtifact(file, reference, de.regelsuche.scoring.ScoreRevision.CURRENT, independentlyReplayedState);
    }

    /** Explicitly identified custom scoring is opt-in; unversioned numbers never imply the built-in metric. */
    public static SearchState verifyArtifact(Path file, SearchReplayArtifact.Reference reference,
            String expectedScoringRevision, Supplier<SearchState> independentlyReplayedState) throws IOException {
        String expected = SearchReplayArtifact.load(file, reference);
        de.regelsuche.scoring.ScoreRevision.requireReplay(expected, SCHEMA, expectedScoringRevision);
        SearchState actual = Objects.requireNonNull(independentlyReplayedState, "replay source").get();
        if (actual.transformations() == null) throw new IllegalArgumentException("observational-only state cannot authorize replay");
        if (!expected.equals(toCanonicalJson(actual))) throw new IllegalArgumentException("search state replay differs from artifact");
        return actual;
    }
}
