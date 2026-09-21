package de.regelsuche.evolution;

import static de.regelsuche.evolution.CheckedSchemaSupport.*;
import de.regelsuche.search.moves.IncrementalProviderContract.Meter;
import de.regelsuche.search.moves.IncrementalProviderContract.Operation;
import de.regelsuche.search.moves.IncrementalProviderContract.Source;
import de.regelsuche.search.moves.IncrementalProviderContract.Status;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One retained match/application at most; never traverses or instantiates later sites eagerly. */
final class CheckedSchemaCursor implements Source {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private record Occurrence(Expr expression, List<Integer> path) {}
    private final CheckedSchemaMatcherPlan plan;
    private final String encodedSource;
    private final Meter meter;
    private final ArrayDeque<Occurrence> pending = new ArrayDeque<>();
    private Expr source;
    private Occurrence occurrence;
    private CheckedSchemaMatcherPlan.Range relevant;
    private int schemaIndex, attempted, produced;
    private CheckedSchemaMatcherPlan.Entry matchedEntry;
    private Map<String, Expr> bindings;
    private Transformation ready;
    private boolean initialized, complete;
    private Status status = Status.READY;

    CheckedSchemaCursor(CheckedSchemaMatcherPlan plan, String encodedSource, Meter meter) {
        this.plan = plan; this.encodedSource = encodedSource; this.meter = meter;
        complete = plan.allSchemasIncluded();
    }
    @Override public Optional<Transformation> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative schema allowance");
        if (terminal()) return Optional.empty();
        long before = total();
        while (total() - before < allowance) {
            status = Status.READY;
            if (ready != null) return emit();
            if (!initialized) initialize();
            else if (matchedEntry != null) instantiate();
            else if (occurrence == null) advance();
            else if (schemaIndex < Math.min(relevant.size(), plan.maximumSchemasPerOccurrence())) match();
            else descend();
            if (terminal()) return Optional.empty();
        }
        status = Status.LIMIT;
        return Optional.empty();
    }
    private boolean terminal() { return status == Status.EXHAUSTED || status == Status.INCONCLUSIVE || status == Status.CLOSED; }
    private long total() { return meter.work().metrics().totalWorkUnitsV2(); }
    private void initialize() {
        initialized = true;
        var work = new Work();
        work.add(1);
        try {
            if (encodedSource.length() > 262_144) throw new IllegalArgumentException("schema source transport size limit");
            source = CODEC.decodeExpression(encodedSource);
            domain(source, plan.bounds(), work);
            pending.push(new Occurrence(source, List.of()));
        } catch (IllegalArgumentException unsupported) {
            complete = false; status = Status.INCONCLUSIVE;
        } finally { meter.charge(Operation.LOAD, work.units); }
    }
    private void advance() {
        meter.charge(Operation.MATCH, 1);
        if (pending.isEmpty()) {
            status = complete ? Status.EXHAUSTED : Status.INCONCLUSIVE;
            return;
        }
        occurrence = pending.pop();
        relevant = plan.relevant(occurrence.expression());
        schemaIndex = 0;
        if (relevant.size() > plan.maximumSchemasPerOccurrence()) complete = false;
    }
    private void match() {
        meter.charge(Operation.MATCH, 1);
        if (attempted >= plan.bounds().maximumMatchAttempts() || produced >= plan.bounds().maximumCandidates()) {
            complete = false; status = Status.INCONCLUSIVE;
            return;
        }
        attempted++;
        var entry = relevant.get(schemaIndex++);
        // Existing exact matcher is bounded but atomic. A completed match is retained across pulls.
        var outcome = entry.matcher().match(occurrence.expression(), new ExprMatcher.MatchOptions(
            null, 1, plan.bounds().maximumExpressionNodes() * 4, plan.bounds().maximumExpressionNodes() * 4));
        meter.charge(Operation.MATCH, (long) outcome.evaluatedSteps() + outcome.patternBranches());
        if (!outcome.complete()) complete = false;
        if (outcome.matched()) {
            matchedEntry = entry;
            bindings = outcome.matches().getFirst().bindings();
        }
    }
    private void instantiate() {
        var work = new Work();
        boolean paidAsMathematics = false;
        try {
            // Reuses the model's private checked application/evidence path, unchanged from eager v1.
            ready = plan.apply(matchedEntry, source, encodedSource, occurrence.path(), bindings, work);
            if (ready != null) {
                meter.charge(ready.executionWork());
                paidAsMathematics = true;
                produced++;
            }
        } catch (IllegalArgumentException unsupported) {
            work.add(1); complete = false;
        } finally {
            if (!paidAsMathematics) meter.charge(Operation.MATCH, work.units);
            matchedEntry = null; bindings = null;
        }
    }
    private Optional<Transformation> emit() {
        meter.charge(Operation.PULL, 1);
        Transformation result = ready;
        ready = null;
        return Optional.of(result);
    }
    private void descend() {
        meter.charge(Operation.MATCH, 1);
        if (occurrence.expression() instanceof BinaryExpr binary) {
            pending.push(new Occurrence(binary.right(), child(occurrence.path(), 1)));
            pending.push(new Occurrence(binary.left(), child(occurrence.path(), 0)));
            meter.charge(Operation.MATCH, 2L * (occurrence.path().size() + 1));
        }
        occurrence = null; relevant = null;
    }
    private static List<Integer> child(List<Integer> path, int index) {
        var result = new ArrayList<>(path);
        result.add(index);
        return List.copyOf(result);
    }
    @Override public Status status() { return status; }
    @Override public void close() {
        pending.clear(); occurrence = null; relevant = null;
        matchedEntry = null; bindings = null; ready = null; source = null;
        status = Status.CLOSED;
    }
}
