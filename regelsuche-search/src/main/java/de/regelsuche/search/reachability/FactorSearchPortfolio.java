package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.reachability.ReversibleFactorSearch.*;
import java.util.*;
import java.util.concurrent.*;

/** Each declared lane owns its allowance; a full barrier precedes deterministic witness selection. */
public final class FactorSearchPortfolio {
    public static final String REVISION="regelsuche.factor-search-portfolio/v1";
    private FactorSearchPortfolio() { }
    public record Lane(String id,Mode mode,int callLimit) {
        public Lane {
            if (id==null || !id.matches("[a-z][a-z0-9-]{0,63}") || mode==null || callLimit<0 || callLimit>100_000)
                throw new IllegalArgumentException("portfolio lane");
        }
    }
    public record LaneResult(Lane lane,Result result) { }
    public record Portfolio(String revision,List<LaneResult> lanes,Optional<Result> selected,int totalCalls) {
        public Portfolio { lanes=List.copyOf(lanes); }
    }
    public static Portfolio run(String source,String target,AssumptionSignature context,int depth,List<Lane> lanes,int workers) {
        lanes=List.copyOf(lanes);
        if (depth<0 || depth>10 || lanes.isEmpty() || lanes.size()>8 || workers<1 || workers>Math.min(8,lanes.size())
                || lanes.stream().map(Lane::id).distinct().count()!=lanes.size()) throw new IllegalArgumentException("portfolio limits or duplicate IDs");
        var outputs=new ArrayList<LaneResult>();
        try (var pool=Executors.newFixedThreadPool(workers)) {
            var pending=new ArrayList<Future<LaneResult>>();
            for (Lane lane:lanes) pending.add(pool.submit(()->new LaneResult(lane,new ReversibleFactorSearch()
                .search(source,target,context,depth,lane.callLimit(),lane.mode()))));
            for (var future:pending) outputs.add(future.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("portfolio interrupted before barrier",interrupted);
        } catch (ExecutionException failed) { throw new IllegalStateException("portfolio lane failed before barrier",failed.getCause()); }
        int total=0;
        for (var row:outputs) {
            int spent=row.result().work().totalCalls();
            if (spent>row.lane().callLimit()) throw new IllegalStateException("lane exceeded its frozen allocation");
            total=Math.addExact(total,spent);
        }
        var selected=outputs.stream().filter(r->r.result().status()==Status.REACHED)
            .min(Comparator.comparingInt((LaneResult r)->r.result().witness().size()).thenComparing(r->r.lane().id()))
            .map(LaneResult::result);
        return new Portfolio(REVISION,outputs,selected,total);
    }
}
