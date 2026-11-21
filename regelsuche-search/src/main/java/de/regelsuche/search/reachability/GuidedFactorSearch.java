package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.reachability.ReversibleFactorFragment.*;
import de.regelsuche.search.reachability.ReversibleFactorSearch.*;
import java.util.*;

/** Iterative deepening with bounded exact adjacency reuse and prior-pass ordering, never prior-pass pruning. */
public final class GuidedFactorSearch {
    public static final String REVISION="regelsuche.guided-factor-deepening/v2";
    /** Candidate discovery precedes the separately budgeted receiver; it is not verified reachability. */
    public record Pass(int depth,int generationCalls,long generated,int cacheHits,long lookupOperations,boolean candidateFound) { }
    public record Guided(String revision,Result result,List<Pass> passes,int cacheHits,int replacements,
                         long lookupOperations,long lookupLimit,String terminalReason) {
        public Guided { passes=List.copyOf(passes); }
    }
    private record Cached(List<Arc> arcs,int horizon,int generation) { }
    private final ReversibleFactorFragment fragment=new ReversibleFactorFragment();
    private final State source,target;
    private final int maximumDepth,capacity;
    private final long lookupLimit;
    private final Counter forward,replay;
    private final Map<State,Cached> table=new HashMap<>();
    private final Map<State,State> principal=new HashMap<>();
    private final List<Pass> passes=new ArrayList<>();
    private List<Step> deepest=List.of();
    private int generation,hits,replacements;
    private long lookups;
    private String terminalReason="COMPLETE";
    private boolean touchedLimit;
    private GuidedFactorSearch(String source,String target,AssumptionSignature assumptions,int depth,int callLimit,int capacity,long lookupLimit) {
        if (depth<0 || depth>10 || capacity<1 || capacity>4096 || callLimit<0 || callLimit>100_000
                || lookupLimit<0 || lookupLimit>10_000_000)
            throw new IllegalArgumentException("guided finite search bounds");
        var context=BoundedReachabilityOracle.normalizeSignature(assumptions);
        this.source=new State(fragment.endpoint(source),context);this.target=new State(fragment.endpoint(target),context);
        maximumDepth=depth;this.capacity=capacity;
        this.lookupLimit=lookupLimit;
        var allowance=new Allowance(callLimit);forward=new Counter(allowance);replay=new Counter(allowance);
    }
    public static Guided search(String source,String target,AssumptionSignature assumptions,int depth,int callLimit,int tableCapacity) {
        return search(source,target,assumptions,depth,callLimit,tableCapacity,Math.max(0,Math.min(1_000_000,64L*callLimit)));
    }
    public static Guided search(String source,String target,AssumptionSignature assumptions,int depth,int callLimit,int tableCapacity,long lookupLimit) {
        return new GuidedFactorSearch(source,target,assumptions,depth,callLimit,tableCapacity,lookupLimit).run();
    }
    private Guided run() {
        if (source.equals(target)) return finish(Status.REACHED,List.of());
        try {
            for (int limit=1;limit<=maximumDepth;limit++) {
                generation=limit;touchedLimit=false;deepest=List.of();
                var before=forward.snapshot();int beforeHits=hits;long beforeLookups=lookups;
                List<Step> found;
                try { found=visit(source,limit,new HashSet<>(Set.of(source)),new ArrayList<>()); }
                catch (BoundedReachabilityOracle.EngineWorkExhausted | LookupLimit cut) {
                    retainPass(limit,before,beforeHits,beforeLookups,false);throw cut;
                }
                retainPass(limit,before,beforeHits,beforeLookups,found!=null);
                if (found!=null) return checked(found);
                if (!touchedLimit || limit == maximumDepth) break;
                principal.clear();
                for (Step step:deepest) { charge(1);principal.put(step.from(),step.to()); }
            }
            return finish(Status.NO_PATH_WITHIN_DEPTH,List.of());
        } catch (BoundedReachabilityOracle.EngineWorkExhausted cut) {
            terminalReason="PRIMITIVE_ENGINE_CALL_LIMIT_REACHED";return finish(Status.WORK_EXHAUSTED,List.of());
        } catch (LookupLimit cut) {
            terminalReason="LOOKUP_LIMIT_REACHED";return finish(Status.WORK_EXHAUSTED,List.of());
        }
    }
    private void retainPass(int depth,Count before,int beforeHits,long beforeLookups,boolean reached) {
        var now=forward.snapshot();
        passes.add(new Pass(depth,now.calls()-before.calls(),now.generated()-before.generated(),hits-beforeHits,lookups-beforeLookups,reached));
    }
    private List<Step> visit(State current,int remaining,Set<State> ancestors,List<Step> path) {
        charge(1);
        if (current.equals(target)) return List.copyOf(path);
        if (remaining==0) {
            touchedLimit=true;
            if (path.size()>deepest.size()) deepest=List.copyOf(path);
            return null;
        }
        var arcs=new ArrayList<>(neighbors(current,remaining));
        charge(1);State preferred=principal.get(current);
        // All admitted arcs remain present; the stable hint changes only traversal order.
        arcs.sort(Comparator.comparingInt((Arc a)-> {
            charge(1);
            return preferred!=null && preferred.expression().equals(a.outgoing().transformedExpression())?0:1;
        }).thenComparing(a->a.outgoing().transformedExpression()).thenComparing(a->a.outgoing().rule()));
        for (Arc arc:arcs) {
            charge(1);var next=new State(arc.outgoing().transformedExpression(),source.assumptions());
            if (!ancestors.add(next)) continue;
            path.add(new Step(current,next,arc.outgoing()));
            var found=visit(next,remaining-1,ancestors,path);
            if (found!=null) return found;
            path.removeLast();ancestors.remove(next);
        }
        return null;
    }
    private List<Arc> neighbors(State state,int horizon) {
        charge(1);var cached=table.get(state);
        if (cached!=null) {
            hits++;table.put(state,new Cached(cached.arcs(),Math.max(cached.horizon(),horizon),generation));
            return cached.arcs();
        }
        var arcs=List.copyOf(fragment.neighbors(state.expression(),forward));
        if (table.size()>=capacity) {
            charge(table.size());
            var victim=table.entrySet().stream().min(Comparator
                .comparingInt((Map.Entry<State,Cached> e)->e.getValue().generation())
                .thenComparingInt(e->e.getValue().horizon()).thenComparing(e->e.getKey().expression())).orElseThrow();
            table.remove(victim.getKey());replacements++;
        }
        table.put(state,new Cached(arcs,horizon,generation));return arcs;
    }
    private Guided checked(List<Step> path) {
        var independent=new ReversibleFactorFragment();State cursor=source;
        for (Step step:path) {
            charge(1);
            if (!cursor.equals(step.from()) || !source.assumptions().equals(step.to().assumptions())
                    || !step.to().expression().equals(step.primitive().transformedExpression())
                    || independent.neighbors(cursor.expression(),replay).stream().noneMatch(a->a.outgoing().equals(step.primitive())))
                throw new IllegalStateException("independent guided witness rejection");
            cursor=step.to();
        }
        if (!cursor.equals(target)) throw new IllegalStateException("guided target differs");
        return finish(Status.REACHED,path);
    }
    private Guided finish(Status status,List<Step> witness) {
        var result=new Result(status,witness,new Work(forward.snapshot(),new Count(0,0),replay.snapshot()));
        return new Guided(REVISION,result,passes,hits,replacements,lookups,lookupLimit,terminalReason);
    }
    private void charge(long count) {
        if(count>lookupLimit-lookups) throw new LookupLimit();
        lookups+=count;
    }
    private static final class LookupLimit extends RuntimeException { }
}
