package de.regelsuche.search;

import java.util.Set;

public record SearchGraphStyle(
        Set<String> discoveryPath,
        Set<String> macroPath,
        Set<String> deadEnds,
        Set<String> convergenceNodes) {

    public SearchGraphStyle {
        discoveryPath = discoveryPath == null ? Set.of() : Set.copyOf(discoveryPath);
        macroPath = macroPath == null ? Set.of() : Set.copyOf(macroPath);
        deadEnds = deadEnds == null ? Set.of() : Set.copyOf(deadEnds);
        convergenceNodes = convergenceNodes == null ? Set.of() : Set.copyOf(convergenceNodes);
    }

    public static SearchGraphStyle empty() {
        return new SearchGraphStyle(Set.of(), Set.of(), Set.of(), Set.of());
    }
}
