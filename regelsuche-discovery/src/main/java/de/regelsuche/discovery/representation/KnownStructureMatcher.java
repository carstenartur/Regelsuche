package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic exact-pattern matching for whole expressions and subexpressions. */
public final class KnownStructureMatcher {
    private final KnownStructureCatalog catalog;
    private final ExpressionParser parser;

    public KnownStructureMatcher(KnownStructureCatalog catalog) {
        this(catalog, new ExpressionParser());
    }

    public KnownStructureMatcher(
        KnownStructureCatalog catalog,
        ExpressionParser parser
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.parser = Objects.requireNonNull(parser, "parser");
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
            Map<String, Expr> bindings = new LinkedHashMap<>();
            if (structure.pattern().match(expression, bindings)) {
                Map<String, String> renderedBindings = new LinkedHashMap<>();
                bindings.entrySet().stream()
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
                    structure.provenance()
                ));
            }
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
}
