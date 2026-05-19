package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.json.JsonReader;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DefaultTransformationImportService implements TransformationImportService {

    @Override
    public ExportBundle importJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Map<String, Object> root = new JsonReader(json).readObject();
        String schemaVersion = stringValue(root.get("schemaVersion"), ExportBundle.CURRENT_SCHEMA_VERSION);
        List<DiscoveredTransformation> transformations = readList(root.get("transformations"))
            .stream()
            .map(this::asMap)
            .map(this::readTransformation)
            .toList();
        List<RuleCandidate> ruleCandidates = readList(root.get("ruleCandidates"))
            .stream()
            .map(this::asMap)
            .map(this::readCandidate)
            .toList();
        List<ReusableRule> reusableRules = readList(root.get("reusableRules"))
            .stream()
            .map(this::asMap)
            .map(this::readReusableRule)
            .toList();
        return new ExportBundle(schemaVersion, transformations, ruleCandidates, reusableRules);
    }

    private DiscoveredTransformation readTransformation(Map<String, Object> values) {
        Map<String, Object> scores = asMap(values.get("scores"));
        ExpressionScore originalScore = readScore(asMap(scores.get("original")));
        ExpressionScore improvedScore = readScore(asMap(scores.get("improved")));
        List<TransformationStep> steps = readList(values.get("steps"))
            .stream()
            .map(this::asMap)
            .map(this::readStep)
            .toList();
        return new DiscoveredTransformation(
            stringValue(values.get("id"), ""),
            stringValue(values.get("originalExpression"), ""),
            stringValue(values.get("improvedExpression"), ""),
            steps,
            originalScore,
            improvedScore,
            intValue(values.get("totalImprovement"), originalScore.improvementTo(improvedScore)),
            CandidateProofStatus.valueOf(stringValue(values.get("validationStatus"), CandidateProofStatus.OBSERVED.name())),
            parseInstant(values.get("discoveredAt"), Instant.EPOCH),
            stringValue(values.get("canonicalHash"), "")
        );
    }

    private TransformationStep readStep(Map<String, Object> values) {
        return new TransformationStep(
            intValue(values.get("index"), 0),
            stringValue(values.get("beforeExpression"), ""),
            stringValue(values.get("afterExpression"), ""),
            stringValue(values.get("ruleId"), ""),
            RewriteKind.valueOf(stringValue(values.get("ruleKind"), RewriteKind.NORMALIZE.name())),
            intValue(values.get("scoreBefore"), 0),
            intValue(values.get("scoreAfter"), 0),
            booleanValue(values.get("equivalencePreserving"), true),
            stringValue(values.get("explanation"), "")
        );
    }

    private ExpressionScore readScore(Map<String, Object> values) {
        return new ExpressionScore(
            intValue(values.get("stringLength"), 0),
            intValue(values.get("astNodeCount"), 0),
            intValue(values.get("operatorCount"), 0),
            intValue(values.get("nestingDepth"), 0),
            intValue(values.get("recognizedPatternBonus"), 0)
        );
    }

    private RuleCandidate readCandidate(Map<String, Object> values) {
        return new RuleCandidate(
            stringValue(values.get("leftPattern"), ""),
            stringValue(values.get("rightPattern"), ""),
            intValue(values.get("examplesCount"), 0),
            doubleValue(values.get("averageScoreImprovement"), 0d),
            intValue(values.get("maximumScoreImprovement"), 0),
            booleanValue(values.get("equivalenceVerified"), false),
            booleanValue(values.get("generalizationPlausible"), false),
            booleanValue(values.get("containsFreeParameters"), false),
            stringList(values.get("parameterRelations")),
            RuleStatus.valueOf(stringValue(values.get("status"), RuleStatus.NEW.name())),
            CandidateProofStatus.valueOf(stringValue(values.get("proofStatus"), CandidateProofStatus.OBSERVED.name())),
            stringValue(values.get("canonicalHash"), ""),
            stringList(values.get("supportingTransformationIds"))
        );
    }

    private ReusableRule readReusableRule(Map<String, Object> values) {
        return new ReusableRule(
            stringValue(values.get("id"), ""),
            stringValue(values.get("leftPattern"), ""),
            stringValue(values.get("rightPattern"), ""),
            stringList(values.get("parameterRelations")),
            CandidateProofStatus.valueOf(stringValue(values.get("proofStatus"), CandidateProofStatus.OBSERVED.name())),
            RuleStatus.valueOf(stringValue(values.get("knownRuleStatus"), RuleStatus.NEW.name())),
            intValue(values.get("supportingExamples"), 0),
            doubleValue(values.get("averageImprovement"), 0d),
            parseInstant(values.get("createdAt"), Instant.EPOCH),
            stringValue(values.get("canonicalHash"), ""),
            values.get("lastUsedAt") == null ? null : parseInstant(values.get("lastUsedAt"), null),
            intValue(values.get("usageCount"), 0)
        );
    }

    private List<Object> readList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> (Object) o).toList();
        }
        throw new IllegalArgumentException("Expected array, got " + raw);
    }

    private List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("Expected array of strings, got " + raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected object, got " + raw);
    }

    private String stringValue(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    private double doubleValue(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(raw));
    }

    private boolean booleanValue(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private Instant parseInstant(Object raw, Instant fallback) {
        if (raw == null) {
            return fallback;
        }
        return Instant.parse(String.valueOf(raw));
    }
}
