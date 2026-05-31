package de.regelsuche.plugin;

import java.util.List;

/**
 * Activation profile that decides which rules, transformations and macros stay enabled
 * based on their tags. A profile enables a set of tags (whitelist) and disables another
 * set of tags (blacklist).
 *
 * <p>Semantics for a tagged entry:
 * <ul>
 *   <li>If any of the entry's tags is listed in {@code disableTags}, the entry is excluded.</li>
 *   <li>If {@code enableTags} is non-empty, the entry is only included when at least one of
 *       its tags is listed in {@code enableTags}.</li>
 *   <li>Otherwise the entry is included.</li>
 * </ul>
 */
public record RuleProfile(String id, List<String> enableTags, List<String> disableTags, String source) {
    public RuleProfile {
        enableTags = List.copyOf(enableTags);
        disableTags = List.copyOf(disableTags);
    }

    /**
     * Decides whether an entry with the given tags should remain enabled under this profile.
     */
    public boolean includes(List<String> tags) {
        for (String tag : tags) {
            if (disableTags.contains(tag)) {
                return false;
            }
        }
        if (enableTags.isEmpty()) {
            return true;
        }
        for (String tag : tags) {
            if (enableTags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
