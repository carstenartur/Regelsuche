package de.regelsuche.plugin;

import java.util.List;

public record RuleMacro(
    String id,
    String input,
    String output,
    String explanation,
    List<String> tags
) {
    public RuleMacro {
        tags = List.copyOf(tags);
    }
}
