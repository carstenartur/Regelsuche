package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import static de.regelsuche.evolution.CheckedSchemaSupport.*;
import de.regelsuche.search.moves.IncrementalProviderContract.Definition;
import de.regelsuche.search.moves.IncrementalProviderContract.Kind;
import de.regelsuche.search.moves.IncrementalProviderContract.Mathematics;
import de.regelsuche.search.moves.IncrementalProviderContract.Registration;
import de.regelsuche.search.moves.IncrementalProviderContract.Registry;
import de.regelsuche.search.moves.IncrementalProviderContract.Transport;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.moves.RegisteredIncrementalMoveProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable preparation shared by cursor sessions, not an eagerly generated candidate list. */
public final class CheckedSchemaMatcherPlan implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(bounds);v.reference(descriptor);v.reference(index);v.reference(application);v.reference(definition);v.reference(compilation);v.reference(model);}
    public static final String REVISION = "regelsuche.checked-schema-matcher-plan/v1";
    public static final String WORK_REVISION = "regelsuche.checked-schema-cursor-work/v2-prepaid-phases";
    public static final String INDEXED_REVISION = "regelsuche.checked-schema-matcher-plan/v2-indexed";
    public static final String INDEXED_WORK_REVISION = "regelsuche.checked-schema-cursor-work/v3-indexed-shared-paths";
    private static final int OTHER_SHAPE = 3;
    private static final int BINARY_SHAPE_BASE = 4;
    private static final int SHAPE_COUNT = BINARY_SHAPE_BASE + BinaryOperator.values().length;
    /** Preparation is a separate lifecycle charge; opening a cursor never rebuilds this index. */
    public record Compilation(String revision, String configurationHash, long workUnits,
            int includedSchemas, long patternNodeVisits, long orderingComparisons)  implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(revision);v.reference(configurationHash);}
    }
    record Entry(CheckedLearnedSchemaModel.Schema schema, ExprMatcher matcher, int reduction)  implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(schema);v.reference(matcher);}
    }
    record Range(List<Entry> shaped, List<Entry> wildcard) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(shaped);v.reference(wildcard);}
        int size() { return shaped.size() + wildcard.size(); }
        Entry get(int index) { return index < shaped.size() ? shaped.get(index) : wildcard.get(index - shaped.size()); }
    }
    @FunctionalInterface interface Application {
        ApplicationSteps start(CheckedLearnedSchemaModel.Schema schema, Expr source, String encodedSource,
            List<Integer> path, Map<String, Expr> bindings, Work work);
    }
    interface ApplicationSteps {
        IncrementalProviderContract.ApplicationPhase phase();
        void advance();
        boolean done();
        Transformation result();
        NativeMoveProof nativeResult();
    }

    private final CheckedLearnedSchemaModel.Bounds bounds;
    private final MoveProvider.Descriptor descriptor;
    private final int maximumSchemasPerOccurrence;
    private final boolean allSchemasIncluded;
    private final Map<String, List<Entry>> index;
    private final Application application;
    private final Definition definition;
    private final Compilation compilation;
    private final CheckedLearnedSchemaModel model;

    CheckedSchemaMatcherPlan(CheckedLearnedSchemaModel model, MoveProvider.Descriptor descriptor, int maximum,
            Map<String, Double> utilities, Set<String> included, Application application) {
        this.model=Objects.requireNonNull(model);
        this.descriptor = Objects.requireNonNull(descriptor);
        this.application = Objects.requireNonNull(application);
        bounds = model.bounds();
        maximumSchemasPerOccurrence = maximum;
        var work = new Work();
        var utility = Map.copyOf(utilities);
        var selected = Set.copyOf(included);
        validate(model, maximum, utility, selected, work);
        allSchemasIncluded = selected.size() == model.schemas().size();
        var entries = new ArrayList<Entry>();
        long patternVisits = prepareEntries(model, selected, entries, work);
        var comparisons = new Work();
        index = orderedIndex(entries, utility, comparisons);
        work.add(comparisons.units + entries.size());
        String configuration = configuration(descriptor.id(), maximum, utility, selected);
        work.add(configuration.length());
        String configurationHash = SchematicProofPlan.hash(configuration);
        definition = new Definition(IncrementalProviderContract.PREPAID_REVISION, descriptor.id(), Kind.REGISTERED_SCHEMA,
            configurationHash, model.inventorySemanticsHash() + ";" + CheckedLearnedSchemaModel.CHECKER_REVISION
                + ";" + REVISION + ";" + WORK_REVISION,
            Transport.TYPED_AST_JSON, Mathematics.EXACT, null);
        compilation = new Compilation(WORK_REVISION, configurationHash, work.units, entries.size(),
            patternVisits, comparisons.units);
    }

    public static CheckedSchemaMatcherPlan prepare(CheckedLearnedSchemaModel model, int maximum,
            Map<String, Double> utilities, Set<String> included) {
        return Objects.requireNonNull(model).prepareCursorPlan(maximum, utilities, included);
    }
    /** Opt-in acceleration. The original plan and its historical work revision stay unchanged. */
    public static CheckedSchemaMatcherPlan prepareIndexed(CheckedLearnedSchemaModel model, int maximum,
            Map<String, Double> utilities, Set<String> included) {
        return prepare(model, maximum, utilities, included).withIndexedDispatch();
    }

    /** Compile once, then share the immutable result across independent cursor sessions. */
    public CheckedSchemaMatcherPlan withIndexedDispatch() {
        return indexedDispatch() ? this : new CheckedSchemaMatcherPlan(this);
    }

    private CheckedSchemaMatcherPlan(CheckedSchemaMatcherPlan original) {
        model = original.model;
        descriptor = original.descriptor;
        application = original.application;
        bounds = original.bounds;
        maximumSchemasPerOccurrence = original.maximumSchemasPerOccurrence;
        allSchemasIncluded = original.allSchemasIncluded;
        var prepared = new TreeMap<String, List<Entry>>();
        long extraWork = 1;
        long patternVisits = 0;
        for (var bucket : original.index.entrySet()) {
            if (bucket.getKey().equals("P")) prepared.put(bucket.getKey(), bucket.getValue());
            else {
                var entries = new IndexedEntries(bucket.getValue());
                prepared.put(bucket.getKey(), entries);
                extraWork = Math.addExact(extraWork, entries.shapes.compilationWork());
                // Each requirement array has three slots; root and at most two children are inspected.
                extraWork = Math.addExact(extraWork, 7L * entries.size() + 1);
                for (var entry : bucket.getValue()) {
                    patternVisits = Math.addExact(patternVisits,
                        entry.schema().source() instanceof PatternExpr.Operation ? 3 : 1);
                }
            }
            extraWork = Math.addExact(extraWork, 2);
        }
        index = Map.copyOf(prepared);
        String configuration = original.compilation.configurationHash() + ";" + INDEXED_REVISION + ";" + INDEXED_WORK_REVISION;
        String configurationHash = SchematicProofPlan.hash(configuration);
        extraWork = Math.addExact(extraWork, configuration.length() + prepared.size() + 1L);
        definition = new Definition(IncrementalProviderContract.PREPAID_REVISION, descriptor.id(), Kind.REGISTERED_SCHEMA,
            configurationHash, original.definition.semanticsRevision() + ";" + INDEXED_REVISION + ";" + INDEXED_WORK_REVISION,
            Transport.TYPED_AST_JSON, Mathematics.EXACT, null);
        compilation = new Compilation(INDEXED_WORK_REVISION, configurationHash,
            Math.addExact(original.compilationWork(), extraWork), original.compilation.includedSchemas(),
            Math.addExact(original.compilation.patternNodeVisits(), patternVisits), original.compilation.orderingComparisons());
    }

    boolean indexedDispatch() { return compilation.revision().equals(INDEXED_WORK_REVISION); }

    long dispatchWork(Range range) {
        return range.shaped() instanceof IndexedEntries ? CheckedSchemaShapeIndex.QUERY_WORK : 1;
    }

    long selectionMask(Range range, Expr expression) {
        int limit = Math.min(range.size(), maximumSchemasPerOccurrence);
        long shaped = CheckedSchemaShapeIndex.prefix(Math.min(range.shaped().size(), limit));
        if (range.shaped() instanceof IndexedEntries entries) {
            int left = OTHER_SHAPE, right = OTHER_SHAPE;
            if (expression instanceof BinaryExpr binary) {
                left = shape(binary.left()); right = shape(binary.right());
            }
            shaped = entries.shapes.candidates(shape(expression), left, right, Math.min(entries.size(), limit));
        }
        int wildcardCount = Math.max(0, limit - range.shaped().size());
        long wildcard = wildcardCount == 0 ? 0 : CheckedSchemaShapeIndex.prefix(wildcardCount) << range.shaped().size();
        return shaped | wildcard;
    }

    private static int shape(Expr expression) {
        if (expression instanceof BinaryExpr binary) return BINARY_SHAPE_BASE + binary.operator().ordinal();
        if (expression instanceof NumberExpr) return 0;
        if (expression instanceof VariableExpr) return 1;
        if (expression instanceof FunctionExpr) return 2;
        return OTHER_SHAPE;
    }

    private static int shape(PatternExpr expression) {
        if (expression instanceof PatternExpr.Operation operation) return BINARY_SHAPE_BASE + operation.operator().ordinal();
        if (expression instanceof PatternExpr.LiteralNumber) return 0;
        if (expression instanceof PatternExpr.LiteralVariable) return 1;
        if (expression instanceof PatternExpr.Function) return 2;
        return CheckedSchemaShapeIndex.ANY;
    }

    private static final class IndexedEntries extends AbstractList<Entry> implements RetainedGraph.View {
        private final List<Entry> entries;
        private final CheckedSchemaShapeIndex shapes;
        IndexedEntries(List<Entry> entries) {
            this.entries = entries;
            int[][] requirements = new int[entries.size()][3];
            for (int i = 0; i < entries.size(); i++) {
                var pattern = entries.get(i).schema().source();
                requirements[i][0] = shape(pattern);
                requirements[i][1] = pattern instanceof PatternExpr.Operation operation ? shape(operation.left()) : CheckedSchemaShapeIndex.ANY;
                requirements[i][2] = pattern instanceof PatternExpr.Operation operation ? shape(operation.right()) : CheckedSchemaShapeIndex.ANY;
            }
            shapes = new CheckedSchemaShapeIndex(SHAPE_COUNT, requirements);
        }
        @Override public Entry get(int index) { return entries.get(index); }
        @Override public int size() { return entries.size(); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(entries); visitor.reference(shapes);
        }
    }

    public Compilation compilationReceipt() { return compilation; }
    public long compilationWork() { return compilation.workUnits(); }
    public de.regelsuche.search.moves.ExprIncrementalProvider nativeProvider() {
        return new NativeProvider();
    }
    public RegisteredIncrementalMoveProvider provider() {
        var registration = new Registration(definition,
            (state, context, meter) -> CheckedSchemaCursor.legacy(this, state.expression(), meter));
        return new RegisteredIncrementalMoveProvider(descriptor, definition, new Registry(List.of(registration)));
    }
    final class NativeProvider implements ExprIncrementalProvider,RetainedGraph.View {
        boolean ownedBy(CheckedLearnedSchemaModel expected){
            de.regelsuche.retention.RetainedOperation.work(1);
            // This predicate is the installed checker's execution-inventory boundary.
            // New dispatch/path accounting is not silently promoted under the old revision.
            return model==expected && expected.ownsApplication(application) && !indexedDispatch();
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(CheckedSchemaMatcherPlan.this);}
        private NativeProvider() {}
        @Override public MoveProvider.Descriptor descriptor(){return descriptor;}
        @Override public Definition contractDefinition(){
            return new Definition(IncrementalProviderContract.NATIVE_PREPAID_REVISION,definition.providerId(),definition.kind(),definition.modelRevision(),
                definition.semanticsRevision()+";"+NativeMoveSearch.REVISION,Transport.NATIVE_EXPR_V1,definition.mathematics(),null);
        }
        @Override public IncrementalProviderContract.ObjectSource<NativeMoveProof> openSource(TypedMoveSearch.State state,
                TypedMoveSearch.Context context,IncrementalProviderContract.Meter meter){
            return CheckedSchemaCursor.nativeSource(CheckedSchemaMatcherPlan.this,state.expression(),meter);
        }
        NativeVerifier independentVerifier(){return model.nativeVerifier();}
    }
    CheckedLearnedSchemaModel.Bounds bounds() { return bounds; }
    int maximumSchemasPerOccurrence() { return maximumSchemasPerOccurrence; }
    boolean allSchemasIncluded() { return allSchemasIncluded; }
    Range relevant(Expr expression) {
        String shape = expression instanceof BinaryExpr binary ? binary.operator().name() : "LEAF";
        return new Range(index.getOrDefault(shape, List.of()), index.getOrDefault("P", List.of()));
    }
    ApplicationSteps startApplication(Entry entry, Expr source, String encoded, List<Integer> path,
            Map<String, Expr> bindings, Work work) {
        return application.start(entry.schema(), source, encoded, path, bindings, work);
    }

    private static void validate(CheckedLearnedSchemaModel model, int maximum, Map<String, Double> utility,
            Set<String> included, Work work) {
        if (maximum < 1 || maximum > model.bounds().maximumSchemas())
            throw new IllegalArgumentException("schema selection bound outside model limits");
        var known = new HashSet<String>();
        for (var schema : model.schemas()) { work.add(1); known.add(schema.id()); }
        work.add(included.size() + utility.size() + 1L);
        if (!known.containsAll(included)) throw new IllegalArgumentException("unregistered included schema");
        for (var entry : utility.entrySet()) {
            if (!known.contains(entry.getKey()) || !Double.isFinite(entry.getValue()))
                throw new IllegalArgumentException("unknown schema or nonfinite selection utility");
        }
    }
    private static long prepareEntries(CheckedLearnedSchemaModel model, Set<String> selected,
            List<Entry> entries, Work work) {
        long visits = 0;
        for (var schema : model.schemas()) {
            work.add(1);
            if (!selected.contains(schema.id())) continue;
            int sourceNodes = nodes(schema.source()), targetNodes = nodes(schema.target());
            visits = Math.addExact(visits, sourceNodes + targetNodes);
            work.add(sourceNodes + targetNodes + 1L);
            entries.add(new Entry(schema, ExprMatcher.pattern(schema.source()), sourceNodes - targetNodes));
        }
        return visits;
    }
    private static Map<String, List<Entry>> orderedIndex(List<Entry> entries, Map<String, Double> utility, Work comparisons) {
        var comparator = Comparator.<Entry>comparingDouble(entry -> utility.getOrDefault(entry.schema().id(), 0.0))
            .reversed().thenComparing(Comparator.comparingInt(Entry::reduction).reversed())
            .thenComparing(entry -> entry.schema().id());
        entries.sort((left, right) -> { comparisons.add(1); return comparator.compare(left, right); });
        var grouped = new TreeMap<String, List<Entry>>();
        for (var entry : entries) {
            PatternExpr pattern = entry.schema().source();
            String shape = pattern instanceof PatternExpr.Operation operation ? operation.operator().name()
                : pattern instanceof PatternExpr.Placeholder ? "P" : "LEAF";
            grouped.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(entry);
        }
        grouped.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(grouped);
    }
    private static String configuration(String modelId, int maximum,
            Map<String, Double> utility, Set<String> selected) {
        var root = JSON.createObjectNode().put("revision", REVISION).put("workRevision", WORK_REVISION)
            .put("modelId", modelId)
            .put("maximumSchemasPerOccurrence", maximum).put("order", "preorder;shaped-before-wildcard;utility-reduction-id");
        var ids = root.putArray("included");
        selected.stream().sorted().forEach(ids::add);
        var values = root.putObject("utilities");
        new TreeMap<>(utility).forEach((id, value) -> values.put(id, Double.toHexString(value)));
        return write(root);
    }
    private static int nodes(PatternExpr expression) {
        return expression instanceof PatternExpr.Operation operation
            ? 1 + nodes(operation.left()) + nodes(operation.right()) : 1;
    }
}
