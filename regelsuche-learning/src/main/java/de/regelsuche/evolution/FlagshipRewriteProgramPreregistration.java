package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen, pre-execution source for the proof-carrying self-improvement flagship
 * experiment tracked in #521.
 */
public final class FlagshipRewriteProgramPreregistration {
    public static final String SCHEMA =
        "regelsuche.flagship-rewrite-program-preregistration/v1";
    public static final String RECEIPT_SCHEMA =
        "regelsuche.flagship-rewrite-program-freeze-receipt/v1";
    public static final String STUDY_ID =
        "proof_carrying_symbolic_self_improvement_2026_v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern IDENTIFIER =
        Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\b");
    private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
        "sin", "cos", "tan", "sqrt", "abs", "log", "exp", "matrix");

    private FlagshipRewriteProgramPreregistration() {
    }

    public static Preregistration create() {
        List<FlagshipCase> cases = cases();
        List<PrimitiveRule> primitives = primitives();
        ProgramGrammar grammar = grammar(primitives);
        PopulationContract population = population();
        List<FitnessWeight> fitness = fitnessWeights();
        List<Baseline> baselines = baselines();
        AcceptanceCriteria acceptance = acceptance();
        ResourceContract resources = resources();
        Preregistration withoutHash = new Preregistration(
            SCHEMA,
            STUDY_ID,
            "ASSUMPTION_SENSITIVE_RATIONAL_AND_POLYNOMIAL_REWRITE_PROGRAMS",
            "SYNTHESIZE_EXECUTABLE_PROGRAM_TOPOLOGY_WITH_INFORMATION_PARITY_BASELINE",
            cases,
            primitives,
            grammar,
            population,
            fitness,
            baselines,
            acceptance,
            resources,
            "NOT_STARTED",
            "SEALED_UNTIL_FROZEN_VALIDATION_SELECTION",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            null);
        String hash = EvolutionGenome.hash(withoutHash.canonicalPayload());
        return new Preregistration(
            withoutHash.schema(),
            withoutHash.studyId(),
            withoutHash.domain(),
            withoutHash.primaryClaim(),
            withoutHash.cases(),
            withoutHash.primitives(),
            withoutHash.grammar(),
            withoutHash.population(),
            withoutHash.fitnessWeights(),
            withoutHash.baselines(),
            withoutHash.acceptance(),
            withoutHash.resources(),
            withoutHash.executionStatus(),
            withoutHash.finalTestStatus(),
            withoutHash.proofStatus(),
            withoutHash.externalNoveltyStatus(),
            withoutHash.promotionStatus(),
            withoutHash.publicEvidenceStatus(),
            hash);
    }

    public static FreezeReceipt freezeReceipt(
        Preregistration preregistration,
        String repositoryRevision
    ) {
        Objects.requireNonNull(preregistration, "preregistration");
        EvolutionGenome.requireSha256(
            repositoryRevision, "repositoryRevision");
        FreezeReceipt withoutHash = new FreezeReceipt(
            RECEIPT_SCHEMA,
            preregistration.studyId(),
            preregistration.contentHash(),
            repositoryRevision,
            preregistration.splitCases(Split.TRAIN).size(),
            preregistration.splitCases(Split.VALIDATION).size(),
            preregistration.splitCases(Split.FINAL_TEST).size(),
            0,
            0,
            0,
            "NO_EVALUATED_RESULTS_EXIST",
            "NOT_STARTED",
            "NOT_EVALUATED",
            "SEALED",
            false,
            null);
        String hash = EvolutionGenome.hash(withoutHash.canonicalPayload());
        return new FreezeReceipt(
            withoutHash.schema(),
            withoutHash.studyId(),
            withoutHash.preregistrationHash(),
            withoutHash.repositoryRevision(),
            withoutHash.trainCaseCount(),
            withoutHash.validationCaseCount(),
            withoutHash.finalTestCaseCount(),
            withoutHash.executedTrainEvaluations(),
            withoutHash.executedValidationEvaluations(),
            withoutHash.executedFinalTestEvaluations(),
            withoutHash.resultExistence(),
            withoutHash.trainStatus(),
            withoutHash.validationStatus(),
            withoutHash.finalTestStatus(),
            withoutHash.publicationAuthorized(),
            hash);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <output-directory> <sha256:repository-revision>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Preregistration preregistration = create();
        FreezeReceipt receipt = freezeReceipt(preregistration, args[1]);
        Files.writeString(
            output.resolve("flagship-preregistration.json"),
            preregistration.toCanonicalJson() + "\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            output.resolve("flagship-freeze-receipt.json"),
            receipt.toCanonicalJson() + "\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            output.resolve("flagship-preregistration.md"),
            markdown(preregistration, receipt),
            StandardCharsets.UTF_8);
    }

    private static List<FlagshipCase> cases() {
        List<FlagshipCase> result = new ArrayList<>();

        result.add(positive(
            "train_direct_factor_cancellation",
            "train_direct_product_pole",
            Split.TRAIN,
            "(x*a)/(x*b)",
            "a/b",
            List.of("x != 0", "b != 0")));
        result.add(positive(
            "train_affine_factor_cancellation",
            "train_affine_shift_pole",
            Split.TRAIN,
            "((u+2)*p)/((u+2)*q)",
            "p/q",
            List.of("u+2 != 0", "q != 0")));
        result.add(positive(
            "train_square_difference_bridge",
            "train_symbolic_square_bridge",
            Split.TRAIN,
            "(r^2-s^2)/(r-s)",
            "r+s",
            List.of("r-s != 0")));
        result.add(positive(
            "train_shared_denominator_division",
            "train_divide_equal_denominators",
            Split.TRAIN,
            "(m/n)/(p/n)",
            "m/p",
            List.of("n != 0", "p != 0")));
        result.add(positive(
            "train_equal_denominator_collection",
            "train_add_equal_denominators",
            Split.TRAIN,
            "a/x+b/x",
            "(a+b)/x",
            List.of("x != 0")));
        result.add(positive(
            "train_identity_then_cancellation",
            "train_normalize_before_cancel",
            Split.TRAIN,
            "((z+1)*1)/((z+1)*t)",
            "1/t",
            List.of("z+1 != 0", "t != 0")));

        result.add(positive(
            "validation_parameterized_square_bridge",
            "validation_scaled_square_bridge",
            Split.VALIDATION,
            "(k^2*x^2-k^2*y^2)/(k*x-k*y)",
            "k*x+k*y",
            List.of("k*x-k*y != 0")));
        result.add(positive(
            "validation_subtractive_affine_cancel",
            "validation_subtractive_shift_pole",
            Split.VALIDATION,
            "((v-3)*a)/((v-3)*b)",
            "a/b",
            List.of("v-3 != 0", "b != 0")));
        result.add(positive(
            "validation_nested_shifted_fraction",
            "validation_nested_shared_denominator",
            Split.VALIDATION,
            "(a/(x+1))/(b/(x+1))",
            "a/b",
            List.of("x+1 != 0", "b != 0")));
        result.add(positive(
            "validation_normalize_product_cancel",
            "validation_identity_product_bridge",
            Split.VALIDATION,
            "((y+0)*(t+1))/((t+1)*1)",
            "y",
            List.of("t+1 != 0")));
        result.add(positive(
            "validation_negative_denominator_collection",
            "validation_signed_denominator",
            Split.VALIDATION,
            "a/(-x)+b/(-x)",
            "-(a+b)/x",
            List.of("x != 0")));
        result.add(newCase(
            "validation_missing_affine_pole",
            "validation_negative_missing_pole",
            Split.VALIDATION,
            CaseKind.NEGATIVE_MISSING_ASSUMPTION,
            "((w+4)*(a+b))/((w+4)*c)",
            "(a+b)/c",
            List.of("c != 0"),
            ExpectedDisposition.MUST_BLOCK));

        result.add(positive(
            "final_nested_product_fraction",
            "final_nested_rational_composition",
            Split.FINAL_TEST,
            "((a*b)/(c*d))/((a*e)/(c*d))",
            "b/e",
            List.of("a != 0", "c != 0", "d != 0", "e != 0")));
        result.add(positive(
            "final_shifted_nested_fraction",
            "final_nested_shift_composition",
            Split.FINAL_TEST,
            "(p/(q+r))/(s/(q+r))",
            "p/s",
            List.of("q+r != 0", "s != 0")));
        result.add(positive(
            "final_literal_square_difference",
            "final_literal_factor_bridge",
            Split.FINAL_TEST,
            "(x^2-9)/(x-3)",
            "x+3",
            List.of("x-3 != 0")));
        result.add(positive(
            "final_scaled_square_difference",
            "final_scaled_factor_bridge",
            Split.FINAL_TEST,
            "(4*u^2-v^2)/(2*u-v)",
            "2*u+v",
            List.of("2*u-v != 0")));
        result.add(positive(
            "final_composite_common_denominator",
            "final_composite_denominator_collection",
            Split.FINAL_TEST,
            "a/(x*y)+b/(x*y)",
            "(a+b)/(x*y)",
            List.of("x != 0", "y != 0")));
        result.add(positive(
            "final_common_denominator_then_factor",
            "final_cross_denominator_factor",
            Split.FINAL_TEST,
            "a/x-a/y",
            "a*(y-x)/(x*y)",
            List.of("x != 0", "y != 0")));
        result.add(positive(
            "final_distribution_ratio",
            "final_temporary_expansion_bridge",
            Split.FINAL_TEST,
            "(x*(a+b))/(x*a+x*b)",
            "1",
            List.of("x != 0", "a+b != 0")));
        result.add(positive(
            "final_binomial_identity_ratio",
            "final_binomial_expansion_bridge",
            Split.FINAL_TEST,
            "((a+b)^2)/(a^2+2*a*b+b^2)",
            "1",
            List.of("a+b != 0")));
        result.add(positive(
            "final_negated_factor_right",
            "final_ast_negation_right",
            Split.FINAL_TEST,
            "(a*(-x))/(b*(-x))",
            "a/b",
            List.of("x != 0", "b != 0")));
        result.add(positive(
            "final_negated_factor_left",
            "final_ast_negation_left",
            Split.FINAL_TEST,
            "((-x)*a)/((-x)*b)",
            "a/b",
            List.of("x != 0", "b != 0")));
        result.add(newCase(
            "final_missing_nested_pole",
            "final_negative_missing_nested_pole",
            Split.FINAL_TEST,
            CaseKind.NEGATIVE_MISSING_ASSUMPTION,
            "(x*(a+b))/(x*c)",
            "(a+b)/c",
            List.of("c != 0"),
            ExpectedDisposition.MUST_BLOCK));
        result.add(newCase(
            "final_literal_zero_denominator",
            "final_negative_zero_denominator",
            Split.FINAL_TEST,
            CaseKind.NEGATIVE_REFUTED,
            "(x*0)/(x*0)",
            "1",
            List.of(),
            ExpectedDisposition.MUST_NOT_FALSE_POSITIVE));
        result.add(newCase(
            "final_partial_fraction_boundary",
            "final_unsupported_partial_fraction",
            Split.FINAL_TEST,
            CaseKind.UNSUPPORTED_BOUNDARY,
            "1/(x*(x+1))",
            "1/x-1/(x+1)",
            List.of("x != 0", "x+1 != 0"),
            ExpectedDisposition.ALLOWED_NO_RESULT));
        result.add(positive(
            "final_geometric_polynomial_quotient",
            "final_high_degree_factor_bridge",
            Split.FINAL_TEST,
            "(x^4-1)/(x-1)",
            "x^3+x^2+x+1",
            List.of("x-1 != 0")));

        return result.stream()
            .sorted(Comparator.comparing(FlagshipCase::caseId))
            .toList();
    }

    private static List<PrimitiveRule> primitives() {
        return List.of(
            rule(
                "cancel_common_factor",
                "(?A*?B)/(?A*?C)",
                "?B/?C",
                List.of("?A != 0", "?C != 0"),
                "SIMPLIFY",
                -3,
                false),
            rule(
                "divide_one",
                "?A/1",
                "?A",
                List.of(),
                "SIMPLIFY",
                -2,
                false),
            rule(
                "multiply_one",
                "?A*1",
                "?A",
                List.of(),
                "SIMPLIFY",
                -1,
                false),
            rule(
                "add_zero",
                "?A+0",
                "?A",
                List.of(),
                "SIMPLIFY",
                -1,
                false),
            rule(
                "factor_square_difference",
                "?A^2-?B^2",
                "(?A-?B)*(?A+?B)",
                List.of(),
                "FACTOR",
                2,
                true),
            rule(
                "combine_equal_denominator",
                "?A/?C+?B/?C",
                "(?A+?B)/?C",
                List.of("?C != 0"),
                "SIMPLIFY",
                -2,
                false),
            rule(
                "divide_same_denominator",
                "(?A/?C)/(?B/?C)",
                "?A/?B",
                List.of("?C != 0", "?B != 0"),
                "SIMPLIFY",
                -3,
                false),
            rule(
                "collect_common_left_factor",
                "?A*?B+?A*?C",
                "?A*(?B+?C)",
                List.of(),
                "FACTOR",
                -1,
                false),
            rule(
                "distribute_left_factor",
                "?A*(?B+?C)",
                "?A*?B+?A*?C",
                List.of(),
                "EXPAND",
                2,
                true),
            rule(
                "expand_binomial_square",
                "(?A+?B)^2",
                "?A^2+2*?A*?B+?B^2",
                List.of(),
                "EXPAND",
                4,
                true),
            rule(
                "normalize_negated_fraction",
                "((-1)*?A)/((-1)*?B)",
                "?A/?B",
                List.of("?B != 0"),
                "NORMALIZE",
                -2,
                false),
            rule(
                "subtract_to_add_negative",
                "?A-?B",
                "?A+(-1)*?B",
                List.of(),
                "NORMALIZE",
                1,
                true));
    }

    private static ProgramGrammar grammar(List<PrimitiveRule> primitives) {
        return new ProgramGrammar(
            primitives.stream().map(PrimitiveRule::ruleId).sorted().toList(),
            List.of("SOURCE", "CHOICE", "FIRST_APPLICABLE", "SEQUENCE",
                "REPEAT", "REQUIRE", "PRIORITIZE", "PRUNE"),
            List.of("1..2", "1..3"),
            List.of(
                "EQUIVALENCE_PRESERVING_BY_CONSTRUCTION",
                "ASSUMPTION_FREE",
                "MAX_ESTIMATED_COST_DELTA:24",
                "MAX_PRIMITIVE_STEPS:6"),
            List.of(
                "ESTIMATED_COST_THEN_RULE",
                "PREFERRED_GENE_ORDER"),
            List.of(16, 32, 64),
            32,
            12,
            6,
            80,
            "EXPLICIT_PRUNING_MARKS_INCOMPLETE_EXECUTION");
    }

    private static PopulationContract population() {
        return new PopulationContract(
            32,
            20,
            4,
            12,
            4,
            4,
            20260801L,
            "TRAIN_ONLY_MUTATION",
            "VALIDATION_ONLY_SELECTION",
            "ONE_FROZEN_CONFIGURATION_EXACTLY_ONCE");
    }

    private static List<FitnessWeight> fitnessWeights() {
        return List.of(
            new FitnessWeight("TRAIN_CASES_NEWLY_SOLVED", 350),
            new FitnessWeight("SUPPORT", 150),
            new FitnessWeight("TRAIN_PATH_LENGTH_REDUCTION", 100),
            new FitnessWeight("TRAIN_EXPLORED_STATE_REDUCTION", 150),
            new FitnessWeight("ASSUMPTION_SIMPLICITY", 50),
            new FitnessWeight("CANDIDATE_COMPLEXITY", 100),
            new FitnessWeight("PROOF_COST_PROXY", 100));
    }

    private static List<Baseline> baselines() {
        return List.of(
            new Baseline(
                "flat_best_first",
                "ORDINARY_PLUS_ALL_GENOME_RULES_WITHOUT_PROGRAM",
                "IDENTICAL_RULE_AND_SEARCH_INFORMATION",
                true),
            new Baseline(
                "fixed_hand_written_program",
                "FROZEN_HUMAN_PROGRAM_OVER_IDENTICAL_RULES",
                "IDENTICAL_CASES_AND_BUDGETS",
                true),
            new Baseline(
                "random_valid_program",
                "UNIFORM_BOUNDED_VALID_TOPOLOGY_SAMPLING",
                "IDENTICAL_GRAMMAR_SEEDS_AND_BUDGETS",
                true),
            new Baseline(
                "flat_genome_mutation_only",
                "RULE_MUTATION_WITHOUT_TOPOLOGY_MUTATION",
                "IDENTICAL_TRAIN_VALIDATION_FINAL_SPLITS",
                true),
            new Baseline(
                "equality_saturation_shared_fragment",
                "EQUALITY_SATURATION_ON_MUTUALLY_SUPPORTED_FRAGMENT",
                "TRANSLATION_LOSSES_RETAINED",
                true),
            new Baseline(
                "ablation_no_sequence",
                "PROGRAM_GRAMMAR_WITHOUT_SEQUENCE",
                "IDENTICAL_OTHER_INPUTS_AND_BUDGETS",
                true),
            new Baseline(
                "ablation_no_repeat",
                "PROGRAM_GRAMMAR_WITHOUT_REPEAT",
                "IDENTICAL_OTHER_INPUTS_AND_BUDGETS",
                true),
            new Baseline(
                "ablation_no_require",
                "PROGRAM_GRAMMAR_WITHOUT_REQUIRE",
                "IDENTICAL_OTHER_INPUTS_AND_BUDGETS",
                true),
            new Baseline(
                "ablation_fixed_allocation",
                "NO_FITNESS_FEEDBACK_REALLOCATION",
                "IDENTICAL_TOTAL_RESOURCE_BUDGET",
                true));
    }

    private static AcceptanceCriteria acceptance() {
        return new AcceptanceCriteria(
            3,
            3,
            5,
            4,
            5,
            3,
            200,
            0,
            0,
            0,
            true,
            true,
            true,
            2,
            1,
            "POSITIVE_RESULT_OR_TRANSPARENT_NULL_RESULT_NO_POST_HOC_REPLACEMENT");
    }

    private static ResourceContract resources() {
        return new ResourceContract(
            20_000,
            5_000,
            512,
            1,
            14,
            20,
            4,
            5_000,
            80,
            4,
            24,
            "WALL_CLOCK_AND_CPU_ARE_NON_CANONICAL_DIAGNOSTICS");
    }

    private static FlagshipCase positive(
        String caseId,
        String familyId,
        Split split,
        String input,
        String target,
        List<String> assumptions
    ) {
        return newCase(
            caseId,
            familyId,
            split,
            CaseKind.POSITIVE,
            input,
            target,
            assumptions,
            ExpectedDisposition.MAY_IMPROVE);
    }

    private static FlagshipCase newCase(
        String caseId,
        String familyId,
        Split split,
        CaseKind kind,
        String input,
        String target,
        List<String> assumptions,
        ExpectedDisposition expectedDisposition
    ) {
        String normalizedInput = normalizeExpression(input);
        String normalizedTarget = normalizeExpression(target);
        List<String> normalizedAssumptions = assumptions.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
        String exactSignature = EvolutionGenome.hash(
            normalizedInput + "\n" + normalizedTarget + "\n"
                + normalizedAssumptions);
        Map<String, String> variables = new LinkedHashMap<>();
        String alphaInput = alphaNormalize(normalizedInput, variables);
        String alphaTarget = alphaNormalize(normalizedTarget, variables);
        List<String> alphaAssumptions = normalizedAssumptions.stream()
            .map(value -> alphaNormalize(value, variables))
            .sorted()
            .toList();
        String alphaSignature = EvolutionGenome.hash(
            alphaInput + "\n" + alphaTarget + "\n" + alphaAssumptions);
        FlagshipCase withoutHash = new FlagshipCase(
            caseId,
            familyId,
            split,
            kind,
            normalizedInput,
            normalizedTarget,
            normalizedAssumptions,
            expectedDisposition,
            split == Split.TRAIN,
            split != Split.FINAL_TEST,
            exactSignature,
            alphaSignature,
            EvolutionGenome.hash(normalizedInput),
            EvolutionGenome.hash(normalizedTarget),
            null);
        return withoutHash.withContentHash(
            EvolutionGenome.hash(withoutHash.canonicalPayload()));
    }

    private static PrimitiveRule rule(
        String ruleId,
        String source,
        String target,
        List<String> assumptions,
        String rewriteKind,
        int estimatedCostDelta,
        boolean mayIncreaseComplexity
    ) {
        PrimitiveRule withoutHash = new PrimitiveRule(
            ruleId,
            source,
            target,
            assumptions.stream().sorted().toList(),
            rewriteKind,
            estimatedCostDelta,
            mayIncreaseComplexity,
            "REQUIRES_EXACT_PATH_VALIDATION",
            null);
        return withoutHash.withContentHash(
            EvolutionGenome.hash(withoutHash.canonicalPayload()));
    }

    private static String normalizeExpression(String expression) {
        return ExpressionFormatter.format(
            new ExpressionParser().parseTerm(expression));
    }

    private static String alphaNormalize(
        String expression,
        Map<String, String> variables
    ) {
        Matcher matcher = IDENTIFIER.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = RESERVED_IDENTIFIERS.contains(token)
                ? token
                : variables.computeIfAbsent(
                    token, ignored -> "V" + variables.size());
            matcher.appendReplacement(
                result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String markdown(
        Preregistration preregistration,
        FreezeReceipt receipt
    ) {
        StringBuilder result = new StringBuilder();
        result.append("# Flagship rewrite-program preregistration\n\n")
            .append("Status: **NOT_STARTED**\n\n")
            .append("- preregistration: `")
            .append(preregistration.contentHash()).append("`\n")
            .append("- freeze receipt: `")
            .append(receipt.contentHash()).append("`\n")
            .append("- TRAIN cases: ")
            .append(receipt.trainCaseCount()).append("\n")
            .append("- VALIDATION cases: ")
            .append(receipt.validationCaseCount()).append("\n")
            .append("- FINAL TEST cases: ")
            .append(receipt.finalTestCaseCount()).append("\n\n")
            .append("No evaluated result existed when this receipt was formed. ")
            .append("FINAL TEST remains sealed until one configuration is selected ")
            .append("from VALIDATION and reserved for exactly one execution.\n\n")
            .append("## Acceptance thresholds\n\n")
            .append("- newly solved FINAL TEST cases: at least ")
            .append(preregistration.acceptance().minimumNewlySolvedCases())
            .append(" across at least ")
            .append(preregistration.acceptance().minimumNewlySolvedFamilies())
            .append(" families\n")
            .append("- improved cases: at least ")
            .append(preregistration.acceptance().minimumImprovedCases())
            .append(" across at least ")
            .append(preregistration.acceptance().minimumImprovedFamilies())
            .append(" families\n")
            .append("- correctness, reachability and missing-assumption regressions: 0\n")
            .append("- clean reproductions: ")
            .append(preregistration.acceptance().requiredCleanRuns())
            .append("; pinned-container reproductions: ")
            .append(preregistration.acceptance().requiredContainerRuns())
            .append("\n");
        return result.toString();
    }

    public enum Split {
        TRAIN,
        VALIDATION,
        FINAL_TEST
    }

    public enum CaseKind {
        POSITIVE,
        NEGATIVE_MISSING_ASSUMPTION,
        NEGATIVE_REFUTED,
        UNSUPPORTED_BOUNDARY
    }

    public enum ExpectedDisposition {
        MAY_IMPROVE,
        MUST_BLOCK,
        MUST_NOT_FALSE_POSITIVE,
        ALLOWED_NO_RESULT
    }

    public record FlagshipCase(
        String caseId,
        String familyId,
        Split split,
        CaseKind kind,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        ExpectedDisposition expectedDisposition,
        boolean formationVisible,
        boolean evaluationVisibleBeforeFinalTest,
        String exactSignature,
        String alphaSignature,
        String inputHash,
        String targetHash,
        String contentHash
    ) {
        public FlagshipCase {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            Objects.requireNonNull(split, "split");
            Objects.requireNonNull(kind, "kind");
            requireText(inputExpression, "inputExpression");
            requireText(targetExpression, "targetExpression");
            assumptions = List.copyOf(assumptions);
            Objects.requireNonNull(expectedDisposition, "expectedDisposition");
            EvolutionGenome.requireSha256(exactSignature, "exactSignature");
            EvolutionGenome.requireSha256(alphaSignature, "alphaSignature");
            EvolutionGenome.requireSha256(inputHash, "inputHash");
            EvolutionGenome.requireSha256(targetHash, "targetHash");
            if (split == Split.TRAIN != formationVisible) {
                throw new IllegalArgumentException(
                    "formation visibility must be TRAIN-only");
            }
            if (split == Split.FINAL_TEST == evaluationVisibleBeforeFinalTest) {
                throw new IllegalArgumentException(
                    "FINAL TEST visibility is inconsistent");
            }
            if (contentHash != null) {
                EvolutionGenome.requireSha256(contentHash, "contentHash");
                String expected = EvolutionGenome.hash(canonicalPayload());
                if (!expected.equals(contentHash)) {
                    throw new IllegalArgumentException(
                        "flagship case contentHash mismatch");
                }
            }
        }

        FlagshipCase withContentHash(String hash) {
            return new FlagshipCase(
                caseId,
                familyId,
                split,
                kind,
                inputExpression,
                targetExpression,
                assumptions,
                expectedDisposition,
                formationVisible,
                evaluationVisibleBeforeFinalTest,
                exactSignature,
                alphaSignature,
                inputHash,
                targetHash,
                hash);
        }

        String canonicalPayload() {
            return new JsonWriter().beginObject()
                .property("caseId", caseId)
                .property("familyId", familyId)
                .property("split", split.name())
                .property("kind", kind.name())
                .property("inputExpression", inputExpression)
                .property("targetExpression", targetExpression)
                .stringArray("assumptions", assumptions)
                .property("expectedDisposition", expectedDisposition.name())
                .property("formationVisible", formationVisible)
                .property("evaluationVisibleBeforeFinalTest",
                    evaluationVisibleBeforeFinalTest)
                .property("exactSignature", exactSignature)
                .property("alphaSignature", alphaSignature)
                .property("inputHash", inputHash)
                .property("targetHash", targetHash)
                .endObject().toString();
        }
    }

    public record PrimitiveRule(
        String ruleId,
        String sourcePattern,
        String targetPattern,
        List<String> assumptions,
        String rewriteKind,
        int estimatedCostDelta,
        boolean mayIncreaseComplexity,
        String evidencePolicy,
        String contentHash
    ) {
        public PrimitiveRule {
            requireId(ruleId, "ruleId");
            requireText(sourcePattern, "sourcePattern");
            requireText(targetPattern, "targetPattern");
            assumptions = List.copyOf(assumptions);
            requireText(rewriteKind, "rewriteKind");
            requireText(evidencePolicy, "evidencePolicy");
            if (contentHash != null) {
                EvolutionGenome.requireSha256(contentHash, "contentHash");
                if (!EvolutionGenome.hash(canonicalPayload()).equals(contentHash)) {
                    throw new IllegalArgumentException(
                        "primitive rule contentHash mismatch");
                }
            }
        }

        PrimitiveRule withContentHash(String hash) {
            return new PrimitiveRule(
                ruleId,
                sourcePattern,
                targetPattern,
                assumptions,
                rewriteKind,
                estimatedCostDelta,
                mayIncreaseComplexity,
                evidencePolicy,
                hash);
        }

        String canonicalPayload() {
            return new JsonWriter().beginObject()
                .property("ruleId", ruleId)
                .property("sourcePattern", sourcePattern)
                .property("targetPattern", targetPattern)
                .stringArray("assumptions", assumptions)
                .property("rewriteKind", rewriteKind)
                .property("estimatedCostDelta", estimatedCostDelta)
                .property("mayIncreaseComplexity", mayIncreaseComplexity)
                .property("evidencePolicy", evidencePolicy)
                .endObject().toString();
        }
    }

    public record ProgramGrammar(
        List<String> sourceRuleIds,
        List<String> nodeKinds,
        List<String> repeatBounds,
        List<String> requirements,
        List<String> priorities,
        List<Integer> pruneLimits,
        int maxNodes,
        int maxDepth,
        int maxPrimitiveSteps,
        int maxCandidatesPerState,
        String pruningPolicy
    ) {
        public ProgramGrammar {
            sourceRuleIds = sortedUnique(sourceRuleIds, "sourceRuleIds");
            nodeKinds = sortedUnique(nodeKinds, "nodeKinds");
            repeatBounds = sortedUnique(repeatBounds, "repeatBounds");
            requirements = sortedUnique(requirements, "requirements");
            priorities = sortedUnique(priorities, "priorities");
            pruneLimits = pruneLimits.stream().distinct().sorted().toList();
            if (maxNodes < 1 || maxDepth < 1 || maxPrimitiveSteps < 1
                    || maxCandidatesPerState < 1) {
                throw new IllegalArgumentException(
                    "program grammar limits must be positive");
            }
            requireText(pruningPolicy, "pruningPolicy");
        }
    }

    public record PopulationContract(
        int populationSize,
        int generationCount,
        int eliteCount,
        int minimumDistinctAlphaStructures,
        int maxOffspringPerLineage,
        int parallelism,
        long randomSeed,
        String mutationInformationPolicy,
        String selectionInformationPolicy,
        String finalTestPolicy
    ) {
        public PopulationContract {
            if (populationSize < 1 || generationCount < 1 || eliteCount < 0
                    || eliteCount > populationSize
                    || minimumDistinctAlphaStructures < 1
                    || minimumDistinctAlphaStructures > populationSize
                    || maxOffspringPerLineage < 1 || parallelism < 1) {
                throw new IllegalArgumentException(
                    "invalid population contract");
            }
            requireText(mutationInformationPolicy, "mutationInformationPolicy");
            requireText(selectionInformationPolicy, "selectionInformationPolicy");
            requireText(finalTestPolicy, "finalTestPolicy");
        }
    }

    public record FitnessWeight(String component, int weightPermille) {
        public FitnessWeight {
            requireText(component, "component");
            if (weightPermille < 1 || weightPermille > 1000) {
                throw new IllegalArgumentException(
                    "weightPermille must be in [1,1000]");
            }
        }
    }

    public record Baseline(
        String baselineId,
        String method,
        String informationParity,
        boolean mandatory
    ) {
        public Baseline {
            requireId(baselineId, "baselineId");
            requireText(method, "method");
            requireText(informationParity, "informationParity");
        }
    }

    public record AcceptanceCriteria(
        int minimumNewlySolvedCases,
        int minimumNewlySolvedFamilies,
        int minimumImprovedCases,
        int minimumImprovedFamilies,
        int minimumProgramUsedCases,
        int minimumPrimitiveStepsOnOneImprovedCase,
        int minimumMedianExploredStateReductionPermille,
        int maximumCorrectnessFailures,
        int maximumReachabilityRegressions,
        int maximumMissingAssumptionFailures,
        boolean requireCandidateNotAlphaEquivalentToSeeds,
        boolean requireEveryConfiguredCaseRetained,
        boolean requireEveryMandatoryBaselineExecuted,
        int requiredCleanRuns,
        int requiredContainerRuns,
        String nullResultPolicy
    ) {
        public AcceptanceCriteria {
            if (minimumNewlySolvedCases < 1
                    || minimumNewlySolvedFamilies < 1
                    || minimumImprovedCases < minimumNewlySolvedCases
                    || minimumImprovedFamilies < minimumNewlySolvedFamilies
                    || minimumProgramUsedCases < minimumNewlySolvedCases
                    || minimumPrimitiveStepsOnOneImprovedCase < 3
                    || minimumMedianExploredStateReductionPermille < 0
                    || minimumMedianExploredStateReductionPermille > 1000
                    || maximumCorrectnessFailures != 0
                    || maximumReachabilityRegressions != 0
                    || maximumMissingAssumptionFailures != 0
                    || requiredCleanRuns < 2
                    || requiredContainerRuns < 1) {
                throw new IllegalArgumentException(
                    "flagship acceptance thresholds are not sufficiently strict");
            }
            if (!requireCandidateNotAlphaEquivalentToSeeds
                    || !requireEveryConfiguredCaseRetained
                    || !requireEveryMandatoryBaselineExecuted) {
                throw new IllegalArgumentException(
                    "mandatory flagship safeguards cannot be disabled");
            }
            requireText(nullResultPolicy, "nullResultPolicy");
        }
    }

    public record ResourceContract(
        int maxMutationAttempts,
        int maxTrainEvaluations,
        int maxValidationEvaluations,
        int maxFinalTestConfigurations,
        int maxFinalTestCaseEvaluations,
        int maxCheckpoints,
        int searchMaxDepth,
        int searchMaxVisitedExpressions,
        int searchMaxCandidatesPerState,
        int searchMaxExpandingSteps,
        int maxAstGrowthPerStep,
        String telemetryPolicy
    ) {
        public ResourceContract {
            if (maxMutationAttempts < 1 || maxTrainEvaluations < 1
                    || maxValidationEvaluations < 1
                    || maxFinalTestConfigurations != 1
                    || maxFinalTestCaseEvaluations < 1
                    || maxCheckpoints < 1 || searchMaxDepth < 1
                    || searchMaxVisitedExpressions < 1
                    || searchMaxCandidatesPerState < 1
                    || searchMaxExpandingSteps < 0
                    || maxAstGrowthPerStep < 0) {
                throw new IllegalArgumentException("invalid resource contract");
            }
            requireText(telemetryPolicy, "telemetryPolicy");
        }
    }

    public record Preregistration(
        String schema,
        String studyId,
        String domain,
        String primaryClaim,
        List<FlagshipCase> cases,
        List<PrimitiveRule> primitives,
        ProgramGrammar grammar,
        PopulationContract population,
        List<FitnessWeight> fitnessWeights,
        List<Baseline> baselines,
        AcceptanceCriteria acceptance,
        ResourceContract resources,
        String executionStatus,
        String finalTestStatus,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public Preregistration {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported flagship preregistration schema");
            }
            requireId(studyId, "studyId");
            requireText(domain, "domain");
            requireText(primaryClaim, "primaryClaim");
            cases = cases.stream()
                .sorted(Comparator.comparing(FlagshipCase::caseId)).toList();
            primitives = primitives.stream()
                .sorted(Comparator.comparing(PrimitiveRule::ruleId)).toList();
            Objects.requireNonNull(grammar, "grammar");
            Objects.requireNonNull(population, "population");
            fitnessWeights = fitnessWeights.stream()
                .sorted(Comparator.comparing(FitnessWeight::component)).toList();
            baselines = baselines.stream()
                .sorted(Comparator.comparing(Baseline::baselineId)).toList();
            Objects.requireNonNull(acceptance, "acceptance");
            Objects.requireNonNull(resources, "resources");
            validatePreregistration(this);
            if (!"NOT_STARTED".equals(executionStatus)
                    || !"SEALED_UNTIL_FROZEN_VALIDATION_SELECTION".equals(
                        finalTestStatus)
                    || !"NOT_EVALUATED".equals(proofStatus)
                    || !"NOT_EVALUATED".equals(externalNoveltyStatus)
                    || !"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "flagship preregistration must remain pre-execution");
            }
            if (contentHash != null) {
                EvolutionGenome.requireSha256(contentHash, "contentHash");
                if (!EvolutionGenome.hash(canonicalPayload()).equals(contentHash)) {
                    throw new IllegalArgumentException(
                        "flagship preregistration contentHash mismatch");
                }
            }
        }

        public List<FlagshipCase> splitCases(Split split) {
            return cases.stream().filter(item -> item.split() == split).toList();
        }

        String canonicalPayload() {
            return render(false);
        }

        public String toCanonicalJson() {
            return render(true);
        }

        private String render(boolean includeHash) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("studyId", studyId)
                .property("domain", domain)
                .property("primaryClaim", primaryClaim)
                .array("cases", array -> cases.forEach(item ->
                    array.objectValue(object -> {
                        object.property("caseId", item.caseId())
                            .property("familyId", item.familyId())
                            .property("split", item.split().name())
                            .property("kind", item.kind().name())
                            .property("inputExpression", item.inputExpression())
                            .property("targetExpression", item.targetExpression())
                            .stringArray("assumptions", item.assumptions())
                            .property("expectedDisposition",
                                item.expectedDisposition().name())
                            .property("formationVisible", item.formationVisible())
                            .property("evaluationVisibleBeforeFinalTest",
                                item.evaluationVisibleBeforeFinalTest())
                            .property("exactSignature", item.exactSignature())
                            .property("alphaSignature", item.alphaSignature())
                            .property("inputHash", item.inputHash())
                            .property("targetHash", item.targetHash())
                            .property("contentHash", item.contentHash());
                    })))
                .array("primitives", array -> primitives.forEach(rule ->
                    array.objectValue(object -> object
                        .property("ruleId", rule.ruleId())
                        .property("sourcePattern", rule.sourcePattern())
                        .property("targetPattern", rule.targetPattern())
                        .stringArray("assumptions", rule.assumptions())
                        .property("rewriteKind", rule.rewriteKind())
                        .property("estimatedCostDelta",
                            rule.estimatedCostDelta())
                        .property("mayIncreaseComplexity",
                            rule.mayIncreaseComplexity())
                        .property("evidencePolicy", rule.evidencePolicy())
                        .property("contentHash", rule.contentHash()))))
                .object("grammar", object -> object
                    .stringArray("sourceRuleIds", grammar.sourceRuleIds())
                    .stringArray("nodeKinds", grammar.nodeKinds())
                    .stringArray("repeatBounds", grammar.repeatBounds())
                    .stringArray("requirements", grammar.requirements())
                    .stringArray("priorities", grammar.priorities())
                    .array("pruneLimits", array -> grammar.pruneLimits()
                        .forEach(array::numberValue))
                    .property("maxNodes", grammar.maxNodes())
                    .property("maxDepth", grammar.maxDepth())
                    .property("maxPrimitiveSteps", grammar.maxPrimitiveSteps())
                    .property("maxCandidatesPerState",
                        grammar.maxCandidatesPerState())
                    .property("pruningPolicy", grammar.pruningPolicy()))
                .object("population", object -> object
                    .property("populationSize", population.populationSize())
                    .property("generationCount", population.generationCount())
                    .property("eliteCount", population.eliteCount())
                    .property("minimumDistinctAlphaStructures",
                        population.minimumDistinctAlphaStructures())
                    .property("maxOffspringPerLineage",
                        population.maxOffspringPerLineage())
                    .property("parallelism", population.parallelism())
                    .property("randomSeed", population.randomSeed())
                    .property("mutationInformationPolicy",
                        population.mutationInformationPolicy())
                    .property("selectionInformationPolicy",
                        population.selectionInformationPolicy())
                    .property("finalTestPolicy", population.finalTestPolicy()))
                .array("fitnessWeights", array -> fitnessWeights.forEach(weight ->
                    array.objectValue(object -> object
                        .property("component", weight.component())
                        .property("weightPermille", weight.weightPermille()))))
                .array("baselines", array -> baselines.forEach(baseline ->
                    array.objectValue(object -> object
                        .property("baselineId", baseline.baselineId())
                        .property("method", baseline.method())
                        .property("informationParity",
                            baseline.informationParity())
                        .property("mandatory", baseline.mandatory()))))
                .object("acceptance", object -> object
                    .property("minimumNewlySolvedCases",
                        acceptance.minimumNewlySolvedCases())
                    .property("minimumNewlySolvedFamilies",
                        acceptance.minimumNewlySolvedFamilies())
                    .property("minimumImprovedCases",
                        acceptance.minimumImprovedCases())
                    .property("minimumImprovedFamilies",
                        acceptance.minimumImprovedFamilies())
                    .property("minimumProgramUsedCases",
                        acceptance.minimumProgramUsedCases())
                    .property("minimumPrimitiveStepsOnOneImprovedCase",
                        acceptance.minimumPrimitiveStepsOnOneImprovedCase())
                    .property("minimumMedianExploredStateReductionPermille",
                        acceptance.minimumMedianExploredStateReductionPermille())
                    .property("maximumCorrectnessFailures",
                        acceptance.maximumCorrectnessFailures())
                    .property("maximumReachabilityRegressions",
                        acceptance.maximumReachabilityRegressions())
                    .property("maximumMissingAssumptionFailures",
                        acceptance.maximumMissingAssumptionFailures())
                    .property("requireCandidateNotAlphaEquivalentToSeeds",
                        acceptance.requireCandidateNotAlphaEquivalentToSeeds())
                    .property("requireEveryConfiguredCaseRetained",
                        acceptance.requireEveryConfiguredCaseRetained())
                    .property("requireEveryMandatoryBaselineExecuted",
                        acceptance.requireEveryMandatoryBaselineExecuted())
                    .property("requiredCleanRuns",
                        acceptance.requiredCleanRuns())
                    .property("requiredContainerRuns",
                        acceptance.requiredContainerRuns())
                    .property("nullResultPolicy", acceptance.nullResultPolicy()))
                .object("resources", object -> object
                    .property("maxMutationAttempts",
                        resources.maxMutationAttempts())
                    .property("maxTrainEvaluations",
                        resources.maxTrainEvaluations())
                    .property("maxValidationEvaluations",
                        resources.maxValidationEvaluations())
                    .property("maxFinalTestConfigurations",
                        resources.maxFinalTestConfigurations())
                    .property("maxFinalTestCaseEvaluations",
                        resources.maxFinalTestCaseEvaluations())
                    .property("maxCheckpoints", resources.maxCheckpoints())
                    .property("searchMaxDepth", resources.searchMaxDepth())
                    .property("searchMaxVisitedExpressions",
                        resources.searchMaxVisitedExpressions())
                    .property("searchMaxCandidatesPerState",
                        resources.searchMaxCandidatesPerState())
                    .property("searchMaxExpandingSteps",
                        resources.searchMaxExpandingSteps())
                    .property("maxAstGrowthPerStep",
                        resources.maxAstGrowthPerStep())
                    .property("telemetryPolicy", resources.telemetryPolicy()))
                .property("executionStatus", executionStatus)
                .property("finalTestStatus", finalTestStatus)
                .property("proofStatus", proofStatus)
                .property("externalNoveltyStatus", externalNoveltyStatus)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus);
            if (includeHash) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    public record FreezeReceipt(
        String schema,
        String studyId,
        String preregistrationHash,
        String repositoryRevision,
        int trainCaseCount,
        int validationCaseCount,
        int finalTestCaseCount,
        int executedTrainEvaluations,
        int executedValidationEvaluations,
        int executedFinalTestEvaluations,
        String resultExistence,
        String trainStatus,
        String validationStatus,
        String finalTestStatus,
        boolean publicationAuthorized,
        String contentHash
    ) {
        public FreezeReceipt {
            if (!RECEIPT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported flagship freeze-receipt schema");
            }
            requireId(studyId, "studyId");
            EvolutionGenome.requireSha256(
                preregistrationHash, "preregistrationHash");
            EvolutionGenome.requireSha256(
                repositoryRevision, "repositoryRevision");
            if (trainCaseCount < 1 || validationCaseCount < 1
                    || finalTestCaseCount < 1
                    || executedTrainEvaluations != 0
                    || executedValidationEvaluations != 0
                    || executedFinalTestEvaluations != 0
                    || !"NO_EVALUATED_RESULTS_EXIST".equals(resultExistence)
                    || !"NOT_STARTED".equals(trainStatus)
                    || !"NOT_EVALUATED".equals(validationStatus)
                    || !"SEALED".equals(finalTestStatus)
                    || publicationAuthorized) {
                throw new IllegalArgumentException(
                    "freeze receipt must predate all evaluated work");
            }
            if (contentHash != null) {
                EvolutionGenome.requireSha256(contentHash, "contentHash");
                if (!EvolutionGenome.hash(canonicalPayload()).equals(contentHash)) {
                    throw new IllegalArgumentException(
                        "freeze receipt contentHash mismatch");
                }
            }
        }

        String canonicalPayload() {
            return render(false);
        }

        public String toCanonicalJson() {
            return render(true);
        }

        private String render(boolean includeHash) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("studyId", studyId)
                .property("preregistrationHash", preregistrationHash)
                .property("repositoryRevision", repositoryRevision)
                .property("trainCaseCount", trainCaseCount)
                .property("validationCaseCount", validationCaseCount)
                .property("finalTestCaseCount", finalTestCaseCount)
                .property("executedTrainEvaluations", executedTrainEvaluations)
                .property("executedValidationEvaluations",
                    executedValidationEvaluations)
                .property("executedFinalTestEvaluations",
                    executedFinalTestEvaluations)
                .property("resultExistence", resultExistence)
                .property("trainStatus", trainStatus)
                .property("validationStatus", validationStatus)
                .property("finalTestStatus", finalTestStatus)
                .property("publicationAuthorized", publicationAuthorized);
            if (includeHash) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    private static void validatePreregistration(Preregistration value) {
        if (value.cases().size() != 26
                || value.splitCases(Split.TRAIN).size() != 6
                || value.splitCases(Split.VALIDATION).size() != 6
                || value.splitCases(Split.FINAL_TEST).size() != 14) {
            throw new IllegalArgumentException(
                "flagship corpus must retain exact 6/6/14 split counts");
        }
        requireUnique(value.cases().stream().map(FlagshipCase::caseId).toList(),
            "case IDs");
        requireUnique(value.cases().stream().map(FlagshipCase::contentHash).toList(),
            "case hashes");
        requireSplitDisjoint(value, FlagshipCase::familyId, "families");
        requireSplitDisjoint(value, FlagshipCase::exactSignature,
            "exact signatures");
        requireSplitDisjoint(value, FlagshipCase::alphaSignature,
            "alpha signatures");
        requireSplitDisjoint(value, FlagshipCase::inputHash, "input hashes");
        requireSplitDisjoint(value, FlagshipCase::targetHash, "target hashes");
        if (value.splitCases(Split.FINAL_TEST).stream()
                .anyMatch(FlagshipCase::formationVisible)
                || value.splitCases(Split.FINAL_TEST).stream()
                    .anyMatch(FlagshipCase::evaluationVisibleBeforeFinalTest)) {
            throw new IllegalArgumentException(
                "FINAL TEST content must remain sealed");
        }
        requireUnique(value.primitives().stream()
            .map(PrimitiveRule::ruleId).toList(), "primitive rule IDs");
        requireUnique(value.primitives().stream()
            .map(PrimitiveRule::contentHash).toList(), "primitive hashes");
        Set<String> primitiveIds = Set.copyOf(value.primitives().stream()
            .map(PrimitiveRule::ruleId).toList());
        if (!primitiveIds.equals(Set.copyOf(value.grammar().sourceRuleIds()))) {
            throw new IllegalArgumentException(
                "program grammar does not bind the exact primitive inventory");
        }
        if (value.fitnessWeights().stream()
                .mapToInt(FitnessWeight::weightPermille).sum() != 1000) {
            throw new IllegalArgumentException(
                "flagship fitness weights must sum to 1000");
        }
        requireUnique(value.fitnessWeights().stream()
            .map(FitnessWeight::component).toList(), "fitness components");
        requireUnique(value.baselines().stream()
            .map(Baseline::baselineId).toList(), "baseline IDs");
        if (value.baselines().stream().filter(Baseline::mandatory).count() < 5) {
            throw new IllegalArgumentException(
                "flagship requires information-parity baselines and ablations");
        }
        if (value.resources().maxFinalTestCaseEvaluations()
                != value.splitCases(Split.FINAL_TEST).size()) {
            throw new IllegalArgumentException(
                "FINAL TEST resource count differs from frozen case count");
        }
    }

    private static <T> void requireSplitDisjoint(
        Preregistration value,
        java.util.function.Function<FlagshipCase, T> selector,
        String name
    ) {
        Map<Split, Set<T>> values = new HashMap<>();
        for (Split split : Split.values()) {
            values.put(split, value.splitCases(split).stream()
                .map(selector).collect(java.util.stream.Collectors.toSet()));
        }
        for (Split left : Split.values()) {
            for (Split right : Split.values()) {
                if (left.ordinal() >= right.ordinal()) {
                    continue;
                }
                Set<T> overlap = new HashSet<>(values.get(left));
                overlap.retainAll(values.get(right));
                if (!overlap.isEmpty()) {
                    throw new IllegalArgumentException(
                        "split collision in " + name + " between " + left
                            + " and " + right + ": " + overlap);
                }
            }
        }
    }

    private static List<String> sortedUnique(
        List<String> values,
        String name
    ) {
        List<String> result = values.stream()
            .map(value -> requireText(value, name))
            .distinct()
            .sorted()
            .toList();
        if (result.size() != values.size() || result.isEmpty()) {
            throw new IllegalArgumentException(
                name + " must be non-empty and unique");
        }
        return result;
    }

    private static void requireUnique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }
}
