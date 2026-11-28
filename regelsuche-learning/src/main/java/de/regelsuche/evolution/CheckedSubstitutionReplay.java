package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.PatternExpr;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static de.regelsuche.evolution.CheckedSchemaSupport.*;

/** One complete, bounded proof of a concrete rewrite in the exact scalar schema grammar. */
final class CheckedSubstitutionReplay {
    private record Size(int nodes, int height) {}
    private final CheckedLearnedSchemaModel.Schema schema;
    private final CheckedLearnedSchemaModel.Bounds bounds;
    private final Work work;
    private final List<Integer> path;
    private final Map<String, Expr> bindings = new TreeMap<>();
    private final Map<String, Size> sizes = new TreeMap<>();
    private int sourceNodes;
    private int targetNodes;
    private int substitutionNodes;
    private int templateNodes;

    CheckedSubstitutionReplay(CheckedLearnedSchemaModel.Schema schema, CheckedLearnedSchemaModel.Bounds bounds,
            List<Integer> path, Work work) {
        this.schema = schema;
        this.bounds = bounds;
        this.path = path;
        this.work = work;
        work.add(3); // Receipt and its two substitution tables.
    }

    Map<String, Expr> bindings() { return bindings; }

    /** The signed legacy work describes proposal construction, independently of this verification's price. */
    long canonicalApplicationWork() {
        return (long) substitutionNodes + templateNodes + path.size() + 1 + targetNodes;
    }

    Expr rewrite(Expr source) { return rewrite(source, 0); }

    private Expr rewrite(Expr expression, int depth) {
        if (depth == path.size()) {
            // All previously visited nodes lie outside the replacement and occur unchanged in size.
            targetNodes = sourceNodes;
            match(schema.source(), expression, depth);
            var replacement = instantiate(schema.target(), depth);
            if (sameBinding(expression, replacement)) throw new CheckedLearnedSchemaModel.ReplayRejected("CHECKED_SCHEMA_UNCHANGED");
            return replacement;
        }
        visitSource(depth);
        if (!(expression instanceof BinaryExpr parent)) throw new CheckedLearnedSchemaModel.ReplayRejected("CHECKED_SCHEMA_ABSENT_OCCURRENCE");
        binaryDomain(parent, bounds);
        boolean left = path.get(depth) == 0;
        measure(left ? parent.right() : parent.left(), depth + 1);
        Expr child = rewrite(left ? parent.left() : parent.right(), depth + 1);
        var changed = left ? new BinaryExpr(child, parent.operator(), parent.right())
            : new BinaryExpr(parent.left(), parent.operator(), child);
        work.add(1);
        binaryDomain(changed, bounds); // The rewritten right operand could be a denominator or exponent.
        return changed;
    }

    private void match(PatternExpr pattern, Expr expression, int depth) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            String name = placeholder.name();
            work.add(1);
            var previous = sizes.get(name);
            if (previous != null) {
                checkLimit((long) sourceNodes + previous.nodes(), depth + previous.height());
                work.add(1);
                if (!sameBinding(bindings.get(name), expression)) throw mismatch();
                sourceNodes += previous.nodes();
            } else {
                int before = sourceNodes;
                int height = measure(expression, depth);
                int nodes = sourceNodes - before;
                bindings.put(name, expression);
                sizes.put(name, new Size(nodes, height));
                substitutionNodes += nodes;
                work.add(3);
            }
            return;
        }
        visitSource(depth);
        if (pattern instanceof PatternExpr.LiteralNumber literal && expression instanceof NumberExpr number
                && literal.value().equals(number.value())) {
            scalarLeaf(number, bounds);
            return;
        }
        if (!(pattern instanceof PatternExpr.Operation op) || !(expression instanceof BinaryExpr binary)
                || op.operator() != binary.operator()) throw mismatch();
        binaryDomain(binary, bounds);
        match(op.left(), binary.left(), depth + 1);
        match(op.right(), binary.right(), depth + 1);
    }

    /** The left operand was fully checked. Equality transfers its size, height and domain membership. */
    private boolean sameBinding(Expr checked, Expr actual) {
        work.add(1);
        return switch (checked) {
            case BinaryExpr a -> actual instanceof BinaryExpr b && a.operator() == b.operator()
                && sameBinding(a.left(), b.left()) && sameBinding(a.right(), b.right());
            case NumberExpr a -> a.equals(actual);
            case VariableExpr a -> a.equals(actual);
            default -> throw new IllegalStateException("unverified substitution");
        };
    }

    /** Count occurrences even when the source AST shares objects. Recursion is bounded by the domain limit. */
    private int measure(Expr expression, int depth) {
        visitSource(depth);
        if (expression instanceof BinaryExpr binary) {
            binaryDomain(binary, bounds);
            return 1 + Math.max(measure(binary.left(), depth + 1), measure(binary.right(), depth + 1));
        }
        scalarLeaf(expression, bounds);
        return 0;
    }

    private Expr instantiate(PatternExpr pattern, int depth) {
        work.add(1);
        templateNodes++;
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            var size = sizes.get(placeholder.name());
            work.add(2); // Read the size/height receipt and the independently recovered expression.
            checkLimit((long) targetNodes + size.nodes(), depth + size.height());
            targetNodes += size.nodes();
            return bindings.get(placeholder.name());
        }
        checkLimit(++targetNodes, depth);
        if (pattern instanceof PatternExpr.LiteralNumber literal) {
            var value = new NumberExpr(literal.value());
            scalarLeaf(value, bounds);
            return value;
        }
        // Only operations, numbers, and source-bound placeholders can enter the independently proved catalog.
        var op = (PatternExpr.Operation) pattern;
        var value = new BinaryExpr(instantiate(op.left(), depth + 1), op.operator(), instantiate(op.right(), depth + 1));
        binaryDomain(value, bounds);
        return value;
    }

    private void visitSource(int depth) {
        work.add(1);
        checkLimit(++sourceNodes, depth);
    }

    private void checkLimit(long nodes, int depth) {
        if (nodes > bounds.maximumExpressionNodes() || depth > bounds.maximumDepth())
            throw new DomainRejected("checked scalar polynomial structure limit");
    }

    private static CheckedLearnedSchemaModel.ReplayRejected mismatch() {
        return new CheckedLearnedSchemaModel.ReplayRejected("CHECKED_SCHEMA_BINDING_MISMATCH");
    }
}
