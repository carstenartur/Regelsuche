package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Target-blind bounded study for issue #1024.
 *
 * <p>The search is told only a generic modular exponent composition law. It is
 * not given the externally known Pocklington factored program as a goal. After
 * the complete one-step closure is exhausted, the reached programs are scored
 * twice: once as ordinary trees and once as computation DAGs that may reuse an
 * identical modular-power call.</p>
 */
public final class ModPowDagRediscoveryStudy {
    public static final String REVISION = "regelsuche.modpow-dag-rediscovery/v1";
    public static final String RULE_LEFT = "modpow-compose-product-left-first";
    public static final String RULE_RIGHT = "modpow-compose-product-right-first";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Expr UNREACHABLE_GOAL =
        new FunctionExpr("unreachable_research_goal", List.of());
    static final List<String> DOMAIN_ASSUMPTIONS = List.of(
        "a integer",
        "n integer",
        "n > 0",
        "q integer",
        "q >= 0",
        "e integer",
        "e >= 0",
        "r integer",
        "r >= 0"
    );

    public record BitProfile(String id, int qBits, int eBits, int rBits) {
        public BitProfile {
            if (id == null || id.isBlank() || qBits < 1 || eBits < 1 || rBits < 1) {
                throw new IllegalArgumentException("positive bit profile required");
            }
        }

        public Map<String, Integer> variables() {
            return Map.of("q", qBits, "e", eBits, "r", rBits);
        }
    }

    public record ProgramCost(long tree, long dag) {
        public ProgramCost {
            if (tree < 0 || dag < 0) {
                throw new IllegalArgumentException("nonnegative program cost required");
            }
        }
    }

    public record CaseResult(
            String id,
            boolean negativeControl,
            MoveSearch.Outcome outcome,
            boolean completeBoundedRelation,
            int reachedStates,
            long generatedSuccessors,
            long totalSearchWork,
            long verificationWork,
            ProgramCost originalCost,
            ProgramCost selectedCost,
            Expr selectedProgram,
            boolean expectedSharedResidue,
            long acceptedProofReceipts) {

        public boolean dagImproved() {
            return selectedCost.dag() < originalCost.dag();
        }

        public boolean treeImproved() {
            return selectedCost.tree() < originalCost.tree();
        }
    }

    public record StudyResult(
            List<CaseResult> formation,
            List<CaseResult> test,
            CaseResult negativeControl,
            boolean green) {
        public StudyResult {
            formation = List.copyOf(formation);
            test = List.copyOf(test);
            Objects.requireNonNull(negativeControl, "negativeControl");
        }
    }

    public static final List<BitProfile> FORMATION = List.of(
        new BitProfile("TRAIN_SMALL", 5, 3, 3),
        new BitProfile("TRAIN_SKEW", 21, 8, 8)
    );

    /** Exponent bit profiles frozen in #1024 before implementation. */
    public static final List<BitProfile> TEST = List.of(
        new BitProfile("C31", 16, 15, 15),
        new BitProfile("C61", 31, 30, 30),
        new BitProfile("C93", 47, 46, 46),
        new BitProfile("C123", 62, 61, 61)
    );

    private ModPowDagRediscoveryStudy() {
    }

    public static StudyResult run() {
        var formation = FORMATION.stream().map(profile -> runCase(profile, false)).toList();
        var test = TEST.stream().map(profile -> runCase(profile, false)).toList();
        var negative = runCase(new BitProfile("NEGATIVE_NO_SHARED_E", 47, 46, 46), true);
        boolean green = test.stream().allMatch(ModPowDagRediscoveryStudy::greenPositive)
            && negative.completeBoundedRelation()
            && negative.outcome() == MoveSearch.Outcome.BOUNDED_EXHAUSTED
            && !negative.dagImproved()
            && !negative.treeImproved();
        return new StudyResult(formation, test, negative, green);
    }

    public static CaseResult runCase(BitProfile profile, boolean negativeControl) {
        Objects.requireNonNull(profile, "profile");
        Expr source = sourceProgram(negativeControl);
        var provider = compositionProvider();
        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(
            source,
            new TypedMoveSearch.Context(
                UNREACHABLE_GOAL,
                DOMAIN_ASSUMPTIONS,
                MoveContext.Phase.FROZEN_EVALUATION),
            List.of(provider),
            TypedMoveSearch.Policy.INVENTORY_ORDER,
            compositionVerifier(),
            state -> 0.0,
            MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE,
            MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(1, 1, 0, 32, 10_000)
        ));

        ProgramCost original = cost(source, profile);
        var selected = result.reachedStates().stream()
            .map(TypedMoveSearch.State::expression)
            .min(Comparator
                .comparingLong((Expr expression) -> cost(expression, profile).dag())
                .thenComparingLong(expression -> cost(expression, profile).tree())
                .thenComparing(CODEC::encodeExpression))
            .orElseThrow();
        ProgramCost selectedCost = cost(selected, profile);
        long acceptedReceipts = result.encodedResult().events().stream()
            .filter(event -> event.decision() == MoveSearch.Decision.ENQUEUED)
            .filter(event -> event.verificationResult().orElseThrow().accepted())
            .count();

        return new CaseResult(
            profile.id(),
            negativeControl,
            result.outcome(),
            result.completeBoundedRelation(),
            result.reachedStates().size(),
            result.metrics().generatedSuccessors(),
            result.metrics().totalWork(),
            result.metrics().verificationWork(),
            original,
            selectedCost,
            selected,
            hasSharedEResidue(selected),
            acceptedReceipts
        );
    }

    static boolean greenPositive(CaseResult result) {
        return result.outcome() == MoveSearch.Outcome.BOUNDED_EXHAUSTED
            && result.completeBoundedRelation()
            && result.reachedStates() == 3
            && result.generatedSuccessors() == 2
            && result.acceptedProofReceipts() == 2
            && result.dagImproved()
            && !result.treeImproved()
            && result.expectedSharedResidue();
    }

    static Expr sourceProgram(boolean negativeControl) {
        Expr a = new VariableExpr("a");
        Expr q = new VariableExpr("q");
        Expr e = new VariableExpr("e");
        Expr n = new VariableExpr("n");
        Expr product = new BinaryExpr(q, BinaryOperator.MUL, e);
        Expr full = modPow(a, product, n);
        Expr retained = modPow(a, new VariableExpr(negativeControl ? "r" : "e"), n);
        return new FunctionExpr("program", List.of(full, retained));
    }

    static ProgramCost cost(Expr program, BitProfile profile) {
        long tree = treeCost(program, profile.variables());
        long dag = dagCost(program, profile.variables(), new HashSet<>());
        return new ProgramCost(tree, dag);
    }

    private static long treeCost(Expr expression, Map<String, Integer> bits) {
        long own = expression instanceof FunctionExpr function && isModPow(function)
            ? exponentWork(function.arguments().get(1), bits)
            : 0;
        long children = 0;
        if (expression instanceof BinaryExpr binary) {
            children = add(treeCost(binary.left(), bits), treeCost(binary.right(), bits));
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                children = add(children, treeCost(argument, bits));
            }
        }
        return add(own, children);
    }

    private static long dagCost(Expr expression, Map<String, Integer> bits, Set<Expr> computedModPows) {
        if (expression instanceof FunctionExpr function && isModPow(function)) {
            if (!computedModPows.add(function)) {
                return 0;
            }
            long total = exponentWork(function.arguments().get(1), bits);
            for (Expr argument : function.arguments()) {
                total = add(total, dagCost(argument, bits, computedModPows));
            }
            return total;
        }
        long total = 0;
        if (expression instanceof BinaryExpr binary) {
            total = add(dagCost(binary.left(), bits, computedModPows),
                dagCost(binary.right(), bits, computedModPows));
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                total = add(total, dagCost(argument, bits, computedModPows));
            }
        }
        return total;
    }

    private static long exponentWork(Expr exponent, Map<String, Integer> bits) {
        if (exponent instanceof VariableExpr variable) {
            Integer value = bits.get(variable.name());
            if (value == null) {
                throw new IllegalArgumentException("missing exponent bit length for " + variable.name());
            }
            return value;
        }
        if (exponent instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            return add(exponentWork(binary.left(), bits), exponentWork(binary.right(), bits));
        }
        throw new IllegalArgumentException("unsupported exponent work expression: " + exponent);
    }

    private static long add(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static boolean hasSharedEResidue(Expr program) {
        if (!(program instanceof FunctionExpr root)
                || !root.name().equals("program") || root.arguments().size() != 2) {
            return false;
        }
        Expr retained = root.arguments().get(1);
        if (!(retained instanceof FunctionExpr retainedPow) || !isModPow(retainedPow)
                || !(retainedPow.arguments().get(1) instanceof VariableExpr exponent)
                || !exponent.name().equals("e")) {
            return false;
        }
        Expr first = root.arguments().get(0);
        if (!(first instanceof FunctionExpr outer) || !isModPow(outer)
                || !(outer.arguments().get(1) instanceof VariableExpr outerExponent)
                || !outerExponent.name().equals("q")) {
            return false;
        }
        return outer.arguments().get(0).equals(retained);
    }

    private record Generated(String rule, Expr target) {
    }

    private static TypedMoveSearch.TypedProvider compositionProvider() {
        var descriptor = new MoveProvider.Descriptor(
            "research-modpow-composition",
            "modpow-composition",
            SearchMove.SourceKind.HYPOTHESIS,
            SearchMove.ProofStrength.VERIFIED,
            proofAssumptions(),
            SearchMove.ValueEvidence.UNKNOWN,
            REVISION
        );
        return new TypedMoveSearch.TypedProvider() {
            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public Batch candidates(MoveState state, MoveContext context) {
                Expr source = CODEC.decodeExpression(state.expression());
                List<Generated> generated = compositions(source);
                TransformationWorkMetrics work = TransformationWorkMetrics.flatEngine(generated.size());
                var moves = new ArrayList<SearchMove>(generated.size());
                for (Generated candidate : generated) {
                    String target = CODEC.encodeExpression(candidate.target());
                    String key = applicationKey(state.expression(), target, candidate.rule());
                    var transformation = new Transformation(
                        candidate.rule(),
                        target,
                        RewriteKind.NORMALIZE,
                        true,
                        0,
                        true,
                        key,
                        proofAssumptions(),
                        "research-1024",
                        "PROJECT"
                    );
                    moves.add(SearchMove.from(transformation, descriptor, work.totalWorkUnits()));
                }
                return new Batch(moves, work, true);
            }
        };
    }

    private static TypedMoveSearch.Verifier compositionVerifier() {
        return (source, move, context) -> {
            Expr target;
            try {
                target = CODEC.decodeExpression(move.transformation().transformedExpression());
            } catch (IllegalArgumentException exception) {
                return new MoveVerifier.Verification(false, 1, List.of(), "MODPOW_TARGET_DECODE_REJECTED");
            }
            String encodedSource = CODEC.encodeExpression(source.expression());
            String encodedTarget = CODEC.encodeExpression(target);
            String rule = move.transformation().rule();
            boolean provenance = move.transformation().provenance()
                    instanceof TransformationProvenance.PrimitiveRewriteSequence
                && move.transformation().primitiveStepCount() == 1
                && move.transformation().primitiveRuleIds().equals(List.of(rule));
            boolean bound = move.transformation().applicationKey()
                .equals(applicationKey(encodedSource, encodedTarget, rule));
            int proofInstances = compositionDifferenceCount(source.expression(), target, rule);
            boolean domain = domainContractSatisfied(source.expression(), context.initialAssumptions())
                && domainContractSatisfied(target, context.initialAssumptions());
            long work = add(nodeCount(source.expression()), nodeCount(target));
            var step = move.transformation();
            boolean retainedContract = step.assumptions().equals(proofAssumptions())
                && context.initialAssumptions().containsAll(proofAssumptions())
                && step.kind() == RewriteKind.NORMALIZE
                && step.mayIncreaseComplexity()
                && step.estimatedCostDelta() == 0
                && step.equivalencePreservingByConstruction()
                && step.packId().equals("research-1024")
                && step.license().equals("PROJECT");
            boolean accepted = provenance && bound && domain && retainedContract && proofInstances == 1;
            return new MoveVerifier.Verification(
                accepted,
                work,
                accepted ? List.of("modpow-product-law/v2:" + proofDigest(encodedSource, encodedTarget, rule))
                    : List.of(),
                accepted ? "MODPOW_PRODUCT_COMPOSITION_VERIFIED" : "MODPOW_PRODUCT_COMPOSITION_REJECTED"
            );
        };
    }

    /**
     * Research proof-domain guard for BigInteger-style modular exponentiation.
     * Every exponent must be a nonnegative integer expression, every modulus a
     * positive integer variable, and variable bases must be declared integral.
     * The proof receipt therefore cannot be granted from AST shape alone.
     */
    static boolean domainContractSatisfied(Expr expression, List<String> assumptions) {
        Set<String> contract = Set.copyOf(assumptions);
        return domainContractSatisfied(expression, contract);
    }

    private static boolean domainContractSatisfied(Expr expression, Set<String> assumptions) {
        if (expression instanceof FunctionExpr function) {
            if (isModPow(function)) {
                Expr base = function.arguments().get(0);
                Expr exponent = function.arguments().get(1);
                Expr modulus = function.arguments().get(2);
                if (!integerValued(base, assumptions)
                        || !nonnegativeInteger(exponent, assumptions)
                        || !positiveIntegerModulus(modulus, assumptions)) {
                    return false;
                }
            }
            for (Expr argument : function.arguments()) {
                if (!domainContractSatisfied(argument, assumptions)) {
                    return false;
                }
            }
        } else if (expression instanceof BinaryExpr binary) {
            return domainContractSatisfied(binary.left(), assumptions)
                && domainContractSatisfied(binary.right(), assumptions);
        }
        return true;
    }

    private static boolean integerValued(Expr expression, Set<String> assumptions) {
        if (expression instanceof VariableExpr variable) {
            return assumptions.contains(variable.name() + " integer");
        }
        if (expression instanceof FunctionExpr function && isModPow(function)) {
            return domainContractSatisfied(function, assumptions);
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            return integerValued(binary.left(), assumptions)
                && integerValued(binary.right(), assumptions);
        }
        return false;
    }

    private static boolean nonnegativeInteger(Expr expression, Set<String> assumptions) {
        if (expression instanceof VariableExpr variable) {
            return assumptions.contains(variable.name() + " integer")
                && assumptions.contains(variable.name() + " >= 0");
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            return nonnegativeInteger(binary.left(), assumptions)
                && nonnegativeInteger(binary.right(), assumptions);
        }
        return false;
    }

    private static boolean positiveIntegerModulus(Expr expression, Set<String> assumptions) {
        return expression instanceof VariableExpr variable
            && assumptions.contains(variable.name() + " integer")
            && assumptions.contains(variable.name() + " > 0");
    }

    /**
     * Independent shape audit: exactly one subtree must instantiate
     * (a^(u*v) mod n) = ((a^u mod n)^v mod n), in the selected factor order.
     */
    static int compositionDifferenceCount(Expr source, Expr target, String rule) {
        if (source.equals(target)) {
            return 0;
        }
        if (directComposition(source, target, rule)) {
            return 1;
        }
        if (source instanceof BinaryExpr left && target instanceof BinaryExpr right
                && left.operator() == right.operator()) {
            int a = compositionDifferenceCount(left.left(), right.left(), rule);
            int b = compositionDifferenceCount(left.right(), right.right(), rule);
            return combineDifferenceCounts(a, b);
        }
        if (source instanceof FunctionExpr left && target instanceof FunctionExpr right
                && left.name().equals(right.name())
                && left.arguments().size() == right.arguments().size()) {
            int total = 0;
            for (int i = 0; i < left.arguments().size(); i++) {
                int current = compositionDifferenceCount(left.arguments().get(i), right.arguments().get(i), rule);
                if (current < 0) {
                    return -1;
                }
                total += current;
                if (total > 1) {
                    return -1;
                }
            }
            return total;
        }
        return -1;
    }

    private static int combineDifferenceCounts(int left, int right) {
        if (left < 0 || right < 0 || left + right > 1) {
            return -1;
        }
        return left + right;
    }

    private static boolean directComposition(Expr source, Expr target, String rule) {
        if (!(source instanceof FunctionExpr before) || !isModPow(before)
                || !(before.arguments().get(1) instanceof BinaryExpr product)
                || product.operator() != BinaryOperator.MUL
                || !(target instanceof FunctionExpr after) || !isModPow(after)) {
            return false;
        }
        if (!before.arguments().get(2).equals(after.arguments().get(2))) {
            return false;
        }
        Expr innerExponent;
        Expr outerExponent;
        if (RULE_LEFT.equals(rule)) {
            innerExponent = product.left();
            outerExponent = product.right();
        } else if (RULE_RIGHT.equals(rule)) {
            innerExponent = product.right();
            outerExponent = product.left();
        } else {
            return false;
        }
        Expr expectedInner = modPow(before.arguments().get(0), innerExponent, before.arguments().get(2));
        return after.arguments().get(0).equals(expectedInner)
            && after.arguments().get(1).equals(outerExponent);
    }

    private static List<Generated> compositions(Expr expression) {
        var raw = new ArrayList<Generated>();
        collectCompositions(expression, raw);
        var unique = new LinkedHashMap<String, Generated>();
        for (Generated candidate : raw) {
            String identity = candidate.rule() + "\n" + CODEC.encodeExpression(candidate.target());
            unique.putIfAbsent(identity, candidate);
        }
        return List.copyOf(unique.values());
    }

    private static void collectCompositions(Expr expression, List<Generated> output) {
        if (expression instanceof FunctionExpr function && isModPow(function)
                && function.arguments().get(1) instanceof BinaryExpr product
                && product.operator() == BinaryOperator.MUL) {
            Expr base = function.arguments().get(0);
            Expr modulus = function.arguments().get(2);
            output.add(new Generated(RULE_LEFT,
                modPow(modPow(base, product.left(), modulus), product.right(), modulus)));
            output.add(new Generated(RULE_RIGHT,
                modPow(modPow(base, product.right(), modulus), product.left(), modulus)));
        }
        if (expression instanceof BinaryExpr binary) {
            for (Generated child : compositions(binary.left())) {
                output.add(new Generated(child.rule(),
                    new BinaryExpr(child.target(), binary.operator(), binary.right())));
            }
            for (Generated child : compositions(binary.right())) {
                output.add(new Generated(child.rule(),
                    new BinaryExpr(binary.left(), binary.operator(), child.target())));
            }
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0; index < function.arguments().size(); index++) {
                Expr argument = function.arguments().get(index);
                for (Generated child : compositions(argument)) {
                    var arguments = new ArrayList<>(function.arguments());
                    arguments.set(index, child.target());
                    output.add(new Generated(child.rule(), new FunctionExpr(function.name(), arguments)));
                }
            }
        }
    }

    private static Expr modPow(Expr base, Expr exponent, Expr modulus) {
        return new FunctionExpr("modpow", List.of(base, exponent, modulus));
    }

    private static boolean isModPow(FunctionExpr function) {
        return function.name().equals("modpow") && function.arguments().size() == 3;
    }

    private static long nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return add(1, add(nodeCount(binary.left()), nodeCount(binary.right())));
        }
        if (expression instanceof FunctionExpr function) {
            long total = 1;
            for (Expr argument : function.arguments()) {
                total = add(total, nodeCount(argument));
            }
            return total;
        }
        return 1;
    }

    private static String applicationKey(String source, String target, String rule) {
        return "modpow-study/v2:" + proofDigest(source, target, rule);
    }

    static List<String> proofAssumptions() {
        return AssumptionSignature.ofExpressions(DOMAIN_ASSUMPTIONS).normalizedAssumptions();
    }

    private static String proofDigest(String source, String target, String rule) {
        return sha256("modpow-conditional-proof/v2\n" + source + "\n" + target + "\n" + rule
            + "\n" + String.join("\n", proofAssumptions()));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
