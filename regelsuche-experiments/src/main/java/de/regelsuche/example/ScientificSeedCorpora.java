package de.regelsuche.example;

import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Curated + file-backed scientific seed corpus helpers. */
public final class ScientificSeedCorpora {
    private ScientificSeedCorpora() {
    }

    public static List<SeedExpression> curated() {
        return normalize(List.of(
            new SeedExpression("identity-binomial-1", "(x + a)^2", "known-identity", "binomial", List.of("scientific"), List.of()),
            new SeedExpression("identity-geometric-series-1", "1 + x + x^2 + x^3", "known-identity", "geometric-series", List.of("scientific"), List.of()),
            new SeedExpression("identity-factorization-1", "x^2 - a^2", "known-identity", "factorization", List.of("scientific"), List.of()),
            new SeedExpression("dlmf-trigonometric-1", "sin(x)^2 + cos(x)^2", "DLMF", "trigonometric", List.of("DLMF", "scientific"), List.of()),
            new SeedExpression("matrix-identity-1", "A * (B + C)", "known-identity", "matrix", List.of("matrix", "scientific"), List.of()),
            new SeedExpression("rational-simplification-1", "(a * b) / b", "known-identity", "rational", List.of("scientific"), List.of("b != 0")),
            new SeedExpression("oeis-formula-A000217", "n * (n + 1) / 2", "OEIS", "oeis-formula", List.of("OEIS", "scientific"), List.of("n >= 0")),
            new SeedExpression("counterexample-trap-1", "(a + b) / b", "counterexample-trap", "counterexample", List.of("trap", "scientific"), List.of("b != 0"))
        ));
    }

    public static List<SeedExpression> fromCatalogs(List<Path> catalogPaths) {
        List<SeedExpression> merged = new ArrayList<>();
        for (Path path : catalogPaths == null ? List.<Path>of() : catalogPaths) {
            merged.addAll(loadCatalog(path));
        }
        return normalize(merged);
    }

    static List<SeedExpression> loadCatalog(Path path) {
        if (path == null) {
            return List.of();
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String content = Files.readString(path);
            if (name.endsWith(".json")) {
                return parseJson(content);
            }
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                return parseYaml(content);
            }
            return List.of();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read seed catalog " + path, exception);
        }
    }

    private static List<SeedExpression> parseJson(String content) {
        List<Object> values = new JsonReader(content == null ? "[]" : content).readArray();
        List<SeedExpression> seeds = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) {
                continue;
            }
            String id = stringValue(object, "id");
            String expression = stringValue(object, "expression");
            if (expression.isBlank()) {
                continue;
            }
            String source = stringValue(object, "source");
            String category = stringValue(object, "category");
            List<String> tags = stringList(object.get("tags"));
            List<String> assumptions = stringList(object.get("assumptions"));
            seeds.add(new SeedExpression(id, expression, source, category, tags, assumptions));
        }
        return normalize(seeds);
    }

    private static List<SeedExpression> parseYaml(String content) {
        List<SeedExpression> seeds = new ArrayList<>();
        List<String> lines = (content == null ? "" : content).lines().toList();
        String id = "";
        String expression = "";
        String source = "local";
        String category = "general";
        List<String> tags = List.of();
        List<String> assumptions = List.of();
        boolean active = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (active && !expression.isBlank()) {
                    seeds.add(new SeedExpression(id, expression, source, category, tags, assumptions));
                }
                id = "";
                expression = "";
                source = "local";
                category = "general";
                tags = List.of();
                assumptions = List.of();
                active = true;
                line = line.substring(2).trim();
                if (!line.isEmpty()) {
                    int split = line.indexOf(':');
                    if (split > 0) {
                        String key = line.substring(0, split).trim();
                        String value = line.substring(split + 1).trim();
                        if ("id".equals(key)) id = stripYamlScalar(value);
                        if ("expression".equals(key)) expression = stripYamlScalar(value);
                        if ("source".equals(key)) source = stripYamlScalar(value);
                        if ("category".equals(key)) category = stripYamlScalar(value);
                        if ("tags".equals(key)) tags = parseInlineYamlArray(value);
                        if ("assumptions".equals(key)) assumptions = parseInlineYamlArray(value);
                    }
                }
                continue;
            }
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if ("id".equals(key)) id = stripYamlScalar(value);
            if ("expression".equals(key)) expression = stripYamlScalar(value);
            if ("source".equals(key)) source = stripYamlScalar(value);
            if ("category".equals(key)) category = stripYamlScalar(value);
            if ("tags".equals(key)) tags = parseInlineYamlArray(value);
            if ("assumptions".equals(key)) assumptions = parseInlineYamlArray(value);
        }
        if (active && !expression.isBlank()) {
            seeds.add(new SeedExpression(id, expression, source, category, tags, assumptions));
        }
        return normalize(seeds);
    }

    private static String stringValue(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (value instanceof String text) {
            return text.trim();
        }
        return "";
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object element : values) {
            if (element instanceof String text && !text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static String stripYamlScalar(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private static List<String> parseInlineYamlArray(String value) {
        String v = value == null ? "" : value.trim();
        if (!v.startsWith("[") || !v.endsWith("]")) {
            return List.of();
        }
        String inner = v.substring(1, v.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : inner.split(",")) {
            String stripped = stripYamlScalar(token);
            if (!stripped.isBlank()) {
                result.add(stripped);
            }
        }
        return List.copyOf(result);
    }

    private static List<SeedExpression> normalize(List<SeedExpression> seeds) {
        Map<String, SeedExpression> deduplicated = new LinkedHashMap<>();
        for (SeedExpression seed : seeds == null ? List.<SeedExpression>of() : seeds) {
            if (seed == null || seed.expression().isBlank()) {
                continue;
            }
            deduplicated.putIfAbsent(seed.stableKey(), seed);
        }
        return deduplicated.values().stream()
            .sorted(Comparator.comparing(SeedExpression::stableKey))
            .toList();
    }
}
