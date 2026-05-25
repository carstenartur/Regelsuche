package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An e-graph: a data structure that compactly represents many equivalent
 * expressions by sharing common sub-terms across an explicit
 * equivalence-class membership relation.
 *
 * <p>This is the core data structure required by the roadmap's PR 2
 * (Equality Saturation). The implementation follows the
 * <a href="https://egraphs-good.github.io/">egg</a> playbook:
 * <ol>
 *   <li>{@link #add(ENode)} hash-conses an e-node into the graph, either
 *       returning the existing e-class that already contains it (via
 *       {@code hashCons}) or creating a fresh one;</li>
 *   <li>{@link #addExpression(Expr)} walks an AST and adds every
 *       sub-expression bottom-up, returning the id of the top-level
 *       e-class;</li>
 *   <li>{@link #union(EClassId, EClassId)} marks two classes as
 *       equivalent — the change is queued in a worklist;</li>
 *   <li>{@link #rebuild()} replays the worklist, restoring two invariants:
 *       hash-cons consistency (each canonical e-node lives in exactly one
 *       class) and congruence closure (if {@code a≡b} then {@code
 *       f(a)≡f(b)});</li>
 *   <li>{@link #extract(EClassId, java.util.function.ToIntFunction)}
 *       extracts the lowest-cost representative as a plain AST.</li>
 * </ol>
 * The class is single-threaded by design — concurrent saturation on a
 * shared e-graph is out of scope for this iteration.</p>
 *
 * <p><b>Roadmap PR 2 split:</b> this commit delivers the foundation
 * (data structures, add/union/rebuild/extract). The follow-up will adapt
 * the existing {@link de.regelsuche.transform.AstRewriteTransformationEngine}
 * rules into egg-style {@code Searcher}/{@code Applier} adapters and
 * expose an {@code EqualitySaturationStrategy}. See
 * {@code docs/search-intelligence-roadmap.md}.</p>
 */
public final class EGraph {

    private final UnionFind unionFind = new UnionFind();
    /** Map id → e-class. Stale ids (post-union) point to the merged class. */
    private final Map<EClassId, EClass> classes = new LinkedHashMap<>();
    /** Hash-cons of canonical e-nodes to their owning class. */
    private final Map<ENode, EClassId> hashCons = new HashMap<>();
    /** Signature index: symbol/arity → candidate e-classes containing such nodes. */
    private final Map<ENodeSignature, LinkedHashSet<EClassId>> signatureIndex = new HashMap<>();
    /** Pending unions waiting for the next {@link #rebuild()} call. */
    private final Deque<EClassId> worklist = new ArrayDeque<>();
    /** E-classes touched by add/union/rebuild, consumed by worklist saturation. */
    private final LinkedHashSet<EClassId> dirtyClasses = new LinkedHashSet<>();
    /** Version increments whenever the graph structure changes. */
    private long version = 0L;

    /**
     * Add an e-node. Children must already be present in the graph (use
     * {@link #addExpression(Expr)} for full AST insertion). Returns the
     * canonical id of the class that now contains this node.
     */
    public EClassId add(ENode node) {
        Objects.requireNonNull(node, "node");
        ENode canonical = node.canonicalize(unionFind);
        EClassId existing = hashCons.get(canonical);
        if (existing != null) {
            return unionFind.find(existing);
        }
        EClassId id = unionFind.makeSet();
        EClass eclass = new EClass(id, canonical);
        classes.put(id, eclass);
        putHashCons(canonical, id);
        // record this node as a parent of each child class.
        for (EClassId child : canonical.children()) {
            classOf(child).addParent(canonical);
        }
        markDirty(id);
        bumpVersion();
        return id;
    }

    /**
     * Walk {@code expression} bottom-up and add every sub-term. The
     * returned id is the e-class for the whole expression.
     */
    public EClassId addExpression(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return add(ENode.leaf("num:" + format(number.value())));
        }
        if (expression instanceof VariableExpr variable) {
            return add(ENode.leaf("var:" + variable.name()));
        }
        if (expression instanceof BinaryExpr binary) {
            EClassId left = addExpression(binary.left());
            EClassId right = addExpression(binary.right());
            return add(new ENode("op:" + binary.operator().name(), List.of(left, right)));
        }
        if (expression instanceof FunctionExpr function) {
            List<EClassId> arguments = new ArrayList<>(function.arguments().size());
            for (Expr argument : function.arguments()) {
                arguments.add(addExpression(argument));
            }
            return add(new ENode("fn:" + function.name(), arguments));
        }
        throw new IllegalArgumentException("Unsupported expression: " + expression.getClass());
    }

    /** Canonical id for {@code id} (i.e. the surviving root after merges). */
    public EClassId find(EClassId id) {
        return unionFind.find(id);
    }

    /** {@code true} iff {@code a} and {@code b} are in the same e-class. */
    public boolean areEquivalent(EClassId a, EClassId b) {
        return unionFind.inSameSet(a, b);
    }

    /**
     * Merge the classes of {@code a} and {@code b}. The change is queued;
     * {@link #rebuild()} restores hash-cons consistency and congruence
     * closure afterward. Returns the surviving canonical id.
     */
    public EClassId union(EClassId a, EClassId b) {
        EClassId rootA = unionFind.find(a);
        EClassId rootB = unionFind.find(b);
        if (rootA.equals(rootB)) {
            return rootA;
        }
        EClassId survivor = mergeClasses(rootA, rootB);
        worklist.add(survivor);
        return survivor;
    }

    /**
     * Restore the two invariants violated by recent {@link #union} calls:
     * hash-cons consistency and congruence closure. Idempotent: calling
     * twice in a row is a no-op the second time.
     */
    public void rebuild() {
        while (!worklist.isEmpty()) {
            // De-duplicate by canonical id — multiple unions may target
            // the same class.
            LinkedHashSet<EClassId> todo = new LinkedHashSet<>();
            while (!worklist.isEmpty()) {
                todo.add(unionFind.find(worklist.pop()));
            }
            for (EClassId id : todo) {
                repair(id);
            }
        }
    }

    /** Snapshot of every <em>canonical</em> e-class. */
    public Collection<EClass> classes() {
        LinkedHashSet<EClassId> seen = new LinkedHashSet<>();
        List<EClass> result = new ArrayList<>();
        for (EClassId raw : classes.keySet()) {
            EClassId canonical = unionFind.find(raw);
            if (seen.add(canonical)) {
                result.add(classes.get(canonical));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Direct O(1) lookup of the canonical e-class for {@code id}. */
    public EClass classOrThrow(EClassId id) {
        return classOf(id);
    }

    /** E-graph change counter used to invalidate matcher memoization. */
    public long version() {
        return version;
    }

    /** Signature index lookup for candidate selection in pattern matching. */
    public Collection<EClassId> classesWith(ENodeSignature signature) {
        Objects.requireNonNull(signature, "signature");
        LinkedHashSet<EClassId> indexed = signatureIndex.get(signature);
        if (indexed == null || indexed.isEmpty()) {
            return List.of();
        }
        return dedupeCanonical(indexed);
    }

    /** Prefix lookup (e.g. {@code num:*}) for pattern candidate selection. */
    public Collection<EClassId> classesWithSymbolPrefix(String symbolPrefix, int arity) {
        Objects.requireNonNull(symbolPrefix, "symbolPrefix");
        LinkedHashSet<EClassId> hits = new LinkedHashSet<>();
        for (Map.Entry<ENodeSignature, LinkedHashSet<EClassId>> entry : signatureIndex.entrySet()) {
            ENodeSignature signature = entry.getKey();
            if (signature.arity() == arity && signature.symbol().startsWith(symbolPrefix)) {
                hits.addAll(entry.getValue());
            }
        }
        return dedupeCanonical(hits);
    }

    /** Consume and clear the set of classes changed since the last call. */
    public Collection<EClassId> consumeDirtyClasses() {
        Collection<EClassId> snapshot = dedupeCanonical(dirtyClasses);
        dirtyClasses.clear();
        return snapshot;
    }

    /** Number of distinct e-classes (after rebuild). */
    public int classCount() {
        return classes().size();
    }

    /** Number of distinct e-nodes currently hash-consed. */
    public int nodeCount() {
        return hashCons.size();
    }

    /**
     * Extract the lowest-cost AST representative of the e-class for
     * {@code id}. {@code costOfNode} is invoked once per e-node (not
     * counting any recursive children) and the per-class cost is the
     * minimum over its nodes of {@code costOfNode(node) + Σ child costs}.
     *
     * <p>This is the classic dynamic-programming extraction described in
     * the egg paper; cycles are handled by initialising every class with
     * {@link Integer#MAX_VALUE} and iterating until the cost table
     * stabilises.</p>
     */
    public Expr extract(EClassId id, java.util.function.ToIntFunction<ENode> costOfNode) {
        Map<EClassId, Integer> bestCost = new HashMap<>();
        Map<EClassId, ENode> bestNode = new HashMap<>();
        for (EClass eclass : classes()) {
            bestCost.put(eclass.id(), Integer.MAX_VALUE);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (EClass eclass : classes()) {
                for (ENode node : eclass.nodes()) {
                    int nodeCost = costOfNode.applyAsInt(node);
                    if (nodeCost == Integer.MAX_VALUE) {
                        continue;
                    }
                    long total = nodeCost;
                    boolean unresolved = false;
                    for (EClassId childRaw : node.children()) {
                        EClassId child = unionFind.find(childRaw);
                        Integer childCost = bestCost.get(child);
                        if (childCost == null || childCost == Integer.MAX_VALUE) {
                            unresolved = true;
                            break;
                        }
                        total += childCost;
                        if (total >= Integer.MAX_VALUE) {
                            unresolved = true;
                            break;
                        }
                    }
                    if (unresolved) {
                        continue;
                    }
                    int totalInt = (int) Math.min(total, Integer.MAX_VALUE - 1);
                    if (totalInt < bestCost.get(eclass.id())) {
                        bestCost.put(eclass.id(), totalInt);
                        bestNode.put(eclass.id(), node);
                        changed = true;
                    }
                }
            }
        }
        EClassId canonical = unionFind.find(id);
        ENode pick = bestNode.get(canonical);
        if (pick == null) {
            throw new IllegalStateException("No extractable node in e-class " + canonical
                + " — every node has unresolved children (cyclic class with no base case?)");
        }
        return buildExpression(canonical, bestNode);
    }

    // --- internal -----------------------------------------------------------

    private EClass classOf(EClassId id) {
        EClass eclass = classes.get(unionFind.find(id));
        if (eclass == null) {
            throw new IllegalStateException("Unknown e-class " + id);
        }
        return eclass;
    }

    /**
     * Re-canonicalize every parent of {@code id} (which may produce new
     * congruences) and rebuild the class' own node set so hash-cons
     * uniqueness is restored.
     */
    private void repair(EClassId id) {
        EClass eclass = classOf(id);
        // 1. re-canonicalize the parents, looking for new congruences.
        Map<ENode, EClassId> newParents = new HashMap<>();
        for (ENode parent : new ArrayList<>(eclass.parents())) {
            ENode parentCanonical = parent.canonicalize(unionFind);
            EClassId hit = removeHashCons(parent);
            if (hit != null) {
                putHashCons(parentCanonical, unionFind.find(hit));
            }
            EClassId alreadyOwned = newParents.get(parentCanonical);
            if (alreadyOwned != null) {
                // Same parent shape, different ids before canonicalization —
                // congruence! merge.
                EClassId here = unionFind.find(hit == null ? alreadyOwned : hit);
                EClassId there = unionFind.find(alreadyOwned);
                if (!here.equals(there)) {
                    EClassId survivor = mergeClasses(here, there);
                    worklist.add(survivor);
                }
                newParents.put(parentCanonical, unionFind.find(here));
            } else {
                newParents.put(parentCanonical, hit == null ? null : unionFind.find(hit));
            }
        }
        eclass.clearParents();
        for (ENode parent : newParents.keySet()) {
            eclass.addParent(parent);
        }
        // 2. re-canonicalize this class' own nodes so each canonical node
        // appears exactly once across the whole graph.
        LinkedHashSet<ENode> dedup = new LinkedHashSet<>();
        for (ENode node : eclass.nodes()) {
            ENode canonical = node.canonicalize(unionFind);
            dedup.add(canonical);
            EClassId owner = hashCons.get(canonical);
            if (owner == null) {
                putHashCons(canonical, eclass.id());
            } else if (!unionFind.find(owner).equals(eclass.id())) {
                // Two classes claim the same canonical node → congruence.
                EClassId survivor = mergeClasses(eclass.id(), owner);
                worklist.add(survivor);
            }
        }
        eclass.replaceNodes(dedup);
        markDirty(eclass.id());
    }

    private Expr buildExpression(EClassId canonical, Map<EClassId, ENode> bestNode) {
        ENode node = bestNode.get(canonical);
        return nodeToExpr(node, bestNode);
    }

    private Expr nodeToExpr(ENode node, Map<EClassId, ENode> bestNode) {
        String symbol = node.symbol();
        if (symbol.startsWith("num:")) {
            return new NumberExpr(Double.parseDouble(symbol.substring(4)));
        }
        if (symbol.startsWith("var:")) {
            return new VariableExpr(symbol.substring(4));
        }
        if (symbol.startsWith("op:")) {
            de.regelsuche.ast.BinaryOperator operator = de.regelsuche.ast.BinaryOperator.valueOf(symbol.substring(3));
            Expr left = buildExpression(unionFind.find(node.children().get(0)), bestNode);
            Expr right = buildExpression(unionFind.find(node.children().get(1)), bestNode);
            return new BinaryExpr(left, operator, right);
        }
        if (symbol.startsWith("fn:")) {
            String name = symbol.substring(3);
            List<Expr> arguments = new ArrayList<>(node.children().size());
            for (EClassId argument : node.children()) {
                arguments.add(buildExpression(unionFind.find(argument), bestNode));
            }
            return new FunctionExpr(name, arguments);
        }
        throw new IllegalStateException("Unknown e-node symbol: " + symbol);
    }

    private static String format(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Diagnostic dump — useful in tests. */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("EGraph{classes=").append(classCount())
            .append(", nodes=").append(nodeCount()).append('}');
        for (EClass eclass : classes()) {
            builder.append("\n  ").append(eclass);
        }
        return builder.toString();
    }

    private EClassId mergeClasses(EClassId left, EClassId right) {
        EClassId rootLeft = unionFind.find(left);
        EClassId rootRight = unionFind.find(right);
        if (rootLeft.equals(rootRight)) {
            return rootLeft;
        }
        EClassId survivor = unionFind.union(rootLeft, rootRight);
        EClassId merged = survivor.equals(rootLeft) ? rootRight : rootLeft;
        EClass survivorClass = classes.get(survivor);
        EClass mergedClass = classes.get(merged);
        if (survivorClass == null || mergedClass == null) {
            throw new IllegalStateException("Unknown merge classes: " + survivor + " / " + merged);
        }
        if (survivorClass != mergedClass) {
            survivorClass.absorb(mergedClass);
        }
        classes.put(merged, survivorClass);
        markDirty(survivor);
        markDirty(merged);
        bumpVersion();
        return survivor;
    }

    private EClassId removeHashCons(ENode node) {
        EClassId owner = hashCons.remove(node);
        if (owner != null) {
            removeSignatureMembership(node, owner);
        }
        return owner;
    }

    private void putHashCons(ENode node, EClassId owner) {
        EClassId previous = hashCons.put(node, owner);
        if (previous != null) {
            removeSignatureMembership(node, previous);
        }
        addSignatureMembership(node, owner);
    }

    private void addSignatureMembership(ENode node, EClassId owner) {
        ENodeSignature signature = ENodeSignature.of(node);
        signatureIndex.computeIfAbsent(signature, ignored -> new LinkedHashSet<>())
            .add(unionFind.find(owner));
    }

    private void removeSignatureMembership(ENode node, EClassId owner) {
        ENodeSignature signature = ENodeSignature.of(node);
        LinkedHashSet<EClassId> owners = signatureIndex.get(signature);
        if (owners == null) {
            return;
        }
        owners.remove(unionFind.find(owner));
        owners.remove(owner);
        if (owners.isEmpty()) {
            signatureIndex.remove(signature);
        }
    }

    private Collection<EClassId> dedupeCanonical(Collection<EClassId> ids) {
        LinkedHashSet<EClassId> canonical = new LinkedHashSet<>();
        for (EClassId id : ids) {
            canonical.add(unionFind.find(id));
        }
        List<EClassId> sorted = new ArrayList<>(canonical);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    private void markDirty(EClassId id) {
        dirtyClasses.add(unionFind.find(id));
    }

    private void bumpVersion() {
        version++;
    }
}
