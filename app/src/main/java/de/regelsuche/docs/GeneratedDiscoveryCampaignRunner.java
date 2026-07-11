package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a deterministic generator-driven discovery campaign from systematic families. */
public final class GeneratedDiscoveryCampaignRunner {
    private static final String CAMPAIGN_ID = "discovery-campaign-generated-1";
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new GeneratedDiscoveryCampaignRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-generated-campaign"));
    }

    CampaignReport run() {
        List<GeneratedCase> cases = generatedCases();
        List<PromotionRecord> records = cases.stream()
            .map(this::toPromotionRecord)
            .toList();
        return new CampaignReport(
            CAMPAIGN_ID,
            cases,
            records,
            familySummaries(cases)
        );
    }

    CampaignReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            CampaignReport report = run();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("generated-discovery-campaign.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("generated-discovery-campaign.md"),
                renderCampaign(report),
                StandardCharsets.UTF_8
            );

            new DiscoveryCandidateReportWriter().write(outputDirectory, report.campaignId(), report.promotionRecords());
            DiscoveryCandidateStore.CandidateStoreReport store =
                new DiscoveryCandidateStore().write(outputDirectory, report.promotionRecords());
            new PatternHypothesisMiner().write(outputDirectory, store);
            new PublicEvidenceGate().write(outputDirectory, report.promotionRecords());
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    List<GeneratedCase> generatedCases() {
        List<GeneratedCase> cases = new ArrayList<>();
        cases.addAll(perfectSquareExpansionFamily());
        cases.addAll(rationalCommonDenominatorFamily());
        return List.copyOf(cases);
    }

    private List<GeneratedCase> perfectSquareExpansionFamily() {
        List<GeneratedCase> cases = new ArrayList<>();
        for (int k : List.of(1, 2, 3)) {
            Map<String, String> seed = new LinkedHashMap<>();
            seed.put("variable", "x");
            seed.put("offset", Integer.toString(k));
            cases.add(new GeneratedCase(
                "generated-perfect-square-" + k,
                "perfect-square-expansion",
                "(x + " + k + ")^2",
                "x^2 + " + (2 * k) + "*x + " + (k * k),
                "generated_perfect_square_expansion",
                "generated-family:perfect-square-expansion",
                seed,
                List.of(),
                List.of("generated_family_seed", "generated_perfect_square_expansion"),
                "generated from parameterized family (x+k)^2 -> x^2+2kx+k^2"
            ));
        }
        return cases;
    }

    private List<GeneratedCase> rationalCommonDenominatorFamily() {
        List<GeneratedCase> cases = new ArrayList<>();
        List<RationalSeed> seeds = List.of(
            new RationalSeed("x", "z", "y"),
            new RationalSeed("a", "b", "c"),
            new RationalSeed("m", "n", "q")
        );
        for (RationalSeed seed : seeds) {
            Map<String, String> seedParameters = new LinkedHashMap<>();
            seedParameters.put("leftNumerator", seed.leftNumerator());
            seedParameters.put("rightNumerator", seed.rightNumerator());
            seedParameters.put("denominator", seed.denominator());
            cases.add(new GeneratedCase(
                "generated-rational-common-denominator-" + seed.denominator(),
                "rational-common-denominator",
                seed.leftNumerator() + " / " + seed.denominator() + " + "
                    + seed.rightNumerator() + " / " + seed.denominator(),
                "(" + seed.leftNumerator() + " + " + seed.rightNumerator() + ") / " + seed.denominator(),
                "generated_rational_common_denominator",
                "generated-family:rational-common-denominator",
                seedParameters,
                List.of("nonZero(" + seed.denominator() + ")"),
                List.of("generated_family_seed", "generated_rational_common_denominator"),
                "generated from parameterized family a/d + b/d -> (a+b)/d under d != 0"
            ));
        }
        return cases;
    }

    private PromotionRecord toPromotionRecord(GeneratedCase generatedCase) {
        return new PromotionRecord(
            generatedCase.id(),
            CAMPAIGN_ID,
            "2026-10-01",
            generatedCase.family(),
            PromotionStage.CANDIDATE,
            generatedCase.inputExpression(),
            generatedCase.targetExpression(),
            "UNAVAILABLE",
            "generated family case; no oracle check has been performed",
            "N/A",
            generatedCase.operatorId(),
            generatedCase.packId(),
            generatedCase.assumptions(),
            generatedCase.rationale(),
            generatedCase.rulePath(),
            false,
            List.of("no-real-search-evidence"),
            false,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("N/A"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
    }

    private List<FamilySummary> familySummaries(List<GeneratedCase> cases) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GeneratedCase generatedCase : cases) {
            counts.merge(generatedCase.family(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .map(entry -> new FamilySummary(entry.getKey(), entry.getValue()))
            .toList();
    }

    private String renderCampaign(CampaignReport report) {
        StringBuilder out = new StringBuilder("# Generated discovery campaign\n\n");
        out.append("Campaign: `").append(report.campaignId()).append("`\n\n");
        out.append("## Families\n\n");
        for (FamilySummary summary : report.familySummaries()) {
            out.append("- ").append(summary.family()).append(": ").append(summary.caseCount()).append(" cases\n");
        }
        out.append("\n## Generated cases\n\n");
        out.append("| Case | Family | Input | Target | Operator | Assumptions | Seed parameters |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (GeneratedCase generatedCase : report.cases()) {
            out.append("| ").append(escape(generatedCase.id()))
                .append(" | ").append(escape(generatedCase.family()))
                .append(" | `").append(escapeInlineCode(generatedCase.inputExpression())).append("`")
                .append(" | `").append(escapeInlineCode(generatedCase.targetExpression())).append("`")
                .append(" | ").append(escape(generatedCase.operatorId()))
                .append(" | ").append(escape(generatedCase.assumptions().isEmpty() ? "—" : String.join(", ", generatedCase.assumptions())))
                .append(" | ").append(escape(generatedCase.sourceSeedParameters().toString()))
                .append(" |\n");
        }
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String escapeInlineCode(String value) {
        return escape(value).replace("`", "\\`");
    }

    record CampaignReport(
        String campaignId,
        List<GeneratedCase> cases,
        List<PromotionRecord> promotionRecords,
        List<FamilySummary> familySummaries
    ) {
        CampaignReport {
            cases = cases == null ? List.of() : List.copyOf(cases);
            promotionRecords = promotionRecords == null ? List.of() : List.copyOf(promotionRecords);
            familySummaries = familySummaries == null ? List.of() : List.copyOf(familySummaries);
        }
    }

    record GeneratedCase(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String operatorId,
        String packId,
        Map<String, String> sourceSeedParameters,
        List<String> assumptions,
        List<String> rulePath,
        String rationale
    ) {
        GeneratedCase {
            sourceSeedParameters = sourceSeedParameters == null ? Map.of() : Map.copyOf(sourceSeedParameters);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            rationale = rationale == null ? "" : rationale;
        }
    }

    record FamilySummary(String family, int caseCount) {
    }

    private record RationalSeed(String leftNumerator, String rightNumerator, String denominator) {
    }
}
