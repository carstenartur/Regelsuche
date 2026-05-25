package de.regelsuche.cli.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small option parser for the simple {@code --key value} / {@code --key=value} CLI shape. */
public final class CliOptions {
    private final Map<String, String> values;

    private CliOptions(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static CliOptions parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        int index = 0;
        while (index < args.length) {
            String current = args[index];
            if (current.startsWith("--")) {
                String body = current.substring(2);
                String key;
                String value;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    key = body.substring(0, eq);
                    value = body.substring(eq + 1);
                    index++;
                } else if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                    key = body;
                    value = args[index + 1];
                    index += 2;
                } else {
                    key = body;
                    value = "true";
                    index++;
                }
                options.put(key, value);
            } else {
                index++;
            }
        }
        return new CliOptions(options);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public Map<String, String> asMap() {
        return values;
    }

    public List<String> csv(String key) {
        return splitCsv(values.get(key));
    }

    public static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }
}
