package de.regelsuche.solver.ir;

import de.regelsuche.json.JsonWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Versioned, solver-neutral contracts for obligations and backend results. */
public final class SolverIr {
    public static final String OBLIGATION_SCHEMA = "regelsuche.solver-obligation/v1";
    public static final String RESULT_SCHEMA = "regelsuche.solver-result/v1";

    private SolverIr() {
    }

    public enum Sort {
        REAL,
        INTEGER,
        BOOLEAN
    }

    public enum Theory {
        REAL_ARITHMETIC,
        INTEGER_ARITHMETIC,
        TRANSCENDENTAL_FUNCTIONS
    }

    public enum BinaryOperator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        POWER
    }

    public enum Relation {
        EQUALS,
        NOT_EQUALS,
        LESS_THAN,
        LESS_OR_EQUAL,
        GREATER_THAN,
        GREATER_OR_EQUAL,
        IS_INTEGER
    }

    public enum RequestedEvidence {
        DECISION,
        COUNTEREXAMPLE,
        SYMBOLIC_CERTIFICATE,
        FORMAL_PROOF
    }

    public enum ResultStatus {
        CONFIRMED,
        REFUTED,
        UNKNOWN,
        TIMEOUT,
        UNSUPPORTED,
        ERROR
    }

    public enum TranslationStatus {
        LOSSLESS,
        APPROXIMATED,
        REJECTED
    }

    public sealed interface Expression permits Literal, Symbol, Binary, Call {
        String canonicalMaterial();

        Set<String> referencedSymbols();
    }

    public record Literal(String value) implements Expression {
        public Literal {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("literal value must not be blank");
            }
            try {
                BigDecimal decimal = new BigDecimal(value.trim()).stripTrailingZeros();
                value = decimal.signum() == 0 ? "0" : decimal.toPlainString();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("literal value must be decimal", exception);
            }
        }

        @Override
        public String canonicalMaterial() {
            return "literal(" + value + ')';
        }

        @Override
        public Set<String> referencedSymbols() {
            return Set.of();
        }
    }

    public record Symbol(String name) implements Expression {
        public Symbol {
            requireIdentifier(name, "symbol name");
        }

        @Override
        public String canonicalMaterial() {
            return "symbol(" + name + ')';
        }

        @Override
        public Set<String> referencedSymbols() {
            return Set.of(name);
        }
    }

    public record Binary(
        BinaryOperator operator,
        Expression left,
        Expression right
    ) implements Expression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }

        @Override
        public String canonicalMaterial() {
            return "binary(" + operator.name() + ',' + left.canonicalMaterial()
                + ',' + right.canonicalMaterial() + ')';
        }

        @Override
        public Set<String> referencedSymbols() {
            LinkedHashSet<String> result = new LinkedHashSet<>(left.referencedSymbols());
            result.addAll(right.referencedSymbols());
            return Set.copyOf(result);
        }
    }

    public record Call(String function, List<Expression> arguments) implements Expression {
        public Call {
            requireIdentifier(function, "function");
            function = function.toLowerCase(java.util.Locale.ROOT);
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException("function arguments must not be empty");
            }
        }

        @Override
        public String canonicalMaterial() {
            return "call(" + function + ',' + arguments.stream()
                .map(Expression::canonicalMaterial).toList() + ')';
        }

        @Override
        public Set<String> referencedSymbols() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            arguments.forEach(argument -> result.addAll(argument.referencedSymbols()));
            return Set.copyOf(result);
        }
    }

    public record SymbolDeclaration(String name, Sort sort) {
        public SymbolDeclaration {
            requireIdentifier(name, "declaration name");
            Objects.requireNonNull(sort, "sort");
        }

        String canonicalMaterial() {
            return name + ':' + sort.name();
        }
    }

    public record Predicate(
        String id,
        Relation relation,
        Expression left,
        Expression right
    ) {
        public Predicate {
            requireText(id, "predicate id");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(left, "left");
            if (relation == Relation.IS_INTEGER) {
                if (right != null) {
                    throw new IllegalArgumentException("IS_INTEGER must not have a right expression");
                }
            } else {
                Objects.requireNonNull(right, "right");
            }
        }

        String canonicalMaterial() {
            return id + '|' + relation.name() + '|' + left.canonicalMaterial()
                + '|' + (right == null ? "" : right.canonicalMaterial());
        }

        Set<String> referencedSymbols() {
            LinkedHashSet<String> result = new LinkedHashSet<>(left.referencedSymbols());
            if (right != null) {
                result.addAll(right.referencedSymbols());
            }
            return Set.copyOf(result);
        }
    }

    public record Goal(Relation relation, Expression left, Expression right) {
        public Goal {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (relation == Relation.IS_INTEGER) {
                throw new IllegalArgumentException("IS_INTEGER is an assumption relation, not a goal");
            }
        }

        String canonicalMaterial() {
            return relation.name() + '|' + left.canonicalMaterial()
                + '|' + right.canonicalMaterial();
        }

        Set<String> referencedSymbols() {
            LinkedHashSet<String> result = new LinkedHashSet<>(left.referencedSymbols());
            result.addAll(right.referencedSymbols());
            return Set.copyOf(result);
        }
    }

    public record SourceProvenance(
        String sourceType,
        String sourceId,
        String revisionHash
    ) {
        public SourceProvenance {
            requireText(sourceType, "sourceType");
            requireText(sourceId, "sourceId");
            requireSha(revisionHash, "revisionHash");
        }

        String canonicalMaterial() {
            return sourceType + '|' + sourceId + '|' + revisionHash;
        }
    }

    public record BackendDescriptor(
        String backendId,
        String backendVersion,
        List<Theory> supportedTheories,
        List<Relation> supportedRelations,
        List<RequestedEvidence> supportedEvidence,
        boolean deterministic
    ) {
        public BackendDescriptor {
            requireText(backendId, "backendId");
            requireText(backendVersion, "backendVersion");
            supportedTheories = sortedEnums(supportedTheories);
            supportedRelations = sortedEnums(supportedRelations);
            supportedEvidence = sortedEnums(supportedEvidence);
        }
    }

    public record Obligation(
        String schema,
        String obligationId,
        List<SymbolDeclaration> declarations,
        List<Theory> theories,
        List<Predicate> assumptions,
        Goal goal,
        RequestedEvidence requestedEvidence,
        SourceProvenance provenance,
        String contentHash
    ) {
        public Obligation {
            if (!OBLIGATION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported solver obligation schema");
            }
            requireText(obligationId, "obligationId");
            declarations = sortedDeclarations(declarations);
            theories = sortedEnums(theories);
            assumptions = sortedPredicates(assumptions);
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(requestedEvidence, "requestedEvidence");
            Objects.requireNonNull(provenance, "provenance");
            validateDeclaredSymbols(declarations, assumptions, goal);
            requireSha(contentHash, "contentHash");
            String expected = obligationHash(
                schema, obligationId, declarations, theories, assumptions,
                goal, requestedEvidence, provenance);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("solver obligation hash does not match canonical fields");
            }
        }

        public static Obligation create(
            String obligationId,
            List<SymbolDeclaration> declarations,
            List<Theory> theories,
            List<Predicate> assumptions,
            Goal goal,
            RequestedEvidence requestedEvidence,
            SourceProvenance provenance
        ) {
            List<SymbolDeclaration> orderedDeclarations = sortedDeclarations(declarations);
            List<Theory> orderedTheories = sortedEnums(theories);
            List<Predicate> orderedAssumptions = sortedPredicates(assumptions);
            String hash = obligationHash(
                OBLIGATION_SCHEMA, obligationId, orderedDeclarations, orderedTheories,
                orderedAssumptions, goal, requestedEvidence, provenance);
            return new Obligation(
                OBLIGATION_SCHEMA, obligationId, orderedDeclarations, orderedTheories,
                orderedAssumptions, goal, requestedEvidence, provenance, hash);
        }

        public String goalHash() {
            return sha256(goal.canonicalMaterial());
        }

        public String assumptionsHash() {
            return sha256(assumptions.stream().map(Predicate::canonicalMaterial).toList().toString());
        }

        public String toCanonicalJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("obligationId", obligationId)
                .array("declarations", array -> declarations.forEach(item ->
                    array.objectValue(object -> object
                        .property("name", item.name())
                        .property("sort", item.sort().name()))))
                .stringArray("theories", theories.stream().map(Enum::name).toList())
                .array("assumptions", array -> assumptions.forEach(item ->
                    array.objectValue(object -> writePredicate(object, item))))
                .object("goal", object -> writeGoal(object, goal))
                .property("requestedEvidence", requestedEvidence.name())
                .object("provenance", object -> object
                    .property("sourceType", provenance.sourceType())
                    .property("sourceId", provenance.sourceId())
                    .property("revisionHash", provenance.revisionHash()))
                .property("contentHash", contentHash);
            return json.endObject().toString();
        }
    }

    public record SolverResult(
        String schema,
        String obligationHash,
        String goalHash,
        String assumptionsHash,
        String backendId,
        String backendVersion,
        ResultStatus status,
        TranslationStatus translationStatus,
        List<String> usedCapabilities,
        List<String> translationIssues,
        String invocationHash,
        String message,
        Map<String, String> counterexample,
        String certificateHash,
        String contentHash
    ) {
        public SolverResult {
            if (!RESULT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported solver result schema");
            }
            requireSha(obligationHash, "obligationHash");
            requireSha(goalHash, "goalHash");
            requireSha(assumptionsHash, "assumptionsHash");
            requireText(backendId, "backendId");
            requireText(backendVersion, "backendVersion");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(translationStatus, "translationStatus");
            usedCapabilities = sortedStrings(usedCapabilities);
            translationIssues = sortedStrings(translationIssues);
            requireSha(invocationHash, "invocationHash");
            message = message == null ? "" : message;
            counterexample = immutableSortedMap(counterexample);
            certificateHash = certificateHash == null ? "" : certificateHash;
            if (!certificateHash.isEmpty()) {
                requireSha(certificateHash, "certificateHash");
            }
            if (status == ResultStatus.UNSUPPORTED
                    && (translationStatus != TranslationStatus.REJECTED
                        || translationIssues.isEmpty())) {
                throw new IllegalArgumentException(
                    "UNSUPPORTED requires rejected translation with visible issues");
            }
            if (translationStatus == TranslationStatus.REJECTED
                    && status != ResultStatus.UNSUPPORTED
                    && status != ResultStatus.ERROR) {
                throw new IllegalArgumentException(
                    "rejected translation cannot produce a solver decision");
            }
            requireSha(contentHash, "contentHash");
            String expected = resultHash(
                schema, obligationHash, goalHash, assumptionsHash,
                backendId, backendVersion, status, translationStatus,
                usedCapabilities, translationIssues, invocationHash, message,
                counterexample, certificateHash);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("solver result hash does not match canonical fields");
            }
        }

        public static SolverResult create(
            Obligation obligation,
            BackendDescriptor descriptor,
            ResultStatus status,
            TranslationStatus translationStatus,
            List<String> usedCapabilities,
            List<String> translationIssues,
            String message,
            Map<String, String> counterexample,
            String certificateHash
        ) {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(descriptor, "descriptor");
            List<String> capabilities = sortedStrings(usedCapabilities);
            List<String> issues = sortedStrings(translationIssues);
            Map<String, String> model = immutableSortedMap(counterexample);
            String normalizedMessage = message == null ? "" : message;
            String normalizedCertificate = certificateHash == null ? "" : certificateHash;
            String invocationHash = sha256(
                descriptor.backendId() + '|' + descriptor.backendVersion()
                    + '|' + obligation.contentHash() + '|' + status.name()
                    + '|' + translationStatus.name() + '|' + capabilities
                    + '|' + issues + '|' + normalizedMessage + '|' + model
                    + '|' + normalizedCertificate);
            String contentHash = resultHash(
                RESULT_SCHEMA, obligation.contentHash(), obligation.goalHash(),
                obligation.assumptionsHash(), descriptor.backendId(),
                descriptor.backendVersion(), status, translationStatus,
                capabilities, issues, invocationHash, normalizedMessage,
                model, normalizedCertificate);
            return new SolverResult(
                RESULT_SCHEMA, obligation.contentHash(), obligation.goalHash(),
                obligation.assumptionsHash(), descriptor.backendId(),
                descriptor.backendVersion(), status, translationStatus,
                capabilities, issues, invocationHash, normalizedMessage,
                model, normalizedCertificate, contentHash);
        }

        public String toCanonicalJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("obligationHash", obligationHash)
                .property("goalHash", goalHash)
                .property("assumptionsHash", assumptionsHash)
                .property("backendId", backendId)
                .property("backendVersion", backendVersion)
                .property("status", status.name())
                .property("translationStatus", translationStatus.name())
                .stringArray("usedCapabilities", usedCapabilities)
                .stringArray("translationIssues", translationIssues)
                .property("invocationHash", invocationHash)
                .property("message", message)
                .object("counterexample", object -> counterexample.forEach(object::property))
                .property("certificateHash", certificateHash)
                .property("contentHash", contentHash);
            return json.endObject().toString();
        }
    }

    public static String sha256(String material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String obligationHash(
        String schema,
        String obligationId,
        List<SymbolDeclaration> declarations,
        List<Theory> theories,
        List<Predicate> assumptions,
        Goal goal,
        RequestedEvidence requestedEvidence,
        SourceProvenance provenance
    ) {
        return sha256(
            schema + "\nid=" + obligationId
                + "\ndeclarations=" + declarations.stream()
                    .map(SymbolDeclaration::canonicalMaterial).toList()
                + "\ntheories=" + theories
                + "\nassumptions=" + assumptions.stream()
                    .map(Predicate::canonicalMaterial).toList()
                + "\ngoal=" + goal.canonicalMaterial()
                + "\nevidence=" + requestedEvidence.name()
                + "\nprovenance=" + provenance.canonicalMaterial());
    }

    private static String resultHash(
        String schema,
        String obligationHash,
        String goalHash,
        String assumptionsHash,
        String backendId,
        String backendVersion,
        ResultStatus status,
        TranslationStatus translationStatus,
        List<String> usedCapabilities,
        List<String> translationIssues,
        String invocationHash,
        String message,
        Map<String, String> counterexample,
        String certificateHash
    ) {
        return sha256(
            schema + "\nobligation=" + obligationHash
                + "\ngoal=" + goalHash
                + "\nassumptions=" + assumptionsHash
                + "\nbackend=" + backendId + '@' + backendVersion
                + "\nstatus=" + status.name()
                + "\ntranslation=" + translationStatus.name()
                + "\ncapabilities=" + usedCapabilities
                + "\nissues=" + translationIssues
                + "\ninvocation=" + invocationHash
                + "\nmessage=" + message
                + "\ncounterexample=" + counterexample
                + "\ncertificate=" + certificateHash);
    }

    private static void writePredicate(JsonWriter json, Predicate predicate) {
        json.property("id", predicate.id())
            .property("relation", predicate.relation().name())
            .object("left", object -> writeExpression(object, predicate.left()));
        if (predicate.right() == null) {
            json.nullProperty("right");
        } else {
            json.object("right", object -> writeExpression(object, predicate.right()));
        }
    }

    private static void writeGoal(JsonWriter json, Goal goal) {
        json.property("relation", goal.relation().name())
            .object("left", object -> writeExpression(object, goal.left()))
            .object("right", object -> writeExpression(object, goal.right()));
    }

    private static void writeExpression(JsonWriter json, Expression expression) {
        if (expression instanceof Literal literal) {
            json.property("kind", "LITERAL").property("value", literal.value());
        } else if (expression instanceof Symbol symbol) {
            json.property("kind", "SYMBOL").property("name", symbol.name());
        } else if (expression instanceof Binary binary) {
            json.property("kind", "BINARY")
                .property("operator", binary.operator().name())
                .object("left", object -> writeExpression(object, binary.left()))
                .object("right", object -> writeExpression(object, binary.right()));
        } else if (expression instanceof Call call) {
            json.property("kind", "CALL")
                .property("function", call.function())
                .array("arguments", array -> call.arguments().forEach(argument ->
                    array.objectValue(object -> writeExpression(object, argument))));
        } else {
            throw new IllegalArgumentException("unsupported expression type: " + expression);
        }
    }

    private static void validateDeclaredSymbols(
        List<SymbolDeclaration> declarations,
        List<Predicate> assumptions,
        Goal goal
    ) {
        Set<String> declared = declarations.stream()
            .map(SymbolDeclaration::name)
            .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> referenced = new LinkedHashSet<>(goal.referencedSymbols());
        assumptions.forEach(assumption -> referenced.addAll(assumption.referencedSymbols()));
        referenced.removeAll(declared);
        if (!referenced.isEmpty()) {
            throw new IllegalArgumentException("undeclared symbols: " + referenced);
        }
    }

    private static List<SymbolDeclaration> sortedDeclarations(
        List<SymbolDeclaration> values
    ) {
        List<SymbolDeclaration> result = values == null
            ? new ArrayList<>() : new ArrayList<>(values);
        result.sort(Comparator.comparing(SymbolDeclaration::name));
        Set<String> names = new LinkedHashSet<>();
        for (SymbolDeclaration declaration : result) {
            Objects.requireNonNull(declaration, "declaration");
            if (!names.add(declaration.name())) {
                throw new IllegalArgumentException(
                    "duplicate symbol declaration: " + declaration.name());
            }
        }
        return List.copyOf(result);
    }

    private static List<Predicate> sortedPredicates(List<Predicate> values) {
        List<Predicate> result = values == null
            ? new ArrayList<>() : new ArrayList<>(values);
        result.sort(Comparator.comparing(Predicate::id));
        Set<String> ids = new LinkedHashSet<>();
        for (Predicate predicate : result) {
            Objects.requireNonNull(predicate, "predicate");
            if (!ids.add(predicate.id())) {
                throw new IllegalArgumentException("duplicate predicate id: " + predicate.id());
            }
        }
        return List.copyOf(result);
    }

    private static <E extends Enum<E>> List<E> sortedEnums(List<E> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).distinct()
            .sorted(Comparator.comparing(Enum::name)).toList();
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
            .distinct().sorted().toList();
    }

    private static Map<String, String> immutableSortedMap(Map<String, String> values) {
        TreeMap<String, String> result = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                requireText(key, "map key");
                result.put(key, value == null ? "" : value);
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requireIdentifier(String value, String name) {
        requireText(value, name);
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(name + " must be an identifier");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
