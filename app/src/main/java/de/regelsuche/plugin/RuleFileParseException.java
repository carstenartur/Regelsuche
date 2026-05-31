package de.regelsuche.plugin;

import java.util.List;
import java.util.stream.Collectors;

public final class RuleFileParseException extends IllegalArgumentException {
    private final List<RuleFileParser.RuleFileDiagnostic> diagnostics;

    public RuleFileParseException(List<RuleFileParser.RuleFileDiagnostic> diagnostics) {
        super(diagnostics.isEmpty() ? "Rule file contains errors"
            : diagnostics.stream().map(RuleFileParser.RuleFileDiagnostic::format)
                .collect(Collectors.joining("\n")));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<RuleFileParser.RuleFileDiagnostic> diagnostics() {
        return diagnostics;
    }
}
