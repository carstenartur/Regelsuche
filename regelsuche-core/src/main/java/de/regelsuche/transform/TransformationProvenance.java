package de.regelsuche.transform;

import de.regelsuche.json.JsonWriter;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical structural distinction between primitive expansion and verified theory work. */
public sealed interface TransformationProvenance permits TransformationProvenance.PrimitiveRewriteSequence,
        TransformationProvenance.ExactTheoryStep, TransformationProvenance.Sequence {
    String SCHEMA = "regelsuche.transformation-provenance/v1";

    List<String> primitiveRuleIds();
    ExecutionWork work();
    void write(JsonWriter writer);

    default String toCanonicalJson() {
        var writer = new JsonWriter().beginObject().property("schema", SCHEMA);
        write(writer);
        return writer.endObject().toString();
    }

    default String contentHash() {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(toCanonicalJson()));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid Unicode in execution provenance", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    default void requireSource(String expression) {
        String boundSource = switch (this) {
            case PrimitiveRewriteSequence ignored -> expression;
            case ExactTheoryStep step -> step.evidence().binding().sourceExpression();
            case Sequence sequence -> sequence.sourceExpression();
        };
        if (!boundSource.equals(expression)) {
            throw new IllegalArgumentException("transformation differs from its evidence-bound source");
        }
    }

    record PrimitiveRewriteSequence(List<String> primitiveRuleIds, String applicationKey)
            implements TransformationProvenance {
        public PrimitiveRewriteSequence {
            if (primitiveRuleIds == null || primitiveRuleIds.isEmpty()) {
                throw new IllegalArgumentException("primitive provenance requires a real nonempty rule sequence");
            }
            boolean needsTrimming = false;
            for (String value : primitiveRuleIds) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("blank primitive rule");
                needsTrimming |= !value.equals(value.trim());
            }
            primitiveRuleIds = needsTrimming ? primitiveRuleIds.stream().map(String::trim).toList()
                : List.copyOf(primitiveRuleIds);
            if (applicationKey == null || applicationKey.isBlank()) {
                throw new IllegalArgumentException("blank primitive application identity");
            }
        }
        @Override public ExecutionWork work() { return new ExecutionWork(primitiveRuleIds.size(), 0, 0); }
        @Override public void write(JsonWriter writer) {
            writer.property("kind", "PRIMITIVE_REWRITE_SEQUENCE")
                .stringArray("primitiveRuleIds", primitiveRuleIds).property("applicationKey", applicationKey);
        }
    }

    record ExactTheoryStep(ExactTheoryEvidence evidence) implements TransformationProvenance {
        public ExactTheoryStep { Objects.requireNonNull(evidence, "verified evidence"); }
        @Override public List<String> primitiveRuleIds() { return List.of(); }
        @Override public ExecutionWork work() { return new ExecutionWork(0, 1, evidence.binding().canonicalWorkUnits()); }
        @Override public void write(JsonWriter writer) {
            var bound = evidence.binding();
            writer.property("kind", "EXACT_THEORY_STEP")
                .property("sourceExpression", bound.sourceExpression())
                .property("transformedExpression", bound.transformedExpression())
                .property("theoryStepId", bound.theoryStepId()).property("evidenceHash", bound.evidenceHash())
                .property("receiptArtifactId", bound.receiptArtifactId()).property("runArtifactId", bound.runArtifactId())
                .property("canonicalWorkUnits", bound.canonicalWorkUnits())
                .stringArray("assumptions", List.of()).property("canonicalEvidenceJson", bound.canonicalEvidenceJson());
        }
    }

    /** Ordered, source-bound composition retaining every application and intermediate expression. */
    record Sequence(String sourceExpression, List<Transformation> steps) implements TransformationProvenance {
        public Sequence {
            if (sourceExpression == null || sourceExpression.isBlank()) throw new IllegalArgumentException("blank source");
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("empty execution sequence");
            String current = sourceExpression;
            ExecutionWork work = ExecutionWork.ZERO;
            for (Transformation step : steps) {
                step.provenance().requireSource(current);
                current = step.transformedExpression();
                work = work.plus(step.executionWork());
            }
        }
        @Override public List<String> primitiveRuleIds() {
            return steps.stream().flatMap(step -> step.primitiveRuleIds().stream()).toList();
        }
        @Override public ExecutionWork work() {
            return steps.stream().map(Transformation::executionWork).reduce(ExecutionWork.ZERO, ExecutionWork::plus);
        }
        @Override public void write(JsonWriter writer) {
            writer.property("kind", "SEQUENCE").property("sourceExpression", sourceExpression)
                .array("steps", array -> steps.forEach(step -> array.objectValue(item -> {
                    item.property("rule", step.rule()).property("output", step.transformedExpression())
                        .property("applicationKey", step.applicationKey()).property("kind", step.kind().name())
                        .property("mayIncreaseComplexity", step.mayIncreaseComplexity())
                        .property("estimatedCostDelta", step.estimatedCostDelta())
                        .property("equivalencePreserving", step.equivalencePreservingByConstruction())
                        .stringArray("assumptions", step.assumptions()).property("packId", step.packId())
                        .property("license", step.license()).property("provenance", step.provenance().toCanonicalJson());
                })));
        }
    }
}
