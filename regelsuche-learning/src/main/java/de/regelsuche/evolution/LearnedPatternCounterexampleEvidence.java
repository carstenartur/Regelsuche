package de.regelsuche.evolution;

import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.hash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireHash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireRevision;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireText;

import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Content-addressed, deterministically replayable counterexample-search evidence. */
public record LearnedPatternCounterexampleEvidence(
    String schema,
    String engineId,
    String genomeHash,
    String geneId,
    String repositoryRevision,
    String sourcePattern,
    String targetPattern,
    String leftExpression,
    String rightExpression,
    BudgetEvidence budget,
    CounterexampleSearchService.Status status,
    List<String> attemptedSources,
    List<String> inferredAssumptions,
    String explanation,
    String resultHash,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.learned-pattern-rule-counterexample-evidence/v1";
    public static final String ENGINE_ID =
        "regelsuche.deterministic-counterexample-search/pattern-authorization-v1";

    public LearnedPatternCounterexampleEvidence {
        if (!SCHEMA.equals(schema) || !ENGINE_ID.equals(engineId)) {
            throw new IllegalArgumentException(
                "unsupported counterexample evidence identity");
        }
        requireHash(genomeHash, "genomeHash");
        requireText(geneId, "geneId");
        requireRevision(repositoryRevision, "repositoryRevision");
        requireText(sourcePattern, "sourcePattern");
        requireText(targetPattern, "targetPattern");
        requireText(leftExpression, "leftExpression");
        requireText(rightExpression, "rightExpression");
        budget = Objects.requireNonNull(budget, "budget");
        status = Objects.requireNonNull(status, "status");
        attemptedSources = List.copyOf(
            Objects.requireNonNull(attemptedSources, "attemptedSources"));
        inferredAssumptions = List.copyOf(
            Objects.requireNonNull(inferredAssumptions, "inferredAssumptions"));
        requireText(explanation, "explanation");
        requireHash(resultHash, "resultHash");
        requireHash(contentHash, "contentHash");
        String expected = hash(payload(
            schema,
            engineId,
            genomeHash,
            geneId,
            repositoryRevision,
            sourcePattern,
            targetPattern,
            leftExpression,
            rightExpression,
            budget,
            status,
            attemptedSources,
            inferredAssumptions,
            explanation,
            resultHash));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "counterexample evidence contentHash mismatch");
        }
    }

    static LearnedPatternCounterexampleEvidence create(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        String repositoryRevision,
        String sourcePattern,
        String targetPattern,
        String leftExpression,
        String rightExpression,
        CounterexampleSearchService.CounterexampleBudget budget,
        CounterexampleSearchService.CounterexampleSearchResult result
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(gene, "gene");
        requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(result, "result");
        BudgetEvidence retainedBudget = BudgetEvidence.fromBudget(budget);
        String retainedResultHash = resultHash(result);
        String contentHash = hash(payload(
            SCHEMA,
            ENGINE_ID,
            genome.contentHash(),
            gene.geneId(),
            repositoryRevision,
            sourcePattern,
            targetPattern,
            leftExpression,
            rightExpression,
            retainedBudget,
            result.status(),
            result.attemptedSources(),
            result.inferredAssumptions(),
            result.explanation(),
            retainedResultHash));
        return new LearnedPatternCounterexampleEvidence(
            SCHEMA,
            ENGINE_ID,
            genome.contentHash(),
            gene.geneId(),
            repositoryRevision,
            sourcePattern,
            targetPattern,
            leftExpression,
            rightExpression,
            retainedBudget,
            result.status(),
            result.attemptedSources(),
            result.inferredAssumptions(),
            result.explanation(),
            retainedResultHash,
            contentHash);
    }

    public static LearnedPatternCounterexampleEvidence fromCanonicalJson(
        String json
    ) {
        return LearnedPatternAuthorizationJson.read(
            json, LearnedPatternCounterexampleEvidence.class,
            "counterexample evidence");
    }

    public String toCanonicalJson() {
        return LearnedPatternAuthorizationJson.write(this);
    }

    void requireSubject(
        String expectedGenomeHash,
        String expectedGeneId,
        String expectedRepositoryRevision,
        String expectedSourcePattern,
        String expectedTargetPattern,
        String expectedLeftExpression,
        String expectedRightExpression
    ) {
        if (!genomeHash.equals(expectedGenomeHash)
                || !geneId.equals(expectedGeneId)
                || !repositoryRevision.equals(expectedRepositoryRevision)
                || !sourcePattern.equals(expectedSourcePattern)
                || !targetPattern.equals(expectedTargetPattern)
                || !leftExpression.equals(expectedLeftExpression)
                || !rightExpression.equals(expectedRightExpression)) {
            throw new IllegalArgumentException(
                "counterexample evidence subject/revision mismatch");
        }
    }

    static String resultHash(
        CounterexampleSearchService.CounterexampleSearchResult result
    ) {
        StringBuilder material = new StringBuilder();
        append(material, result.status().name());
        appendList(material, result.inferredAssumptions());
        appendList(material, result.attemptedSources());
        append(material, result.explanation());
        if (result.counterexample().isPresent()) {
            CounterexampleSearchService.Counterexample counterexample =
                result.counterexample().orElseThrow();
            append(material, "COUNTEREXAMPLE");
            appendList(material, counterexample.assignments());
            append(material, counterexample.leftValue());
            append(material, counterexample.rightValue());
        } else {
            append(material, "NO_COUNTEREXAMPLE");
        }
        for (CounterexampleSearchService.TypedAssumption assumption
                : result.typedAssumptions()) {
            append(material, assumption.kind().name());
            append(material, assumption.normalizedPredicate());
            append(material, assumption.subjectExpression());
            appendList(material, assumption.affectedVariables());
            appendList(material, assumption.evidenceSources());
            for (CounterexampleSearchService.ProofEncoding proof
                    : assumption.proofEncodings()) {
                append(material, proof.dialect());
                append(material, proof.expression());
            }
            append(material, assumption.classification().name());
        }
        return EvolutionGenome.hash(material.toString());
    }

    private static Map<String, Object> payload(
        String schema,
        String engineId,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        String sourcePattern,
        String targetPattern,
        String leftExpression,
        String rightExpression,
        BudgetEvidence budget,
        CounterexampleSearchService.Status status,
        List<String> attemptedSources,
        List<String> inferredAssumptions,
        String explanation,
        String resultHash
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("attemptedSources", attemptedSources);
        value.put("budget", budget);
        value.put("engineId", engineId);
        value.put("explanation", explanation);
        value.put("geneId", geneId);
        value.put("genomeHash", genomeHash);
        value.put("inferredAssumptions", inferredAssumptions);
        value.put("leftExpression", leftExpression);
        value.put("repositoryRevision", repositoryRevision);
        value.put("resultHash", resultHash);
        value.put("rightExpression", rightExpression);
        value.put("schema", schema);
        value.put("sourcePattern", sourcePattern);
        value.put("status", status);
        value.put("targetPattern", targetPattern);
        return value;
    }

    private static void appendList(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    /** Complete counterexample-search budget retained by the evidence. */
    public record BudgetEvidence(
        int numericRandomSamples,
        boolean includeEdgeCases,
        boolean includeMatrixAssignments,
        long randomSeed,
        boolean includeComplexAssignments,
        boolean includeRationalAssignments,
        int maxMatrixDimension,
        long timeoutMillis
    ) {
        public BudgetEvidence {
            // Validate constructor parameters directly. Record fields are assigned
            // only after the compact-constructor body has completed.
            new CounterexampleSearchService.CounterexampleBudget(
                numericRandomSamples,
                includeEdgeCases,
                includeMatrixAssignments,
                randomSeed,
                includeComplexAssignments,
                includeRationalAssignments,
                maxMatrixDimension,
                timeoutMillis);
        }

        static BudgetEvidence fromBudget(
            CounterexampleSearchService.CounterexampleBudget budget
        ) {
            Objects.requireNonNull(budget, "budget");
            return new BudgetEvidence(
                budget.numericRandomSamples(),
                budget.includeEdgeCases(),
                budget.includeMatrixAssignments(),
                budget.randomSeed(),
                budget.includeComplexAssignments(),
                budget.includeRationalAssignments(),
                budget.maxMatrixDimension(),
                budget.timeoutMillis());
        }

        CounterexampleSearchService.CounterexampleBudget toBudget() {
            return new CounterexampleSearchService.CounterexampleBudget(
                numericRandomSamples,
                includeEdgeCases,
                includeMatrixAssignments,
                randomSeed,
                includeComplexAssignments,
                includeRationalAssignments,
                maxMatrixDimension,
                timeoutMillis);
        }
    }
}
