package de.regelsuche.search.reachability;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.search.reachability.ReversibleFactorSearch.*;
import de.regelsuche.search.reachability.ReversibleFactorFragment.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source-generated finite table; a miss says nothing about the surrounding rewrite system. */
public final class FiniteFactorKnowledge {
    public static final String SCHEMA = "regelsuche.finite-factor-knowledge/v1";
    private static final int MAX_ARTIFACT_BYTES = 4_194_304;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    private static final Set<String> RULES = Set.of("ast_factor_common_left", "ast_factor_common_right",
        "ast_distribute_left_add", "ast_distribute_right_add");

    public record Plan(List<String> seeds, AssumptionSignature assumptions, int depth, int maxStates, int maxCalls) {
        public Plan {
            assumptions = BoundedReachabilityOracle.normalizeSignature(assumptions);
            if (seeds == null || seeds.isEmpty() || seeds.size() > 16 || depth < 0 || depth > 10
                    || maxStates < 1 || maxStates > 4096 || maxCalls < 0 || maxCalls > 100_000)
                throw new IllegalArgumentException("finite table plan limits");
            var fragment = new ReversibleFactorFragment();
            seeds = seeds.stream().map(s -> {
                if (s == null || s.length() > 2048) throw new IllegalArgumentException("seed length");
                return fragment.endpoint(s);
            }).distinct().sorted().toList();
            if (seeds.size() > maxStates) throw new IllegalArgumentException("seeds exceed state budget");
        }
    }
    public record QueryBudget(int depth, long lookupOperations, int replayCalls) {
        public QueryBudget {
            if (depth < 0 || depth > 10 || lookupOperations < 0 || replayCalls < 0 || replayCalls > 100_000)
                throw new IllegalArgumentException("finite lookup limits");
        }
    }
    public enum Status {
        REACHED, NOT_IN_TABLE, NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH,
        OUTSIDE_LANGUAGE, CONTEXT_MISMATCH, WORK_EXHAUSTED
    }
    public record Query(Status status, List<Step> witness, long lookupWork, Count replayWork) {
        public Query { witness = List.copyOf(witness); }
    }
    public static final class GenerationFailure extends IllegalArgumentException {
        private final Count work;
        private GenerationFailure(String message, Count work) { super(message); this.work = work; }
        public Count work() { return work; }
    }
    private record Edge(String from, String to, String rule, String application) { }
    private record Data(String schema, String inventoryHash, Plan plan, List<String> language,
                        Map<String,Integer> distanceFromSeeds, List<Edge> edges, Count acquisition) { }
    private record Envelope(Data content, String contentHash) { }
    private record Visit(String expression, int depth) { }
    private final Envelope artifact;
    private final Map<String,List<Step>> graph;
    private final Count restoration;

    private FiniteFactorKnowledge(Envelope artifact, Map<String,List<Step>> graph, Count restoration) {
        this.artifact = artifact; this.graph = Map.copyOf(graph); this.restoration = restoration;
    }
    public Count acquisition() { return artifact.content().acquisition(); }
    public Count restoration() { return restoration; }
    public String contentHash() { return artifact.contentHash(); }
    public String toCanonicalJson() { return json(artifact); }
    public List<String> language() { return artifact.content().language(); }
    public Map<String,Integer> distanceFromSeeds() { return artifact.content().distanceFromSeeds(); }

    /** Complete source-defined expansion to the declared depth, or an explicit failure carrying spent calls. */
    public static FiniteFactorKnowledge generate(Plan plan) {
        return generate(plan, MAX_ARTIFACT_BYTES);
    }

    static FiniteFactorKnowledge generate(Plan plan, int maxArtifactBytes) {
        Objects.requireNonNull(plan);
        if (maxArtifactBytes < 1 || maxArtifactBytes > MAX_ARTIFACT_BYTES)
            throw new IllegalArgumentException("invalid table byte limit");
        var fragment = new ReversibleFactorFragment();
        var work = new Counter(new Allowance(plan.maxCalls()));
        var depths = new LinkedHashMap<String,Integer>();
        var frontier = new ArrayDeque<Visit>();
        plan.seeds().forEach(s -> { depths.put(s,0); frontier.add(new Visit(s,0)); });
        var graph = new TreeMap<String,List<Step>>();
        try {
            while (!frontier.isEmpty()) {
                var current = frontier.removeFirst();
                if (current.depth() >= plan.depth()) { graph.putIfAbsent(current.expression(),List.of()); continue; }
                var steps = new ArrayList<Step>();
                for (var arc : fragment.neighbors(current.expression(),work)) {
                    String output = arc.outgoing().transformedExpression();
                    if (!depths.containsKey(output)) {
                        if (depths.size() >= plan.maxStates()) throw new GenerationFailure("state limit before complete table",work.snapshot());
                        depths.put(output,current.depth()+1); frontier.addLast(new Visit(output,current.depth()+1));
                    }
                    steps.add(new Step(new State(current.expression(),plan.assumptions()),new State(output,plan.assumptions()),arc.outgoing()));
                }
                graph.put(current.expression(),steps.stream().distinct().sorted(stepOrder()).toList());
            }
        } catch (BoundedReachabilityOracle.EngineWorkExhausted limit) {
            throw new GenerationFailure("primitive call limit before complete table",work.snapshot());
        }
        List<Edge> edges = graph.values().stream().flatMap(Collection::stream)
            .map(s -> new Edge(s.from().expression(),s.to().expression(),s.primitive().rule(),s.primitive().applicationKey())).toList();
        String inventory = RuleInventoryFingerprint.contentHash(AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(r -> RULES.contains(r.id())).toList());
        var data = new Data(SCHEMA,inventory,plan,depths.keySet().stream().sorted().toList(),
            Map.copyOf(depths),edges,work.snapshot());
        var artifact = new Envelope(data,hash(json(data)));
        if (!fitsByteLimit(json(artifact), maxArtifactBytes))
            throw new GenerationFailure("canonical table exceeds byte limit", data.acquisition());
        return new FiniteFactorKnowledge(artifact,graph,new Count(0,0));
    }

    /** A stored success flag or rehashed forged edge cannot authorize execution: the whole source plan reruns. */
    public static FiniteFactorKnowledge restore(String canonical) {
        if (canonical == null || !fitsByteLimit(canonical, MAX_ARTIFACT_BYTES))
            throw new IllegalArgumentException("table byte limit");
        try {
            var supplied = JSON.readValue(canonical,Envelope.class);
            if (supplied == null || supplied.content()==null || !SCHEMA.equals(supplied.content().schema()) || !canonical.equals(json(supplied))
                    || !hash(json(supplied.content())).equals(supplied.contentHash())) throw new IllegalArgumentException("table binding differs");
            var rebuilt = generate(supplied.content().plan());
            if (!canonical.equals(rebuilt.toCanonicalJson())) throw new IllegalArgumentException("independent table reconstruction differs");
            return new FiniteFactorKnowledge(rebuilt.artifact,rebuilt.graph,rebuilt.acquisition());
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("invalid table",malformed); }
    }

    public Query lookup(String source, String target, AssumptionSignature assumptions, QueryBudget budget) {
        Objects.requireNonNull(budget);
        if (!artifact.content().plan().assumptions().equals(BoundedReachabilityOracle.normalizeSignature(assumptions)))
            return query(Status.CONTEXT_MISMATCH,List.of(),0,new Count(0,0));
        var fragment = new ReversibleFactorFragment();
        String start = fragment.endpoint(source), goal = fragment.endpoint(target);
        if (!graph.containsKey(start)) return query(Status.OUTSIDE_LANGUAGE,List.of(),0,new Count(0,0));
        var lookup = new Lookup(start,goal,budget);
        try { return lookup.execute(fragment); }
        catch (LookupLimit | BoundedReachabilityOracle.EngineWorkExhausted limit) {
            return query(Status.WORK_EXHAUSTED,List.of(),lookup.work,lookup.replay.snapshot());
        }
    }
    private final class Lookup {
        final String start, goal;
        final QueryBudget budget;
        final Map<String,Step> parent = new HashMap<>();
        final Set<String> seen = new HashSet<>();
        final ArrayDeque<Visit> frontier = new ArrayDeque<>();
        final Counter replay;
        long work;
        Lookup(String start,String goal,QueryBudget budget) {
            this.start=start;this.goal=goal;this.budget=budget;replay=new Counter(new Allowance(budget.replayCalls()));
            seen.add(start);frontier.add(new Visit(start,0));
        }
        void charge() { if (work >= budget.lookupOperations()) throw new LookupLimit(); work++; }
        Query execute(ReversibleFactorFragment fragment) {
            while (!frontier.isEmpty()) {
                charge();var current=frontier.removeFirst();
                if (current.expression().equals(goal)) return checked(fragment);
                if (current.depth() >= budget.depth()) continue;
                for (Step step:graph.getOrDefault(current.expression(),List.of())) {
                    charge();
                    if (seen.add(step.to().expression())) {
                        parent.put(step.to().expression(),step);frontier.addLast(new Visit(step.to().expression(),current.depth()+1));
                    }
                }
            }
            var status = graph.containsKey(goal) ? Status.NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH : Status.NOT_IN_TABLE;
            return query(status,List.of(),work,replay.snapshot());
        }
        Query checked(ReversibleFactorFragment fragment) {
            var path=new ArrayList<Step>();
            for (String cursor=goal;!cursor.equals(start);) {
                charge();var step=Objects.requireNonNull(parent.get(cursor));path.add(step);cursor=step.from().expression();
            }
            Collections.reverse(path);
            for (Step step:path) {
                if (fragment.neighbors(step.from().expression(),replay).stream().noneMatch(a->a.outgoing().equals(step.primitive())))
                    throw new IllegalStateException("table path no longer replays");
            }
            return query(Status.REACHED,path,work,replay.snapshot());
        }
    }
    private static final class LookupLimit extends RuntimeException { }
    private static Query query(Status status,List<Step> path,long work,Count replay) { return new Query(status,path,work,replay); }
    private static Comparator<Step> stepOrder() {
        return Comparator.comparing((Step s)->s.to().expression()).thenComparing(s->s.primitive().rule()).thenComparing(s->s.primitive().applicationKey());
    }
    private static boolean fitsByteLimit(String canonical, int maxArtifactBytes) {
        return canonical.length() <= maxArtifactBytes
            && canonical.getBytes(StandardCharsets.UTF_8).length <= maxArtifactBytes;
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("cannot encode table",failure); }
    }
    private static String hash(String value) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
