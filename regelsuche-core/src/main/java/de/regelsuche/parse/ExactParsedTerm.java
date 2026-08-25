package de.regelsuche.parse;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalEvidenceVerifier;
import de.regelsuche.scalar.ExactRationalParseEvidence;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One parsed term together with source-bound exact evidence for every numeric
 * token that produced a {@link NumberExpr} leaf.
 *
 * <p>The ordinary AST remains unchanged: literal spelling is deliberately not
 * part of {@code NumberExpr.equals}, canonical search identity or historical
 * evidence. Consumers that need exact source coefficients must retain this
 * parser-issued companion object and resolve occurrences by node identity.</p>
 */
public final class ExactParsedTerm {
    private final String source;
    private final Expr expression;
    private final List<LiteralOccurrence> literals;
    private final Map<NumberExpr, LiteralOccurrence> literalsByNode;

    ExactParsedTerm(
        String source,
        Expr expression,
        List<LiteralOccurrence> literals
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.expression = Objects.requireNonNull(expression, "expression");
        this.literals = List.copyOf(
            Objects.requireNonNull(literals, "literals"));
        this.literalsByNode = validateAndIndex();
    }

    private Map<NumberExpr, LiteralOccurrence> validateAndIndex() {
        Map<NumberExpr, LiteralOccurrence> occurrences =
            new IdentityHashMap<>();
        for (LiteralOccurrence literal : literals) {
            Objects.requireNonNull(literal, "literal");
            if (occurrences.put(literal.node(), literal) != null) {
                throw new IllegalArgumentException(
                    "numeric literal node occurs more than once");
            }
            if (literal.startInclusive() < 0
                    || literal.endExclusive() <= literal.startInclusive()
                    || literal.endExclusive() > source.length()
                    || !source.substring(
                        literal.startInclusive(),
                        literal.endExclusive()).equals(
                            literal.sourceLexeme())) {
                throw new IllegalArgumentException(
                    "numeric literal source range is invalid");
            }
            ExactRationalParseEvidence evidence = literal.evidence();
            if (!evidence.exact()
                    || !literal.sourceLexeme().equals(
                        evidence.sourceLiteral())
                    || evidence.verify().status()
                        != ExactRationalEvidenceVerifier.Status
                            .VERIFIED_EXACT) {
                throw new IllegalArgumentException(
                    "numeric literal lacks verified exact evidence");
            }
        }
        return Collections.unmodifiableMap(occurrences);
    }

    public String source() {
        return source;
    }

    public Expr expression() {
        return expression;
    }

    public List<LiteralOccurrence> literals() {
        return literals;
    }

    /**
     * Resolves evidence for the exact parser-created node instance. Value-equal
     * nodes created later do not inherit source provenance.
     */
    public Optional<LiteralOccurrence> literalFor(NumberExpr node) {
        Objects.requireNonNull(node, "node");
        return Optional.ofNullable(literalsByNode.get(node));
    }

    /** Parser-issued occurrence; construction is restricted to this package. */
    public static final class LiteralOccurrence {
        private final NumberExpr node;
        private final int startInclusive;
        private final int endExclusive;
        private final String sourceLexeme;
        private final ExactRationalParseEvidence evidence;

        LiteralOccurrence(
            NumberExpr node,
            int startInclusive,
            int endExclusive,
            String sourceLexeme,
            ExactRationalParseEvidence evidence
        ) {
            this.node = Objects.requireNonNull(node, "node");
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.sourceLexeme = Objects.requireNonNull(
                sourceLexeme,
                "sourceLexeme");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        public NumberExpr node() {
            return node;
        }

        public int startInclusive() {
            return startInclusive;
        }

        public int endExclusive() {
            return endExclusive;
        }

        public String sourceLexeme() {
            return sourceLexeme;
        }

        public ExactRationalParseEvidence evidence() {
            return evidence;
        }

        public ExactRational exactValue() {
            return evidence.value().orElseThrow();
        }
    }
}
