package de.regelsuche.api.searchgraph;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.PathReplayDto;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.MacroRuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for {@link SearchGraphRecord} (full session: graph, replays,
 * macro rules, identities, exports, timestamp, search profile, domains).
 *
 * <p>Self-contained: relies only on the project's tiny
 * {@link JsonWriter}/{@link JsonReader}, no external dependency.</p>
 */
public final class SearchGraphRecordCodec {

    private SearchGraphRecordCodec() {
    }

    // ============================================================ toJson
    public static String toJson(SearchGraphRecord record) {
        Objects.requireNonNull(record, "record");
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("id", record.id());
        writer.property("createdAt", record.createdAt().toString());
        writer.property("searchProfile", record.searchProfile());
        writer.stringArray("domains", record.domains());
        writer.object("graph", graph -> writeGraph(graph, record.graph()));
        writer.array("replays", w -> record.replays().forEach(replay -> w.objectValue(inner -> writeReplay(inner, replay))));
        writer.array("macroRules", w -> record.macroRules().forEach(macro -> w.objectValue(inner -> writeMacro(inner, macro))));
        writer.array("identities", w -> record.identities().forEach(identity -> w.objectValue(inner -> writeIdentity(inner, identity))));
        writer.object("exports", exports -> record.exports().forEach(exports::property));
        writer.endObject();
        return writer.toString();
    }

    private static void writeGraph(JsonWriter writer, SearchGraphDto dto) {
        writer.array("nodes", w -> dto.nodes().forEach(node -> w.objectValue(inner -> {
            inner.property("id", node.id());
            inner.property("expression", node.expression());
            inner.property("latex", node.latex());
            inner.property("score", node.score());
            inner.property("depth", node.depth());
            inner.property("visitedCount", node.visitedCount());
            inner.property("isBest", node.isBest());
            inner.property("isDeadEnd", node.isDeadEnd());
            inner.property("candidateStatus", node.candidateStatus().name());
            inner.property("clusterId", node.clusterId());
        })));
        writer.array("edges", w -> dto.edges().forEach(edge -> w.objectValue(inner -> {
            inner.property("from", edge.from());
            inner.property("to", edge.to());
            inner.property("ruleId", edge.ruleId());
            inner.property("ruleLatex", edge.ruleLatex());
            inner.property("ruleKind", edge.ruleKind().name());
            inner.property("scoreDelta", edge.scoreDelta());
            inner.stringArray("assumptions", edge.assumptions());
            inner.stringArray("pathIds", edge.pathIds());
            inner.property("equivalencePreserving", edge.equivalencePreserving());
        })));
        writer.array("clusters", w -> dto.clusters().forEach(cluster -> w.objectValue(inner -> {
            inner.property("id", cluster.id());
            inner.property("label", cluster.label());
            inner.property("type", cluster.type().name());
            inner.stringArray("nodeIds", cluster.nodeIds());
            inner.stringArray("supportingPathIds", cluster.supportingPathIds());
            inner.property("cohesionScore", cluster.cohesionScore());
        })));
        writer.object("stats", stats -> {
            SearchGraphStatsDto s = dto.stats();
            stats.property("nodesVisited", s.nodesVisited());
            stats.property("edgesGenerated", s.edgesGenerated());
            stats.property("deadEnds", s.deadEnds());
            stats.property("bestScore", s.bestScore());
            stats.property("averageBranchingFactor", s.averageBranchingFactor());
            stats.property("maxDepthReached", s.maxDepthReached());
            stats.object("ruleUsageFrequency", rules -> s.ruleUsageFrequency().forEach(rules::property));
            stats.stringArray("mostUsefulRules", s.mostUsefulRules());
            stats.property("candidateCount", s.candidateCount());
            stats.property("macroRuleCount", s.macroRuleCount());
        });
    }

    private static void writeReplay(JsonWriter writer, PathReplayDto replay) {
        writer.property("pathId", replay.pathId());
        writer.property("alignedDerivationLatex", replay.alignedDerivationLatex());
        writer.array("steps", w -> replay.steps().forEach(step -> w.objectValue(inner -> {
            inner.property("stepIndex", step.stepIndex());
            inner.property("fromExpression", step.fromExpression());
            inner.property("fromLatex", step.fromLatex());
            inner.property("toExpression", step.toExpression());
            inner.property("toLatex", step.toLatex());
            inner.property("ruleId", step.ruleId());
            inner.property("ruleExplanation", step.ruleExplanation());
            inner.property("scoreDelta", step.scoreDelta());
            inner.property("equivalencePreserving", step.equivalencePreserving());
        })));
    }

    private static void writeMacro(JsonWriter writer, MacroRuleCandidate macro) {
        writer.property("id", macro.id());
        writer.stringArray("ruleIdSequence", macro.ruleIdSequence());
        writer.property("occurrences", macro.occurrences());
        writer.property("leftPattern", macro.leftPattern());
        writer.property("rightPattern", macro.rightPattern());
        writer.property("compressionRatio", macro.compressionRatio());
        writer.property("proofStatus", macro.proofStatus().name());
        writer.stringArray("supportingTransformationIds", macro.supportingTransformationIds());
    }

    private static void writeIdentity(JsonWriter writer, IdentityReportDto identity) {
        writer.property("id", identity.id());
        writer.property("leftPattern", identity.leftPattern());
        writer.property("rightPattern", identity.rightPattern());
        writer.stringArray("ruleIdSequence", identity.ruleIdSequence());
        writer.property("occurrences", identity.occurrences());
        writer.property("compressionRatio", identity.compressionRatio());
        writer.property("proofStatus", identity.proofStatus().name());
        writer.property("knownRuleStatus", identity.knownRuleStatus().name());
        writer.stringArray("supportingTransformationIds", identity.supportingTransformationIds());
    }

    // ============================================================ fromJson
    public static SearchGraphRecord fromJson(String json) {
        Objects.requireNonNull(json, "json");
        return fromMap(new JsonReader(json).readObject());
    }

    @SuppressWarnings("unchecked")
    public static SearchGraphRecord fromMap(Map<String, Object> root) {
        SearchGraphDto graph = readGraph(asMap(root.get("graph")));
        List<PathReplayDto> replays = readList(root.get("replays")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(SearchGraphRecordCodec::readReplay)
            .toList();
        List<MacroRuleCandidate> macros = readList(root.get("macroRules")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(SearchGraphRecordCodec::readMacro)
            .toList();
        List<IdentityReportDto> identities = readList(root.get("identities")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(SearchGraphRecordCodec::readIdentity)
            .toList();
        Map<String, String> exports = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(root.get("exports")).entrySet()) {
            exports.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return new SearchGraphRecord(
            stringValue(root.get("id"), ""),
            parseInstant(root.get("createdAt"), Instant.EPOCH),
            stringValue(root.get("searchProfile"), ""),
            stringList(root.get("domains")),
            graph,
            replays,
            macros,
            identities,
            exports
        );
    }

    private static SearchGraphDto readGraph(Map<String, Object> values) {
        List<SearchGraphNodeDto> nodes = readList(values.get("nodes")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(m -> new SearchGraphNodeDto(
                stringValue(m.get("id"), ""),
                stringValue(m.get("expression"), ""),
                stringValue(m.get("latex"), ""),
                intValue(m.get("score"), 0),
                intValue(m.get("depth"), 0),
                intValue(m.get("visitedCount"), 0),
                booleanValue(m.get("isBest"), false),
                booleanValue(m.get("isDeadEnd"), false),
                CandidateProofStatus.valueOf(stringValue(m.get("candidateStatus"), CandidateProofStatus.OBSERVED.name())),
                stringValue(m.get("clusterId"), "")
            ))
            .toList();
        List<SearchGraphEdgeDto> edges = readList(values.get("edges")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(m -> {
                String ruleId = stringValue(m.get("ruleId"), "");
                String ruleLatex = stringValue(m.get("ruleLatex"),
                    de.regelsuche.export.MathPresentation.DEFAULT.ruleLatex(ruleId));
                return new SearchGraphEdgeDto(
                    stringValue(m.get("from"), ""),
                    stringValue(m.get("to"), ""),
                    ruleId,
                    ruleLatex,
                    RewriteKind.valueOf(stringValue(m.get("ruleKind"), RewriteKind.NORMALIZE.name())),
                    intValue(m.get("scoreDelta"), 0),
                    stringList(m.get("assumptions")),
                    stringList(m.get("pathIds")),
                    booleanValue(m.get("equivalencePreserving"), true)
                );
            })
            .toList();
        List<SearchGraphClusterDto> clusters = readList(values.get("clusters")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(m -> new SearchGraphClusterDto(
                stringValue(m.get("id"), ""),
                stringValue(m.get("label"), ""),
                ClusterType.valueOf(stringValue(m.get("type"), ClusterType.RULE_USAGE.name())),
                stringList(m.get("nodeIds")),
                stringList(m.get("supportingPathIds")),
                doubleValue(m.get("cohesionScore"), 0.0)
            ))
            .toList();
        Map<String, Object> stats = asMap(values.get("stats"));
        Map<String, Integer> ruleUsage = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(stats.get("ruleUsageFrequency")).entrySet()) {
            ruleUsage.put(entry.getKey(), intValue(entry.getValue(), 0));
        }
        SearchGraphStatsDto statsDto = new SearchGraphStatsDto(
            intValue(stats.get("nodesVisited"), 0),
            intValue(stats.get("edgesGenerated"), 0),
            intValue(stats.get("deadEnds"), 0),
            intValue(stats.get("bestScore"), 0),
            doubleValue(stats.get("averageBranchingFactor"), 0.0),
            intValue(stats.get("maxDepthReached"), 0),
            ruleUsage,
            stringList(stats.get("mostUsefulRules")),
            intValue(stats.get("candidateCount"), 0),
            intValue(stats.get("macroRuleCount"), 0)
        );
        return new SearchGraphDto(nodes, edges, clusters, statsDto);
    }

    private static PathReplayDto readReplay(Map<String, Object> values) {
        List<PathReplayDto.ReplayStep> steps = readList(values.get("steps")).stream()
            .map(SearchGraphRecordCodec::asMap)
            .map(m -> new PathReplayDto.ReplayStep(
                intValue(m.get("stepIndex"), 0),
                stringValue(m.get("fromExpression"), ""),
                stringValue(m.get("fromLatex"), ""),
                stringValue(m.get("toExpression"), ""),
                stringValue(m.get("toLatex"), ""),
                stringValue(m.get("ruleId"), ""),
                stringValue(m.get("ruleExplanation"), ""),
                intValue(m.get("scoreDelta"), 0),
                booleanValue(m.get("equivalencePreserving"), true)
            ))
            .toList();
        String pathId = stringValue(values.get("pathId"), "?");
        Object persisted = values.get("alignedDerivationLatex");
        if (persisted instanceof String s && !s.isBlank()) {
            return new PathReplayDto(pathId, steps, s);
        }
        return new PathReplayDto(pathId, steps);
    }

    private static MacroRuleCandidate readMacro(Map<String, Object> values) {
        return new MacroRuleCandidate(
            stringValue(values.get("id"), "?"),
            stringList(values.get("ruleIdSequence")),
            intValue(values.get("occurrences"), 0),
            stringValue(values.get("leftPattern"), ""),
            stringValue(values.get("rightPattern"), ""),
            doubleValue(values.get("compressionRatio"), 0.0),
            CandidateProofStatus.valueOf(stringValue(values.get("proofStatus"), CandidateProofStatus.OBSERVED.name())),
            stringList(values.get("supportingTransformationIds"))
        );
    }

    private static IdentityReportDto readIdentity(Map<String, Object> values) {
        return new IdentityReportDto(
            stringValue(values.get("id"), "?"),
            stringValue(values.get("leftPattern"), ""),
            stringValue(values.get("rightPattern"), ""),
            stringList(values.get("ruleIdSequence")),
            intValue(values.get("occurrences"), 0),
            doubleValue(values.get("compressionRatio"), 0.0),
            CandidateProofStatus.valueOf(stringValue(values.get("proofStatus"), CandidateProofStatus.OBSERVED.name())),
            RuleStatus.valueOf(stringValue(values.get("knownRuleStatus"), RuleStatus.NEW.name())),
            stringList(values.get("supportingTransformationIds"))
        );
    }

    // ============================================================ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected object, got " + raw);
    }

    private static List<Object> readList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new IllegalArgumentException("Expected array, got " + raw);
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("Expected array of strings, got " + raw);
    }

    private static String stringValue(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double doubleValue(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static Instant parseInstant(Object raw, Instant fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
