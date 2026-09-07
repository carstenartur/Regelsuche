package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.ConfirmedReplay;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.search.program.RewritePrograms;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded TRAIN-only enumeration of finite polynomial template sequences.
 * This is an experimental selector, not evolution, a new expression interpreter,
 * learned grammar, production promotion, or a flagship FINAL TEST protocol.
 */
public final class FinitePolynomialStrategySearch {
    public static final String REVISION = "regelsuche.finite-polynomial-strategy-search/v1";
    private static final SchematicProofPlan.Limits PLAN_LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private static final ExplorationLimits EXECUTION_LIMITS = new ExplorationLimits(16, 8, 3);

    /** The {@code @v} slot uses only the single parser-issued input variable name. */
    public record Template(String id, String expression, List<HoleDomain> domains) {
        public Template {
            id = SchematicProofPlan.requireId(id, "template id");
            if (id.length() > 64) { throw new IllegalArgumentException("template id exceeds 64 characters"); }
            expression = text(expression);
            if (!expression.contains("@v")) {
                throw new IllegalArgumentException("template must declare the variable slot @v");
            }
            domains = List.copyOf(domains).stream()
                .sorted(Comparator.comparing(HoleDomain::holeId)).toList();
            if (domains.isEmpty() || domains.size() > 4
                    || domains.stream().map(HoleDomain::holeId).distinct().count() != domains.size()) {
                throw new IllegalArgumentException("require one to four unique finite holes");
            }
            if (assignments(domains) > 256) {
                throw new IllegalArgumentException("template exceeds 256 assignments");
            }
        }

        long assignmentCount() { return assignments(domains); }
        String instantiateVariable(String variable) { return expression.replace("@v", variable); }
    }

    /** All trials use the same non-resettable within-trial limits. */
    public record Grammar(List<Template> templates, int maxSequenceLength,
                          long maxAssignmentEvaluationsPerTrial, long maxTheoryPathWork) {
        public Grammar {
            templates = List.copyOf(templates).stream().sorted(Comparator.comparing(Template::id)).toList();
            if (templates.isEmpty() || templates.size() > 4
                    || templates.stream().map(Template::id).distinct().count() != templates.size()
                    || maxSequenceLength < 1 || maxSequenceLength > 3
                    || maxAssignmentEvaluationsPerTrial < 0 || maxAssignmentEvaluationsPerTrial > 10_000
                    || maxTheoryPathWork < 0 || maxTheoryPathWork > 1_000_000) {
                throw new IllegalArgumentException("invalid finite strategy grammar or budget");
            }
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("revision", REVISION)
                .property("resolverRevision", ExactFinitePolynomialPlanResolver.REVISION_HASH)
                .property("candidateVerifierRevision", ExactFinitePolynomialPlanCandidateEvidenceVerifier.REVISION_HASH)
                .property("programRevision", BudgetedRewriteProgramExecution.REVISION)
                .property("viewId", ExactParsedUnivariatePolynomialView.VIEW_ID)
                .property("inputPolicy", "4096_CHARS_128_OPERATORS_EXACT_UNIVARIATE_DEFAULT_VIEW_V1")
                .property("preparation", "ONE_GENERATION_ONE_RECEIPT_REPLAY_ONE_CONFIRMATION_REPLAY")
                .object("planLimits", value -> value.property("steps", PLAN_LIMITS.maxSteps())
                    .property("holes", PLAN_LIMITS.maxHoles()).property("obligations", PLAN_LIMITS.maxObligations())
                    .property("canonicalBytes", PLAN_LIMITS.maxCanonicalBytes()))
                .object("executionLimits", value -> value.property("nodeVisits", EXECUTION_LIMITS.maxNodeVisits())
                    .property("pathExtensions", EXECUTION_LIMITS.maxPathExtensions())
                    .property("pathSteps", EXECUTION_LIMITS.maxPathSteps()))
                .property("objective", "EXACT_UNIVARIATE_NONCONSTANT_ROOT_FACTORS_V1")
                .property("selection", "MAX_TRAIN_HITS_MIN_INCOMPLETE_ASSIGNMENTS_PATH_WORK_LENGTH_IDS_V1")
                .property("candidateSelection", "LOWEST_RETAINED_CANDIDATE_HASH;NO_RETRY_ON_IDENTITY")
                .property("retainedSolutionLimit", 2)
                .property("maxSequenceLength", maxSequenceLength)
                .property("maxAssignmentEvaluationsPerTrial", maxAssignmentEvaluationsPerTrial)
                .property("maxTheoryPathWork", maxTheoryPathWork)
                .array("templates", array -> templates.forEach(template -> array.objectValue(item -> {
                    item.property("id", template.id()).property("expression", template.expression());
                    item.array("domains", values -> template.domains().forEach(domain ->
                        values.objectValue(value -> value.property("id", domain.holeId())
                            .property("kind", domain.kind().name())
                            .array("values", scalars -> domain.values().forEach(scalar ->
                                scalars.value(scalar.canonicalText()))))));
                }))).endObject().toString();
        }

        public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
    }

    public record TrainingInput(String id, String expression) {
        public TrainingInput {
            id = SchematicProofPlan.requireId(id, "training input id");
            expression = text(expression);
        }
    }

    public enum Outcome {
        OBJECTIVE_REACHED, OBJECTIVE_MISS, COMPLETE_NO_SOLUTION, NO_CHANGE, ALREADY_SATISFIED,
        ASSIGNMENT_BUDGET_INCONCLUSIVE, PATH_BUDGET_INCONCLUSIVE
    }

    /** Retains the real generated run, checked receipt, replay and selected evidence. */
    public record Attempt(String templateId, SchematicProofPlan plan, ExactFinitePolynomialPlanRun run,
                          ReplayReceipt receipt, ConfirmedReplay confirmation,
                          Optional<VerifiedCandidateEvidence> candidate) {
        public Attempt {
            Objects.requireNonNull(templateId, "templateId");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(confirmation, "confirmation");
            Objects.requireNonNull(candidate, "candidate");
        }
        public long assignmentEvaluations() {
            // One generation, one receipt replay, one independently loaded confirmation replay.
            return Math.multiplyExact(2L + confirmation.exactReplayExecutions(),
                run.solverResult().evaluatedAssignments());
        }
    }

    /** Raw dimensions stay separate; assignment counts are not all CPU/native work. */
    public record Trial(String grammarHash, List<String> sequence, String input, String inputAlphaPolynomialHash,
                        Outcome outcome, List<Attempt> attempts,
                        Optional<BudgetedRewriteProgramExecution> execution,
                        long assignmentEvaluations, long objectiveViewWork,
                        long requiredNextAssignmentEvaluations) {
        public Trial {
            sequence = List.copyOf(sequence);
            attempts = List.copyOf(attempts);
            Objects.requireNonNull(execution, "execution");
        }
        public long pathWork() {
            return execution.filter(value -> !value.candidates().isEmpty())
                .map(value -> value.candidates().getFirst().mathematicalWorkUnits()).orElse(0L);
        }
        public boolean incomplete() {
            return outcome == Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE
                || outcome == Outcome.PATH_BUDGET_INCONCLUSIVE;
        }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("revision", REVISION)
                .property("grammarHash", grammarHash)
                .array("sequence", array -> sequence.forEach(array::value))
                .property("input", input).property("inputAlphaPolynomialHash", inputAlphaPolynomialHash)
                .property("outcome", outcome.name()).property("assignmentEvaluations", assignmentEvaluations)
                .property("objectiveViewWork", objectiveViewWork)
                .property("requiredNextAssignmentEvaluations", requiredNextAssignmentEvaluations)
                .property("programExecutionHash", execution.map(BudgetedRewriteProgramExecution::contentHash).orElse(""))
                .array("attempts", array -> attempts.forEach(attempt -> array.objectValue(value ->
                    value.property("templateId", attempt.templateId()).property("planHash", attempt.plan().contentHash())
                        .property("planRunHash", attempt.run().contentHash())
                        .property("runStatus", attempt.run().status().name())
                        .property("receiptHash", attempt.receipt().contentHash())
                        .property("confirmationHash", attempt.confirmation().confirmationHash())
                        .property("candidateHash", attempt.candidate().map(VerifiedCandidateEvidence::evidenceHash).orElse(""))
                        .property("assignmentEvaluations", attempt.assignmentEvaluations()))))
                .endObject().toString();
        }
        public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
    }

    public record TrainingRow(String inputId, Trial trial) {}
    public record Score(List<String> sequence, int hits, int incomplete,
                        long assignmentEvaluations, long pathWork) {
        public Score { sequence = List.copyOf(sequence); }
    }

    /** Only a completed train() call can issue a selection. No held-out setter exists. */
    public static final class FrozenSelection {
        private final Grammar grammar;
        private final List<TrainingRow> rows;
        private final List<Score> scores;
        private final List<String> trainingIdentities;
        private final long inputViewWork;
        private final String contentHash;

        private FrozenSelection(Grammar grammar, List<TrainingRow> rows,
                                List<Score> scores, List<String> identities, long inputViewWork) {
            this.grammar = grammar;
            this.rows = List.copyOf(rows);
            this.scores = List.copyOf(scores);
            this.trainingIdentities = List.copyOf(identities);
            this.inputViewWork = inputViewWork;
            this.contentHash = SchematicProofPlan.hash(toCanonicalJson());
        }
        public Grammar grammar() { return grammar; }
        public List<TrainingRow> rows() { return rows; }
        public List<Score> scores() { return scores; }
        public Optional<List<String>> selectedSequence() {
            return scores.getFirst().hits() == 0 ? Optional.empty() : Optional.of(scores.getFirst().sequence());
        }
        public String contentHash() { return contentHash; }
        public long totalTrainingAssignmentEvaluations() {
            return rows.stream().mapToLong(row -> row.trial().assignmentEvaluations())
                .reduce(0L, Math::addExact);
        }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("revision", REVISION)
                .property("state", "TRAIN_COMPLETE_SELECTION_FROZEN")
                .property("grammarHash", grammar.contentHash()).property("inputViewWork", inputViewWork)
                .property("totalTrainingAssignmentEvaluations", totalTrainingAssignmentEvaluations())
                .array("selectedSequence", array -> selectedSequence().orElse(List.of()).forEach(array::value))
                .array("trainingIdentities", array -> trainingIdentities.forEach(array::value))
                .array("rows", array -> rows.forEach(row -> array.objectValue(value ->
                    value.property("inputId", row.inputId()).property("trialHash", row.trial().contentHash()))))
                .array("scores", array -> scores.forEach(score -> array.objectValue(value ->
                    value.array("sequence", ids -> score.sequence().forEach(ids::value))
                        .property("hits", score.hits()).property("incomplete", score.incomplete())
                        .property("assignmentEvaluations", score.assignmentEvaluations())
                        .property("pathWork", score.pathWork())))).endObject().toString();
        }
    }

    public record Application(String selectionHash, Trial trial, long inputViewWork) {
        public String contentHash() {
            return SchematicProofPlan.hash(new JsonWriter().beginObject().property("revision", REVISION)
                .property("selectionHash", selectionHash).property("trialHash", trial.contentHash())
                .property("inputViewWork", inputViewWork).endObject().toString());
        }
    }

    public FrozenSelection train(Grammar grammar, List<TrainingInput> inputs) {
        Objects.requireNonNull(grammar, "grammar");
        List<TrainingInput> training = List.copyOf(inputs).stream()
            .sorted(Comparator.comparing(TrainingInput::id)).toList();
        if (training.isEmpty() || training.size() > 16
                || training.stream().map(TrainingInput::id).distinct().count() != training.size()) {
            throw new IllegalArgumentException("require one to sixteen unique TRAIN input IDs");
        }
        List<List<Template>> sequences = enumerate(grammar);
        if (Math.multiplyExact(sequences.size(), training.size()) > 512) {
            throw new IllegalArgumentException("TRAIN matrix exceeds 512 complete trials");
        }
        // Validate every input and its exact/alpha duplicate boundary before any solving.
        List<InputAnalysis> analyzed = training.stream().map(value -> analyze(value.expression())).toList();
        List<String> identities = analyzed.stream().map(InputAnalysis::alphaHash).toList();
        if (new HashSet<>(identities).size() != identities.size()) {
            throw new IllegalArgumentException("duplicate alpha-equivalent TRAIN polynomials");
        }
        List<TrainingRow> rows = new ArrayList<>();
        List<Score> scores = new ArrayList<>();
        for (List<Template> sequence : sequences) {
            int hits = 0;
            int incomplete = 0;
            long assignments = 0;
            long work = 0;
            for (int index = 0; index < training.size(); index++) {
                Trial trial = evaluate(grammar, sequence, analyzed.get(index));
                rows.add(new TrainingRow(training.get(index).id(), trial));
                if (trial.outcome() == Outcome.OBJECTIVE_REACHED) { hits++; }
                if (trial.incomplete()) { incomplete++; }
                assignments = Math.addExact(assignments, trial.assignmentEvaluations());
                work = Math.addExact(work, trial.pathWork());
            }
            scores.add(new Score(ids(sequence), hits, incomplete, assignments, work));
        }
        scores.sort(Comparator.comparingInt(Score::hits).reversed()
            .thenComparingInt(Score::incomplete).thenComparingLong(Score::assignmentEvaluations)
            .thenComparingLong(Score::pathWork).thenComparingInt(value -> value.sequence().size())
            .thenComparing(value -> String.join("/", value.sequence())));
        return new FrozenSelection(grammar, rows, scores, identities,
            analyzed.stream().mapToLong(InputAnalysis::viewWork).reduce(0L, Math::addExact));
    }

    /** Fresh coefficient solving with a frozen sequence; never retrains on this input. */
    public Application apply(FrozenSelection selection, String expression) {
        Objects.requireNonNull(selection, "selection");
        List<String> selected = selection.selectedSequence().orElseThrow(() ->
            new IllegalStateException("no successful TRAIN strategy was selected"));
        InputAnalysis input = analyze(expression);
        if (selection.trainingIdentities.contains(input.alphaHash())) {
            throw new IllegalArgumentException("application repeats a TRAIN polynomial, including alpha renaming");
        }
        List<Template> sequence = selected.stream().map(id -> selection.grammar.templates().stream()
            .filter(template -> template.id().equals(id)).findFirst().orElseThrow()).toList();
        return new Application(selection.contentHash(), evaluate(selection.grammar, sequence, input), input.viewWork());
    }

    private Trial evaluate(Grammar grammar, List<Template> sequence, InputAnalysis input) {
        if (input.alreadyFactored()) {
            return new Trial(grammar.contentHash(), ids(sequence), input.syntax(), input.alphaHash(),
                Outcome.ALREADY_SATISFIED, List.of(), Optional.empty(), 0, 0, 0);
        }
        List<Attempt> attempts = new ArrayList<>();
        List<RewriteProgram> nodes = new ArrayList<>();
        String current = input.syntax();
        long assignments = 0;
        long pathWork = 0;
        long requiredNext = 0;
        Outcome outcome = Outcome.OBJECTIVE_MISS;
        for (Template template : sequence) {
            requiredNext = Math.multiplyExact(3L, template.assignmentCount());
            if (requiredNext > grammar.maxAssignmentEvaluationsPerTrial() - assignments) {
                outcome = Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE;
                break;
            }
            Attempt attempt = prepare(template, current, input.variable());
            if (attempt.assignmentEvaluations() != requiredNext) {
                throw new IllegalStateException("preparation replay count differs from admitted work contract");
            }
            attempts.add(attempt);
            assignments = Math.addExact(assignments, attempt.assignmentEvaluations());
            requiredNext = 0;
            if (attempt.candidate().isEmpty()) {
                outcome = attempt.run().candidates().isEmpty() ? Outcome.COMPLETE_NO_SOLUTION : Outcome.NO_CHANGE;
                break;
            }
            VerifiedCandidateEvidence candidate = attempt.candidate().orElseThrow();
            if (candidate.data().sourceExpression().equals(candidate.data().transformedExpression())) {
                outcome = Outcome.NO_CHANGE;
                break;
            }
            nodes.add(RewritePrograms.budgetedSource("step-" + nodes.size(),
                new VerifiedFinitePolynomialCandidateSource(candidate)));
            long required = candidate.data().canonicalWork().totalWorkUnits();
            if (required > grammar.maxTheoryPathWork() - pathWork) {
                outcome = Outcome.PATH_BUDGET_INCONCLUSIVE;
                break;
            }
            pathWork = Math.addExact(pathWork, required);
            current = candidate.data().transformedExpression();
        }
        Optional<BudgetedRewriteProgramExecution> execution = nodes.isEmpty() ? Optional.empty()
            : Optional.of(new RewriteProgramInterpreter().executeBudgeted(
                new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("strategy"), nodes),
                input.syntax(), new PathBudget(0, grammar.maxTheoryPathWork()), EXECUTION_LIMITS));
        if (execution.isPresent() && !execution.orElseThrow().complete()
                && outcome != Outcome.PATH_BUDGET_INCONCLUSIVE) {
            throw new IllegalStateException("unexpected incomplete execution of admitted finite strategy");
        }
        long objectiveWork = 0;
        if (outcome == Outcome.OBJECTIVE_MISS && nodes.size() == sequence.size()) {
            BudgetedRewriteProgramExecution executed = execution.orElseThrow();
            if (executed.candidates().size() != 1
                    || !executed.candidates().getFirst().transformedExpression().equals(current)) {
                throw new IllegalStateException("verified sequence did not replay its selected endpoint");
            }
            Objective objective = factored(current);
            objectiveWork = objective.viewWork();
            if (objective.reached()) { outcome = Outcome.OBJECTIVE_REACHED; }
        }
        return new Trial(grammar.contentHash(), ids(sequence), input.syntax(), input.alphaHash(), outcome,
            attempts, execution, assignments, objectiveWork, requiredNext);
    }

    private Attempt prepare(Template template, String source, String variable) {
        String ansatz = template.instantiateVariable(variable);
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan("strategy-" + template.id(), source, ansatz,
            template.domains(), 2, PLAN_LIMITS);
        var run = resolver.resolve(plan, source, ansatz, template.domains(), 2);
        ReplayReceipt receipt = new ExactFinitePolynomialPlanReplayVerifier()
            .verify(plan, source, ansatz, template.domains(), 2, run);
        var byteVerifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptReference = byteVerifier.describeReceipt(receipt);
        byte[] receiptBytes = receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedReceipt = byteVerifier.verifyReceipt(receiptReference,
            ignored -> new LoadedArtifact(receiptReference.artifactId(), receiptBytes));
        var receiptArtifact = new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier().verify(checkedReceipt);
        var runReference = byteVerifier.describePlanRun(run);
        byte[] runBytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedRun = byteVerifier.verifyPlanRun(runReference,
            ignored -> new LoadedArtifact(runReference.artifactId(), runBytes));
        ConfirmedReplay confirmation = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(
            receiptArtifact, checkedRun, run, plan, source, ansatz, template.domains(), 2);
        Optional<VerifiedCandidateEvidence> selected = run.candidates().stream()
            .min(Comparator.comparing(ExactFinitePolynomialResolvedCandidate::contentHash))
            .filter(candidate -> !run.solverResult().sourceExpression()
                .equals(candidate.solution().instantiatedExpression()))
            .map(candidate -> new ExactFinitePolynomialPlanCandidateEvidenceVerifier().verify(
                confirmation, plan, run, candidate.contentHash()));
        return new Attempt(template.id(), plan, run, receipt, confirmation, selected);
    }

    private record InputAnalysis(String syntax, String variable, String alphaHash, long viewWork, boolean alreadyFactored) {}
    private record Objective(boolean reached, long viewWork) {}

    private static InputAnalysis analyze(String expression) {
        ExactParsedTerm parsed = new ExpressionParser().parseExactTerm(text(expression));
        var analysis = new ExactParsedUnivariatePolynomialView().analyze(parsed);
        SparsePolynomial<ExactRational> polynomial = analysis.polynomial().orElseThrow(() ->
            new IllegalArgumentException("unsupported/inconclusive polynomial input: " + analysis.detailCode()));
        if (polynomial.isConstant()) { throw new IllegalArgumentException("input must be nonconstant univariate"); }
        // Coefficients/exponents, not binary64 values or variable names, define split identity.
        JsonWriter alpha = new JsonWriter().beginObject().property("kind", "exact-univariate-alpha/v1")
            .array("terms", array -> polynomial.terms().forEach((monomial, coefficient) ->
                array.objectValue(value -> value.property("exponent", monomial.exponent(0))
                    .property("coefficient", coefficient.canonicalText()))));
        Objective initialObjective = factored(expression);
        return new InputAnalysis(ExactExpressionFormatter.format(parsed.expression(), parsed),
            polynomial.ring().variables().getFirst().id(),
            SchematicProofPlan.hash(alpha.endObject().toString()),
            Math.addExact(analysis.work().totalWorkUnits(), initialObjective.viewWork()), initialObjective.reached());
    }

    private static Objective factored(String expression) {
        ExactParsedTerm parsed = new ExpressionParser().parseExactTerm(text(expression));
        if (!(parsed.expression() instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.MUL) {
            return new Objective(false, 0);
        }
        var view = new ExactParsedUnivariatePolynomialView();
        var parser = new ExpressionParser();
        var left = view.analyze(parser.parseExactTerm(ExactExpressionFormatter.format(binary.left(), parsed)));
        var right = view.analyze(parser.parseExactTerm(ExactExpressionFormatter.format(binary.right(), parsed)));
        if (left.polynomial().isEmpty() || right.polynomial().isEmpty()) {
            throw new IllegalStateException("objective cannot classify a verified factor within its exact bounds");
        }
        return new Objective(!left.polynomial().orElseThrow().isConstant()
            && !right.polynomial().orElseThrow().isConstant(),
            Math.addExact(left.work().totalWorkUnits(), right.work().totalWorkUnits()));
    }

    private static List<List<Template>> enumerate(Grammar grammar) {
        List<List<Template>> all = new ArrayList<>();
        List<List<Template>> frontier = List.of(List.of());
        for (int depth = 0; depth < grammar.maxSequenceLength(); depth++) {
            List<List<Template>> next = new ArrayList<>();
            for (List<Template> prefix : frontier) {
                for (Template template : grammar.templates()) {
                    List<Template> word = new ArrayList<>(prefix);
                    word.add(template);
                    next.add(List.copyOf(word));
                }
            }
            all.addAll(next);
            frontier = next;
        }
        return List.copyOf(all);
    }

    private static List<String> ids(List<Template> templates) {
        return templates.stream().map(Template::id).toList();
    }
    private static long assignments(List<HoleDomain> domains) {
        long count = 1;
        for (HoleDomain domain : domains) { count = Math.multiplyExact(count, domain.values().size()); }
        return count;
    }
    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.chars().anyMatch(Character::isISOControl)
                || value.chars().filter(c -> "+-*/^(),".indexOf(c) >= 0).count() > 128) {
            throw new IllegalArgumentException("invalid or over-limit strategy text");
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
