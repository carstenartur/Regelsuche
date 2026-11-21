package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.reachability.*;
import de.regelsuche.search.reachability.ReversibleFactorSearch.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Frozen public diagnostics. Native calls, controller operations and measured JVM costs remain separate. */
public final class FiniteSearchKnowledgeComparison {
    public static final String REVISION="regelsuche.finite-search-knowledge-comparison/v2";
    private static final int CALLS=4096;
    private static final long LOOKUPS=262144;
    private static final AssumptionSignature CONTEXT=AssumptionSignature.ofExpressions(List.of());
    public record Case(String id,String source,String target,int depth) { }
    public record Row(String caseId,String arm,String status,int pathLength,long acquisitionCalls,long restorationCalls,
                      long searchCalls,long replayCalls,long generated,long lookupOperations,long cacheHits,long replacements,
                      int referenceMinimumDepth,String referenceStatus) {
        public long totalNativeCalls() { return acquisitionCalls+restorationCalls+searchCalls+replayCalls; }
    }
    public record Measurement(String caseId,String arm,long wallNanos,long currentThreadAllocatedBytes) { }
    public record Report(List<Row> rows,List<Measurement> measurements,Map<String,String> sourceTables) {
        public Report {
            rows=List.copyOf(rows);measurements=List.copyOf(measurements);
            sourceTables=java.util.Collections.unmodifiableMap(new TreeMap<>(sourceTables));
        }
        public String canonicalJson() {
            var out=new JsonWriter().beginObject().property("schema",REVISION)
                .property("corpusSha256",HistoricalRediscoveryCorpus.load().contentSha256())
                .property("guidedController",GuidedFactorSearch.REVISION).property("portfolio",FactorSearchPortfolio.REVISION)
                .property("regime","PUBLIC_TARGET_DIRECTED_EXACT_SYNTAX")
                .property("workComparability","NATIVE_CALLS_ONLY_FULL_MECHANICAL_WORK_UNMATCHED")
                .property("maximumNativeCallsPerArm",CALLS).property("guidedLookupLimit",LOOKUPS)
                .property("portfolioAllocation","three declared lanes with 1365 calls each; every lane charged")
                .property("warmTableScope","query and fresh replay only; acquisition is separately retained in TABLE_COLD")
                .property("stretch1000x","UNMET")
                .property("excludedPublicTarget","sophie-germain: original VALUE_EQUIVALENT relation is outside this exact-syntax fragment")
                .object("sourceTables",o->sourceTables.forEach(o::property))
                .array("rows",a->rows.forEach(r->a.objectValue(o->write(o,r))));
            return out.endObject().toString()+"\n";
        }
        public String measurementsJson() {
            var out=new JsonWriter().beginObject().property("schema",REVISION+"/runtime")
                .property("javaRuntime",System.getProperty("java.runtime.version"))
                .property("claim","single host diagnostic; not JMH, not canonical work, no statistical speedup claim")
                .property("allocationScope","current thread only; -1 for unsupported counters or worker portfolios")
                .array("samples",a->measurements.forEach(m->a.objectValue(o->o.property("caseId",m.caseId())
                    .property("arm",m.arm()).property("wallNanos",m.wallNanos())
                    .property("currentThreadAllocatedBytes",m.currentThreadAllocatedBytes()))));
            return out.endObject().toString()+"\n";
        }
    }
    private FiniteSearchKnowledgeComparison() { }

    public static void main(String[] args) throws IOException {
        if(args.length!=2) throw new IllegalArgumentException("canonical output and runtime output paths required");
        var report=run();
        write(Path.of(args[0]),report.canonicalJson());
        write(Path.of(args[1]),report.measurementsJson());
    }
    private static void write(Path path,String text) throws IOException {
        var absolute=path.toAbsolutePath();Files.createDirectories(absolute.getParent());Files.writeString(absolute,text);
    }
    public static Report run() {
        var cases=new ArrayList<Case>();
        for(var sample:HistoricalRediscoveryCorpus.load().cases()) {
            if(sample.targetRelation()==HistoricalRediscoveryCorpus.TargetRelation.SYNTAX_EXACT)
                cases.add(new Case(sample.id(),sample.source(),sample.target(),sample.oracleMaxDepth()));
        }
        for(int count:List.of(3,5,7)) cases.add(independentFactors(count));
        return runCases(cases);
    }
    static Case independentFactors(int count) {
        var source=new ArrayList<String>();var target=new ArrayList<String>();
        for(int i=0;i<count;i++) {
            source.add("a"+i+"*x+a"+i+"*y");target.add("a"+i+"*(x+y)");
        }
        return new Case("independent-factor-sites-"+count,"tuple("+String.join(",",source)+")",
            "tuple("+String.join(",",target)+")",count);
    }
    static Report runCases(List<Case> cases) {
        var rows=new ArrayList<Row>();var measurements=new ArrayList<Measurement>();var tables=new TreeMap<String,String>();
        for(var sample:cases) compare(sample,rows,measurements,tables);
        return new Report(rows,measurements,tables);
    }
    private static void compare(Case sample,List<Row> rows,List<Measurement> measurements,Map<String,String> tables) {
        var search=new ReversibleFactorSearch();
        var oracle=search.closure(sample.source(),CONTEXT,new BoundedReachabilityOracle.Budget(sample.depth(),sample.depth(),4096,100000),100000);
        String target=ExpressionFormatter.format(new ExpressionParser().parseTerm(sample.target()));
        int reference=oracle.oracle().states().stream().filter(s->s.expression().equals(target)).mapToInt(s->s.depth()).min().orElse(-1);
        String referenceStatus=oracle.oracle().closureComplete()?"COMPLETE_BOUNDED_CLOSURE":oracle.oracle().status().name();
        for(var mode:Mode.values()) measure(rows,measurements,sample.id(),mode.name(),false,()->
            row(sample.id(),mode.name(),search.search(sample.source(),sample.target(),CONTEXT,sample.depth(),CALLS,mode),reference,referenceStatus));
        for(int capacity:List.of(1,128)) {
            String name="GUIDED_CACHE_"+capacity;
            measure(rows,measurements,sample.id(),name,false,()->{
                var guided=GuidedFactorSearch.search(sample.source(),sample.target(),CONTEXT,sample.depth(),CALLS,capacity,LOOKUPS);
                var base=row(sample.id(),name,guided.result(),reference,referenceStatus);
                return new Row(base.caseId(),base.arm(),base.status()+":"+guided.terminalReason(),base.pathLength(),0,0,
                    base.searchCalls(),base.replayCalls(),base.generated(),guided.lookupOperations(),guided.cacheHits(),guided.replacements(),reference,referenceStatus);
            });
        }
        var lanes=List.of(new FactorSearchPortfolio.Lane("bfs",Mode.ONE_SIDED,CALLS/3),
            new FactorSearchPortfolio.Lane("meeting",Mode.BIDIRECTIONAL,CALLS/3),
            new FactorSearchPortfolio.Lane("deepening",Mode.ITERATIVE_DEEPENING,CALLS/3));
        for(int workers:List.of(1,3)) {
            String name="PORTFOLIO_"+workers;
            measure(rows,measurements,sample.id(),name,true,()->portfolio(sample,lanes,workers,reference,referenceStatus));
        }
        table(sample,rows,measurements,tables,reference,referenceStatus);
    }
    private static Row portfolio(Case sample,List<FactorSearchPortfolio.Lane> lanes,int workers,int reference,String referenceStatus) {
        var run=FactorSearchPortfolio.run(sample.source(),sample.target(),CONTEXT,sample.depth(),lanes,workers);
        long replay=run.lanes().stream().mapToLong(l->l.result().work().replay().calls()).sum();
        long generated=run.lanes().stream().mapToLong(l->generated(l.result())).sum();
        String status=run.selected().isPresent()?Status.REACHED.name():
            run.lanes().stream().anyMatch(l->l.result().status()==Status.WORK_EXHAUSTED)?Status.WORK_EXHAUSTED.name():Status.NO_PATH_WITHIN_DEPTH.name();
        return new Row(sample.id(),"PORTFOLIO_"+workers,status,run.selected().map(r->r.witness().size()).orElse(-1),
            0,0,run.totalCalls()-replay,replay,generated,0,0,0,reference,referenceStatus);
    }
    private static void table(Case sample,List<Row> rows,List<Measurement> measurements,Map<String,String> tables,int reference,String referenceStatus) {
        var retained=new FiniteFactorKnowledge[1];
        measure(rows,measurements,sample.id(),"TABLE_COLD",false,()->{
            try {
                retained[0]=FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(sample.source()),CONTEXT,sample.depth(),4096,CALLS));
                return query(sample,"TABLE_COLD",retained[0],retained[0].acquisition().calls(),0,reference,referenceStatus);
            } catch(FiniteFactorKnowledge.GenerationFailure limit) {
                return new Row(sample.id(),"TABLE_COLD","ACQUISITION_INCONCLUSIVE",-1,limit.work().calls(),0,0,0,limit.work().generated(),0,0,0,reference,referenceStatus);
            }
        });
        if(retained[0]!=null) tables.put(sample.id(),retained[0].toCanonicalJson());
        for(String arm:List.of("TABLE_RESTORED","TABLE_WARM")) measure(rows,measurements,sample.id(),arm,false,()->{
            if(retained[0]==null) return new Row(sample.id(),arm,"NO_COMPLETE_SOURCE_TABLE",-1,0,0,0,0,0,0,0,0,reference,referenceStatus);
            var table=arm.equals("TABLE_RESTORED")?FiniteFactorKnowledge.restore(retained[0].toCanonicalJson()):retained[0];
            return query(sample,arm,table,0,arm.equals("TABLE_RESTORED")?table.restoration().calls():0,reference,referenceStatus);
        });
    }
    private static Row query(Case sample,String arm,FiniteFactorKnowledge table,int acquisition,int restoration,int reference,String referenceStatus) {
        var result=table.lookup(sample.source(),sample.target(),CONTEXT,
            new FiniteFactorKnowledge.QueryBudget(sample.depth(),LOOKUPS,CALLS-acquisition-restoration));
        long generated=result.replayWork().generated()+(acquisition>0?table.acquisition().generated():0)+(restoration>0?table.restoration().generated():0);
        return new Row(sample.id(),arm,result.status().name(),result.status()==FiniteFactorKnowledge.Status.REACHED?result.witness().size():-1,
            acquisition,restoration,0,result.replayWork().calls(),generated,result.lookupWork(),0,0,reference,referenceStatus);
    }
    private static Row row(String id,String arm,Result result,int reference,String referenceStatus) {
        return new Row(id,arm,result.status().name(),result.status()==Status.REACHED?result.witness().size():-1,0,0,
            result.work().forward().calls()+result.work().reverse().calls(),result.work().replay().calls(),generated(result),0,0,0,reference,referenceStatus);
    }
    private static long generated(Result result) {
        return result.work().forward().generated()+result.work().reverse().generated()+result.work().replay().generated();
    }
    private static void measure(List<Row> rows,List<Measurement> costs,String id,String arm,boolean workers,Supplier<Row> action) {
        long allocated=allocation(),start=System.nanoTime();
        Row row=action.get();
        long elapsed=System.nanoTime()-start,after=allocation();
        if(row.totalNativeCalls()>CALLS) throw new IllegalStateException("comparison arm exceeded its declared call allowance");
        if(row.pathLength()>=0 && row.referenceMinimumDepth()<0 && row.referenceStatus().equals("COMPLETE_BOUNDED_CLOSURE"))
            throw new IllegalStateException("search reported a path absent from complete reference");
        rows.add(row);costs.add(new Measurement(id,arm,elapsed,workers || allocated<0 || after<0?-1:after-allocated));
    }
    private static long allocation() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean measured && measured.isThreadAllocatedMemorySupported()
            && measured.isThreadAllocatedMemoryEnabled()?measured.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
    }
    private static void write(JsonWriter out,Row r) {
        out.property("caseId",r.caseId()).property("arm",r.arm()).property("status",r.status()).property("pathLength",r.pathLength())
            .property("acquisitionCalls",r.acquisitionCalls()).property("restorationCalls",r.restorationCalls())
            .property("searchCalls",r.searchCalls()).property("replayCalls",r.replayCalls()).property("totalNativeCalls",r.totalNativeCalls())
            .property("generated",r.generated()).property("lookupOperations",r.lookupOperations()).property("cacheHits",r.cacheHits())
            .property("replacements",r.replacements()).property("referenceMinimumDepth",r.referenceMinimumDepth()).property("referenceStatus",r.referenceStatus());
    }
}
