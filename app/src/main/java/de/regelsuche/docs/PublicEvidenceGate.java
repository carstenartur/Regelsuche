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
import java.util.stream.Collectors;

/** Strict gate for candidates that may be shown as public discovery evidence. */
final class PublicEvidenceGate {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    GateReport evaluate(List<PromotionRecord> records) {
        List<PromotionRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        return evaluate(safeRecords, noveltyByCandidateId(safeRecords));
    }

    GateReport evaluate(List<PromotionRecord> records, Map<String, NoveltyStatus> noveltyByCandidate) {
        List<PromotionRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        Map<String, NoveltyStatus> safeNovelty = noveltyByCandidate == null ? Map.of() : Map.copyOf(noveltyByCandidate);
        List<GateDecision> decisions = safeRecords.stream()
            .map(record -> evaluate(record, safeNovelty.getOrDefault(record.candidateId(), NoveltyStatus.UNKNOWN)))
            .sorted(Comparator.comparing(GateDecision::candidateId))
            .toList();
        return new GateReport(
            decisions,
            decisions.stream().filter(GateDecision::accepted).count(),
            decisions.stream().filter(decision -> !decision.accepted()).count()
        );
    }

    GateReport write(Path outputDirectory, List<PromotionRecord> records) {
        List<PromotionRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        return write(outputDirectory, safeRecords, noveltyByCandidateId(safeRecords));
    }

    GateReport write(Path outputDirectory, List<PromotionRecord> records, Map<String, NoveltyStatus> noveltyByCandidate) {
        try {
            Files.createDirectories(outputDirectory);
            GateReport report = evaluate(records, noveltyByCandidate);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("public-evidence-gate.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("public-evidence-rejections.md"),
                renderRejections(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    GateDecision evaluate(PromotionRecord record, NoveltyStatus noveltyStatus) {
        List<String> reasons = new ArrayList<>();
        NoveltyStatus effectiveNovelty = noveltyStatus == null ? NoveltyStatus.UNKNOWN : noveltyStatus;
        boolean success = record.stage().atLeast(PromotionStage.PROMOTED);
        if (!success) {
            reasons.add("success=false");
        }
        if (!record.evidenceExists() || record.rulePath().isEmpty()) {
            reasons.add("pathSource!=REGELSUCHE_SEARCH");
        }
        if (record.curatedPathPresent()) {
            reasons.add("curated-path=true");
        }
        if (record.fallbackUsed()) {
            reasons.add("fallback=true");
        }
        if ("DISAGREE".equalsIgnoreCase(record.oracleStatus())) {
            reasons.add("oracle=DISAGREE");
        }
        if (!record.ablationEvidence().hasStructuredMetrics()) {
            reasons.add("ablation=missing-structured");
        } else if (!record.ablationEvidence().promotionReady()) {
            reasons.add("ablation=" + record.ablationEvidence().ablationStatus());
        }
        if (record.sourceOperator().isBlank()) {
            reasons.add("operator=missing");
        }
        if (record.sourcePack().isBlank()) {
            reasons.add("pack=missing");
        }
        if (!hasVisibleGraphEvidence(record)) {
            reasons.add("visible-graph=insufficient");
        }
        if (!(effectiveNovelty == NoveltyStatus.NEW || effectiveNovelty == NoveltyStatus.VARIANT)) {
            reasons.add("novelty=" + effectiveNovelty.name());
        }
        de.regelsuche.proof.ProofPolicy policy = record.proofPolicy();
        if (policy.requiresConfirmedProofForPublicEvidence()
                && !policy.satisfiedBy(record.proverExecutionStatus())) {
            reasons.add("proof=" + de.regelsuche.proof.ProofPolicy.normaliseExecutionStatus(record.proverExecutionStatus()));
        }
        String pathSource = reasons.stream().anyMatch(reason -> reason.equals("pathSource!=REGELSUCHE_SEARCH"))
            ? "UNKNOWN"
            : "REGELSUCHE_SEARCH";
        return new GateDecision(
            record.candidateId(),
            record.sourceCampaign(),
            effectiveNovelty,
            reasons.isEmpty(),
            pathSource,
            record.stage(),
            record.oracleStatus(),
            record.ablationEvidence().ablationStatus(),
            record.sourceOperator(),
            record.sourcePack(),
            reasons
        );
    }

    String renderRejections(GateReport report) {
        StringBuilder out = new StringBuilder("# Public evidence gate rejections\n\n");
        out.append("| Candidate | Campaign | Stage | Novelty | Rejection reasons |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        List<GateDecision> rejected = report.decisions().stream()
            .filter(decision -> !decision.accepted())
            .toList();
        if (rejected.isEmpty()) {
            out.append("| — | — | — | — | none |\n");
            return out.toString();
        }
        for (GateDecision decision : rejected) {
            out.append("| ").append(escape(decision.candidateId()))
                .append(" | ").append(escape(decision.sourceCampaign()))
                .append(" | ").append(decision.stage().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(decision.noveltyStatus().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(escape(String.join(", ", decision.rejectionReasons())))
                .append(" |\n");
        }
        return out.toString();
    }

    private boolean hasVisibleGraphEvidence(PromotionRecord record) {
        return record.evidenceExists() && !record.rulePath().isEmpty();
    }

    private Map<String, NoveltyStatus> noveltyByCandidateId(List<PromotionRecord> records) {
        DiscoveryCandidateStore.CandidateStoreReport storeReport = new DiscoveryCandidateStore().build(records);
        Map<String, NoveltyStatus> novelty = new LinkedHashMap<>();
        for (DiscoveryCandidateStore.CandidateEntry entry : storeReport.candidates()) {
            novelty.put(entry.candidateId(), entry.noveltyStatus());
            for (DiscoveryCandidateStore.ConcreteExample example : entry.concreteExamples()) {
                novelty.put(example.exampleId(), example.noveltyStatus());
            }
        }
        return novelty;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record GateReport(List<GateDecision> decisions, long acceptedCount, long rejectedCount) {
        GateReport {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }

        List<GateDecision> accepted() {
            return decisions.stream().filter(GateDecision::accepted).toList();
        }

        List<GateDecision> rejected() {
            return decisions.stream().filter(decision -> !decision.accepted()).toList();
        }

        Map<String, Long> rejectionReasonCounts() {
            return decisions.stream()
                .flatMap(decision -> decision.rejectionReasons().stream())
                .collect(Collectors.groupingBy(reason -> reason, LinkedHashMap::new, Collectors.counting()));
        }
    }

    record GateDecision(
        String candidateId,
        String sourceCampaign,
        NoveltyStatus noveltyStatus,
        boolean accepted,
        String pathSource,
        PromotionStage stage,
        String oracleStatus,
        String ablationStatus,
        String operatorId,
        String packId,
        List<String> rejectionReasons
    ) {
        GateDecision {
            candidateId = candidateId == null ? "" : candidateId;
            sourceCampaign = sourceCampaign == null ? "" : sourceCampaign;
            noveltyStatus = noveltyStatus == null ? NoveltyStatus.UNKNOWN : noveltyStatus;
            pathSource = pathSource == null || pathSource.isBlank() ? "UNKNOWN" : pathSource;
            stage = stage == null ? PromotionStage.OBSERVED : stage;
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
            operatorId = operatorId == null ? "" : operatorId;
            packId = packId == null ? "" : packId;
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        }
    }
}
