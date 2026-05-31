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
    private static final Pattern HEADER = Pattern.compile("^(rule|macro|profile)\\s+([A-Za-z0-9_.-]+):\\s*$");
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*):\\s*(.*)$");
    private static final Pattern CONDITION = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*)\\s*:\\s*(\\S.*)$");

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
                    "Expected 'rule <id>:', 'macro <id>:' or 'profile <id>:'"));
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
                    String signature = rule.pattern();
                    String existing = signatures.putIfAbsent(signature, rule.id());
                    if (existing != null) {
                        diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.WARNING,
                            "Rule '" + rule.id() + "' has the same source pattern as '" + existing + "'"));
                    }
                }
            } else if ("macro".equals(kind)) {
                MacroDefinition macro = buildMacro(path, lineNumber, id, properties, diagnostics);
                if (macro != null) {
                    entries.add(macro);
                }
            } else {
                ProfileDefinition profile = buildProfile(path, lineNumber, id, properties, diagnostics);
                if (profile != null) {
                        entries.add(profile);
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
        List<RuleCondition> conditions = conditionProperty("conditions", properties, path, lineNumber, diagnostics);
        String explanation = stringOrDefault("explanation", properties, "");
        String difficulty = stringOrDefault("difficulty", properties, "unspecified");
        RuleDirection direction = parseDirection(path, lineNumber, stringOrDefault("direction", properties, "forward"), diagnostics);
        int priority = intOrDefault(path, lineNumber, "priority", properties, 0, diagnostics);
        validateKnownProperties(path, lineNumber, properties,
            Set.of("pattern", "replace", "tags", "conditions", "explanation", "difficulty", "direction", "priority"), diagnostics);
        return new RuleDefinition(id, lineNumber, pattern, replace, direction, priority, tags, conditions, explanation, difficulty);
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
        int priority = intOrDefault(path, lineNumber, "priority", properties, 0, diagnostics);
        String difficulty = stringOrDefault("difficulty", properties, "unspecified");
        validateKnownProperties(path, lineNumber, properties,
            Set.of("input", "output", "tags", "explanation", "priority", "difficulty"), diagnostics);
        return new MacroDefinition(id, lineNumber, input, output, explanation, tags, priority, difficulty);
    }

    private ProfileDefinition buildProfile(
        Path path,
        int lineNumber,
        String id,
        Map<String, Object> properties,
        List<RuleFileDiagnostic> diagnostics
    ) {
        List<String> enableTags = listProperty("enable_tags", properties);
        List<String> disableTags = listProperty("disable_tags", properties);
        validateKnownProperties(path, lineNumber, properties, Set.of("enable_tags", "disable_tags"), diagnostics);
        if (enableTags.isEmpty() && disableTags.isEmpty()) {
            diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.WARNING,
                "Profile '" + id + "' has neither 'enable_tags' nor 'disable_tags' and has no effect"));
        }
        return new ProfileDefinition(id, lineNumber, enableTags, disableTags);
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

    private int intOrDefault(
        Path path,
        int lineNumber,
        String key,
        Map<String, Object> properties,
        int defaultValue,
        List<RuleFileDiagnostic> diagnostics
    ) {
        Object value = properties.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ex) {
                diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
                    "Property '" + key + "' must be an integer"));
                return defaultValue;
            }
        }
        diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
            "Property '" + key + "' must be an integer"));
        return defaultValue;
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

    private List<RuleCondition> conditionProperty(
        String key,
        Map<String, Object> properties,
        Path path,
        int lineNumber,
        List<RuleFileDiagnostic> diagnostics
    ) {
        List<String> values = listProperty(key, properties);
        List<RuleCondition> conditions = new ArrayList<>();
        for (String value : values) {
            Matcher matcher = CONDITION.matcher(value);
            if (!matcher.matches()) {
                diagnostics.add(new RuleFileDiagnostic(path, lineNumber, Severity.ERROR,
                    "Condition '" + value + "' must use '<name>: <value>'"));
                continue;
            }
            conditions.add(new RuleCondition(matcher.group(1), matcher.group(2)));
        }
        return List.copyOf(conditions);
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

    public sealed interface Entry permits RuleDefinition, MacroDefinition, ProfileDefinition {
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
        int priority,
        List<String> tags,
        List<RuleCondition> conditions,
        String explanation,
        String difficulty
    ) implements Entry {
        public RuleDefinition {
            tags = List.copyOf(tags);
            conditions = List.copyOf(conditions);
        }
    }

    public record RuleCondition(String name, String value) {
    }

    public record MacroDefinition(
        String id,
        int line,
        String input,
        String output,
        String explanation,
        List<String> tags,
        int priority,
        String difficulty
    ) implements Entry {
        public MacroDefinition {
            tags = List.copyOf(tags);
        }
    }

    public record ProfileDefinition(
        String id,
        int line,
        List<String> enableTags,
        List<String> disableTags
    ) implements Entry {
        public ProfileDefinition {
            enableTags = List.copyOf(enableTags);
            disableTags = List.copyOf(disableTags);
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
