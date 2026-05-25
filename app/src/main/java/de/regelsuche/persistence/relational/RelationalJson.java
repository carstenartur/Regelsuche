package de.regelsuche.persistence.relational;

import de.regelsuche.inventory.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class RelationalJson {
    private RelationalJson() {
    }

    static String array(List<String> values) {
        List<String> safe = values == null ? List.of() : values;
        return safe.stream().map(RelationalJson::quote).collect(Collectors.joining(",", "[", "]"));
    }

    static List<String> arrayValues(String json) {
        return MiniJson.parseStringArray(json == null || json.isBlank() ? "[]" : json);
    }

    static String object(List<SearchFacet> facets) {
        List<SearchFacet> safe = facets == null ? List.of() : facets;
        return safe.stream()
            .collect(Collectors.toMap(SearchFacet::key, SearchFacet::value, (left, right) -> right, java.util.TreeMap::new))
            .entrySet().stream()
            .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    }

    static List<SearchFacet> facets(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) {
            return List.of();
        }
        String wrapped = "{\"facets\":[" + json + "]}";
        List<Map<String, String>> objects = MiniJson.parseObjectArray(wrapped, "facets");
        if (objects.isEmpty()) {
            return List.of();
        }
        return objects.getFirst().entrySet().stream()
            .map(entry -> new SearchFacet(entry.getKey(), entry.getValue()))
            .toList();
    }

    static String join(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    static String joinFacets(List<SearchFacet> facets) {
        return facets == null ? "" : facets.stream()
            .map(facet -> facet.key() + ":" + facet.value())
            .collect(Collectors.joining(" "));
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
