package de.regelsuche.moves.hypothesis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mathematical Parameter Intelligence: derives rewrite-move parameters from the
 * structure of an expression (and optional target) instead of blindly
 * enumerating them.
 *
 * <p>It runs every {@link ParameterHypothesisGenerator}, merges and deduplicates
 * their {@link ParameterHypothesis hypotheses}, sorts them deterministically and
 * exposes "Search Space Intelligence": for every parameter it records the
 * {@link HypothesisSource mathematical source} it was derived from.</p>
 */
public final class MathematicalParameterIntelligence {

    private final List<ParameterHypothesisGenerator> generators;

    /** Creates the intelligence with the full, deterministically ordered generator set. */
    public MathematicalParameterIntelligence() {
        this(List.of(
                new CancellationHypothesisGenerator(),
                new CommonFactorHypothesisGenerator(),
                new RepeatedSubtreeHypothesisGenerator(),
                new CompleteSquareHypothesisGenerator(),
                new TargetDifferenceHypothesisGenerator(),
                new EquationIsolationHypothesisGenerator()));
    }

    public MathematicalParameterIntelligence(List<ParameterHypothesisGenerator> generators) {
        this.generators = List.copyOf(generators);
    }

    /** Analyses an input expression with no target, using default bounds. */
    public HypothesisReport analyse(String inputExpression) {
        return analyse(ParameterContext.of(inputExpression));
    }

    /** Analyses an input/target pair, using default bounds. */
    public HypothesisReport analyse(String inputExpression, String targetExpression) {
        return analyse(ParameterContext.of(inputExpression, targetExpression));
    }

    /** Analyses a fully specified context. */
    public HypothesisReport analyse(ParameterContext context) {
        Map<String, ParameterHypothesis> distinct = new LinkedHashMap<>();
        TreeMap<String, Integer> generatorHistogram = new TreeMap<>();
        for (ParameterHypothesisGenerator generator : generators) {
            List<ParameterHypothesis> proposed = generator.propose(context);
            for (ParameterHypothesis hypothesis : proposed) {
                if (distinct.putIfAbsent(hypothesis.dedupeKey(), hypothesis) == null) {
                    generatorHistogram.merge(generator.id(), 1, Integer::sum);
                }
            }
        }
        List<ParameterHypothesis> hypotheses = new ArrayList<>(distinct.values());
        hypotheses.sort(ParameterHypothesis.CANONICAL_ORDER);

        TreeMap<String, Integer> sourceHistogram = new TreeMap<>();
        for (ParameterHypothesis hypothesis : hypotheses) {
            sourceHistogram.merge(hypothesis.source().name(), 1, Integer::sum);
        }
        return new HypothesisReport(
                List.copyOf(hypotheses),
                new LinkedHashMap<>(sourceHistogram),
                new LinkedHashMap<>(generatorHistogram));
    }

    /**
     * The deterministic result of one analysis run: the sorted hypotheses plus
     * the Search Space Intelligence histograms.
     *
     * @param hypotheses         deterministically sorted, deduplicated hypotheses
     * @param sourceHistogram    count of hypotheses per {@link HypothesisSource}
     * @param generatorHistogram count of hypotheses contributed per generator id
     */
    public record HypothesisReport(
            List<ParameterHypothesis> hypotheses,
            Map<String, Integer> sourceHistogram,
            Map<String, Integer> generatorHistogram) {

        public HypothesisReport {
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
            sourceHistogram = sourceHistogram == null ? Map.of() : Map.copyOf(sourceHistogram);
            generatorHistogram = generatorHistogram == null ? Map.of() : Map.copyOf(generatorHistogram);
        }

        /** @return hypotheses derived from the given source, in canonical order. */
        public List<ParameterHypothesis> bySource(HypothesisSource source) {
            List<ParameterHypothesis> filtered = new ArrayList<>();
            for (ParameterHypothesis hypothesis : hypotheses) {
                if (hypothesis.source() == source) {
                    filtered.add(hypothesis);
                }
            }
            return List.copyOf(filtered);
        }

        /** @return the distinct sources that contributed at least one hypothesis. */
        public Set<String> sources() {
            return sourceHistogram.keySet();
        }
    }
}
