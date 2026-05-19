package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FreshBindingGenerator {
    private static final int[][] VALUE_SETS = {
        {2, 3, 4, 5, 6},
        {-3, 5, -4, 6, 7},
        {5, -3, 7, -5, 8},
        {4, -3, 5, -6, 7},
        {-2, 5, 6, -4, 8},
        {6, 7, -3, 4, -5}
    };

    public List<Map<String, Integer>> generate(Set<String> placeholders) {
        List<String> orderedPlaceholders = new ArrayList<>(placeholders);
        if (orderedPlaceholders.isEmpty()) {
            return List.of(Map.of());
        }
        List<Map<String, Integer>> result = new ArrayList<>();
        for (int[] values : VALUE_SETS) {
            Map<String, Integer> binding = new LinkedHashMap<>();
            for (int index = 0; index < orderedPlaceholders.size(); index++) {
                binding.put(orderedPlaceholders.get(index), values[index % values.length]);
            }
            result.add(binding);
        }
        return result;
    }
}
