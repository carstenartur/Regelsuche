package de.regelsuche.search.telemetry;

import java.util.List;

final class SearchEventJson {

    private SearchEventJson() {
    }

    static String toJson(SearchEvent event) {
        StringBuilder json = new StringBuilder(320);
        json.append('{');
        appendNumberField(json, "sequence", event.sequence());
        appendStringField(json, "type", event.type().name());
        appendStringField(json, "expression", event.expression());
        appendStringField(json, "canonicalHash", event.canonicalHash());
        appendNumberField(json, "depth", event.depth());
        appendNumberField(json, "score", event.score());
        appendStringField(json, "parentCanonicalHash", event.parentCanonicalHash());
        appendStringField(json, "ruleId", event.ruleId());
        appendEnumField(json, "rewriteKind", event.rewriteKind());
        appendArrayField(json, "assumptions", event.assumptions());
        appendNumberField(json, "frontierSize", event.frontierSize());
        appendNumberField(json, "visitedCount", event.visitedCount());
        appendNumberField(json, "generatedCount", event.generatedCount());
        appendLastStringField(json, "pruningReason", event.pruningReason());
        json.append('}');
        return json.toString();
    }

    private static void appendNumberField(StringBuilder json, String name, long value) {
        appendFieldName(json, name);
        json.append(value).append(',');
    }

    private static void appendLastStringField(StringBuilder json, String name, String value) {
        appendFieldName(json, name);
        appendQuoted(json, value);
        // no trailing comma — this is the last field
    }

    private static void appendStringField(StringBuilder json, String name, String value) {
        appendFieldName(json, name);
        appendQuoted(json, value);
        json.append(',');
    }

    private static void appendEnumField(StringBuilder json, String name, Enum<?> value) {
        appendFieldName(json, name);
        if (value == null) {
            json.append("null");
        } else {
            appendQuoted(json, value.name());
        }
        json.append(',');
    }

    private static void appendArrayField(StringBuilder json, String name, List<String> values) {
        appendFieldName(json, name);
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendQuoted(json, values.get(i));
        }
        json.append("],");
    }

    private static void appendFieldName(StringBuilder json, String name) {
        appendQuoted(json, name);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
