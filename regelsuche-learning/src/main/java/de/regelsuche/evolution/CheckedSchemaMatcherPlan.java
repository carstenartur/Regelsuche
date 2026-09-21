package de.regelsuche.evolution;

import static de.regelsuche.evolution.CheckedSchemaSupport.*;
import de.regelsuche.search.moves.IncrementalProviderContract.Definition;
import de.regelsuche.search.moves.IncrementalProviderContract.Kind;
import de.regelsuche.search.moves.IncrementalProviderContract.Mathematics;
import de.regelsuche.search.moves.IncrementalProviderContract.Registration;
import de.regelsuche.search.moves.IncrementalProviderContract.Registry;
import de.regelsuche.search.moves.IncrementalProviderContract.Transport;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.RegisteredIncrementalMoveProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable preparation shared by cursor sessions, not an eagerly generated candidate list. */
public final class CheckedSchemaMatcherPlan {
    public static final String REVISION = "regelsuche.checked-schema-matcher-plan/v1";
    public static final String WORK_REVISION = "regelsuche.checked-schema-cursor-work/v1";
    /** Preparation is a separate lifecycle charge; opening a cursor never rebuilds this index. */
    public record Compilation(String revision, String configurationHash, long workUnits,
            int includedSchemas, long patternNodeVisits, long orderingComparisons) {}
    record Entry(CheckedLearnedSchemaModel.Schema schema, ExprMatcher matcher, int reduction) {}
    record Range(List<Entry> shaped, List<Entry> wildcard) {
        int size() { return shaped.size() + wildcard.size(); }
        Entry get(int index) { return index < shaped.size() ? shaped.get(index) : wildcard.get(index - shaped.size()); }
    }
    @FunctionalInterface interface Application {
        Transformation apply(CheckedLearnedSchemaModel.Schema schema, Expr source, String encodedSource,
            List<Integer> path, Map<String, Expr> bindings, Work work);
    }

    private final CheckedLearnedSchemaModel.Bounds bounds;
    private final MoveProvider.Descriptor descriptor;
    private final int maximumSchemasPerOccurrence;
    private final boolean allSchemasIncluded;
    private final Map<String, List<Entry>> index;
    private final Application application;
    private final Definition definition;
    private final Compilation compilation;

    CheckedSchemaMatcherPlan(CheckedLearnedSchemaModel model, MoveProvider.Descriptor descriptor, int maximum,
            Map<String, Double> utilities, Set<String> included, Application application) {
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
        definition = new Definition(IncrementalProviderContract.REVISION, descriptor.id(), Kind.REGISTERED_SCHEMA,
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
    public Compilation compilationReceipt() { return compilation; }
    public long compilationWork() { return compilation.workUnits(); }
    public RegisteredIncrementalMoveProvider provider() {
        var registration = new Registration(definition,
            (state, context, meter) -> new CheckedSchemaCursor(this, state.expression(), meter));
        return new RegisteredIncrementalMoveProvider(descriptor, definition, new Registry(List.of(registration)));
    }
    CheckedLearnedSchemaModel.Bounds bounds() { return bounds; }
    int maximumSchemasPerOccurrence() { return maximumSchemasPerOccurrence; }
    boolean allSchemasIncluded() { return allSchemasIncluded; }
    Range relevant(Expr expression) {
        String shape = expression instanceof BinaryExpr binary ? binary.operator().name() : "LEAF";
        return new Range(index.getOrDefault(shape, List.of()), index.getOrDefault("P", List.of()));
    }
    Transformation apply(Entry entry, Expr source, String encoded, List<Integer> path,
            Map<String, Expr> bindings, Work work) {
        return application.apply(entry.schema(), source, encoded, path, bindings, work);
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
