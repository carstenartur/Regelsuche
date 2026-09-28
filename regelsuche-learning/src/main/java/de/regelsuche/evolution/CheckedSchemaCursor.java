package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import static de.regelsuche.evolution.CheckedSchemaSupport.*;
import de.regelsuche.search.moves.IncrementalProviderContract.Meter;
import de.regelsuche.search.moves.IncrementalProviderContract.Operation;
import de.regelsuche.search.moves.IncrementalProviderContract.Source;
import de.regelsuche.search.moves.IncrementalProviderContract.ObjectSource;
import de.regelsuche.search.moves.NativeMoveProof;
import de.regelsuche.search.program.AstExpressionValidation;
import de.regelsuche.transform.ExecutionWork;
import java.util.function.Function;
import de.regelsuche.search.moves.IncrementalProviderContract.Status;
import de.regelsuche.search.moves.IncrementalProviderContract.PrepaidApplication;

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
final class CheckedSchemaCursor<T> implements ObjectSource<T>,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(plan);v.reference(encodedSource);v.reference(meter);v.reference(pending);v.reference(source);v.reference(occurrence);v.reference(relevant);v.reference(matchedEntry);v.reference(bindings);v.reference(application);v.reference(applicationWork);v.reference(payment);v.reference(ready);v.reference(result);v.reference(mathematics);v.reference(status);}
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private record Occurrence(Expr expression, List<Integer> path)  implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(expression);v.reference(path);}
    }
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
    private CheckedSchemaMatcherPlan.ApplicationSteps application;
    private Work applicationWork;
    private PrepaidApplication payment;
    private T ready;
    private final Function<CheckedSchemaMatcherPlan.ApplicationSteps,T> result;
    private final Function<T,ExecutionWork> mathematics;
    private boolean initialized, complete;
    private Status status = Status.READY;

    private CheckedSchemaCursor(CheckedSchemaMatcherPlan plan,String encodedSource,Expr source,Meter meter,
            Function<CheckedSchemaMatcherPlan.ApplicationSteps,T> result,Function<T,ExecutionWork> mathematics) {
        this.plan=plan;this.encodedSource=encodedSource;this.source=source;this.meter=meter;
        this.result=result;this.mathematics=mathematics;complete=plan.allSchemasIncluded();
    }
    static Source legacy(CheckedSchemaMatcherPlan plan,String encodedSource,Meter meter) {
        var cursor=new CheckedSchemaCursor<>(plan,encodedSource,null,meter,
            CheckedSchemaMatcherPlan.ApplicationSteps::result,Transformation::executionWork);
        return new Source() {
            @Override public Optional<Transformation> next(long allowance){return cursor.next(allowance);}
            @Override public Status status(){return cursor.status();}
            @Override public void close(){cursor.close();}
        };
    }
    static ObjectSource<NativeMoveProof> nativeSource(CheckedSchemaMatcherPlan plan,Expr source,Meter meter) {
        return new CheckedSchemaCursor<>(plan,null,source,meter,NativeResult.INSTANCE,NativeMathematics.INSTANCE);
    }
    private enum NativeResult implements Function<CheckedSchemaMatcherPlan.ApplicationSteps,NativeMoveProof>,RetainedGraph.View {
        INSTANCE;
        @Override public NativeMoveProof apply(CheckedSchemaMatcherPlan.ApplicationSteps steps){return steps.nativeResult();}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private enum NativeMathematics implements Function<NativeMoveProof,ExecutionWork>,RetainedGraph.View {
        INSTANCE;
        @Override public ExecutionWork apply(NativeMoveProof proof){return proof.work();}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    @Override public Optional<T> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative schema allowance");
        if (terminal()) return Optional.empty();
        long before = total();
        while (total() - before < allowance) {
            status = Status.READY;
            if (ready != null) return emit();
            if (!initialized) initialize();
            else if (matchedEntry != null) advanceApplication();
            else if (occurrence == null) advance();
            else if (schemaIndex < Math.min(relevant.size(), plan.maximumSchemasPerOccurrence())) match();
            else descend();
            de.regelsuche.retention.RetainedOperation.work(1);
            de.regelsuche.retention.RetainedOperation.checkpoint();
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
            if(encodedSource!=null) {
                if(encodedSource.length()>262144)throw new IllegalArgumentException("schema source transport size limit");
                source=CODEC.decodeExpression(encodedSource);
            } else if(AstExpressionValidation.inspect(source).canonicalCharacters()>262144)
                throw new IllegalArgumentException("schema source transport size limit");
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
    private void advanceApplication() {
        if (application == null) {
            applicationWork = new Work();
            payment = meter.beginPrepaidApplication();
            application = plan.startApplication(matchedEntry, source, encodedSource, occurrence.path(), bindings, applicationWork);
        }
        long before = applicationWork.units;
        var phase = application.phase();
        boolean failed = false;
        try {
            application.advance();
        } catch (IllegalArgumentException unsupported) {
            meter.charge(Operation.MATCH, 1); complete = false; failed = true;
        } finally {
            meter.prepay(payment, phase, applicationWork.units - before);
        }
        if (failed) meter.abandon(payment);
        else if (application.done()) {
            ready = result.apply(application);
            if (ready == null) meter.abandon(payment);
            else { meter.complete(payment, mathematics.apply(ready)); produced++; }
        } else return;
        de.regelsuche.retention.RetainedOperation.work(1);
        de.regelsuche.retention.RetainedOperation.checkpoint();
        clearApplication();
    }
    private void clearApplication() {
        application = null; applicationWork = null; payment = null;
        matchedEntry = null; bindings = null;
    }
    private Optional<T> emit() {
        meter.charge(Operation.PULL, 1);
        T result = ready;
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
        de.regelsuche.retention.RetainedOperation.work(pending.size()+10L);
        pending.clear(); occurrence = null; relevant = null;
        clearApplication(); ready = null; source = null;
        status = Status.CLOSED;
    }
}
