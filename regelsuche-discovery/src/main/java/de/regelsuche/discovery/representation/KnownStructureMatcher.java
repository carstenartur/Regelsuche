package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic known-structure matching at every AST occurrence. */
public final class KnownStructureMatcher {
    private final KnownStructureCatalog catalog;
    private final ExpressionParser parser;
    private final ExprMatcher.MatchOptions matchOptions;

    public KnownStructureMatcher(KnownStructureCatalog catalog) {
        this(
            catalog,
            new ExpressionParser(),
            EquivalentExpressionProvider.identity()
        );
    }

    public KnownStructureMatcher(
        KnownStructureCatalog catalog,
        EquivalentExpressionProvider representativeProvider
    ) {
        this(catalog, new ExpressionParser(), representativeProvider);
    }

    public KnownStructureMatcher(
        KnownStructureCatalog catalog,
        ExpressionParser parser
    ) {
        this(catalog, parser, EquivalentExpressionProvider.identity());
    }

    public KnownStructureMatcher(
        KnownStructureCatalog catalog,
        ExpressionParser parser,
        EquivalentExpressionProvider representativeProvider
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.matchOptions = ExprMatcher.MatchOptions.defaults()
            .withRepresentativeProvider(Objects.requireNonNull(
                representativeProvider,
                "representativeProvider"
            ));
    }

    public String catalogHash() {
        return catalog.contentHash();
    }

    /**
     * Strict convenience API. A bounded recognition limit fails closed instead
     * of being reported as a mathematical non-match.
     */
    public List<KnownStructureMatch> match(String expression) {
        Objects.requireNonNull(expression, "expression");
        return match(parser.parseTerm(expression));
    }

    /**
     * Strict convenience API. Use {@link #scan(Expr)} when diagnostics should
     * be retained instead of raised.
     */
    public List<KnownStructureMatch> match(Expr expression) {
        ScanResult result = scan(expression);
        if (!result.complete()) {
            throw new IncompleteRecognitionException(result.diagnostics());
        }
        return result.matches();
    }

    public ScanResult scan(String expression) {
        Objects.requireNonNull(expression, "expression");
        return scan(parser.parseTerm(expression));
    }

    public ScanResult scan(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        Map<String, KnownStructureMatch> matches = new LinkedHashMap<>();
        List<RecognitionDiagnostic> diagnostics = new ArrayList<>();
        collect(
            expression,
            ExpressionOccurrencePath.root(),
            matches,
            diagnostics
        );
        List<KnownStructureMatch> orderedMatches = matches.values().stream()
            .sorted(Comparator
                .comparing(KnownStructureMatch::structureId)
                .thenComparing(KnownStructureMatch::occurrencePath)
                .thenComparing(KnownStructureMatch::identity))
            .toList();
        List<RecognitionDiagnostic> orderedDiagnostics = diagnostics.stream()
            .sorted(Comparator
                .comparing(RecognitionDiagnostic::structureId)
                .thenComparing(RecognitionDiagnostic::occurrencePath)
                .thenComparing(RecognitionDiagnostic::code)
                .thenComparing(RecognitionDiagnostic::matcherDescriptor))
            .toList();
        return new ScanResult(orderedMatches, orderedDiagnostics);
    }

    private void collect(
        Expr expression,
        ExpressionOccurrencePath path,
        Map<String, KnownStructureMatch> matches,
        List<RecognitionDiagnostic> diagnostics
    ) {
        for (KnownStructure structure : catalog.structures()) {
            ExprMatcher.MatchOutcome outcome = structure.matcher().match(
                expression,
                matchOptions
            );
            for (ExprMatcher.MatchDiagnostic diagnostic
                    : outcome.diagnostics()) {
                diagnostics.add(new RecognitionDiagnostic(
                    structure.id(),
                    path,
                    diagnostic.code(),
                    diagnostic.matcherDescriptor()
                ));
            }
            for (ExprMatcher.MatchResult result : outcome.matches()) {
                Map<String, String> renderedBindings =
                    new LinkedHashMap<>();
                result.bindings().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> renderedBindings.put(
                        entry.getKey(),
                        ExpressionFormatter.format(entry.getValue())
                    ));
                KnownStructureMatch match = new KnownStructureMatch(
                    structure.id(),
                    structure.domainId(),
                    path,
                    renderedBindings,
                    structure.requiredAssumptions(),
                    structure.consequenceIds(),
                    structure.provenance(),
                    recognitionMode(result.recognitionStrength()),
                    ExpressionFormatter.format(result.representative()),
                    result.representativeIndex()
                );
                matches.putIfAbsent(match.identity(), match);
            }
        }

        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), path.append(0), matches, diagnostics);
            collect(binary.right(), path.append(1), matches, diagnostics);
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0;
                    index < function.arguments().size();
                    index++) {
                collect(
                    function.arguments().get(index),
                    path.append(index),
                    matches,
                    diagnostics
                );
            }
        }
    }

    private static String recognitionMode(
        ExprMatcher.RecognitionStrength strength
    ) {
        return switch (strength) {
            case EXACT -> KnownStructureMatch.RECOGNITION_EXACT;
            case EQUIVALENCE_AWARE ->
                KnownStructureMatch.RECOGNITION_EQUIVALENCE_AWARE;
            case BOUNDED_REPRESENTATIVE ->
                KnownStructureMatch.RECOGNITION_BOUNDED_REPRESENTATIVE;
        };
    }

    public record ScanResult(
        List<KnownStructureMatch> matches,
        List<RecognitionDiagnostic> diagnostics
    ) {
        public ScanResult {
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        public boolean complete() {
            return diagnostics.isEmpty();
        }
    }

    public record RecognitionDiagnostic(
        String structureId,
        ExpressionOccurrencePath occurrencePath,
        String code,
        String matcherDescriptor
    ) {
        public RecognitionDiagnostic {
            structureId = RepresentationCandidateAssessment.requireText(
                structureId, "structureId");
            occurrencePath = Objects.requireNonNull(
                occurrencePath, "occurrencePath");
            code = RepresentationCandidateAssessment.requireText(
                code, "code");
            matcherDescriptor =
                RepresentationCandidateAssessment.requireText(
                    matcherDescriptor,
                    "matcherDescriptor"
                );
        }
    }

    public static final class IncompleteRecognitionException
        extends IllegalStateException {
        private final List<RecognitionDiagnostic> diagnostics;

        private IncompleteRecognitionException(
            List<RecognitionDiagnostic> diagnostics
        ) {
            super("known-structure recognition was inconclusive: "
                + diagnostics);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<RecognitionDiagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
