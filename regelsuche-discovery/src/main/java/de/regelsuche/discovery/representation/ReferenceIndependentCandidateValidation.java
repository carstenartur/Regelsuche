package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.TargetFreeIntrinsicCandidateValidator.IntrinsicValidation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Content-addressed validation companion; historical labels are not inputs. */
public final class ReferenceIndependentCandidateValidation {
    public static final String SCHEMA =
        "reference-independent-candidate-validation/v1";
    public static final String EVIDENCE_SCOPE =
        "ALREADY_PUBLIC_ARCHIVED_MATRIX_NOT_FRESH_HELD_OUT";
    public static final String ORACLE_REVISION =
        "regelsuche-numeric-samples-and-quadratic-coefficients/v1";
    public static final String WORK_AUTHORITY =
        "ADMITTED_ORACLE_CALLS_AND_INPUT_CHARACTERS_NOT_INTERNAL_OPERATIONS";
    public static final String CLAIM_BOUNDARY =
        "Companion validation of an already public frozen candidate matrix. "
            + "The existing oracle uses deterministic numeric samples or "
            + "normalized quadratic coefficients, not external SymPy. "
            + "Its legacy SYMBOLICALLY_VERIFIED status does not establish "
            + "formal proof, primitive replay, original-domain completeness, "
            + "salience, downstream utility, fresh held-out performance or novelty.";
    static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .build();

    private ReferenceIndependentCandidateValidation() {
    }

    /** Separate validation limits; never modifies formation work budgets. */
    public record Budget(int maxOracleCalls, int maxInputCharacters, int timeoutMillis) {
        public Budget {
            if (maxOracleCalls < 0 || maxInputCharacters < 1
                    || maxInputCharacters > 8192 || timeoutMillis < 1
                    || timeoutMillis > 60_000) {
                throw new IllegalArgumentException("invalid validation budget");
            }
        }
    }

    /** An unchanged v1 frozen lineage, including its aggregate declaration. */
    public record Candidate(
        String candidateHash, String expression, List<String> assumptions,
        int depth, List<String> pathExpressions, List<String> pathRuleIds,
        List<String> primitiveRuleIds, boolean equivalencePreserving,
        boolean temporaryComplexityIncrease
    ) {
        public Candidate {
            CandidateEvidence checked = new CandidateEvidence(candidateHash,
                expression, assumptions, depth, pathExpressions, pathRuleIds,
                primitiveRuleIds, equivalencePreserving, temporaryComplexityIncrease);
            assumptions = checked.assumptions();
            pathExpressions = checked.pathExpressions();
            pathRuleIds = checked.pathRuleIds();
            primitiveRuleIds = checked.primitiveRuleIds();
        }

        static Candidate fromFrozen(CandidateEvidence value) {
            return new Candidate(value.candidateHash(), value.expression(),
                value.assumptions(), value.depth(), value.pathExpressions(),
                value.pathRuleIds(), value.primitiveRuleIds(),
                value.equivalencePreserving(), value.temporaryComplexityIncrease());
        }
    }

    /** Work is charged before invocation; failed invocations retain their cost. */
    public record Work(int candidateVisits, int oracleCalls, int completedOracleCalls,
                       long inputCharacters) {
        public Work {
            if (candidateVisits < 0 || oracleCalls < 0 || completedOracleCalls < 0
                    || completedOracleCalls > oracleCalls || oracleCalls > candidateVisits
                    || inputCharacters < 0 || (oracleCalls == 0 && inputCharacters != 0)) {
                throw new IllegalArgumentException("invalid validation work ledger");
            }
        }

        static Work sum(List<Work> values) {
            int visits = 0;
            int calls = 0;
            int completed = 0;
            long characters = 0;
            for (Work value : values) {
                visits = Math.addExact(visits, value.candidateVisits());
                calls = Math.addExact(calls, value.oracleCalls());
                completed = Math.addExact(completed, value.completedOracleCalls());
                characters = Math.addExact(characters, value.inputCharacters());
            }
            return new Work(visits, calls, completed, characters);
        }
    }

    public record Outcome(
        Candidate candidate, String inputHash, String occurrencePath,
        String primitivePathHash, String primitiveReplayStatus,
        String exactSourceEquality, IntrinsicValidation validation,
        String terminalReason, String evidenceStrength, String formalProofStatus,
        String evidenceHash, Work work
    ) {
        public Outcome {
            Objects.requireNonNull(candidate, "candidate");
            requireSha256(inputHash, "inputHash");
            requireSha256(primitivePathHash, "primitivePathHash");
            Objects.requireNonNull(validation, "validation");
            requireText(terminalReason, "terminalReason");
            requireText(evidenceStrength, "evidenceStrength");
            Objects.requireNonNull(work, "work");
            if (!"$".equals(occurrencePath)
                    || !"FROZEN_LINEAGE_NOT_REPLAYED".equals(primitiveReplayStatus)
                    || !"NOT_ESTABLISHED".equals(formalProofStatus)
                    || work.candidateVisits() != 1
                    || !hash(validation).equals(evidenceHash)
                    || !pathHash(candidate).equals(primitivePathHash)) {
                throw new IllegalArgumentException("invalid candidate validation evidence");
            }
        }
    }

    public record Row(
        int sequence, String configurationId, String sourceExpression,
        String sourceHash, String candidateBatchHash, String candidateSetHash,
        String candidateFreezeReceiptHash, String formationWorkHash,
        List<Outcome> outcomes, Work work
    ) {
        public Row {
            if (sequence < 1) {
                throw new IllegalArgumentException("invalid validation row sequence");
            }
            requireText(sourceExpression, "sourceExpression");
            for (String value : List.of(configurationId, sourceHash,
                    candidateBatchHash, candidateSetHash,
                    candidateFreezeReceiptHash, formationWorkHash)) {
                requireSha256(value, "row binding");
            }
            outcomes = List.copyOf(outcomes);
            if (!hash(sourceExpression).equals(sourceHash)
                    || !Work.sum(outcomes.stream().map(Outcome::work).toList()).equals(work)) {
                throw new IllegalArgumentException("validation row work or source mismatch");
            }
            for (Outcome outcome : outcomes) {
                if (!inputHash(configurationId, sourceExpression, outcome.candidate())
                        .equals(outcome.inputHash())) {
                    throw new IllegalArgumentException("candidate input binding mismatch");
                }
            }
        }
    }

    public record Summary(int rows, int candidates, int oracleCalls,
                          int completedOracleCalls, long inputCharacters,
                          Map<String, Integer> terminalReasons) {
        public Summary {
            terminalReasons = Map.copyOf(terminalReasons);
        }

        static Summary derive(List<Row> rows) {
            Work work = Work.sum(rows.stream().map(Row::work).toList());
            Map<String, Integer> reasons = new TreeMap<>();
            rows.stream().flatMap(row -> row.outcomes().stream())
                .forEach(value -> reasons.merge(value.terminalReason(), 1, Math::addExact));
            return new Summary(rows.size(), work.candidateVisits(), work.oracleCalls(),
                work.completedOracleCalls(), work.inputCharacters(), reasons);
        }
    }

    public record Content(
        String schema, String evidenceScope, String repositoryRevision,
        String formationRepositoryRevision, String planHash,
        String candidateFreezeHash, String oracleRevision,
        String workAuthority, Budget budget, List<Row> rows,
        Summary summary, String claimBoundary
    ) {
        public Content {
            if (!SCHEMA.equals(schema) || !EVIDENCE_SCOPE.equals(evidenceScope)
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !formationRepositoryRevision.matches("[0-9a-f]{40}")
                    || !ORACLE_REVISION.equals(oracleRevision)
                    || !WORK_AUTHORITY.equals(workAuthority)
                    || !CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException("invalid companion authority");
            }
            requireSha256(planHash, "planHash");
            requireSha256(candidateFreezeHash, "candidateFreezeHash");
            Objects.requireNonNull(budget, "budget");
            rows = List.copyOf(rows);
            if (rows.size() != 144 || !Summary.derive(rows).equals(summary)
                    || summary.oracleCalls() > budget.maxOracleCalls()) {
                throw new IllegalArgumentException("companion matrix or budget does not balance");
            }
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).sequence() != index + 1) {
                    throw new IllegalArgumentException("companion row sequence mismatch");
                }
            }
        }
    }

    public record Artifact(Content content, String contentHash) {
        public Artifact {
            Objects.requireNonNull(content, "content");
            requireSha256(contentHash, "contentHash");
            if (!hash(content).equals(contentHash)) {
                throw new IllegalArgumentException("companion content hash mismatch");
            }
        }

        static Artifact create(Content content) {
            return new Artifact(content, hash(content));
        }

        public String toCanonicalJson() {
            return canonical(this);
        }

        public static Artifact fromCanonicalJson(String source) {
            try {
                Artifact value = JSON.readValue(source, Artifact.class);
                if (!value.toCanonicalJson().equals(source)) {
                    throw new IllegalArgumentException("companion JSON is not canonical");
                }
                return value;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("invalid companion JSON", exception);
            }
        }
    }

    static String hash(Object value) {
        return KnownStructureCatalog.sha256(canonical(value));
    }

    static String inputHash(String configurationId, String source, Candidate candidate) {
        return hash(List.of(SCHEMA + "/input", configurationId, source, "$", candidate));
    }

    static String pathHash(Candidate candidate) {
        return hash(List.of(SCHEMA + "/primitive-path", candidate.pathExpressions(),
            candidate.pathRuleIds(), candidate.primitiveRuleIds()));
    }
}
