package de.regelsuche.plugin;

import java.util.List;

public record RuleMacro(
    String id,
    String input,
    String output,
    String explanation,
    List<String> tags,
    int priority,
    String difficulty
) {
    public RuleMacro {
        tags = List.copyOf(tags);
        difficulty = difficulty == null || difficulty.isBlank() ? "unspecified" : difficulty;
    }

    public RuleMacro(String id, String input, String output, String explanation, List<String> tags) {
        this(id, input, output, explanation, tags, 0, "unspecified");
    }
}
