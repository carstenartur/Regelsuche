package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.release.ProductionCandidateUtilityEvaluator.UtilityCase;
import de.regelsuche.release.ReleaseReadinessRunner.ReleaseRun;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Executes, verifies and presents the supported autonomous discovery walkthrough. */
public final class AutonomousDiscoveryWalkthroughRunner {

    public AutonomousDiscoveryResultCard run(
        Path outputDirectory,
        String repositoryRevision
    ) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        try {
            reset(outputDirectory);
            ReleaseRun run = new ReleaseReadinessRunner().runQualified(null);
            return generate(outputDirectory, repositoryRevision, run);
        } catch (RuntimeException | Error failure) {
            preserveFailure(outputDirectory, failure);
            throw failure;
        }
    }

    AutonomousDiscoveryResultCard generate(
        Path outputDirectory,
        String repositoryRevision,
        ReleaseRun run
    ) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            Path evidence = outputDirectory.resolve("evidence");
            new ReleaseReadinessRunner().write(evidence, run);
            AutonomousDiscoveryResultCard card =
                AutonomousDiscoveryResultCard.create(
                    repositoryRevision, run, outputDirectory);
            write(outputDirectory.resolve("result-card.json"),
                card.toCanonicalJson());
            write(outputDirectory.resolve("result-card.md"),
                card.toMarkdown());
            write(outputDirectory.resolve("walkthrough.md"),
                walkthrough(card));
            writeFigures(outputDirectory.resolve("figures"), run, card);
            return card;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write autonomous discovery walkthrough", exception);
        }
    }

    private static String walkthrough(AutonomousDiscoveryResultCard card) {
        return card.toMarkdown()
            + "\n## How to inspect this run\n\n"
            + "1. Start with `result-card.json`; its `contentHash` covers every "
            + "canonical card field except the hash itself.\n"
            + "2. Follow each artifact role to `evidence/`; `semanticHash` binds "
            + "the domain object and `fileSha256` binds the exact retained bytes.\n"
            + "3. Inspect `evidence/campaign/brief-v2.json`, "
            + "`observations.json` and `full-mining-evidence.json` for the "
            + "target-free formation path.\n"
            + "4. Inspect validation, counterexample, project-novelty and proof "
            + "reports before reading the qualification bundle.\n"
            + "5. Compare every baseline/candidate row in "
            + "`qualification/qualification-utility.json`.\n"
            + "6. Treat `NOT_EVALUATED` external novelty, promotion and Public "
            + "Evidence as hard claim boundaries, not missing decoration.\n\n"
            + "Independent verification:\n\n"
            + "```bash\n"
            + "python3 scripts/verify-autonomous-discovery-walkthrough.py \\\n"
            + "  --root regelsuche-release/build/reports/"
            + "autonomous-discovery-walkthrough \\\n"
            + "  --schema docs/schemas/"
            + "regelsuche-autonomous-discovery-result-card-v1.schema.json\n"
            + "```\n";
    }

    private static void writeFigures(
        Path figures,
        ReleaseRun run,
        AutonomousDiscoveryResultCard card
    ) throws IOException {
        Files.createDirectories(figures);
        JsonNode document = card.document();
        List<UtilityCase> cases = run.qualificationRun().utility().cases();
        UtilityCase representative = cases.stream()
            .filter(UtilityCase::materialGain)
            .findFirst()
            .orElse(cases.getFirst());
        write(figures.resolve("sequence.svg"), sequenceSvg(document));
        write(figures.resolve("paired-utility.svg"), utilitySvg(cases));
        write(figures.resolve("candidate-lineage.svg"), lineageSvg(document));
        write(figures.resolve("representative-search.svg"),
            representativeSvg(representative));
    }

    private static String sequenceSvg(JsonNode card) {
        List<String> labels = List.of(
            "Research Brief",
            card.path("researchBrief").path("seedFamilyCount").asText()
                + " seed families",
            card.path("researchBrief").path("observationCount").asText()
                + " untargeted observations",
            "Aggregate candidate formation",
            card.path("lifecycle").path("validationStatus").asText()
                + " / "
                + card.path("lifecycle").path("counterexampleStatus").asText(),
            card.path("lifecycle").path("projectNoveltyStatus").asText()
                + " / "
                + card.path("lifecycle").path("proofEvidenceStatus").asText(),
            "Qualification: "
                + card.path("qualification").path("qualified").asText(),
            "Paired held-out reuse"
        );
        StringBuilder svg = svgStart(1200, 720);
        banner(svg, 1200);
        for (int index = 0; index < labels.size(); index++) {
            int y = 105 + index * 73;
            svg.append("<rect class=\"box\" x=\"180\" y=\"")
                .append(y).append("\" width=\"840\" height=\"48\"/>")
                .append("<text class=\"label\" x=\"600\" y=\"")
                .append(y + 30).append("\" text-anchor=\"middle\">")
                .append(xml(labels.get(index))).append("</text>");
            if (index + 1 < labels.size()) {
                svg.append("<path class=\"arrow\" d=\"M600 ")
                    .append(y + 48).append(" V").append(y + 73)
                    .append("\"/>");
            }
        }
        return svgEnd(svg);
    }

    private static String utilitySvg(List<UtilityCase> cases) {
        long maximum = cases.stream()
            .flatMapToLong(item -> java.util.stream.LongStream.of(
                item.baselineExploredStates(), item.candidateExploredStates()))
            .max().orElse(1L);
        int height = 155 + cases.size() * 52;
        StringBuilder svg = svgStart(1200, height);
        banner(svg, 1200);
        svg.append("<text class=\"title\" x=\"60\" y=\"92\">")
            .append("Paired held-out explored states (baseline / candidate)")
            .append("</text>");
        for (int index = 0; index < cases.size(); index++) {
            UtilityCase item = cases.get(index);
            int y = 125 + index * 52;
            int baselineWidth = Math.max(1,
                Math.toIntExact(item.baselineExploredStates() * 360 / maximum));
            int candidateWidth = Math.max(1,
                Math.toIntExact(item.candidateExploredStates() * 360 / maximum));
            svg.append("<text class=\"small\" x=\"30\" y=\"")
                .append(y + 19).append("\">")
                .append(xml(item.id())).append("</text>")
                .append("<rect class=\"baseline\" x=\"330\" y=\"")
                .append(y).append("\" width=\"").append(baselineWidth)
                .append("\" height=\"16\"/>")
                .append("<rect class=\"candidate\" x=\"330\" y=\"")
                .append(y + 21).append("\" width=\"").append(candidateWidth)
                .append("\" height=\"16\"/>")
                .append("<text class=\"small\" x=\"710\" y=\"")
                .append(y + 14).append("\">B ")
                .append(item.baselineExploredStates())
                .append(" states, depth ").append(item.baselinePathLength())
                .append("</text>")
                .append("<text class=\"small\" x=\"710\" y=\"")
                .append(y + 35).append("\">C ")
                .append(item.candidateExploredStates())
                .append(" states, depth ").append(item.candidatePathLength())
                .append(item.materialGain() ? " · gain" : "")
                .append(item.regression() ? " · regression" : "")
                .append("</text>");
        }
        return svgEnd(svg);
    }

    private static String lineageSvg(JsonNode card) {
        JsonNode observations = card.path("candidate")
            .path("supportingObservationIds");
        int count = observations.size();
        int height = Math.max(520, 190 + count * 42);
        StringBuilder svg = svgStart(1200, height);
        banner(svg, 1200);
        svg.append("<text class=\"title\" x=\"60\" y=\"92\">")
            .append("Exact supporting lineage to retained candidate")
            .append("</text>")
            .append("<rect class=\"candidateBox\" x=\"710\" y=\"")
            .append(145 + count * 18)
            .append("\" width=\"420\" height=\"95\"/>")
            .append("<text class=\"label\" x=\"920\" y=\"")
            .append(182 + count * 18).append("\" text-anchor=\"middle\">")
            .append(xml(card.path("candidate").path("conjectureId").asText()))
            .append("</text>")
            .append("<text class=\"small\" x=\"920\" y=\"")
            .append(210 + count * 18).append("\" text-anchor=\"middle\">")
            .append(xml(card.path("candidate").path("leftPattern").asText()))
            .append(" → ")
            .append(xml(card.path("candidate").path("rightPattern").asText()))
            .append("</text>");
        for (int index = 0; index < count; index++) {
            int y = 125 + index * 42;
            int targetY = 192 + count * 18;
            svg.append("<rect class=\"box\" x=\"40\" y=\"")
                .append(y).append("\" width=\"480\" height=\"30\"/>")
                .append("<text class=\"small\" x=\"55\" y=\"")
                .append(y + 20).append("\">")
                .append(xml(observations.get(index).asText())).append("</text>")
                .append("<path class=\"line\" d=\"M520 ")
                .append(y + 15).append(" L710 ").append(targetY)
                .append("\"/>");
        }
        svg.append("<text class=\"small\" x=\"60\" y=\"")
            .append(height - 35).append("\">Lineage root: ")
            .append(xml(card.path("candidate").path("lineageRoot").asText()))
            .append("</text>");
        return svgEnd(svg);
    }

    private static String representativeSvg(UtilityCase item) {
        StringBuilder svg = svgStart(1200, 500);
        banner(svg, 1200);
        svg.append("<text class=\"title\" x=\"60\" y=\"92\">")
            .append("Representative held-out search: ")
            .append(xml(item.id())).append("</text>")
            .append("<rect class=\"box\" x=\"70\" y=\"135\" width=\"470\" ")
            .append("height=\"240\"/>")
            .append("<rect class=\"candidateBox\" x=\"660\" y=\"135\" ")
            .append("width=\"470\" height=\"240\"/>")
            .append("<text class=\"title\" x=\"305\" y=\"180\" ")
            .append("text-anchor=\"middle\">Baseline</text>")
            .append("<text class=\"title\" x=\"895\" y=\"180\" ")
            .append("text-anchor=\"middle\">Candidate enabled</text>")
            .append(metric("Reached", Boolean.toString(item.baselineReached()),
                Boolean.toString(item.candidateReached()), 225))
            .append(metric("Path length",
                Integer.toString(item.baselinePathLength()),
                Integer.toString(item.candidatePathLength()), 275))
            .append(metric("Explored states",
                Long.toString(item.baselineExploredStates()),
                Long.toString(item.candidateExploredStates()), 325))
            .append("<path class=\"arrowWide\" d=\"M540 255 H660\"/>")
            .append("<text class=\"small\" x=\"600\" y=\"415\" ")
            .append("text-anchor=\"middle\">")
            .append(item.materialGain() ? "Material gain retained" : "No material gain")
            .append(item.regression() ? " · correctness regression" : " · no regression")
            .append("</text>");
        return svgEnd(svg);
    }

    private static String metric(
        String name,
        String baseline,
        String candidate,
        int y
    ) {
        return "<text class=\"label\" x=\"305\" y=\"" + y
            + "\" text-anchor=\"middle\">" + xml(name + ": " + baseline)
            + "</text><text class=\"label\" x=\"895\" y=\"" + y
            + "\" text-anchor=\"middle\">" + xml(name + ": " + candidate)
            + "</text>";
    }

    private static StringBuilder svgStart(int width, int height) {
        return new StringBuilder()
            .append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            .append(width).append(' ').append(height).append("\">")
            .append("<style>")
            .append(".box{fill:#f7f7f7;stroke:#222;stroke-width:2}")
            .append(".candidateBox{fill:#eef8ee;stroke:#222;stroke-width:2}")
            .append(".label{font:18px sans-serif;fill:#111}")
            .append(".title{font:bold 22px sans-serif;fill:#111}")
            .append(".small{font:14px monospace;fill:#111}")
            .append(".banner{fill:#fff3f3;stroke:#9b1c1c;stroke-width:2}")
            .append(".bannerText{font:bold 25px sans-serif;fill:#9b1c1c}")
            .append(".arrow,.arrowWide{fill:none;stroke:#222;stroke-width:3}")
            .append(".line{fill:none;stroke:#555;stroke-width:1.5}")
            .append(".baseline{fill:#c8c8c8;stroke:#222}")
            .append(".candidate{fill:#9dd39d;stroke:#222}")
            .append("</style>");
    }

    private static void banner(StringBuilder svg, int width) {
        svg.append("<rect class=\"banner\" x=\"20\" y=\"15\" width=\"")
            .append(width - 40).append("\" height=\"55\"/>")
            .append("<text class=\"bannerText\" x=\"")
            .append(width / 2).append("\" y=\"51\" text-anchor=\"middle\">")
            .append(AutonomousDiscoveryResultCard.CLAIM_BANNER)
            .append("</text>");
    }

    private static String svgEnd(StringBuilder svg) {
        return svg.append("</svg>\n").toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private static void reset(Path outputDirectory) {
        if (!Files.exists(outputDirectory)) {
            return;
        }
        try (var paths = Files.walk(outputDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not reset walkthrough output", exception);
        }
    }

    private static void preserveFailure(Path outputDirectory, Throwable failure) {
        try {
            Files.createDirectories(outputDirectory);
            StringWriter details = new StringWriter();
            failure.printStackTrace(new PrintWriter(details));
            write(outputDirectory.resolve("walkthrough-failure.txt"),
                details.toString());
        } catch (IOException ignored) {
            failure.addSuppressed(ignored);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
