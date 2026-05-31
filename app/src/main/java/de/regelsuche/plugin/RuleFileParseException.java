package de.regelsuche.plugin;

import java.util.List;

public final class RuleFileParseException extends IllegalArgumentException {
    private final List<RuleFileParser.RuleFileDiagnostic> diagnostics;

    public RuleFileParseException(List<RuleFileParser.RuleFileDiagnostic> diagnostics) {
        super(diagnostics.stream().map(RuleFileParser.RuleFileDiagnostic::format).reduce((left, right) -> left + "\n" + right)
            .orElse("Rule file contains errors"));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<RuleFileParser.RuleFileDiagnostic> diagnostics() {
        return diagnostics;
    }
}
