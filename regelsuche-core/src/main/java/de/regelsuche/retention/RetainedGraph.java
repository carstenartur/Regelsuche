package de.regelsuche.retention;

import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;

/**
 * Explicit logical ownership, not JVM bytes. Each described field/collection entry is a slot;
 * collection backing storage contributes one slot. Only exact audited owning container classes are supported;
 * wrappers/views/subclasses and opaque comparators are rejected. Scalars use canonical decimal characters;
 * BigInteger conversion text is paid and overlaps the retained scalar in the peak. Null fields still occupy slots. Shared objects
 * count once by identity. Global enum constants are borrowed. No reflection or retained registry.
 */
public final class RetainedGraph {
    private RetainedGraph() {}
    public interface View { void retainedReferences(Visitor visitor); }
    public interface Visitor { void reference(Object value); }
    public record Usage(long nodes,long characters,long references) implements View {
        @Override public void retainedReferences(Visitor visitor) {}
        public Usage { if(nodes<0 || characters<0 || references<0)throw new IllegalArgumentException("negative retention"); }
        public Usage maximum(Usage other){return new Usage(Math.max(nodes,other.nodes),Math.max(characters,other.characters),Math.max(references,other.references));}
    }
    public record Observation(Usage retained,Usage peak,long work,long objects) {}
    public static final class Unmeasured extends IllegalArgumentException {
        private final Observation attempted;
        private Unmeasured(Object payload,Observation attempted){super("unsupported retention payload: "+payload.getClass().getName());this.attempted=attempted;}
        public Observation attempted(){return attempted;}
    }
    /** Each invocation pays fresh traversal/index work and drops all strong bookkeeping references. */
    public static Observation measure(Object root) {
        var scan=new Scan();
        try {
            scan.reference(root);
            while(!scan.pending.isEmpty()) {
                Object value=scan.pending.removeFirst();scan.work=Math.addExact(scan.work,1);
                if(scan.seen.put(value,Boolean.TRUE)!=null)continue;
                scan.work=Math.addExact(scan.work,1);scan.accountingPeak();scan.inspect(value);
            }
            return scan.observation();
        } finally { scan.pending.clear();scan.seen.clear(); }
    }
    private static final class Scan implements Visitor {
        final IdentityHashMap<Object,Boolean> seen=new IdentityHashMap<>();
        final ArrayDeque<Object> pending=new ArrayDeque<>();
        long nodes,characters,references,work,accountingReferences=5,temporaryCharacters;
        @Override public void reference(Object value){
            references=Math.addExact(references,1);work=Math.addExact(work,1);
            if(value!=null && !(value instanceof Enum<?>))pending.addLast(value);
            accountingPeak();
        }
        void accountingPeak(){
            // Scan owns map, queue and current-object slots plus their two backing-storage slots.
            accountingReferences=Math.max(accountingReferences,Math.addExact(5,Math.addExact(2L*seen.size(),pending.size())));
        }
        void inspect(Object value){
            switch(value) {
                case BinaryExpr binary -> { node();reference(binary.left());reference(binary.operator());reference(binary.right()); }
                case FunctionExpr function -> { node();reference(function.name());reference(function.arguments()); }
                case VariableExpr variable -> { node();reference(variable.name());reference(variable.symbol().orElse(null)); }
                case NumberExpr number -> { node();reference(number.value()); }
                case String text -> characters=Math.addExact(characters,text.length());
                case ExactRational rational -> { reference(rational.numerator());reference(rational.denominator()); }
                case BigInteger integer -> {
                    if(integer.getClass()!=BigInteger.class)throw new Unmeasured(integer,observation());
                    String decimal=integer.toString();int digits=decimal.length();
                    characters=Math.addExact(characters,digits);temporaryCharacters=Math.max(temporaryCharacters,digits);
                    accountingReferences=Math.max(accountingReferences,Math.addExact(6,Math.addExact(2L*seen.size(),pending.size())));
                    work=Math.addExact(work,Math.addExact(digits,2L)); // conversion scan and temporary reference acquisition/release
                }
                case SymbolId symbol -> reference(symbol.namespace());
                case UUID ignored -> {}
                case Long ignored -> {}
                case Integer ignored -> {}
                case Double ignored -> {}
                case Float ignored -> {}
                case Short ignored -> {}
                case Byte ignored -> {}
                case Boolean ignored -> {}
                case Character ignored -> characters=Math.addExact(characters,1);
                case View view -> view.retainedReferences(this);
                case Map<?,?> map when standardContainer(map) -> {
                    reference(null); // logical backing slot
                    if(map instanceof SortedMap<?,?> sorted)reference(sorted.comparator());
                    for(var entry:map.entrySet()){reference(entry.getKey());reference(entry.getValue());}
                }
                case Collection<?> collection when standardContainer(collection) -> {
                    reference(null);
                    if(collection instanceof SortedSet<?> sorted)reference(sorted.comparator());
                    if(collection instanceof PriorityQueue<?> queue)reference(queue.comparator());
                    for(Object entry:collection)reference(entry);
                }
                case Object[] array -> { for(Object entry:array)reference(entry); }
                case long[] array -> { for(int i=0;i<array.length;i++)reference(null); }
                case byte[] array -> characters=Math.addExact(characters,array.length);
                case char[] array -> characters=Math.addExact(characters,array.length);
                default -> throw new Unmeasured(value,observation());
            }
        }
        void node(){nodes=Math.addExact(nodes,1);}
        boolean standardContainer(Object value){
            Class<?> type=value.getClass();
            if(type==TreeMap.class)return ((TreeMap<?,?>)value).comparator()==null;
            if(type==TreeSet.class)return ((TreeSet<?>)value).comparator()==null;
            if(type==PriorityQueue.class) {
                var comparator=((PriorityQueue<?>)value).comparator();
                return comparator==null || comparator instanceof View;
            }
            if(type==ArrayList.class || type==LinkedList.class || type==ArrayDeque.class || type==HashMap.class
                    || type==LinkedHashMap.class || type==IdentityHashMap.class || type==HashSet.class || type==LinkedHashSet.class)return true;
            return switch(type.getName()) {
                case "java.util.ImmutableCollections$ListN", "java.util.ImmutableCollections$List12",
                    "java.util.ImmutableCollections$MapN", "java.util.ImmutableCollections$Map1",
                    "java.util.ImmutableCollections$SetN", "java.util.ImmutableCollections$Set12",
                    "java.util.Collections$EmptyList", "java.util.Collections$EmptyMap", "java.util.Collections$EmptySet",
                    "java.util.Collections$SingletonList", "java.util.Collections$SingletonMap", "java.util.Collections$SingletonSet" -> true;
                default -> false;
            };
        }
        Observation observation(){
            var retained=new Usage(nodes,characters,references);
            // Both paths leave through measure's finally. Settle each occupied identity slot and
            // pending traversal slot before publishing the receipt; none can survive the call.
            long settledWork=Math.addExact(work,Math.addExact(2L*seen.size(),pending.size()));
            return new Observation(retained,new Usage(nodes,Math.addExact(characters,temporaryCharacters),Math.addExact(references,accountingReferences)),settledWork,seen.size());
        }
    }
}
