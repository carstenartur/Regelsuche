package de.regelsuche.explanation;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured explanation for a single mathematical transformation discovery.
 *
 * <p>Captures the before/after expressions at a specific tree position, the
 * rule / operator path, and evidence-based reasons without any rendering
 * concern. Use {@link #toExplanation()} to convert this into a generic
 * {@link Explanation} that can be passed to any {@link ExplanationRenderer}.
 *
 * <p>Example:
 * <pre>
 *   TransformationExplanation
 *   - position: 000
 *   - before: x^2 + 6*x + 5
 *   - after: (x + 3)^2 - 4
 *   - rulePath: [COMPLETE_SQUARE]
 *   - interestReasons: [promotion-eligible: oracle and ablation confirmed]
 *   - pathReasons: [oracle agrees, evidence present]
 * </pre>
 */
public record TransformationExplanation(
    String candidateId,
    String position,
    String before,
    String after,
    List<String> rulePath,
    List<String> interestReasons,
    List<String> pathReasons,
    String oracleStatus,
    String ablationStatus,
    boolean evidenceExists,
    boolean measuredImprovement,
    List<String> reusedMacroIds
) {
    public TransformationExplanation {
        candidateId = candidateId == null ? "" : candidateId;
        position = position == null ? "" : position;
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        interestReasons = interestReasons == null ? List.of() : List.copyOf(interestReasons);
        pathReasons = pathReasons == null ? List.of() : List.copyOf(pathReasons);
        oracleStatus = oracleStatus == null ? "" : oracleStatus;
        ablationStatus = ablationStatus == null ? "" : ablationStatus;
        reusedMacroIds = reusedMacroIds == null ? List.of() : List.copyOf(reusedMacroIds);
    }

    /**
     * Converts this domain-specific explanation into the generic
     * {@link Explanation} structure so that any {@link ExplanationRenderer}
     * can render it without knowing about discovery internals.
     */
    public Explanation toExplanation() {
        List<ExplanationSection> sections = new ArrayList<>();

        // Transformation section
        List<ExplanationFact> transformFacts = new ArrayList<>();
        transformFacts.add(new ExplanationFact("position", position.isBlank() ? "root" : position));
        transformFacts.add(new ExplanationFact("before", before));
        transformFacts.add(new ExplanationFact("after", after));
        if (!rulePath.isEmpty()) {
            transformFacts.add(new ExplanationFact("rulePath", String.join(" -> ", rulePath)));
        }
        sections.add(new ExplanationSection("Transformation", transformFacts, List.of(), List.of()));

        // Interest / path reasons section
        List<ExplanationFact> reasonFacts = new ArrayList<>();
        if (!interestReasons.isEmpty()) {
            reasonFacts.add(new ExplanationFact("interestReason", String.join("; ", interestReasons)));
        }
        if (!pathReasons.isEmpty()) {
            reasonFacts.add(new ExplanationFact("pathReason", String.join("; ", pathReasons)));
        }
        if (!reasonFacts.isEmpty()) {
            sections.add(new ExplanationSection("Reasons", reasonFacts, List.of(), List.of()));
        }

        // Evidence section
        List<ExplanationFact> evidenceFacts = new ArrayList<>();
        evidenceFacts.add(new ExplanationFact("oracle", oracleStatus));
        evidenceFacts.add(new ExplanationFact("ablation", ablationStatus));
        evidenceFacts.add(new ExplanationFact("evidencePresent", String.valueOf(evidenceExists)));
        List<ExplanationMetric> evidenceMetrics = new ArrayList<>();
        if (measuredImprovement) {
            evidenceMetrics.add(new ExplanationMetric("measuredImprovement", 1));
        }
        if (!reusedMacroIds.isEmpty()) {
            evidenceMetrics.add(new ExplanationMetric("reusedMacros", reusedMacroIds.size()));
        }
        sections.add(new ExplanationSection("Evidence", evidenceFacts, evidenceMetrics, List.of()));

        return new Explanation(candidateId, sections);
    }
}
