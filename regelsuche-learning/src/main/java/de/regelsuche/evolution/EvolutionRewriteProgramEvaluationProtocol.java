package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.json.JsonWriter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed contract for the paired evaluator used by one evolutionary
 * rewrite-program study.
 *
 * <p>The protocol identity is deliberately separate from the TRAIN suite. A
 * suite fixes tasks and budgets; this artifact fixes which information each
 * side receives, how targets and correctness are evaluated, which result paths
 * may receive resource credit, and the implementation expected to realize
 * those semantics.</p>
 */
public record EvolutionRewriteProgramEvaluationProtocol(
    String schema,
    String protocolId,
    EvaluatorProfile evaluatorProfile,
    BaselineContract baselineContract,
    CandidateContract candidateContract,
    TargetRelation targetRelation,
    CorrectnessContract correctnessContract,
    ResourceAttributionContract resourceAttributionContract,
    String implementationClass,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-evaluation-protocol/v1";
    public static final String INFORMATION_PARITY_PROTOCOL_ID =
        "information_parity_exact_rational_v1";
    public static final String INFORMATION_PARITY_IMPLEMENTATION =
        "de.regelsuche.evolution."
            + "ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramEvaluationProtocol {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program evaluation-protocol schema");
        }
        requireId(protocolId, "protocolId");
        Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
        Objects.requireNonNull(baselineContract, "baselineContract");
        Objects.requireNonNull(candidateContract, "candidateContract");
        Objects.requireNonNull(targetRelation, "targetRelation");
        Objects.requireNonNull(correctnessContract, "correctnessContract");
        Objects.requireNonNull(
            resourceAttributionContract, "resourceAttributionContract");
        implementationClass = requireText(
            implementationClass, "implementationClass");
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            protocolId,
            evaluatorProfile,
            baselineContract,
            candidateContract,
            targetRelation,
            correctnessContract,
            resourceAttributionContract,
            implementationClass,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "evaluation-protocol contentHash mismatch");
        }
    }

    /** The only protocol authorized for the #521 flagship TRAIN campaign. */
    public static EvolutionRewriteProgramEvaluationProtocol
            informationParityExactRationalV1() {
        return create(
            INFORMATION_PARITY_PROTOCOL_ID,
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            BaselineContract.ORDINARY_PLUS_FLAT_CANDIDATE_GENOME_RULES,
            CandidateContract.BASELINE_PLUS_COMPILED_REWRITE_PROGRAM,
            TargetRelation.SYNTAX_EXACT,
            CorrectnessContract
                .EXACT_RATIONAL_NORMAL_FORM_PER_PATH_EDGE_WITH_DECLARED_ASSUMPTIONS,
            ResourceAttributionContract
                .CONFIRMED_RETAINED_PATH_REQUIRES_PROGRAM_EDGE,
            INFORMATION_PARITY_IMPLEMENTATION);
    }

    /** Package-visible negative-control protocol used only by contract tests. */
    static EvolutionRewriteProgramEvaluationProtocol testingProtocol(
        String protocolId
    ) {
        return create(
            protocolId,
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            BaselineContract.ORDINARY_PLUS_FLAT_CANDIDATE_GENOME_RULES,
            CandidateContract.BASELINE_PLUS_COMPILED_REWRITE_PROGRAM,
            TargetRelation.SYNTAX_EXACT,
            CorrectnessContract
                .EXACT_RATIONAL_NORMAL_FORM_PER_PATH_EDGE_WITH_DECLARED_ASSUMPTIONS,
            ResourceAttributionContract
                .CONFIRMED_RETAINED_PATH_REQUIRES_PROGRAM_EDGE,
            "de.regelsuche.evolution.testing." + protocolId);
    }

    private static EvolutionRewriteProgramEvaluationProtocol create(
        String protocolId,
        EvaluatorProfile evaluatorProfile,
        BaselineContract baselineContract,
        CandidateContract candidateContract,
        TargetRelation targetRelation,
        CorrectnessContract correctnessContract,
        ResourceAttributionContract resourceAttributionContract,
        String implementationClass
    ) {
        requireId(protocolId, "protocolId");
        implementationClass = requireText(implementationClass, "implementationClass");
        String hash = EvolutionGenome.hash(render(
            protocolId,
            evaluatorProfile,
            baselineContract,
            candidateContract,
            targetRelation,
            correctnessContract,
            resourceAttributionContract,
            implementationClass,
            null));
        return new EvolutionRewriteProgramEvaluationProtocol(
            SCHEMA,
            protocolId,
            evaluatorProfile,
            baselineContract,
            candidateContract,
            targetRelation,
            correctnessContract,
            resourceAttributionContract,
            implementationClass,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            protocolId,
            evaluatorProfile,
            baselineContract,
            candidateContract,
            targetRelation,
            correctnessContract,
            resourceAttributionContract,
            implementationClass,
            contentHash);
    }

    private static String render(
        String protocolId,
        EvaluatorProfile evaluatorProfile,
        BaselineContract baselineContract,
        CandidateContract candidateContract,
        TargetRelation targetRelation,
        CorrectnessContract correctnessContract,
        ResourceAttributionContract resourceAttributionContract,
        String implementationClass,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("protocolId", protocolId)
            .property("evaluatorProfile", evaluatorProfile.name())
            .property("baselineContract", baselineContract.name())
            .property("candidateContract", candidateContract.name())
            .property("targetRelation", targetRelation.name())
            .property("correctnessContract", correctnessContract.name())
            .property(
                "resourceAttributionContract",
                resourceAttributionContract.name())
            .property("implementationClass", implementationClass);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public enum BaselineContract {
        ORDINARY_PLUS_FLAT_CANDIDATE_GENOME_RULES
    }

    public enum CandidateContract {
        BASELINE_PLUS_COMPILED_REWRITE_PROGRAM
    }

    public enum TargetRelation {
        SYNTAX_EXACT
    }

    public enum CorrectnessContract {
        EXACT_RATIONAL_NORMAL_FORM_PER_PATH_EDGE_WITH_DECLARED_ASSUMPTIONS
    }

    public enum ResourceAttributionContract {
        CONFIRMED_RETAINED_PATH_REQUIRES_PROGRAM_EDGE
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
