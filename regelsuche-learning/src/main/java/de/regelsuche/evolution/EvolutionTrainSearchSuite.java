package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Frozen TRAIN cases and search policy used by evolutionary fitness. */
public record EvolutionTrainSearchSuite(
    String schema,
    String suiteId,
    List<TrainCase> cases,
    SearchHeuristic heuristic,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-train-search-suite/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionTrainSearchSuite {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported TRAIN suite schema");
        }
        requireId(suiteId, "suiteId");
        Objects.requireNonNull(cases, "cases");
        cases = cases.stream()
            .map(item -> Objects.requireNonNull(item, "TRAIN case"))
            .sorted(Comparator.comparing(TrainCase::caseId))
            .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("TRAIN suite must not be empty");
        }
        if (new HashSet<>(cases.stream().map(TrainCase::caseId).toList()).size()
                != cases.size()) {
            throw new IllegalArgumentException("TRAIN case IDs must be unique");
        }
        Objects.requireNonNull(heuristic, "heuristic");
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            suiteId, cases, heuristic, null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("TRAIN suite contentHash mismatch");
        }
    }

    public static EvolutionTrainSearchSuite create(
        String suiteId,
        List<TrainCase> cases,
        SearchHeuristic heuristic
    ) {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(heuristic, "heuristic");
        List<TrainCase> canonicalCases = cases.stream()
            .map(item -> Objects.requireNonNull(item, "TRAIN case"))
            .sorted(Comparator.comparing(TrainCase::caseId))
            .toList();
        String hash = EvolutionGenome.hash(render(
            suiteId, canonicalCases, heuristic, null));
        return new EvolutionTrainSearchSuite(
            SCHEMA, suiteId, canonicalCases, heuristic, hash);
    }

    public String toCanonicalJson() {
        return render(suiteId, cases, heuristic, contentHash);
    }

    private static String render(
        String suiteId,
        List<TrainCase> cases,
        SearchHeuristic heuristic,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("suiteId", suiteId)
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyId", item.familyId())
                    .property("inputExpression", item.inputExpression())
                    .property("targetExpression", item.targetExpression()))))
            .object("heuristic", object -> object
                .property("maxDepth", heuristic.maxDepth())
                .property("maxVisitedExpressions",
                    heuristic.maxVisitedExpressions())
                .property("significantImprovementThreshold",
                    heuristic.significantImprovementThreshold())
                .property("maxExpandingSteps", heuristic.maxExpandingSteps())
                .property("maxCandidatesPerState",
                    heuristic.maxCandidatesPerState())
                .property("beamWidth", heuristic.beamWidth()));
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public record TrainCase(
        String caseId,
        String familyId,
        String inputExpression,
        String targetExpression
    ) {
        public TrainCase {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            inputExpression = normalizeExpression(inputExpression);
            targetExpression = normalizeExpression(targetExpression);
        }
    }

    private static String normalizeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        return ExpressionFormatter.format(
            new ExpressionParser().parseTerm(expression));
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }
}
