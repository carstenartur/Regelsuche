package de.regelsuche.api.searchgraph;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups nodes that appear inside the same recurring macro rule-id sequence.
 *
 * <p>For every contiguous rule-id window of length {@code >= minLength} that
 * occurs in at least {@code minOccurrences} different transformations, all
 * intermediate expressions of those occurrences are joined into a single
 * {@link ClusterType#MACRO_SEQUENCE} cluster.</p>
 *
 * <p>Cohesion is the empirical share of the cluster's transformations that
 * support the macro window (1.0 when every supporting transformation uses the
 * macro).</p>
 */
public final class MacroSequenceClusterer {

    private final int minLength;
    private final int minOccurrences;

    public MacroSequenceClusterer() {
        this(2, 2);
    }

    public MacroSequenceClusterer(int minLength, int minOccurrences) {
        this.minLength = Math.max(2, minLength);
        this.minOccurrences = Math.max(2, minOccurrences);
    }

    public List<SearchGraphClusterDto> cluster(List<DiscoveredTransformation> transformations) {
        if (transformations == null || transformations.isEmpty()) {
            return List.of();
        }

        // ruleSeq -> [ (transformationIndex, startStep) ]
        Map<List<String>, List<int[]>> windows = new LinkedHashMap<>();
        for (int t = 0; t < transformations.size(); t++) {
            List<TransformationStep> steps = transformations.get(t).steps();
            List<String> ruleIds = new ArrayList<>(steps.size());
            for (TransformationStep step : steps) {
                ruleIds.add(step.ruleId());
            }
            for (int len = minLength; len <= ruleIds.size(); len++) {
                for (int start = 0; start + len <= ruleIds.size(); start++) {
                    List<String> window = List.copyOf(ruleIds.subList(start, start + len));
                    windows.computeIfAbsent(window, k -> new ArrayList<>()).add(new int[] {t, start, len});
                }
            }
        }

        List<SearchGraphClusterDto> clusters = new ArrayList<>();
        int counter = 0;
        Set<List<String>> claimedBy = new LinkedHashSet<>();
        List<Map.Entry<List<String>, List<int[]>>> orderedEntries = new ArrayList<>(windows.entrySet());
        // longest-first: ensures sub-windows can be detected and suppressed
        orderedEntries.sort((a, b) -> {
            int cmp = Integer.compare(b.getKey().size(), a.getKey().size());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(b.getValue().size(), a.getValue().size());
        });
        for (Map.Entry<List<String>, List<int[]>> entry : orderedEntries) {
            // distinct transformations supporting the window
            Set<Integer> distinct = new LinkedHashSet<>();
            for (int[] occ : entry.getValue()) {
                distinct.add(occ[0]);
            }
            if (distinct.size() < minOccurrences) {
                continue;
            }
            // suppress sub-windows of an already-claimed longer window
            // (longest-first preference: skip windows whose super-sequence we already emitted)
            boolean subsumed = false;
            for (List<String> longer : claimedBy) {
                if (longer.size() > entry.getKey().size() && contains(longer, entry.getKey())) {
                    subsumed = true;
                    break;
                }
            }
            if (subsumed) {
                continue;
            }
            claimedBy.add(entry.getKey());

            List<String> nodeIds = new ArrayList<>();
            List<String> supportingPathIds = new ArrayList<>();
            Set<String> nodeSeen = new LinkedHashSet<>();
            for (int[] occ : entry.getValue()) {
                DiscoveredTransformation transformation = transformations.get(occ[0]);
                if (!supportingPathIds.contains(transformation.id())) {
                    supportingPathIds.add(transformation.id());
                }
                List<TransformationStep> steps = transformation.steps();
                int start = occ[1];
                int len = occ[2];
                for (int i = start; i < start + len; i++) {
                    TransformationStep step = steps.get(i);
                    if (nodeSeen.add(step.beforeExpression())) {
                        nodeIds.add(step.beforeExpression());
                    }
                    if (nodeSeen.add(step.afterExpression())) {
                        nodeIds.add(step.afterExpression());
                    }
                }
            }
            double cohesion = supportingPathIds.isEmpty() ? 0.0
                : (double) supportingPathIds.size() / transformations.size();
            String label = "macro:" + String.join("→", entry.getKey());
            clusters.add(new SearchGraphClusterDto(
                "macro-seq-" + (counter++),
                label,
                ClusterType.MACRO_SEQUENCE,
                nodeIds,
                supportingPathIds,
                Math.min(1.0, cohesion)
            ));
        }
        return clusters;
    }

    private static boolean contains(List<String> haystack, List<String> needle) {
        if (needle.size() > haystack.size()) {
            return false;
        }
        outer:
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!haystack.get(i + j).equals(needle.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
