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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cross-campaign candidate store for discovery promotion records.
 *
 * <p>The store aggregates concrete observations into candidate lifecycle entries.
 * Exact duplicates and alpha-equivalent candidates are merged into the first
 * representative; family/operator variants stay separate but keep their novelty
 * link to the related candidate.</p>
 */
final class DiscoveryCandidateStore {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    CandidateStoreReport build(List<PromotionRecord> promotionRecords) {
        List<PromotionRecord> records = (promotionRecords == null ? List.<PromotionRecord>of() : promotionRecords).stream()
            .sorted(Comparator.comparing(PromotionRecord::sourceCampaign)
                .thenComparing(PromotionRecord::candidateId))
            .toList();
        List<NoveltyChecker.Candidate> noveltyCandidates = records.stream()
            .map(this::toNoveltyCandidate)
            .toList();
        List<NoveltyChecker.NoveltyResult> novelty = new NoveltyChecker().classifyAll(noveltyCandidates);

        Map<String, String> representativeByCandidateId = new LinkedHashMap<>();
        Map<String, EntryBuilder> builders = new LinkedHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            PromotionRecord record = records.get(index);
            NoveltyChecker.NoveltyResult noveltyResult = novelty.get(index);
            String representativeId = representativeId(record, noveltyResult, representativeByCandidateId);
            representativeByCandidateId.put(record.candidateId(), representativeId);
            builders.computeIfAbsent(representativeId, id -> new EntryBuilder(id)).add(record, noveltyResult);
        }

        List<CandidateEntry> entries = builders.values().stream()
            .map(EntryBuilder::build)
            .sorted(Comparator.comparing(CandidateEntry::candidateId))
            .toList();
        return new CandidateStoreReport(
            entries,
            new CandidateStoreMetrics(
                records.size(),
                entries.size(),
                countMergedSupport(entries),
                countByLifecycle(entries),
                countByNovelty(entries)
            )
        );
    }

    CandidateStoreReport write(Path outputDirectory, List<PromotionRecord> promotionRecords) {
        try {
            Files.createDirectories(outputDirectory);
            CandidateStoreReport report = build(promotionRecords);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-candidate-store.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("discovery-candidate-store.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    String renderMarkdown(CandidateStoreReport report) {
        StringBuilder out = new StringBuilder("# Discovery candidate store\n\n");
        out.append("| Candidate | Lifecycle | Promotion | Novelty | Support | Campaigns | Family | Operator | Pack | Rejection |\n");
        out.append("| --- | --- | --- | --- | ---: | --- | --- | --- | --- | --- |\n");
        for (CandidateEntry entry : report.candidates()) {
            out.append("| ").append(escape(entry.candidateId()))
                .append(" | ").append(entry.lifecycleStatus().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(entry.promotionStatus().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(entry.noveltyStatus().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(entry.supportCount())
                .append(" | ").append(escape(String.join(", ", entry.sourceCampaigns())))
                .append(" | ").append(escape(entry.family()))
                .append(" | ").append(escape(orDash(entry.operatorId())))
                .append(" | ").append(escape(orDash(entry.packId())))
                .append(" | ").append(escape(orDash(entry.rejectionReason())))
                .append(" |\n");
        }
        out.append("\n## Metrics\n\n");
        out.append("- totalRecords: ").append(report.metrics().totalRecords()).append('\n');
        out.append("- candidateCount: ").append(report.metrics().candidateCount()).append('\n');
        out.append("- mergedSupportRecords: ").append(report.metrics().mergedSupportRecords()).append('\n');
        out.append("- lifecycleCounts: ").append(renderMap(report.metrics().lifecycleCounts())).append('\n');
        out.append("- noveltyCounts: ").append(renderMap(report.metrics().noveltyCounts())).append('\n');
        out.append("\n## Support examples\n\n");
        for (CandidateEntry entry : report.candidates()) {
            out.append("### ").append(escape(entry.candidateId())).append("\n\n");
            for (ConcreteExample example : entry.concreteExamples()) {
                out.append("- ").append(escape(example.exampleId()))
                    .append(" [").append(example.lifecycleStatus().name().toLowerCase(Locale.ROOT)).append("]")
                    .append(" campaign=").append(escape(example.sourceCampaign()))
                    .append(" novelty=").append(example.noveltyStatus().name().toLowerCase(Locale.ROOT));
                if (!example.matchedCandidateId().isBlank()) {
                    out.append(" matched=").append(escape(example.matchedCandidateId()));
                }
                out.append(" input=`").append(escapeInlineCode(example.inputExpression()))
                    .append("` target=`").append(escapeInlineCode(example.targetExpression()))
                    .append("`\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private NoveltyChecker.Candidate toNoveltyCandidate(PromotionRecord record) {
        return new NoveltyChecker.Candidate(
            record.candidateId(),
            record.family(),
            record.originalExpression(),
            record.discoveredStructure(),
            record.sourceOperator(),
            record.rulePath()
        );
    }

    private String representativeId(
        PromotionRecord record,
        NoveltyChecker.NoveltyResult noveltyResult,
        Map<String, String> representativeByCandidateId
    ) {
        if ((noveltyResult.status() == NoveltyStatus.DUPLICATE
            || noveltyResult.status() == NoveltyStatus.ALPHA_EQUIVALENT)
            && !noveltyResult.matchedCandidateId().isBlank()) {
            return representativeByCandidateId.getOrDefault(
                noveltyResult.matchedCandidateId(),
                noveltyResult.matchedCandidateId()
            );
        }
        return record.candidateId();
    }

    private long countMergedSupport(List<CandidateEntry> entries) {
        return entries.stream()
            .mapToLong(entry -> Math.max(0, entry.supportCount() - 1))
            .sum();
    }

    private Map<String, Long> countByLifecycle(List<CandidateEntry> entries) {
        return entries.stream()
            .collect(Collectors.groupingBy(
                entry -> entry.lifecycleStatus().name().toLowerCase(Locale.ROOT),
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private Map<String, Long> countByNovelty(List<CandidateEntry> entries) {
        return entries.stream()
            .collect(Collectors.groupingBy(
                entry -> entry.noveltyStatus().name().toLowerCase(Locale.ROOT),
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private String renderMap(Map<String, Long> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", ", "{", "}"));
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String escapeInlineCode(String value) {
        return escape(value).replace("`", "\\`");
    }

    private static CandidateLifecycleStatus lifecycleFor(PromotionRecord record) {
        if ("DISAGREE".equalsIgnoreCase(record.oracleStatus()) || record.curatedPathPresent() || record.fallbackUsed()) {
            return CandidateLifecycleStatus.REJECTED;
        }
        if (record.stage().atLeast(PromotionStage.PROMOTED)) {
            return CandidateLifecycleStatus.PROMOTED;
        }
        if (record.stage().atLeast(PromotionStage.VALIDATED)) {
            return CandidateLifecycleStatus.VALIDATED;
        }
        if (record.stage().atLeast(PromotionStage.CANDIDATE)) {
            return CandidateLifecycleStatus.CANDIDATE;
        }
        return CandidateLifecycleStatus.OBSERVED;
    }

    private static String rejectionReason(PromotionRecord record) {
        List<String> reasons = new ArrayList<>();
        if ("DISAGREE".equalsIgnoreCase(record.oracleStatus())) {
            reasons.add("oracle=DISAGREE");
        }
        if (record.curatedPathPresent()) {
            reasons.add("curated-path=true");
        }
        if (record.fallbackUsed()) {
            reasons.add("fallback=true");
        }
        return String.join(", ", reasons);
    }

    enum CandidateLifecycleStatus {
        OBSERVED,
        CANDIDATE,
        VALIDATED,
        PROMOTED,
        REJECTED
    }

    record CandidateStoreReport(List<CandidateEntry> candidates, CandidateStoreMetrics metrics) {
        CandidateStoreReport {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            metrics = metrics == null ? CandidateStoreMetrics.empty() : metrics;
        }
    }

    record CandidateStoreMetrics(
        long totalRecords,
        long candidateCount,
        long mergedSupportRecords,
        Map<String, Long> lifecycleCounts,
        Map<String, Long> noveltyCounts
    ) {
        CandidateStoreMetrics {
            lifecycleCounts = lifecycleCounts == null ? Map.of() : Map.copyOf(lifecycleCounts);
            noveltyCounts = noveltyCounts == null ? Map.of() : Map.copyOf(noveltyCounts);
        }

        static CandidateStoreMetrics empty() {
            return new CandidateStoreMetrics(0, 0, 0, Map.of(), Map.of());
        }
    }

    record CandidateEntry(
        String candidateId,
        String family,
        String inputPattern,
        String targetPattern,
        List<ConcreteExample> concreteExamples,
        List<String> sourceCampaigns,
        List<String> rulePath,
        String operatorId,
        String packId,
        List<String> assumptions,
        List<String> oracleStatuses,
        List<String> ablationStatuses,
        NoveltyStatus noveltyStatus,
        CandidateLifecycleStatus lifecycleStatus,
        PromotionStage promotionStatus,
        String rejectionReason,
        int supportCount,
        List<String> relatedCandidateIds
    ) {
        CandidateEntry {
            family = family == null ? "" : family;
            inputPattern = inputPattern == null ? "" : inputPattern;
            targetPattern = targetPattern == null ? "" : targetPattern;
            concreteExamples = concreteExamples == null ? List.of() : List.copyOf(concreteExamples);
            sourceCampaigns = sourceCampaigns == null ? List.of() : List.copyOf(sourceCampaigns);
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            operatorId = operatorId == null ? "" : operatorId;
            packId = packId == null ? "" : packId;
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            oracleStatuses = oracleStatuses == null ? List.of() : List.copyOf(oracleStatuses);
            ablationStatuses = ablationStatuses == null ? List.of() : List.copyOf(ablationStatuses);
            noveltyStatus = noveltyStatus == null ? NoveltyStatus.UNKNOWN : noveltyStatus;
            lifecycleStatus = lifecycleStatus == null ? CandidateLifecycleStatus.OBSERVED : lifecycleStatus;
            promotionStatus = promotionStatus == null ? PromotionStage.OBSERVED : promotionStatus;
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
            relatedCandidateIds = relatedCandidateIds == null ? List.of() : List.copyOf(relatedCandidateIds);
        }
    }

    record ConcreteExample(
        String exampleId,
        String sourceCampaign,
        String inputExpression,
        String targetExpression,
        CandidateLifecycleStatus lifecycleStatus,
        PromotionStage promotionStatus,
        NoveltyStatus noveltyStatus,
        String matchedCandidateId,
        String oracleStatus,
        String ablationStatus
    ) {
        ConcreteExample {
            exampleId = exampleId == null ? "" : exampleId;
            sourceCampaign = sourceCampaign == null ? "" : sourceCampaign;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            lifecycleStatus = lifecycleStatus == null ? CandidateLifecycleStatus.OBSERVED : lifecycleStatus;
            promotionStatus = promotionStatus == null ? PromotionStage.OBSERVED : promotionStatus;
            noveltyStatus = noveltyStatus == null ? NoveltyStatus.UNKNOWN : noveltyStatus;
            matchedCandidateId = matchedCandidateId == null ? "" : matchedCandidateId;
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
        }
    }

    private static final class EntryBuilder {
        private final String candidateId;
        private String family = "";
        private String inputPattern = "";
        private String targetPattern = "";
        private final List<ConcreteExample> examples = new ArrayList<>();
        private final Set<String> sourceCampaigns = new LinkedHashSet<>();
        private List<String> rulePath = List.of();
        private String operatorId = "";
        private String packId = "";
        private final Set<String> assumptions = new LinkedHashSet<>();
        private final Set<String> oracleStatuses = new LinkedHashSet<>();
        private final Set<String> ablationStatuses = new LinkedHashSet<>();
        private NoveltyStatus noveltyStatus = NoveltyStatus.UNKNOWN;
        private CandidateLifecycleStatus lifecycleStatus = CandidateLifecycleStatus.OBSERVED;
        private PromotionStage promotionStatus = PromotionStage.OBSERVED;
        private final Set<String> rejectionReasons = new LinkedHashSet<>();
        private final Set<String> relatedCandidateIds = new LinkedHashSet<>();

        private EntryBuilder(String candidateId) {
            this.candidateId = candidateId;
        }

        private void add(PromotionRecord record, NoveltyChecker.NoveltyResult noveltyResult) {
            if (family.isBlank()) family = record.family();
            if (inputPattern.isBlank()) inputPattern = record.originalExpression();
            if (targetPattern.isBlank()) targetPattern = record.discoveredStructure();
            if (operatorId.isBlank()) operatorId = record.sourceOperator();
            if (packId.isBlank()) packId = record.sourcePack();
            sourceCampaigns.add(record.sourceCampaign());
            List<String> recordRulePath = record.rulePath();
            if (recordRulePath != null && recordRulePath.size() > rulePath.size()) {
                rulePath = List.copyOf(recordRulePath);
            }
            assumptions.addAll(record.assumptions());
            oracleStatuses.add(record.oracleStatus());
            ablationStatuses.add(record.ablationStatus());
            if (examples.isEmpty() || noveltyStatus == NoveltyStatus.UNKNOWN) {
                noveltyStatus = noveltyResult.status();
            }
            if (!noveltyResult.matchedCandidateId().isBlank()) {
                relatedCandidateIds.add(noveltyResult.matchedCandidateId());
            }
            CandidateLifecycleStatus exampleLifecycle = lifecycleFor(record);
            lifecycleStatus = examples.isEmpty() ? exampleLifecycle : mergeLifecycle(lifecycleStatus, exampleLifecycle);
            promotionStatus = higherPromotionStage(promotionStatus, record.stage());
            String rejectionReason = rejectionReason(record);
            if (!rejectionReason.isBlank()) {
                rejectionReasons.add(rejectionReason);
            }
            examples.add(new ConcreteExample(
                record.candidateId(),
                record.sourceCampaign(),
                record.originalExpression(),
                record.discoveredStructure(),
                exampleLifecycle,
                record.stage(),
                noveltyResult.status(),
                noveltyResult.matchedCandidateId(),
                record.oracleStatus(),
                record.ablationStatus()
            ));
        }

        private CandidateEntry build() {
            return new CandidateEntry(
                candidateId,
                family,
                inputPattern,
                targetPattern,
                examples,
                List.copyOf(sourceCampaigns),
                rulePath,
                operatorId,
                packId,
                List.copyOf(assumptions),
                List.copyOf(oracleStatuses),
                List.copyOf(ablationStatuses),
                noveltyStatus,
                lifecycleStatus,
                promotionStatus,
                String.join(", ", rejectionReasons),
                examples.size(),
                List.copyOf(relatedCandidateIds)
            );
        }

        private CandidateLifecycleStatus mergeLifecycle(CandidateLifecycleStatus left, CandidateLifecycleStatus right) {
            if (left == CandidateLifecycleStatus.REJECTED || right == CandidateLifecycleStatus.REJECTED) {
                return CandidateLifecycleStatus.REJECTED;
            }
            return left.ordinal() >= right.ordinal() ? left : right;
        }

        private PromotionStage higherPromotionStage(PromotionStage left, PromotionStage right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }
}
