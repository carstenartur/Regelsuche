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
public record RuleProfile(
    String id,
    List<String> enableTags,
    List<String> disableTags,
    List<String> whitelist,
    List<String> blacklist,
    String source
) {
    public RuleProfile {
        enableTags = List.copyOf(enableTags);
        disableTags = List.copyOf(disableTags);
        whitelist = List.copyOf(whitelist);
        blacklist = List.copyOf(blacklist);
    }

    public RuleProfile(String id, List<String> enableTags, List<String> disableTags, String source) {
        this(id, enableTags, disableTags, List.of(), List.of(), source);
    }

    /**
     * Decides whether an entry with the given tags should remain enabled under this profile.
     */
    public boolean includes(List<String> tags) {
        return includes("", tags);
    }

    /**
     * Decides whether an entry with the given id and tags should remain enabled under this profile.
     */
    public boolean includes(String entryId, List<String> tags) {
        if (matchesId(blacklist, entryId)) {
            return false;
        }
        if (matchesId(whitelist, entryId)) {
            return true;
        }
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

    public List<String> conflictingIds() {
        return whitelist.stream()
            .filter(blacklist::contains)
            .toList();
    }

    private boolean matchesId(List<String> ids, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        if (ids.contains(entryId)) {
            return true;
        }
        for (String id : ids) {
            if (entryId.startsWith(id + ".")) {
                return true;
            }
        }
        return false;
    }
}
