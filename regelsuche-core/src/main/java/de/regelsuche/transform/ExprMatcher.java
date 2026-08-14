package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Declarative, nestable matcher algebra for symbolic expressions.
 *
 * <p>A matcher is intentionally not an {@link ExprTemplate}. Nodes such as
 * {@link Not}, {@link Contains} and {@link AnyOf} can recognize expressions but
 * do not describe one unique expression that could be instantiated. Existing
 * {@link PatternExpr} values remain usable through {@link #pattern(PatternExpr)}.
 * The algebra is sealed so knowledge packs can be inspected, hashed and
 * executed without loading arbitrary predicate code.</p>
 */
public sealed interface ExprMatcher
    permits ExprMatcher.Any, ExprMatcher.LiteralNumber,
        ExprMatcher.LiteralVariable, ExprMatcher.NumberProperty,
        ExprMatcher.Pattern, ExprMatcher.Bind, ExprMatcher.AllOf,
        ExprMatcher.AnyOf, ExprMatcher.Not, ExprMatcher.Operation,
        ExprMatcher.Function, ExprMatcher.Contains,
        ExprMatcher.Equivalent, ExprMatcher.Where {

    default MatchOutcome match(Expr expression) {
        return match(expression, MatchOptions.defaults());
    }

    default MatchOutcome match(Expr expression, MatchOptions options) {
        return ExprMatcherEngine.match(
            this,
            Objects.requireNonNull(expression, "expression"),
            Objects.requireNonNull(options, "options")
        );
    }

    /** Stable structural description used by content-addressed catalogs. */
    String canonicalDescriptor();

    static ExprMatcher any() {
        return new Any();
    }

    static ExprMatcher literalNumber(double value) {
        return new LiteralNumber(value);
    }

    static ExprMatcher literalVariable(String name) {
        return new LiteralVariable(name);
    }

    static ExprMatcher numberLiteral() {
        return new NumberProperty(NumberPropertyKind.NUMBER_LITERAL);
    }

    static ExprMatcher integerLiteral() {
        return new NumberProperty(NumberPropertyKind.INTEGER_LITERAL);
    }

    static ExprMatcher nonZeroNumberLiteral() {
        return new NumberProperty(
            NumberPropertyKind.NON_ZERO_NUMBER_LITERAL);
    }

    static ExprMatcher pattern(PatternExpr pattern) {
        return pattern(pattern, RecognitionProfile.exact());
    }

    static ExprMatcher pattern(
        PatternExpr pattern,
        RecognitionProfile profile
    ) {
        Pattern structural = new Pattern(pattern, profile);
        if (profile.recognitionRuleIds().isEmpty()
                || profile.maxEquivalenceDepth() == 0) {
            return structural;
        }
        return new Equivalent(profile, structural);
    }

    static ExprMatcher bind(String name, ExprMatcher matcher) {
        return new Bind(name, matcher, RecognitionProfile.exact());
    }

    static ExprMatcher bindEquivalent(
        String name,
        ExprMatcher matcher,
        RecognitionProfile equalityProfile
    ) {
        return new Bind(name, matcher, equalityProfile);
    }

    static ExprMatcher allOf(ExprMatcher... matchers) {
        return new AllOf(List.of(matchers));
    }

    static ExprMatcher anyOf(ExprMatcher... matchers) {
        return new AnyOf(List.of(matchers));
    }

    static ExprMatcher not(ExprMatcher matcher) {
        return new Not(matcher);
    }

    static ExprMatcher op(
        BinaryOperator operator,
        ExprMatcher left,
        ExprMatcher right
    ) {
        return new Operation(operator, left, right);
    }

    static ExprMatcher fn(String name, ExprMatcher... arguments) {
        return new Function(name, List.of(arguments));
    }

    /** Matches when the nested matcher succeeds at this node or a descendant. */
    static ExprMatcher contains(ExprMatcher matcher) {
        return new Contains(matcher);
    }

    /**
     * Applies the nested matcher to a bounded set of equivalent
     * representatives supplied by {@link MatchOptions#representativeProvider()}.
     */
    static ExprMatcher equivalent(
        RecognitionProfile profile,
        ExprMatcher matcher
    ) {
        return new Equivalent(profile, matcher);
    }

    static ExprMatcher where(
        ExprMatcher matcher,
        Constraint constraint
    ) {
        return new Where(matcher, constraint);
    }

    static Constraint bindingMatches(
        String bindingName,
        ExprMatcher matcher
    ) {
        return new BindingMatches(bindingName, matcher);
    }

    static Constraint sameAs(
        String leftBinding,
        String rightBinding
    ) {
        return sameAs(
            leftBinding,
            rightBinding,
            RecognitionProfile.exact()
        );
    }

    static Constraint sameAs(
        String leftBinding,
        String rightBinding,
        RecognitionProfile profile
    ) {
        return new SameAs(leftBinding, rightBinding, profile);
    }

    static Constraint allConstraints(Constraint... constraints) {
        return new AllConstraints(List.of(constraints));
    }

    static Constraint anyConstraint(Constraint... constraints) {
        return new AnyConstraint(List.of(constraints));
    }

    static Constraint notConstraint(Constraint constraint) {
        return new NotConstraint(constraint);
    }

    record MatchOptions(
        EquivalentExpressionProvider representativeProvider,
        int maxResults,
        int maxSteps,
        int maxPatternBranches
    ) {
        public MatchOptions {
            representativeProvider = representativeProvider == null
                ? EquivalentExpressionProvider.identity()
                : representativeProvider;
            if (maxResults < 1 || maxSteps < 1
                    || maxPatternBranches < 1) {
                throw new IllegalArgumentException(
                    "matcher limits must be positive");
            }
        }

        public static MatchOptions defaults() {
            return new MatchOptions(
                EquivalentExpressionProvider.identity(),
                64,
                20_000,
                10_000
            );
        }

        public MatchOptions withRepresentativeProvider(
            EquivalentExpressionProvider provider
        ) {
            return new MatchOptions(
                provider,
                maxResults,
                maxSteps,
                maxPatternBranches
            );
        }
    }

    enum MatchStatus {
        MATCHED,
        NOT_MATCHED,
        INCONCLUSIVE
    }

    enum RecognitionStrength {
        EXACT,
        EQUIVALENCE_AWARE,
        BOUNDED_REPRESENTATIVE;

        static RecognitionStrength strongest(
            RecognitionStrength left,
            RecognitionStrength right
        ) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    record MatchResult(
        Map<String, Expr> bindings,
        Expr representative,
        int representativeIndex,
        RecognitionStrength recognitionStrength,
        List<String> trace
    ) {
        public MatchResult {
            Objects.requireNonNull(bindings, "bindings");
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(
                new TreeMap<>(bindings)));
            representative = Objects.requireNonNull(
                representative, "representative");
            if (representativeIndex < 0) {
                throw new IllegalArgumentException(
                    "representativeIndex must not be negative");
            }
            recognitionStrength = Objects.requireNonNull(
                recognitionStrength, "recognitionStrength");
            trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        }
    }

    record MatchDiagnostic(
        String code,
        String matcherDescriptor
    ) {
        public MatchDiagnostic {
            code = requireText(code, "code");
            matcherDescriptor = requireText(
                matcherDescriptor, "matcherDescriptor");
        }
    }

    record MatchOutcome(
        List<MatchResult> matches,
        List<MatchDiagnostic> diagnostics,
        int evaluatedSteps,
        int patternBranches
    ) {
        public MatchOutcome {
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics"));
            if (evaluatedSteps < 0 || patternBranches < 0) {
                throw new IllegalArgumentException(
                    "work counters must not be negative");
            }
        }

        public MatchStatus status() {
            if (!matches.isEmpty()) {
                return MatchStatus.MATCHED;
            }
            return diagnostics.isEmpty()
                ? MatchStatus.NOT_MATCHED
                : MatchStatus.INCONCLUSIVE;
        }

        public boolean matched() {
            return !matches.isEmpty();
        }

        public boolean complete() {
            return diagnostics.isEmpty();
        }
    }

    record Any() implements ExprMatcher {
        @Override
        public String canonicalDescriptor() {
            return descriptor("any");
        }
    }

    record LiteralNumber(double value) implements ExprMatcher {
        public LiteralNumber {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                    "literal number must be finite");
            }
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor("literal-number", Double.toString(value));
        }
    }

    record LiteralVariable(String name) implements ExprMatcher {
        public LiteralVariable {
            name = requireText(name, "name");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor("literal-variable", name);
        }
    }

    enum NumberPropertyKind {
        NUMBER_LITERAL,
        INTEGER_LITERAL,
        NON_ZERO_NUMBER_LITERAL
    }

    record NumberProperty(
        NumberPropertyKind kind
    ) implements ExprMatcher {
        public NumberProperty {
            kind = Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor("number-property", kind.name());
        }
    }

    record Pattern(
        PatternExpr pattern,
        RecognitionProfile recognitionProfile
    ) implements ExprMatcher {
        public Pattern {
            pattern = Objects.requireNonNull(pattern, "pattern");
            recognitionProfile = recognitionProfile == null
                ? RecognitionProfile.exact()
                : recognitionProfile;
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "pattern",
                pattern.toString(),
                profileDescriptor(recognitionProfile)
            );
        }
    }

    record Bind(
        String name,
        ExprMatcher matcher,
        RecognitionProfile equalityProfile
    ) implements ExprMatcher {
        public Bind {
            name = requireText(name, "name");
            matcher = Objects.requireNonNull(matcher, "matcher");
            equalityProfile = equalityProfile == null
                ? RecognitionProfile.exact()
                : equalityProfile;
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "bind",
                name,
                matcher.canonicalDescriptor(),
                profileDescriptor(equalityProfile)
            );
        }
    }

    record AllOf(List<ExprMatcher> matchers) implements ExprMatcher {
        public AllOf {
            matchers = matcherList(matchers, "matchers");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "all-of",
                matcherDescriptors(matchers)
            );
        }
    }

    record AnyOf(List<ExprMatcher> matchers) implements ExprMatcher {
        public AnyOf {
            matchers = matcherList(matchers, "matchers");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "any-of",
                matcherDescriptors(matchers)
            );
        }
    }

    record Not(ExprMatcher matcher) implements ExprMatcher {
        public Not {
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor("not", matcher.canonicalDescriptor());
        }
    }

    record Operation(
        BinaryOperator operator,
        ExprMatcher left,
        ExprMatcher right
    ) implements ExprMatcher {
        public Operation {
            operator = Objects.requireNonNull(operator, "operator");
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "operation",
                operator.name(),
                left.canonicalDescriptor(),
                right.canonicalDescriptor()
            );
        }
    }

    record Function(
        String name,
        List<ExprMatcher> arguments
    ) implements ExprMatcher {
        public Function {
            name = requireText(name, "name");
            arguments = matcherList(arguments, "arguments");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "function",
                name,
                matcherDescriptors(arguments)
            );
        }
    }

    record Contains(ExprMatcher matcher) implements ExprMatcher {
        public Contains {
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "contains",
                matcher.canonicalDescriptor()
            );
        }
    }

    record Equivalent(
        RecognitionProfile recognitionProfile,
        ExprMatcher matcher
    ) implements ExprMatcher {
        public Equivalent {
            recognitionProfile = recognitionProfile == null
                ? RecognitionProfile.exact()
                : recognitionProfile;
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "equivalent",
                profileDescriptor(recognitionProfile),
                matcher.canonicalDescriptor()
            );
        }
    }

    record Where(
        ExprMatcher matcher,
        Constraint constraint
    ) implements ExprMatcher {
        public Where {
            matcher = Objects.requireNonNull(matcher, "matcher");
            constraint = Objects.requireNonNull(constraint, "constraint");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "where",
                matcher.canonicalDescriptor(),
                constraint.canonicalDescriptor()
            );
        }
    }

    sealed interface Constraint
        permits BindingMatches, SameAs, AllConstraints,
            AnyConstraint, NotConstraint {
        String canonicalDescriptor();
    }

    record BindingMatches(
        String bindingName,
        ExprMatcher matcher
    ) implements Constraint {
        public BindingMatches {
            bindingName = requireText(bindingName, "bindingName");
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "binding-matches",
                bindingName,
                matcher.canonicalDescriptor()
            );
        }
    }

    record SameAs(
        String leftBinding,
        String rightBinding,
        RecognitionProfile recognitionProfile
    ) implements Constraint {
        public SameAs {
            leftBinding = requireText(leftBinding, "leftBinding");
            rightBinding = requireText(rightBinding, "rightBinding");
            recognitionProfile = recognitionProfile == null
                ? RecognitionProfile.exact()
                : recognitionProfile;
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "same-as",
                leftBinding,
                rightBinding,
                profileDescriptor(recognitionProfile)
            );
        }
    }

    record AllConstraints(
        List<Constraint> constraints
    ) implements Constraint {
        public AllConstraints {
            constraints = constraintList(constraints, "constraints");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "all-constraints",
                constraintDescriptors(constraints)
            );
        }
    }

    record AnyConstraint(
        List<Constraint> constraints
    ) implements Constraint {
        public AnyConstraint {
            constraints = constraintList(constraints, "constraints");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "any-constraint",
                constraintDescriptors(constraints)
            );
        }
    }

    record NotConstraint(
        Constraint constraint
    ) implements Constraint {
        public NotConstraint {
            constraint = Objects.requireNonNull(constraint, "constraint");
        }

        @Override
        public String canonicalDescriptor() {
            return descriptor(
                "not-constraint",
                constraint.canonicalDescriptor()
            );
        }
    }

    private static List<ExprMatcher> matcherList(
        List<ExprMatcher> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                field + " must not be empty");
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, field + " entry"))
            .toList();
    }

    private static List<Constraint> constraintList(
        List<Constraint> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                field + " must not be empty");
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, field + " entry"))
            .toList();
    }

    private static String matcherDescriptors(List<ExprMatcher> matchers) {
        return descriptor(
            "matcher-list",
            matchers.stream()
                .map(ExprMatcher::canonicalDescriptor)
                .toArray(String[]::new)
        );
    }

    private static String constraintDescriptors(
        List<Constraint> constraints
    ) {
        return descriptor(
            "constraint-list",
            constraints.stream()
                .map(Constraint::canonicalDescriptor)
                .toArray(String[]::new)
        );
    }

    private static String profileDescriptor(RecognitionProfile profile) {
        return descriptor(
            "recognition-profile",
            descriptor(
                "associative",
                profile.associativeOperators().stream()
                    .map(Enum::name)
                    .sorted()
                    .toArray(String[]::new)
            ),
            descriptor(
                "commutative",
                profile.commutativeOperators().stream()
                    .map(Enum::name)
                    .sorted()
                    .toArray(String[]::new)
            ),
            Boolean.toString(profile.inferAlgebraicBindings()),
            descriptor(
                "recognition-rules",
                profile.recognitionRuleIds().stream()
                    .sorted()
                    .toArray(String[]::new)
            ),
            Integer.toString(profile.maxEquivalenceDepth())
        );
    }

    private static String descriptor(String type, String... fields) {
        StringBuilder result = new StringBuilder();
        appendField(result, requireText(type, "type"));
        Arrays.stream(fields).forEach(field ->
            appendField(result, Objects.requireNonNull(field, "field")));
        return result.toString();
    }

    private static void appendField(StringBuilder result, String field) {
        result.append(field.length()).append(':').append(field);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
