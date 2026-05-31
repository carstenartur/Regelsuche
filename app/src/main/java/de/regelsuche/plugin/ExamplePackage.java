package de.regelsuche.plugin;

import java.util.List;

public record ExamplePackage(
    String id,
    String name,
    List<ExampleEntry> examples,
    List<String> tags
) implements PluginExtension {
    public ExamplePackage {
        examples = List.copyOf(examples);
        tags = List.copyOf(tags);
    }

    public record ExampleEntry(String title, String input, String expected) {
    }
}
