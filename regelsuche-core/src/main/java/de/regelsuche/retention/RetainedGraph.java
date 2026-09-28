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
 * count once by identity. Only explicitly audited stateless enum types are borrowed; enum Views are traversed. No reflection or retained registry.
 */
public final class RetainedGraph {
    private RetainedGraph() {}
    // Cross-module names avoid a core -> search dependency. Same-loader exact declaring types
    // only: no package-prefix admission and no exemption for caller-defined enum callbacks.
    private static final Set<String> STATELESS_ENUMS=Set.of(
        "de.regelsuche.ast.BinaryOperator", "de.regelsuche.transform.RewriteKind",
        "de.regelsuche.assumption.Assumption$Kind", "de.regelsuche.knowledge.DerivationType",
        "de.regelsuche.knowledge.RuleStatus", "de.regelsuche.knowledge.SearchEffect",
        "de.regelsuche.search.moves.MoveSearch$Mode", "de.regelsuche.search.moves.MoveSearch$Scheduling",
        "de.regelsuche.search.moves.MoveSearch$Outcome", "de.regelsuche.search.moves.MoveSearch$Decision",
        "de.regelsuche.search.moves.MoveContext$Phase", "de.regelsuche.search.moves.SearchMove$SourceKind",
        "de.regelsuche.search.moves.SearchMove$ProofStrength", "de.regelsuche.search.moves.MovePriorityPolicy$Stage",
        "de.regelsuche.search.moves.SearchContinuationContract", "de.regelsuche.search.moves.NativeMoveSearch$ZeroScore",
        "de.regelsuche.search.moves.NativeMovePriorityPolicy$InventoryOrder", "de.regelsuche.search.moves.NativeStateValue$Empty",
        "de.regelsuche.search.moves.IncrementalProviderContract$ApplicationPhase",
        "de.regelsuche.search.moves.IncrementalProviderContract$Kind", "de.regelsuche.search.moves.IncrementalProviderContract$Transport",
        "de.regelsuche.search.moves.IncrementalProviderContract$Mathematics", "de.regelsuche.search.moves.IncrementalProviderContract$Status",
        "de.regelsuche.search.moves.IncrementalProviderContract$Operation");
    private static boolean borrowedEnum(Object value){
        if(!(value instanceof Enum<?> enumeration) || value instanceof View)return false;
        Class<?> type=enumeration.getDeclaringClass();
        return type.getClassLoader()==RetainedGraph.class.getClassLoader() && STATELESS_ENUMS.contains(type.getName());
    }

    public interface View { void retainedReferences(Visitor visitor); }
    public interface Visitor {
        void reference(Object value);
        void requireExact(Object value,Class<?> auditedType);
    }
    public record Usage(long nodes,long characters,long references) implements View {
        @Override public void retainedReferences(Visitor visitor) {}
        public Usage { if(nodes<0 || characters<0 || references<0)throw new IllegalArgumentException("negative retention"); }
        public Usage maximum(Usage other){return new Usage(Math.max(nodes,other.nodes),Math.max(characters,other.characters),Math.max(references,other.references));}
    }
    public record Observation(Usage retained,Usage peak,long work,long objects) {}
    public static final class Unmeasured extends IllegalArgumentException {
        private final Observation attempted;
        Unmeasured(Object payload,Observation attempted){super("unsupported retention payload: "+payload.getClass().getName());this.attempted=attempted;}
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
    /** Bounded per-session immutable accounting data; never mathematical verification authority. */
    public static final class Inventory implements View {
        private ImmutableRetentionInventory data;
        public Inventory(int vertexLimit, int wordLimit, int childLimit) {
            data=new ImmutableRetentionInventory(vertexLimit,wordLimit,childLimit);
        }
        public Inventory(){this(1_024,4_096,4_096);}
        /** The primary ownership root must contain an actual edge to this inventory. */
        public Observation measure(Object ownershipRoot) {
            return measure(ownershipRoot,new Usage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE));
        }
        public Observation measure(Object ownershipRoot, Usage limits) {
            if(data==null)throw new IllegalStateException("closed immutable inventory");
            return data.measure(ownershipRoot,this,limits);
        }
        public int cachedVertices(){return data==null?0:data.cachedVertices();}
        /** Paid logical release; repeated close performs no additional operation. */
        public long close(){if(data==null)return 0;long work=data.close();data=null;return Math.addExact(work,1);}
        @Override public void retainedReferences(Visitor visitor){visitor.reference(data);}
    }
    public static final class InventoryFailure extends IllegalArgumentException {
        private final Observation attempted;
        InventoryFailure(String message,Observation attempted){super(message);this.attempted=attempted;}
        public Observation attempted(){return attempted;}
    }

    static class Scan implements Visitor {
        final IdentityHashMap<Object,Boolean> seen=new IdentityHashMap<>();
        final ArrayDeque<Object> pending=new ArrayDeque<>();
        long nodes,characters,references,work,accountingReferences=5,temporaryCharacters;
        @Override public void requireExact(Object value,Class<?> auditedType){
            if(value.getClass()!=auditedType)throw new Unmeasured(value,observation());
        }
        @Override public void reference(Object value){
            references=Math.addExact(references,1);work=Math.addExact(work,1);
            if(value!=null && !borrowedEnum(value))pending.addLast(value);
            accountingPeak();
        }
        void accountingPeak(){
            // Scan owns map, queue and current-object slots plus their two backing-storage slots.
            accountingReferences=Math.max(accountingReferences,Math.addExact(5,Math.addExact(2L*seen.size(),pending.size())));
        }
        void inspect(Object value){
            switch(value) {
                case Equation equation -> { reference(equation.left());reference(equation.right()); }
                case BinaryExpr binary -> { node();reference(binary.left());reference(binary.operator());reference(binary.right()); }
                case FunctionExpr function -> { node();reference(function.name());reference(function.arguments()); }
                case VariableExpr variable -> { node();reference(variable.name());reference(variable.symbol().orElse(null)); }
                case NumberExpr number -> { node();reference(number.value()); }
                case com.fasterxml.jackson.core.io.ContentReference content when content.getClass()==com.fasterxml.jackson.core.io.ContentReference.class -> {reference(content.getRawContent());reference(null); /* pre-existing error-report metadata */}
                case com.fasterxml.jackson.databind.node.TextNode text when text.getClass()==com.fasterxml.jackson.databind.node.TextNode.class -> reference(text.textValue());
                case com.fasterxml.jackson.databind.node.IntNode valueNode when valueNode.getClass()==com.fasterxml.jackson.databind.node.IntNode.class -> {}
                case com.fasterxml.jackson.databind.node.LongNode valueNode when valueNode.getClass()==com.fasterxml.jackson.databind.node.LongNode.class -> {}
                case com.fasterxml.jackson.databind.node.DoubleNode valueNode when valueNode.getClass()==com.fasterxml.jackson.databind.node.DoubleNode.class -> {}
                case com.fasterxml.jackson.databind.node.BooleanNode valueNode when valueNode.getClass()==com.fasterxml.jackson.databind.node.BooleanNode.class -> {}
                case com.fasterxml.jackson.databind.node.NullNode valueNode when valueNode.getClass()==com.fasterxml.jackson.databind.node.NullNode.class -> {}
                case Optional<?> optional -> reference(optional.orElse(null));
                case String text -> characters=Math.addExact(characters,text.length());
                case StringBuilder text -> {reference(null);characters=Math.addExact(characters,text.length());}
                case ExactRational rational -> { reference(rational.numerator());reference(rational.denominator()); }
                case BigInteger integer -> {
                    if(integer.getClass()!=BigInteger.class)throw new Unmeasured(integer,observation());
                    String decimal=integer.toString();int digits=decimal.length();
                    characters=Math.addExact(characters,digits);temporaryCharacters=Math.max(temporaryCharacters,digits);
                    accountingReferences=Math.max(accountingReferences,Math.addExact(6,Math.addExact(2L*seen.size(),pending.size())));
                    work=Math.addExact(work,Math.addExact(digits,2L)); // conversion scan and temporary reference acquisition/release
                }
                case SymbolId symbol -> reference(symbol.namespace());
                // The exact ThreadLocal key has no strong value field; values belong to the thread map.
                case ThreadLocal<?> local when local.getClass()==ThreadLocal.class -> {}
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
                case java.util.concurrent.atomic.AtomicReferenceArray<?> array when array.getClass()==java.util.concurrent.atomic.AtomicReferenceArray.class -> { reference(null);for(int i=0;i<array.length();i++)reference(array.get(i)); }
                case Object[] array -> { for(Object entry:array)reference(entry); }
                case long[] array -> { for(int i=0;i<array.length;i++)reference(null); }
                case int[] array -> { for(int i=0;i<array.length;i++)reference(null); }
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
