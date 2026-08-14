package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.EquivalenceClassPatternMatcher;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.RecognitionProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic known-structure matching at every AST occurrence.
 *
 * <p>Each catalog entry owns its recognition profile. A bounded representative
 * provider may additionally expose explicitly allow-listed equivalent forms.</p>
 */
public final class KnownStructureMatcher {
    private final KnownStructureCatalog catalog;
    private final ExpressionParser parser;
    private final EquivalentExpressionProvider representativeProvider;
    private final EquivalenceClassPatternMatcher patternMatcher =
        new EquivalenceClassPatternMatcher();

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
        this.representativeProvider = Objects.requireNonNull(
            representativeProvider, "representativeProvider");
    }

    public String catalogHash() {
        return catalog.contentHash();
    }

    public List<KnownStructureMatch> match(String expression) {
        Objects.requireNonNull(expression, "expression");
        return match(parser.parseTerm(expression));
    }

    public List<KnownStructureMatch> match(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        List<KnownStructureMatch> matches = new ArrayList<>();
        collect(expression, ExpressionOccurrencePath.root(), matches);
        matches.sort(Comparator
            .comparing(KnownStructureMatch::structureId)
            .thenComparing(KnownStructureMatch::occurrencePath)
            .thenComparing(KnownStructureMatch::identity));
        return List.copyOf(matches);
    }

    private void collect(
        Expr expression,
        ExpressionOccurrencePath path,
        List<KnownStructureMatch> matches
    ) {
        for (KnownStructure structure : catalog.structures()) {
            Map<String, Expr> exactBindings = new LinkedHashMap<>();
            boolean exact = structure.pattern().match(expression, exactBindings);
            var result = exact
                ? new EquivalenceClassPatternMatcher.MatchResult(
                    true, expression, Map.copyOf(exactBindings), 0)
                : patternMatcher.match(
                    structure.pattern(),
                    expression,
                    structure.recognitionProfile(),
                    representativeProvider
                );
            if (!result.matched()) {
                continue;
            }
            Map<String, String> renderedBindings = new LinkedHashMap<>();
            result.bindings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> renderedBindings.put(
                    entry.getKey(),
                    ExpressionFormatter.format(entry.getValue())
                ));
            matches.add(new KnownStructureMatch(
                structure.id(),
                structure.domainId(),
                path,
                renderedBindings,
                structure.requiredAssumptions(),
                structure.consequenceIds(),
                structure.provenance(),
                recognitionMode(exact, result.representativeIndex()),
                ExpressionFormatter.format(result.representative()),
                result.representativeIndex()
            ));
        }

        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), path.append(0), matches);
            collect(binary.right(), path.append(1), matches);
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0; index < function.arguments().size(); index++) {
                collect(function.arguments().get(index), path.append(index), matches);
            }
        }
    }

    private static String recognitionMode(
        boolean exact,
        int representativeIndex
    ) {
        if (exact) {
            return KnownStructureMatch.RECOGNITION_EXACT;
        }
        return representativeIndex > 0
            ? KnownStructureMatch.RECOGNITION_BOUNDED_REPRESENTATIVE
            : KnownStructureMatch.RECOGNITION_EQUIVALENCE_AWARE;
    }
}
