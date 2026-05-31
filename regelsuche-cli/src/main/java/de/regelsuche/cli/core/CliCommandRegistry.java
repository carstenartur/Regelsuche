package de.regelsuche.cli.core;

import java.util.Locale;
import java.util.Set;

/** Registry of top-level command names understood by the CLI entry point. */
public final class CliCommandRegistry {
    private static final CliCommandRegistry DEFAULT = new CliCommandRegistry(Set.of(
        "discover", "transform", "inventory", "path", "benchmark", "serve", "explain", "plugins", "rules"
    ));

    private final Set<String> commands;

    public CliCommandRegistry(Set<String> commands) {
        this.commands = Set.copyOf(commands);
    }

    public static CliCommandRegistry defaults() {
        return DEFAULT;
    }

    public boolean contains(String token) {
        return token != null && commands.contains(token.toLowerCase(Locale.ROOT));
    }

    public Set<String> commands() {
        return commands;
    }
}
