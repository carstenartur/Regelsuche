package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExactTheoryPath;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Learns a frozen sequence of finite coefficient ansatz templates from selected,
 * verifier-backed training paths. It does not learn a search controller, prove
 * the generalized templates, or authorize their execution as transformations.
 * Every later instantiation must pass the existing solver and evidence pipeline.
 */
public final class ExactFinitePolynomialTraceLearner {
    public static final String REVISION = "regelsuche.exact-finite-polynomial-trace-learner/v1";
    private static final String VARIABLE = "${variable}";
    private static final int MAX_EXPRESSION_CHARS = 16_384;
    private static final int MAX_STRUCTURAL_TOKENS = 128;
    private static final int MAX_AST_NODES = 256;
    private static final ExactParsedUnivariatePolynomialView.Budget IDENTITY_BUDGET =
        new ExactParsedUnivariatePolynomialView.Budget(64, 4096, MAX_AST_NODES, 50_000);

    /** Policy is fixed before training; it is never enlarged after a reuse miss. */
    public record Limits(int minimumTraces, int maximumTraces, int maximumSteps,
                         int maximumHoles, int minimumCoefficient, int maximumCoefficient,
                         long maximumAssignmentsPerStep) {
        public Limits {
            long width = (long) maximumCoefficient - minimumCoefficient + 1;
            if (minimumTraces < 2 || maximumTraces < minimumTraces || maximumTraces > 32
                    || maximumSteps < 1 || maximumSteps > 8 || maximumHoles < 1 || maximumHoles > 12
                    || width < 1 || width > 128 || maximumAssignmentsPerStep < 1
                    || maximumAssignmentsPerStep > 100_000) {
                throw new IllegalArgumentException("invalid finite trace-learning limits");
            }
        }
    }

    /** The learner checks the selected path against every verifier-owned step. */
    public record TrainingTrace(BudgetedRewriteProgramExecution execution, String selectedPathHash,
                                List<VerifiedCandidateEvidence> evidence) {
        public TrainingTrace {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(selectedPathHash, "selectedPathHash");
            Objects.requireNonNull(evidence, "evidence");
            if (evidence.isEmpty() || evidence.size() > 8) {
                throw new IllegalArgumentException("training trace step limit");
            }
            evidence = List.copyOf(evidence);
        }
    }

    /** Non-authorizing inputs for the existing ExactFinitePolynomialPlanResolver. */
    public record Instantiation(String sourceExpression, String ansatzTemplate,
                                List<HoleDomain> holeDomains) {
        public Instantiation { holeDomains = List.copyOf(holeDomains); }
    }

    public record Stage(String sourceShape, String ansatzTemplate, List<HoleDomain> holeDomains) {
        public Stage { holeDomains = List.copyOf(holeDomains); }
    }

    /** Only learn() can issue a learned plan. No target or TEST outcome is an input. */
    public static final class LearnedPlan {
        private final Limits limits;
        private final List<Stage> stages;
        private final List<String> trainingRoots;
        private final List<String> trainingInputIdentities;
        private final long trainingIdentityWorkUnits;
        private final String canonicalJson;
        private final String contentHash;

        private LearnedPlan(Limits limits, List<Stage> stages, List<String> trainingRoots,
                            List<String> trainingInputIdentities, long trainingIdentityWorkUnits) {
            this.limits = limits;
            this.stages = List.copyOf(stages);
            this.trainingRoots = List.copyOf(trainingRoots);
            this.trainingInputIdentities = List.copyOf(trainingInputIdentities);
            this.trainingIdentityWorkUnits = trainingIdentityWorkUnits;
            this.canonicalJson = render(limits, stages, trainingRoots,
                this.trainingInputIdentities, trainingIdentityWorkUnits);
            this.contentHash = SchematicProofPlan.hash(canonicalJson);
        }

        public List<Stage> stages() { return stages; }
        public List<String> trainingRoots() { return trainingRoots; }
        public List<String> trainingInputIdentities() { return trainingInputIdentities; }
        /** Diagnostic exact-view work only, not total training or learning cost. */
        public long trainingIdentityWorkUnits() { return trainingIdentityWorkUnits; }
        public Limits limits() { return limits; }
        public String toCanonicalJson() { return canonicalJson; }
        public String contentHash() { return contentHash; }

        /**
         * Exact syntactic applicability only. Empty means a shape mismatch, not
         * mathematical impossibility. Unsupported syntax/limits throw explicitly.
         */
        public Optional<Instantiation> instantiate(int stageIndex, String expression) {
            Stage stage = stages.get(stageIndex);
            Parsed parsed = parse(expression);
            if (stageIndex == 0 && trainingInputIdentities.contains(inputSignature(parsed).identity())) {
                throw new IllegalArgumentException("training-equivalent input is not held-out reuse");
            }
            if (!stage.sourceShape().equals(shape(parsed, parsed.term().expression(), false))) {
                return Optional.empty();
            }
            String template = stage.ansatzTemplate().replace(VARIABLE, parsed.variable());
            return Optional.of(new Instantiation(
                ExactExpressionFormatter.format(parsed.term().expression(), parsed.term()),
                template, stage.holeDomains()));
        }
    }

    public LearnedPlan learn(List<TrainingTrace> training, Limits limits) {
        Objects.requireNonNull(training, "training");
        Objects.requireNonNull(limits, "limits");
        if (training.size() < limits.minimumTraces() || training.size() > limits.maximumTraces()) {
            throw new IllegalArgumentException("insufficient or oversized training support");
        }
        List<CheckedTrace> traces = training.stream().map(trace -> check(trace, limits))
            .sorted(Comparator.comparing(CheckedTrace::root)).toList();
        Set<String> sourceIdentities = new HashSet<>();
        long identityWork = 0;
        int stepCount = traces.getFirst().path().steps().size();
        for (CheckedTrace trace : traces) {
            if (trace.path().steps().size() != stepCount) {
                throw new IllegalArgumentException("training paths have different step counts");
            }
            Parsed source = parse(trace.path().sourceExpression());
            InputSignature signature = inputSignature(source);
            identityWork = Math.addExact(identityWork, signature.workUnits());
            if (!sourceIdentities.add(signature.identity())) {
                throw new IllegalArgumentException("duplicate, alpha-renamed or equivalent training input");
            }
        }
        List<Stage> stages = new ArrayList<>();
        for (int index = 0; index < stepCount; index++) {
            final int step = index;
            List<Parsed> inputs = traces.stream().map(trace -> parse(
                trace.path().steps().get(step).transition().sourceExpression())).toList();
            List<Parsed> outputs = traces.stream().map(trace -> parse(
                trace.path().steps().get(step).transition().transformedExpression())).toList();
            for (int row = 0; row < inputs.size(); row++) {
                if (!inputs.get(row).variable().equals(outputs.get(row).variable())) {
                    throw new IllegalArgumentException("step changes its variable identity");
                }
            }
            String inputShape = shape(inputs.getFirst(), inputs.getFirst().term().expression(), false);
            if (inputs.stream().anyMatch(input -> !inputShape.equals(
                    shape(input, input.term().expression(), false)))) {
                throw new IllegalArgumentException("training sources have different syntax shapes");
            }
            Generalization context = new Generalization(limits);
            String template = generalize(outputs, outputs.stream().map(p -> p.term().expression()).toList(),
                false, context);
            if (context.domains.isEmpty()) {
                throw new IllegalArgumentException("no varying coefficient: stage would memorize a fixed form");
            }
            stages.add(new Stage(inputShape, template, context.domains));
        }
        return new LearnedPlan(limits, stages, traces.stream().map(CheckedTrace::root).toList(),
            sourceIdentities.stream().sorted().toList(), identityWork);
    }

    private static CheckedTrace check(TrainingTrace trace, Limits limits) {
        Objects.requireNonNull(trace, "trace");
        if (!trace.execution().complete()) {
            throw new IllegalArgumentException("incomplete training execution");
        }
        ExactTheoryPath path = trace.execution().candidates().stream()
            .filter(p -> p.contentHash().equals(trace.selectedPathHash())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("selected path is absent"));
        if (path.steps().size() > limits.maximumSteps() || path.steps().size() != trace.evidence().size()) {
            throw new IllegalArgumentException("path/evidence step mismatch");
        }
        JsonWriter root = new JsonWriter().beginObject()
            .property("execution", trace.execution().contentHash()).property("path", path.contentHash());
        List<String> roots = new ArrayList<>();
        for (int index = 0; index < path.steps().size(); index++) {
            var step = path.steps().get(index);
            VerifiedCandidateEvidence evidence = trace.evidence().get(index);
            if (evidence.data().runStatus() != ExactFinitePolynomialPlanRun.Status.COMPLETE_WITH_RESOLUTIONS) {
                throw new IllegalArgumentException("truncated training solver evidence");
            }
            var source = new VerifiedFinitePolynomialCandidateSource(evidence);
            var execution = step.sourceExecution();
            if (!source.identity().equals(execution.sourceIdentity())
                    || !source.transform(execution.inputExpression(), execution.availableMathematicalWorkUnits())
                        .equals(execution.sourceResult())
                    || !execution.candidates().contains(step.transition())
                    || !evidence.data().transformedExpression().equals(step.transition().transformedExpression())) {
                throw new IllegalArgumentException("training step is not bound to its verified evidence");
            }
            roots.add(evidence.evidenceHash());
        }
        return new CheckedTrace(path, SchematicProofPlan.hash(root.stringArray("evidence", roots).endObject().toString()));
    }

    /** Numeric column reuse preserves repeated-coefficient relations across all rows. */
    private static String generalize(List<Parsed> rows, List<Expr> nodes, boolean exponent,
                                     Generalization context) {
        Expr first = nodes.getFirst();
        if (nodes.stream().allMatch(node -> node instanceof NumberExpr)) {
            List<BigInteger> values = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) { values.add(integer(rows.get(i), (NumberExpr) nodes.get(i))); }
            if (values.stream().distinct().count() == 1) { return values.getFirst().toString(); }
            if (exponent) { throw new IllegalArgumentException("varying exponent is not a coefficient hole"); }
            return "${" + context.hole(values) + "}";
        }
        if (nodes.stream().allMatch(node -> node instanceof VariableExpr)) { return VARIABLE; }
        if (!(first instanceof BinaryExpr binary) || nodes.stream().anyMatch(node ->
                !(node instanceof BinaryExpr other) || other.operator() != binary.operator())) {
            throw new IllegalArgumentException("training outputs have different operator structure");
        }
        List<Expr> left = nodes.stream().map(node -> ((BinaryExpr) node).left()).toList();
        List<Expr> right = nodes.stream().map(node -> ((BinaryExpr) node).right()).toList();
        return "(" + generalize(rows, left, false, context) + " " + binary.operator().symbol() + " "
            + generalize(rows, right, binary.operator() == BinaryOperator.POW, context) + ")";
    }

    private static final class Generalization {
        private final Limits limits;
        private final Map<List<BigInteger>, String> columns = new LinkedHashMap<>();
        private final List<HoleDomain> domains = new ArrayList<>();
        private long assignments = 1;
        private Generalization(Limits limits) { this.limits = limits; }
        private String hole(List<BigInteger> values) {
            String existing = columns.get(values);
            if (existing != null) { return existing; }
            if (domains.size() >= limits.maximumHoles()) { throw new IllegalArgumentException("coefficient hole limit"); }
            if (values.stream().anyMatch(value -> value.compareTo(BigInteger.valueOf(limits.minimumCoefficient())) < 0
                    || value.compareTo(BigInteger.valueOf(limits.maximumCoefficient())) > 0)) {
                throw new IllegalArgumentException("training coefficient outside frozen finite domain");
            }
            long width = (long) limits.maximumCoefficient() - limits.minimumCoefficient() + 1;
            if (assignments > limits.maximumAssignmentsPerStep() / width) {
                throw new IllegalArgumentException("learned template assignment limit");
            }
            assignments *= width;
            String id = "coefficient-" + domains.size();
            columns.put(List.copyOf(values), id);
            domains.add(HoleDomain.integerRange(id, limits.minimumCoefficient(), limits.maximumCoefficient()));
            return id;
        }
    }

    private static Parsed parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank() || expression.length() > MAX_EXPRESSION_CHARS) {
            throw new IllegalArgumentException("training expression length limit");
        }
        int structural = 0;
        for (int i = 0; i < expression.length(); i++) {
            if ("+-*/^(),".indexOf(expression.charAt(i)) >= 0 && ++structural > MAX_STRUCTURAL_TOKENS) {
                throw new IllegalArgumentException("training expression structural limit");
            }
        }
        ExactParsedTerm term = new ExpressionParser().parseExactTerm(expression);
        Set<String> variables = new HashSet<>();
        validate(term.expression(), term, variables, new int[1]);
        if (variables.size() != 1) { throw new IllegalArgumentException("exactly one variable is required"); }
        return new Parsed(term, variables.iterator().next());
    }

    private static void validate(Expr node, ExactParsedTerm term, Set<String> variables, int[] visits) {
        if (++visits[0] > MAX_AST_NODES) { throw new IllegalArgumentException("training AST limit"); }
        if (node instanceof NumberExpr number) {
            integer(new Parsed(term, ""), number);
        } else if (node instanceof VariableExpr variable) {
            variables.add(variable.name());
        } else if (node instanceof BinaryExpr binary && binary.operator() != BinaryOperator.DIV) {
            if (binary.operator() == BinaryOperator.POW) {
                if (!(binary.right() instanceof NumberExpr power)) { throw new IllegalArgumentException("literal exponent required"); }
                BigInteger value = integer(new Parsed(term, ""), power);
                if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(32)) > 0) {
                    throw new IllegalArgumentException("unsupported exponent");
                }
            }
            validate(binary.left(), term, variables, visits);
            validate(binary.right(), term, variables, visits);
        } else { throw new IllegalArgumentException("unsupported polynomial syntax in trace learner"); }
    }

    private static BigInteger integer(Parsed parsed, NumberExpr number) {
        // ExactParsedTerm validates every literal. Only its synthetic unary-minus
        // zero may lack literal evidence; no caller-supplied AST is accepted here.
        ExactRational value = parsed.term().literalFor(number)
            .map(ExactParsedTerm.LiteralOccurrence::exactValue).orElse(ExactRational.ZERO);
        if (!value.isInteger() || value.numerator().bitLength() > 512) {
            throw new IllegalArgumentException("bounded integer coefficients required");
        }
        return value.numerator();
    }

    private static String shape(Parsed row, Expr node, boolean exponent) {
        if (node instanceof NumberExpr number) {
            return exponent ? integer(row, number).toString() : "#";
        }
        if (node instanceof VariableExpr) { return VARIABLE; }
        BinaryExpr binary = (BinaryExpr) node;
        return "(" + shape(row, binary.left(), false) + " " + binary.operator().symbol()
            + " " + shape(row, binary.right(), binary.operator() == BinaryOperator.POW) + ")";
    }

    /** Variable-blind exact coefficients: syntax variants cannot inflate TRAIN support. */
    private static InputSignature inputSignature(Parsed parsed) {
        var analysis = new ExactParsedUnivariatePolynomialView(IDENTITY_BUDGET).analyze(parsed.term());
        if (!analysis.supported()) {
            throw new IllegalArgumentException("training identity " + analysis.status()
                + ": " + analysis.detailCode());
        }
        var polynomial = analysis.polynomial().orElseThrow();
        String canonical = new JsonWriter().beginObject()
            .property("schema", "regelsuche.trace-input-polynomial/v1")
            .property("domain", polynomial.ring().coefficientDomain().id())
            .array("terms", writer -> polynomial.terms().entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().exponent(0)))
                .forEach(entry -> writer.objectValue(term -> term
                    .property("exponent", entry.getKey().exponent(0))
                    .property("coefficient", entry.getValue().canonicalText()))))
            .endObject().toString();
        return new InputSignature(SchematicProofPlan.hash(canonical), analysis.work().totalWorkUnits());
    }

    private static String render(Limits limits, List<Stage> stages, List<String> roots,
                                 List<String> inputIdentities, long identityWork) {
        return new JsonWriter().beginObject().property("schema", REVISION)
            .property("authority", "NON_EXECUTABLE_REQUIRES_FRESH_VERIFICATION")
            .property("resolverRevision", ExactFinitePolynomialPlanResolver.REVISION_HASH)
            .property("verifierRevision", ExactFinitePolynomialPlanCandidateEvidenceVerifier.REVISION_HASH)
            .property("sourceRevision", VerifiedFinitePolynomialCandidateSource.REVISION_HASH)
            .property("programRevision", BudgetedRewriteProgramExecution.REVISION)
            .property("identityViewRevision", ExactParsedUnivariatePolynomialView.VIEW_ID)
            .property("identityViewBudget", IDENTITY_BUDGET.canonicalMaterial())
            .property("trainingIdentityWorkUnits", identityWork)
            .stringArray("trainingInputIdentities", inputIdentities)
            .object("limits", w -> w.property("minimumTraces", limits.minimumTraces())
                .property("maximumTraces", limits.maximumTraces()).property("maximumSteps", limits.maximumSteps())
                .property("maximumHoles", limits.maximumHoles()).property("minimumCoefficient", limits.minimumCoefficient())
                .property("maximumCoefficient", limits.maximumCoefficient())
                .property("maximumAssignmentsPerStep", limits.maximumAssignmentsPerStep()))
            .stringArray("trainingRoots", roots)
            .array("stages", w -> stages.forEach(stage -> w.objectValue(s -> s
                .property("sourceShape", stage.sourceShape()).property("ansatzTemplate", stage.ansatzTemplate())
                .stringArray("coefficientHoles", stage.holeDomains().stream().map(HoleDomain::holeId).toList()))))
            .endObject().toString();
    }

    private record InputSignature(String identity, long workUnits) {}
    private record Parsed(ExactParsedTerm term, String variable) {}
    private record CheckedTrace(ExactTheoryPath path, String root) {}
}
