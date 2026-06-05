package de.regelsuche.moves.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes the {@code move-tree-report.json} and {@code move-tree-report.md}
 * artefacts into the discovery-pipeline output directory.
 */
public final class MoveTreeReportWriter {

    public static final String JSON_FILE_NAME = "move-tree-report.json";
    public static final String MARKDOWN_FILE_NAME = "move-tree-report.md";

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Writes both report artefacts and returns the report unchanged. */
    public MoveTreeReport write(Path outputDirectory, MoveTreeReport report) {
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(
                    outputDirectory.resolve(JSON_FILE_NAME),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.writeString(
                    outputDirectory.resolve(MARKDOWN_FILE_NAME),
                    renderMarkdown(report),
                    StandardCharsets.UTF_8);
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    String renderMarkdown(MoveTreeReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Move tree report — ").append(report.scenarioId()).append("\n\n");

        markdown.append("## Applied moves on the successful path\n\n");
        if (report.successfulPathMoves().isEmpty()) {
            markdown.append("_No successful path was found._\n\n");
        } else {
            markdown.append("| # | Kind | Rule | Source | Target | Ordinal | Parameters |\n");
            markdown.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            int index = 1;
            for (RewriteMove move : report.successfulPathMoves()) {
                markdown.append("| ").append(index++)
                        .append(" | ").append(move.kind())
                        .append(" | ").append(escape(move.ruleId()))
                        .append(" | ").append(escape(move.sourceExpression()))
                        .append(" | ").append(escape(move.targetExpression()))
                        .append(" | ").append(ordinal(move))
                        .append(" | ").append(escape(renderParameters(move.parameters())))
                        .append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## First depth-1 candidate moves\n\n");
        if (report.depth1Candidates().isEmpty()) {
            markdown.append("_No depth-1 candidates were enumerated._\n\n");
        } else {
            markdown.append("| Enumerator | Kind | Parameter | Value | Ordinal |\n");
            markdown.append("| --- | --- | --- | --- | --- |\n");
            for (Depth1MoveEnumerator.CandidateMove candidate : report.depth1Candidates()) {
                MoveParameter parameter = candidate.parameter();
                markdown.append("| ").append(escape(candidate.enumeratorId()))
                        .append(" | ").append(candidate.kind())
                        .append(" | ").append(escape(parameter.name()))
                        .append(" | ").append(escape(parameter.value()))
                        .append(" | ").append(ordinalText(candidate.ordinal().ruleOrdinal(),
                                candidate.ordinal().occurrenceOrdinal(), candidate.ordinal().parameterOrdinals()))
                        .append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## Macro moves\n\n");
        if (report.macroMoves().isEmpty()) {
            markdown.append("_No macro moves were available._\n\n");
        } else {
            markdown.append("| Macro | Version | Atomic | Expanded steps | Validation | Tags |\n");
            markdown.append("| --- | --- | --- | --- | --- | --- |\n");
            for (RewriteMove macro : report.macroMoves()) {
                markdown.append("| ").append(escape(macro.macroId()))
                        .append(" | ").append(macro.macroVersion())
                        .append(" | ").append(macro.atomic())
                        .append(" | ").append(macro.expandedMoves().size())
                        .append(" | ").append(escape(macro.validationStatus()))
                        .append(" | ").append(escape(String.join(", ", macro.tags())))
                        .append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## Unresolved parameters\n\n");
        if (report.unresolvedParameters().isEmpty()) {
            markdown.append("_All parameters were resolved._\n");
        } else {
            markdown.append("| Move | Kind | Rule |\n");
            markdown.append("| --- | --- | --- |\n");
            for (MoveTreeReport.UnresolvedParameterEntry entry : report.unresolvedParameters()) {
                markdown.append("| ").append(escape(entry.moveId()))
                        .append(" | ").append(escape(entry.kind()))
                        .append(" | ").append(escape(entry.ruleId()))
                        .append(" |\n");
            }
        }
        return markdown.toString();
    }

    private String renderParameters(List<MoveParameter> parameters) {
        if (parameters.isEmpty()) {
            return "—";
        }
        return parameters.stream()
                .map(parameter -> parameter.name() + "=" + parameter.value())
                .collect(Collectors.joining(", "));
    }

    private String ordinal(RewriteMove move) {
        return ordinalText(move.ordinal().ruleOrdinal(), move.ordinal().occurrenceOrdinal(),
                move.ordinal().parameterOrdinals());
    }

    private String ordinalText(int ruleOrdinal, int occurrenceOrdinal, List<Integer> parameterOrdinals) {
        return ruleOrdinal + ":" + occurrenceOrdinal + ":" + parameterOrdinals;
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("|", "\\|");
    }
}
