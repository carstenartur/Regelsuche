package de.regelsuche.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleFileParser {
    private static final Pattern HEADER = Pattern.compile("^(rule|macro)\\s+([A-Za-z0-9_.-]+):\\s*$");
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*):\\s*(.*)$");

    public RulePackage parse(Path path) {
        try {
            return parse(path, Files.readAllLines(path));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read rule file " + path + ": " + ex.getMessage(), ex);
        }
    }

    RulePackage parse(Path path, List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        List<RuleFileDiagnostic> diagnostics = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> signatures = new LinkedHashMap<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                index++;
                continue;
            }
            Matcher header = HEADER.matcher(line);
            if (!header.matches()) {
                diagnostics.add(new RuleFileDiagnostic(path, index + 1, Severity.ERROR,
                    "Expected 'rule <id>:' or 'macro <id>:'"));
                index++;
                continue;
            }
            String kind = header.group(1);
            String id = header.group(2);
            int lineNumber = index + 1;
            if (!ids.add(id)) {
                diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
                    "Duplicate entry id '" + id + "'"));
            }
            index++;
            Map<String, Object> properties = new LinkedHashMap<>();
            while (index < lines.size()) {
                String raw = lines.get(index);
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    index++;
                    continue;
                }
                if (HEADER.matcher(trimmed).matches()) {
                    break;
                }
                Matcher property = PROPERTY.matcher(trimmed);
                if (!property.matches()) {
                    diagnostics.add(new RuleFileDiagnostic(path, index + 1, Severity.ERROR,
                        "Expected property '<name>: <value>'"));
                    index++;
                    continue;
                }
                String key = property.group(1);
                String value = property.group(2).trim();
                if (value.isEmpty()) {
                    List<String> items = new ArrayList<>();
                    int listIndex = index + 1;
                    while (listIndex < lines.size()) {
                        String listLine = lines.get(listIndex).trim();
                        if (listLine.isEmpty() || listLine.startsWith("#")) {
                            listIndex++;
                            continue;
                        }
                        if (!listLine.startsWith("-")) {
                            break;
                        }
                        items.add(stripQuotes(listLine.substring(1).trim()));
                        listIndex++;
                    }
                    properties.put(key, List.copyOf(items));
                    index = listIndex;
                    continue;
                }
                properties.put(key, stripQuotes(value));
                index++;
            }
            if ("rule".equals(kind)) {
                RuleDefinition rule = buildRule(path, lineNumber, id, properties, diagnostics);
                if (rule != null) {
                    entries.add(rule);
                    String signature = rule.pattern() + " -> " + rule.replace();
                    String existing = signatures.putIfAbsent(signature, rule.id());
                    if (existing != null) {
                        diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.WARNING,
                            "Rule '" + rule.id() + "' duplicates the pattern of '" + existing + "'"));
                    }
                }
            } else {
                MacroDefinition macro = buildMacro(path, lineNumber, id, properties, diagnostics);
                if (macro != null) {
                    entries.add(macro);
                }
            }
        }
        return new RulePackage(path, entries, diagnostics);
    }

    private RuleDefinition buildRule(
        Path path,
        int lineNumber,
        String id,
        Map<String, Object> properties,
        List<RuleFileDiagnostic> diagnostics
    ) {
        String pattern = stringProperty("pattern", properties, path, lineNumber, diagnostics);
        String replace = stringProperty("replace", properties, path, lineNumber, diagnostics);
        if (pattern == null || replace == null) {
            return null;
        }
        List<String> tags = listProperty("tags", properties);
        List<String> conditions = listProperty("conditions", properties);
        String explanation = stringOrDefault("explanation", properties, "");
        String difficulty = stringOrDefault("difficulty", properties, "unspecified");
        RuleDirection direction = parseDirection(path, lineNumber, stringOrDefault("direction", properties, "forward"), diagnostics);
        validateKnownProperties(path, lineNumber, properties,
            Set.of("pattern", "replace", "tags", "conditions", "explanation", "difficulty", "direction"), diagnostics);
        return new RuleDefinition(id, lineNumber, pattern, replace, direction, tags, conditions, explanation, difficulty);
    }

    private MacroDefinition buildMacro(
        Path path,
        int lineNumber,
        String id,
        Map<String, Object> properties,
        List<RuleFileDiagnostic> diagnostics
    ) {
        String input = stringProperty("input", properties, path, lineNumber, diagnostics);
        String output = stringProperty("output", properties, path, lineNumber, diagnostics);
        if (input == null || output == null) {
            return null;
        }
        List<String> tags = listProperty("tags", properties);
        String explanation = stringOrDefault("explanation", properties, "");
        validateKnownProperties(path, lineNumber, properties, Set.of("input", "output", "tags", "explanation"), diagnostics);
        return new MacroDefinition(id, lineNumber, input, output, explanation, tags);
    }

    private void validateKnownProperties(
        Path path,
        int lineNumber,
        Map<String, Object> properties,
        Set<String> known,
        List<RuleFileDiagnostic> diagnostics
    ) {
        for (String key : properties.keySet()) {
            if (!known.contains(key)) {
                diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.WARNING,
                    "Unknown property '" + key + "'"));
            }
        }
    }

    private String stringProperty(
        String key,
        Map<String, Object> properties,
        Path path,
        int lineNumber,
        List<RuleFileDiagnostic> diagnostics
    ) {
        Object value = properties.get(key);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
            "Missing required property '" + key + "'"));
        return null;
    }

    private String stringOrDefault(String key, Map<String, Object> properties, String defaultValue) {
        Object value = properties.get(key);
        return value instanceof String string ? string : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> listProperty(String key, Map<String, Object> properties) {
        Object value = properties.get(key);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(stripQuotes(string));
        }
        return List.of();
    }

    private RuleDirection parseDirection(
        Path path,
        int lineNumber,
        String direction,
        List<RuleFileDiagnostic> diagnostics
    ) {
        try {
            return RuleDirection.valueOf(direction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
                "Unknown direction '" + direction + "' (expected forward, backward or both)"));
            return RuleDirection.FORWARD;
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public sealed interface Entry permits RuleDefinition, MacroDefinition {
        String id();

        int line();
    }

    public enum RuleDirection {
        FORWARD,
        BACKWARD,
        BOTH
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public record RuleDefinition(
        String id,
        int line,
        String pattern,
        String replace,
        RuleDirection direction,
        List<String> tags,
        List<String> conditions,
        String explanation,
        String difficulty
    ) implements Entry {
        public RuleDefinition {
            tags = List.copyOf(tags);
            conditions = List.copyOf(conditions);
        }
    }

    public record MacroDefinition(
        String id,
        int line,
        String input,
        String output,
        String explanation,
        List<String> tags
    ) implements Entry {
        public MacroDefinition {
            tags = List.copyOf(tags);
        }
    }

    public record RulePackage(Path path, List<Entry> entries, List<RuleFileDiagnostic> diagnostics) {
        public RulePackage {
            entries = List.copyOf(entries);
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
        }
    }

    public record RuleFileDiagnostic(Path path, int line, Severity severity, String message) {
        public String format() {
            return severity + " " + path.getFileName() + ":" + line + " - " + message;
        }
    }
}
