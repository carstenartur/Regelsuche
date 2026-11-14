package de.regelsuche.quality.complexity;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Writes both diagnostics before returning a failing hotspot decision to the build. */
public final class ComplexityHotspotReportMain {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    private ComplexityHotspotReportMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("expected <complexity.json> <policy.json> <output directory>");
        var report = ComplexityHotspots.evaluate(JSON.readTree(Path.of(args[0]).toFile()),
            JSON.readTree(Path.of(args[1]).toFile()), LocalDate.now());
        Path output = Files.createDirectories(Path.of(args[2]));
        var indent = new DefaultIndenter("  ", "\n");
        var printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectFieldValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(indent).withArrayIndenter(indent);
        write(output.resolve("complexity-hotspot-report.md"), markdown(report));
        write(output.resolve("complexity-hotspot-report.json"), JSON.writer(printer).writeValueAsString(report) + "\n");
        System.out.println("complexityHotspotStatus=" + report.status());
        System.out.println("complexityHotspotCount=" + report.currentHotspotCount());
        if (!report.violations().isEmpty()) throw new IllegalStateException(String.join("\n", report.violations()));
    }

    private static void write(Path output, String contents) throws IOException {
        var stage = Files.createTempFile(output.getParent(), ".complexity-", ".tmp");
        try {
            Files.writeString(stage, contents);
            Files.move(stage, output, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static String markdown(ComplexityHotspots.Report report) {
        var text = new StringBuilder("# Cognitive-complexity hotspot trend\n\nStatus: **")
            .append(report.status()).append("**\n\n")
            .append("| Status | Cognitive Δ | Cyclomatic Δ | Method |\n| --- | ---: | ---: | --- |\n");
        for (var change : report.changes()) {
            text.append("| ").append(change.status()).append(" | ").append(signed(change.cognitiveIncrease()))
                .append(" | ").append(signed(change.cyclomaticIncrease())).append(" | `")
                .append(change.current().sourceFile()).append(" :: ").append(change.current().signature()).append("` |\n");
        }
        text.append("\n## Exception format\n\nAn exception must name an exact source/signature pair, finite cognitive and ")
            .append("cyclomatic ceilings, a rationale and an ISO expiry date. Expired, duplicate ")
            .append("or ceiling-exceeding exceptions fail closed.\n");
        if (!report.violations().isEmpty()) {
            text.append("\n## Violations\n\n");
            report.violations().forEach(item -> text.append("- ").append(item).append('\n'));
        }
        return text.toString();
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }
}
