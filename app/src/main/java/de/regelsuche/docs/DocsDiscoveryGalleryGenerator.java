package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DocsDiscoveryGalleryGenerator {
    public static final String GENERATED_BY = DiscoveryEvidenceSchemaV1.PRODUCER_ID;
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final List<String> PUBLIC_SCENARIO_IDS = List.of("complete-square-factorization", "sophie-germain");

    private final DiscoveryBenchmarkScenarioLoader scenarioLoader = new DiscoveryBenchmarkScenarioLoader();
    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();
    private final SearchSpaceGallerySvgWriter svgWriter = new SearchSpaceGallerySvgWriter();
    private final PublicBenchmarkEvidenceGate publicGate = new PublicBenchmarkEvidenceGate();
    private final DiscoveryEvidenceSchemaV1 evidenceSchema = new DiscoveryEvidenceSchemaV1();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0 ? Path.of(".").toAbsolutePath().normalize() : Path.of(args[0]).toAbsolutePath().normalize();
        new DocsDiscoveryGalleryGenerator().generate(repoRoot);
    }

    public void generate(Path repoRoot) {
        try {
            Path generatedRoot = repoRoot.resolve("docs/generated/discovery");
            deleteDirectory(generatedRoot);
            Files.createDirectories(generatedRoot);

            List<PublicScenarioArtifact> artifacts = new ArrayList<>();
            List<PublicBenchmarkEvidenceGate.GateDecision> gateDecisions = new ArrayList<>();
            for (DiscoveryBenchmarkScenario scenario : scenarioLoader.loadAll("discovery-scenarios")) {
                if (!PUBLIC_SCENARIO_IDS.contains(scenario.id())) {
                    continue;
                }
                DiscoveryBenchmarkEvidence evidence = executor.execute(scenario);
                PublicBenchmarkEvidenceGate.GateDecision gateDecision = publicGate.evaluate(scenario, evidence);
                gateDecisions.add(gateDecision);
                if (gateDecision.accepted()) {
                    artifacts.add(writeScenarioArtifacts(generatedRoot, scenario, evidence, gateDecision));
                }
            }
            PublicBenchmarkEvidenceGate.GateReport gateReport = publicGate.write(generatedRoot, gateDecisions);
            if (gateReport.rejectedCount() > 0) {
                throw new IllegalStateException("Public discovery scenario gate rejected "
                    + gateReport.rejectedCount() + " scenario(s); see docs/generated/discovery/public-scenario-rejections.md");
            }
            ensureAllPublicScenariosGenerated(artifacts);
            writeIndex(generatedRoot.resolve("index.json"), artifacts);
            Files.writeString(generatedRoot.resolve("README.md"), renderGeneratedReadme(), StandardCharsets.UTF_8);
            Files.writeString(repoRoot.resolve("docs/demo-gallery.md"), renderGallery(artifacts), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private PublicScenarioArtifact writeScenarioArtifacts(
            Path generatedRoot,
            DiscoveryBenchmarkScenario scenario,
            DiscoveryBenchmarkEvidence evidence,
            PublicBenchmarkEvidenceGate.GateDecision gateDecision) throws IOException {
        String slug = slugFor(scenario.id());
        Path scenarioDir = generatedRoot.resolve(slug);
        Files.createDirectories(scenarioDir);
        Path evidencePath = scenarioDir.resolve("evidence.json");
        Path svgPath = scenarioDir.resolve("search-space.svg");
        Path summaryPath = scenarioDir.resolve("summary.md");
        String relativeEvidence = slug + "/evidence.json";
        String relativeSvg = slug + "/search-space.svg";
        String relativeSummary = slug + "/summary.md";

        Files.writeString(svgPath, svgWriter.write(evidence, "evidence.json"), StandardCharsets.UTF_8);
        Files.writeString(summaryPath, renderSummary(scenario, evidence), StandardCharsets.UTF_8);
        List<DiscoveryEvidenceSchemaV1.ArtifactDescriptor> artifacts = List.of(
            artifact("summary", summaryPath, "summary.md", "text/markdown"),
            artifact("search-space", svgPath, "search-space.svg", "image/svg+xml")
        );
        DiscoveryEvidenceSchemaV1.EvidenceDocument document = evidenceSchema.createDocument(scenario, evidence, gateDecision, artifacts);
        evidenceSchema.assertValidDocument(document.body(), scenarioDir);
        AtomicJsonFile.writeUtf8(evidencePath, evidenceSchema.prettyJson(document.body()));
        return new PublicScenarioArtifact(scenario, evidence, relativeEvidence, relativeSvg, relativeSummary,
            document.canonicalEvidenceId(), document.canonicalEvidenceHash());
    }

    private DiscoveryEvidenceSchemaV1.ArtifactDescriptor artifact(
        String name,
        Path path,
        String relativePath,
        String mediaType
    ) throws IOException {
        return new DiscoveryEvidenceSchemaV1.ArtifactDescriptor(
            name,
            relativePath,
            mediaType,
            "sha256:" + sha256(Files.readAllBytes(path))
        );
    }

    private void writeIndex(Path path, List<PublicScenarioArtifact> artifacts) throws IOException {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("generatedBy", GENERATED_BY);
        List<Map<String, Object>> scenarios = artifacts.stream().map(artifact -> {
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id", artifact.evidence().scenarioId());
            scenario.put("success", artifact.evidence().success());
            scenario.put("oracleStatus", artifact.evidence().oracleStatus());
            scenario.put("promotionEligible", artifact.evidence().promotionEligible());
            scenario.put("evidence", artifact.evidencePath());
            scenario.put("svg", artifact.svgPath());
            scenario.put("summary", artifact.summaryPath());
            scenario.put("canonicalEvidenceId", artifact.canonicalEvidenceId());
            scenario.put("canonicalEvidenceHash", artifact.canonicalEvidenceHash());
            return scenario;
        }).toList();
        index.put("scenarios", scenarios);
        AtomicJsonFile.writeUtf8(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    private String renderGallery(List<PublicScenarioArtifact> artifacts) {
        PublicScenarioArtifact completeSquare = artifactById(artifacts, "complete-square-factorization");
        PublicScenarioArtifact sophieGermain = artifactById(artifacts, "sophie-germain");
        return """
                # Regelsuche Discovery Gallery

                This gallery contains generated evidence only.

                Public entries are admitted only after `PublicBenchmarkEvidenceGate` accepts their generated search evidence. Rejections are written to `generated/discovery/public-scenario-rejections.md`.

                ## Complete-square factorization

                ${completeSquare}

                ## Sophie-Germain discovery

                ${sophieGermain}

                ## Scenario comparison

                | Scenario | Success | Oracle | States | Edges | Bridge rules | Learned macros | Reused macros |
                |---|---|---|---:|---:|---:|---:|---:|
                ${rows}
                """
                .replace("${completeSquare}", renderScenarioSection(completeSquare, "Bridge used"))
                .replace("${sophieGermain}", renderScenarioSection(sophieGermain, "Hidden bridge used"))
                .replace("${rows}", renderComparisonRows(artifacts));
    }

    private String renderScenarioSection(PublicScenarioArtifact artifact, String bridgeLabel) {
        DiscoveryBenchmarkEvidence evidence = artifact.evidence();
        return """
                - Input: `${input}`
                - Target: `${target}`
                - Evidence status: success
                - Oracle status: `${oracleStatus}`
                - ${bridgeLabel}: ${bridges}
                - Macro learned: ${learned}
                - Macro reused: ${reused}
                - Search-space excerpt: [SVG](generated/discovery/${svg})
                - Evidence JSON link: [evidence.json](generated/discovery/${evidence})
                - Canonical evidence ID: [`${canonicalEvidenceId}`](generated/discovery/${evidence})

                <img src="generated/discovery/${svg}" alt="Generated evidence search-space for ${scenario}">
                """
                .replace("${input}", evidence.inputExpression())
                .replace("${target}", evidence.targetExpression())
                .replace("${oracleStatus}", evidence.oracleStatus())
                .replace("${bridgeLabel}", bridgeLabel)
                .replace("${bridges}", inlineList(evidence.bridgeRulesUsed()))
                .replace("${learned}", inlineList(evidence.learnedMacros()))
                .replace("${reused}", inlineList(evidence.reusedMacros()))
                .replace("${svg}", artifact.svgPath())
                .replace("${evidence}", artifact.evidencePath())
                .replace("${canonicalEvidenceId}", artifact.canonicalEvidenceId())
                .replace("${scenario}", escapeMarkdown(evidence.scenarioId()));
    }

    private String renderComparisonRows(List<PublicScenarioArtifact> artifacts) {
        StringBuilder rows = new StringBuilder();
        for (PublicScenarioArtifact artifact : artifacts) {
            DiscoveryBenchmarkEvidence evidence = artifact.evidence();
            rows.append("| ").append(escapeMarkdown(artifact.scenario().displayName())).append(" | ")
                    .append(evidence.success() ? "yes" : "no").append(" | ")
                    .append(escapeMarkdown(evidence.oracleStatus().toLowerCase(Locale.ROOT))).append(" | ")
                    .append(evidence.nodeCount()).append(" | ")
                    .append(evidence.edgeCount()).append(" | ")
                    .append(evidence.bridgeRulesUsed().size()).append(" | ")
                    .append(evidence.learnedMacros().size()).append(" | ")
                    .append(evidence.reusedMacros().size()).append(" |\n");
        }
        return rows.toString();
    }

    private String renderSummary(
        DiscoveryBenchmarkScenario scenario,
        DiscoveryBenchmarkEvidence evidence
    ) {
        return """
                # ${name}

                - generatedBy: `${generatedBy}`
                - scenarioId: `${scenarioId}`
                - inputExpression: `${input}`
                - targetExpression: `${target}`
                - oracleStatus: `${oracleStatus}`
                - promotionEligible: `${promotionEligible}`
                - nodeCount: ${nodes}
                - edgeCount: ${edges}
                - bridgeRulesUsed: ${bridges}
                - learnedMacros: ${learned}
                - reusedMacros: ${reused}
                - evidence: success
                """
                .replace("${name}", scenario.displayName())
                .replace("${generatedBy}", GENERATED_BY)
                .replace("${scenarioId}", evidence.scenarioId())
                .replace("${input}", evidence.inputExpression())
                .replace("${target}", evidence.targetExpression())
                .replace("${oracleStatus}", evidence.oracleStatus())
                .replace("${promotionEligible}", Boolean.toString(evidence.promotionEligible()))
                .replace("${nodes}", Integer.toString(evidence.nodeCount()))
                .replace("${edges}", Integer.toString(evidence.edgeCount()))
                .replace("${bridges}", inlineList(evidence.bridgeRulesUsed()))
                .replace("${learned}", inlineList(evidence.learnedMacros()))
                .replace("${reused}", inlineList(evidence.reusedMacros()));
    }

    private String renderGeneratedReadme() {
        return """
                # Generated discovery evidence

                These files are generated — do not edit manually.

                Regenerate with:

                ```
                ./gradlew :app:generateDiscoveryGallery
                ```

                The generated evidence documents implement `${schemaId}`.
                """
            .replace("${schemaId}", DiscoveryEvidenceSchemaV1.SCHEMA_ID);
    }

    private PublicScenarioArtifact artifactById(List<PublicScenarioArtifact> artifacts, String scenarioId) {
        return artifacts.stream()
                .filter(artifact -> artifact.evidence().scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing public scenario: " + scenarioId));
    }

    private void ensureAllPublicScenariosGenerated(List<PublicScenarioArtifact> artifacts) {
        List<String> ids = artifacts.stream().map(artifact -> artifact.evidence().scenarioId()).toList();
        for (String scenarioId : PUBLIC_SCENARIO_IDS) {
            if (!ids.contains(scenarioId)) {
                throw new IllegalStateException("Public scenario was not generated: " + scenarioId);
            }
        }
    }

    private String inlineList(List<String> values) {
        if (values.isEmpty()) {
            return "none";
        }
        return values.stream().map(value -> "`" + escapeMarkdown(value) + "`").reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private String slugFor(String scenarioId) {
        String normalized = scenarioId.toLowerCase(Locale.ROOT);
        if (normalized.equals("complete-square-factorization")) {
            return "complete-square";
        }
        return normalized.replaceAll("[^a-z0-9-]", "-");
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private record PublicScenarioArtifact(
            DiscoveryBenchmarkScenario scenario,
            DiscoveryBenchmarkEvidence evidence,
            String evidencePath,
            String svgPath,
            String summaryPath,
            String canonicalEvidenceId,
            String canonicalEvidenceHash) {
    }

    private String sha256(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (byte value : digest.digest(bytes)) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
