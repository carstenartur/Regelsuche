package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Frozen assumption-aware TRAIN cases for executable rewrite-program fitness. */
public record EvolutionRewriteProgramTrainSuite(
    String schema,
    String suiteId,
    EvaluatorProfile evaluatorProfile,
    List<TrainCase> cases,
    SearchHeuristic heuristic,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-train-suite/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramTrainSuite {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program TRAIN suite schema");
        }
        requireId(suiteId, "suiteId");
        Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
        cases = canonicalCases(cases);
        Objects.requireNonNull(heuristic, "heuristic");
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            suiteId, evaluatorProfile, cases, heuristic, null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program TRAIN suite contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramTrainSuite create(
        String suiteId,
        EvaluatorProfile evaluatorProfile,
        List<TrainCase> cases,
        SearchHeuristic heuristic
    ) {
        requireId(suiteId, "suiteId");
        Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
        List<TrainCase> canonicalCases = canonicalCases(cases);
        Objects.requireNonNull(heuristic, "heuristic");
        String hash = EvolutionGenome.hash(render(
            suiteId, evaluatorProfile, canonicalCases, heuristic, null));
        return new EvolutionRewriteProgramTrainSuite(
            SCHEMA,
            suiteId,
            evaluatorProfile,
            canonicalCases,
            heuristic,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            suiteId, evaluatorProfile, cases, heuristic, contentHash);
    }

    private static List<TrainCase> canonicalCases(List<TrainCase> values) {
        Objects.requireNonNull(values, "cases");
        List<TrainCase> result = values.stream()
            .map(item -> Objects.requireNonNull(item, "TRAIN case"))
            .sorted(Comparator.comparing(TrainCase::caseId))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program TRAIN suite must not be empty");
        }
        if (new HashSet<>(result.stream().map(TrainCase::caseId).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "rewrite-program TRAIN case IDs must be unique");
        }
        return result;
    }

    private static String render(
        String suiteId,
        EvaluatorProfile evaluatorProfile,
        List<TrainCase> cases,
        SearchHeuristic heuristic,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("suiteId", suiteId)
            .property("evaluationSplit", "TRAIN")
            .property("evaluatorProfile", evaluatorProfile.name())
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyId", item.familyId())
                    .property("inputExpression", item.inputExpression())
                    .property("targetExpression", item.targetExpression())
                    .stringArray("assumptions", item.assumptions()))))
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

    public enum EvaluatorProfile {
        EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS
    }

    public record TrainCase(
        String caseId,
        String familyId,
        String inputExpression,
        String targetExpression,
        List<String> assumptions
    ) {
        public TrainCase {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            inputExpression = normalizeExpression(inputExpression);
            targetExpression = normalizeExpression(targetExpression);
            assumptions = AssumptionSignature.ofExpressions(assumptions)
                .normalizedAssumptions();
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
