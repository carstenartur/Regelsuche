package de.regelsuche.extension;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class ExtensionIdentifiers {
    private static final Pattern IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}"
    );
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    private ExtensionIdentifiers() {
    }

    static String identifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a valid identifier");
        }
        return value;
    }

    static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return unicodeText(value.trim(), name);
    }

    static String optionalText(String value) {
        return value == null ? "" : unicodeText(value.trim(), "text");
    }

    static List<String> normalizedStrings(Collection<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        var normalized = new TreeSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " contains a blank value");
            }
            normalized.add(unicodeText(value.trim(), name));
        }
        return List.copyOf(normalized);
    }

    // Reject malformed UTF-16 before a later UTF-8 encoder can replace it with '?'.
    // Otherwise distinct metadata strings can acquire the same catalog hash input.
    private static String unicodeText(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate");
            }
        }
        return value;
    }

    static String hash(String value, String name, boolean optional) {
        String normalized = optionalText(value);
        if (optional && normalized.isEmpty()) {
            return "";
        }
        if (!HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be sha256:<64 lowercase hex>");
        }
        return normalized;
    }
}
