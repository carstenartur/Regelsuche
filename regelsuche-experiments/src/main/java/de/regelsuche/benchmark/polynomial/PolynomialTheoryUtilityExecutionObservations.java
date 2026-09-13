package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCanonicalWorkProjection.RawWork;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Complete ordered execution evidence for result v3. Raw work is partitioned
 * into preparation and occurrence increments; rounding is applied only to
 * their cumulative sum by the unchanged work-projection v2.
 */
public record PolynomialTheoryUtilityExecutionObservations(
        String observationId, RawWork rawWork, PolynomialWorkLedger preparationWork,
        List<Occurrence> occurrences) {
    public static final String SCHEMA = "regelsuche.polynomial-theory-utility-execution-observations/v1";

    public PolynomialTheoryUtilityExecutionObservations {
        Objects.requireNonNull(rawWork, "rawWork");
        Objects.requireNonNull(preparationWork, "preparationWork");
        occurrences = List.copyOf(occurrences);
        if (occurrences.isEmpty() || !rawWork.equals(partition(preparationWork, occurrences))) {
            throw new IllegalArgumentException("occurrences do not exactly partition the complete raw work");
        }
        if (!identity(rawWork, preparationWork, occurrences).equals(observationId)) {
            throw new IllegalArgumentException("execution observation identity differs from its evidence");
        }
    }

    public static PolynomialTheoryUtilityExecutionObservations create(
            PolynomialWorkLedger preparationWork, List<Occurrence> occurrences) {
        var retained = List.copyOf(occurrences);
        var raw = partition(preparationWork, retained);
        return new PolynomialTheoryUtilityExecutionObservations(
            identity(raw, preparationWork, retained), raw, preparationWork, retained);
    }

    public TerminalStatus terminalStatus() {
        var statuses = occurrences.stream().map(Occurrence::terminalStatus).distinct().toList();
        return statuses.size() == 1 ? statuses.getFirst() : TerminalStatus.MIXED_OUTCOMES;
    }

    void validateAgainst(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation, TerminalStatus terminal,
            PolynomialTheoryUtilityWorkBreakdown work,
            List<PolynomialTheoryUtilityTransitionOutcome> transitions) {
        var paths = paths(formation);
        if (occurrences.size() != paths.size() || terminal != terminalStatus()
                || !PolynomialTheoryUtilityCanonicalWorkProjection.project(input, rawWork).work().equals(work)) {
            throw new IllegalArgumentException("execution observations differ from the frozen row or its work");
        }
        var cumulative = preparationWork;
        long primitive = 0;
        int nextTransition = 0;
        for (int index = 0; index < occurrences.size(); index++) {
            var occurrence = occurrences.get(index);
            if (occurrence.occurrenceIndex() != index || !paths.get(index).equals(occurrence.path())) {
                throw new IllegalArgumentException("execution observations omit or reorder a frozen occurrence");
            }
            var before = PolynomialTheoryUtilityCanonicalWorkProjection.project(input,
                PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitive, cumulative)).work();
            cumulative = plus(cumulative, occurrence.rawWork());
            primitive = Math.addExact(primitive, occurrence.primitiveWork());
            var after = PolynomialTheoryUtilityCanonicalWorkProjection.project(input,
                PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitive, cumulative)).work();
            if (occurrence.terminalStatus() == TerminalStatus.VALIDATED_TRANSITION) {
                if (nextTransition >= transitions.size()) {
                    throw new IllegalArgumentException("successful occurrence lacks its retained transition");
                }
                var transition = transitions.get(nextTransition++);
                if (!occurrence.transitionId().equals(transition.transitionId())
                        || !occurrence.path().equals(transition.occurrencePath())
                        || !occurrence.pipelineEvidenceHash().equals(transition.transitionEvidenceHash())
                        || !difference(after, before).equals(transition.work())) {
                    throw new IllegalArgumentException("occurrence transition or work differs from its consumed prefix");
                }
            }
        }
        if (nextTransition != transitions.size()) {
            throw new IllegalArgumentException("execution observations omit a retained transition");
        }
    }

    public static List<List<Integer>> paths(PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        return switch (formation.occurrenceLayout()) {
            case "ROOT" -> List.of(List.of());
            case "NESTED_RIGHT" -> List.of(List.of(1));
            case "TWO_IDENTICAL_SIBLINGS" -> List.of(List.of(0), List.of(1));
            case "FOUR_IDENTICAL_LEAVES" -> List.of(List.of(0, 0), List.of(0, 1), List.of(1, 0), List.of(1, 1));
            default -> throw new IllegalArgumentException("unknown frozen occurrence layout");
        };
    }

    private static RawWork partition(PolynomialWorkLedger preparation, List<Occurrence> occurrences) {
        var total = Objects.requireNonNull(preparation, "preparationWork");
        long primitive = 0;
        for (var occurrence : occurrences) {
            total = plus(total, occurrence.rawWork());
            primitive = Math.addExact(primitive, occurrence.primitiveWork());
        }
        return PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitive, total);
    }

    static PolynomialWorkLedger plus(PolynomialWorkLedger first, PolynomialWorkLedger second) {
        var stages = new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        return new PolynomialWorkLedger(stages);
    }

    static PolynomialWorkLedger difference(PolynomialWorkLedger after, PolynomialWorkLedger before) {
        var stages = new LinkedHashMap<>(after.stages());
        before.stages().forEach((stage, units) -> stages.merge(stage, -units, Math::addExact));
        return new PolynomialWorkLedger(stages);
    }

    static PolynomialTheoryUtilityWorkBreakdown difference(PolynomialTheoryUtilityWorkBreakdown a,
            PolynomialTheoryUtilityWorkBreakdown b) {
        return new PolynomialTheoryUtilityWorkBreakdown(a.primitiveWork() - b.primitiveWork(),
            a.matchingWork() - b.matchingWork(), a.sourceValidationWork() - b.sourceValidationWork(),
            a.factorizationWork() - b.factorizationWork(), a.verificationWork() - b.verificationWork(),
            a.renderingWork() - b.renderingWork(), a.reparseWork() - b.reparseWork(),
            a.reconstructionWork() - b.reconstructionWork(), a.occurrenceReplacementWork() - b.occurrenceReplacementWork(),
            a.cacheLookupWork() - b.cacheLookupWork(), a.cacheInsertionWork() - b.cacheInsertionWork(),
            a.cacheEvictionWork() - b.cacheEvictionWork(), a.cacheReplayWork() - b.cacheReplayWork(),
            a.evidenceConstructionWork() - b.evidenceConstructionWork());
    }

    private static String identity(RawWork raw, PolynomialWorkLedger preparation, List<Occurrence> occurrences) {
        var material = new StringBuilder();
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityCanonicalWorkProjection.REVISION);
        append(material, Long.toString(raw.primitiveWork()));
        append(material, raw.totalMechanicalWork().canonicalMaterial());
        append(material, preparation.canonicalMaterial());
        append(material, Integer.toString(occurrences.size()));
        occurrences.forEach(value -> append(material, value.identityMaterial()));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder material, String value) {
        material.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static String hash(String value, String name) {
        if (!Objects.requireNonNull(value, name).matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return value;
    }

    /** One terminal occurrence, including attempts which never accepted a transition. */
    public record Occurrence(int occurrenceIndex, List<Integer> path, TerminalStatus terminalStatus,
            String detailCode, String pipelineEvidenceHash, String transitionId, long primitiveWork,
            PolynomialWorkLedger rawWork, List<String> factorizationAttemptIds, List<String> cacheEventIds) {
        public Occurrence {
            if (occurrenceIndex < 0 || primitiveWork < 0) throw new IllegalArgumentException("negative occurrence work or index");
            path = List.copyOf(path);
            if (path.stream().anyMatch(value -> value < 0)) throw new IllegalArgumentException("negative occurrence path");
            Objects.requireNonNull(terminalStatus, "terminalStatus");
            if (terminalStatus == TerminalStatus.MIXED_OUTCOMES) {
                throw new IllegalArgumentException("one occurrence cannot conceal mixed terminal outcomes");
            }
            if (Objects.requireNonNull(detailCode, "detailCode").isBlank()) {
                throw new IllegalArgumentException("occurrence detail must not be blank");
            }
            if (!"NONE".equals(pipelineEvidenceHash)) hash(pipelineEvidenceHash, "pipelineEvidenceHash");
            if (!"NONE".equals(transitionId)) hash(transitionId, "transitionId");
            if ((terminalStatus == TerminalStatus.VALIDATED_TRANSITION) == "NONE".equals(transitionId)) {
                throw new IllegalArgumentException("occurrence terminal status and accepted transition disagree");
            }
            if (terminalStatus == TerminalStatus.VALIDATED_TRANSITION && "NONE".equals(pipelineEvidenceHash)) {
                throw new IllegalArgumentException("successful occurrence lacks its pipeline evidence");
            }
            Objects.requireNonNull(rawWork, "rawWork");
            factorizationAttemptIds = List.copyOf(factorizationAttemptIds);
            cacheEventIds = List.copyOf(cacheEventIds);
            factorizationAttemptIds.forEach(value -> hash(value, "factorizationAttemptId"));
            cacheEventIds.forEach(value -> hash(value, "cacheEventId"));
        }

        String identityMaterial() {
            var material = new StringBuilder();
            append(material, Integer.toString(occurrenceIndex));
            append(material, path.toString());
            append(material, terminalStatus.name());
            append(material, detailCode);
            append(material, pipelineEvidenceHash);
            append(material, transitionId);
            append(material, Long.toString(primitiveWork));
            append(material, rawWork.canonicalMaterial());
            append(material, Integer.toString(factorizationAttemptIds.size()));
            factorizationAttemptIds.forEach(value -> append(material, value));
            append(material, Integer.toString(cacheEventIds.size()));
            cacheEventIds.forEach(value -> append(material, value));
            return material.toString();
        }
    }
}
