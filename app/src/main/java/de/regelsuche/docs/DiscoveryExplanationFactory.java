package de.regelsuche.docs;

import de.regelsuche.explanation.TransformationExplanation;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds structured {@link TransformationExplanation} objects from
 * {@link PromotionRecord} data.
 *
 * <p>This factory encapsulates the domain logic that determines <em>why</em> a
 * discovery candidate is interesting and <em>why</em> its rule path works. The
 * result is a pure data object with no rendering concern; callers can pass it
 * to any {@link de.regelsuche.explanation.ExplanationRenderer} to obtain a
 * concrete text representation.
 */
public final class DiscoveryExplanationFactory {

    /**
     * Builds a {@link TransformationExplanation} for a single discovery
     * candidate record.
     *
     * @param record the promotion record to explain
     * @return structured explanation capturing position, before/after, rules,
     *         interest reasons, path reasons, and evidence
     */
    public TransformationExplanation buildTransformationExplanation(PromotionRecord record) {
        List<String> interestReasons = buildInterestReasons(record);
        List<String> pathReasons = buildPathReasons(record);

        String pathKey = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.pathKey="))
            .map(a -> a.substring("treePosition.pathKey=".length()))
            .findFirst()
            .orElse("");
        String positionBefore = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.before="))
            .map(a -> a.substring("treePosition.before=".length()))
            .findFirst()
            .orElse("");
        String positionAfter = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.after="))
            .map(a -> a.substring("treePosition.after=".length()))
            .findFirst()
            .orElse("");

        String before = positionBefore.isBlank() ? record.originalExpression() : positionBefore;
        String after = positionAfter.isBlank() ? record.discoveredStructure() : positionAfter;

        return new TransformationExplanation(
            record.candidateId(),
            pathKey.isBlank() ? "root" : pathKey,
            before,
            after,
            record.rulePath(),
            interestReasons,
            pathReasons,
            record.oracleStatus(),
            record.ablationStatus(),
            record.evidenceExists(),
            record.measuredImprovement(),
            record.reusedMacroIds()
        );
    }

    /**
     * Computes the list of reasons why a gallery candidate is interesting.
     * Each element is a self-contained plain-text statement; joining them is
     * the responsibility of the renderer.
     */
    List<String> buildInterestReasons(PromotionRecord record) {
        List<String> reasons = new ArrayList<>();
        if (record.stage().atLeast(PromotionStage.REUSED)) {
            reasons.add("macro reused");
        } else if (record.stage().atLeast(PromotionStage.PROMOTED)) {
            reasons.add("promotion-eligible: oracle and ablation confirmed");
        }
        if (record.measuredImprovement()) {
            reasons.add("expression score improved");
        }
        if (!record.reusedMacroIds().isEmpty()) {
            reasons.add("reused macros: " + String.join(", ", record.reusedMacroIds()));
        }
        if (!record.oracleEvidence().isBlank()) {
            reasons.add("oracle evidence: " + record.oracleEvidence());
        }
        return List.copyOf(reasons);
    }

    /**
     * Computes the list of reasons why the rule path for a candidate is
     * considered valid. Each element is a self-contained plain-text statement.
     */
    List<String> buildPathReasons(PromotionRecord record) {
        List<String> reasons = new ArrayList<>();
        String oracleStatus = record.oracleStatus();
        if ("AGREE".equalsIgnoreCase(oracleStatus)) {
            reasons.add("oracle agrees");
        } else if (!"UNAVAILABLE".equalsIgnoreCase(oracleStatus)) {
            reasons.add("oracle=" + oracleStatus);
        }
        if (record.evidenceExists()) {
            reasons.add("evidence present");
        }
        if (!record.ablationStatus().isBlank() && !"N/A".equals(record.ablationStatus())) {
            reasons.add("ablation=" + record.ablationStatus());
        }
        return List.copyOf(reasons);
    }
}
