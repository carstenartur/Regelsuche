package de.regelsuche.retention;

import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;

/** Private bounded accounting metadata. IDs are never reused before this inventory is closed. */
final class ImmutableRetentionInventory implements RetainedGraph.View {
    private final int vertexLimit,wordLimit,childLimit;
    private final IdentityHashMap<Object,Vertex> index=new IdentityHashMap<>();
    private final ArrayList<Vertex> rows=new ArrayList<>();
    private int childSlots,words,liveRows,childArrays,maskArrays;
    ImmutableRetentionInventory(int vertexLimit,int wordLimit,int childLimit){
        if(vertexLimit<1 || wordLimit<1 || childLimit<1)throw new IllegalArgumentException("nonpositive immutable inventory limit");
        this.vertexLimit=vertexLimit;this.wordLimit=wordLimit;this.childLimit=childLimit;
    }
    @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(index);visitor.reference(rows);}
    int cachedVertices(){int count=0;for(var row:rows)if(row!=null && row.active)count++;return count;}
    RetainedGraph.Observation measure(Object root,RetainedGraph.Inventory owner,RetainedGraph.Usage limits){
        return new InventoryScan(this,owner,limits).measure(root);
    }
    private static final class Vertex implements RetainedGraph.View {
        final int id;Object key;int[] children;long[] mask;int firstWord;
        long nodes,characters,references,aggregateNodes,aggregateCharacters,aggregateReferences;
        boolean ready,active,building;
        Vertex(int id,Object key){this.id=id;this.key=key;}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(key);visitor.reference(children);visitor.reference(mask);}
    }
    private Vertex reserve(Object value,InventoryScan scan){
        scan.pay(1);var previous=index.get(value);if(previous!=null)return previous;
        if(rows.size()>=vertexLimit)return null;
        var vertex=new Vertex(rows.size(),value);index.put(value,vertex);rows.add(vertex);liveRows++;
        scan.pay(3);scan.accountingPeak();return vertex;
    }
    private boolean known(Object value){
        return value instanceof Expr || value instanceof ExactRational || value instanceof String
            || value!=null && value.getClass()==BigInteger.class || value instanceof SymbolId || value instanceof UUID;
    }
    private boolean eligible(Object value){
        if(known(value))return true;
        var vertex=index.get(value);return vertex!=null && vertex.key instanceof List<?>;
    }
    private void stage(Object value,ArrayList<Object> children,long nodes,long characters,long references,InventoryScan scan){
        var vertex=reserve(value,scan);if(vertex==null || vertex.ready)return;
        scan.markPrimary(vertex.id);
        if(children.size()>childLimit-childSlots)return;
        int[] ids=new int[children.size()];scan.temporarySlots=children.size()+2L;scan.pay(ids.length+1L);
        vertex.children=ids;childSlots+=ids.length;childArrays++;scan.accountingPeak();
        for(int i=0;i<children.size();i++){
            Object child=children.get(i);scan.pay(1);
            boolean arguments=value instanceof FunctionExpr function && child==function.arguments();
            var next=known(child) || arguments ? reserve(child,scan) : null;
            ids[i]=next==null?-1:next.id;
        }
        vertex.nodes=nodes;vertex.characters=characters;vertex.references=references;vertex.ready=true;
        scan.pay(4);scan.temporarySlots=0;scan.accountingPeak();
    }
    private boolean closure(Vertex vertex,InventoryScan scan){
        if(vertex==null || !vertex.ready)return false;
        if(vertex.mask!=null)return true;
        if(vertex.building)return false;
        vertex.building=true;scan.pay(1);
        try {
            int first=vertex.id>>>6,last=first;
            for(int child:vertex.children){
                scan.pay(1);if(child<0 || !closure(rows.get(child),scan))return false;
                var nested=rows.get(child);first=Math.min(first,nested.firstWord);last=Math.max(last,nested.firstWord+nested.mask.length-1);
            }
            int length=last-first+1;if(length>wordLimit-words)return false;
            vertex.mask=new long[length];vertex.firstWord=first;words+=length;maskArrays++;
            scan.pay(length+2L);scan.accountingPeak();
            vertex.mask[(vertex.id>>>6)-first]|=1L<<(vertex.id&63);scan.pay(1);
            for(int child:vertex.children){
                var nested=rows.get(child);scan.pay(1);
                for(int i=0;i<nested.mask.length;i++){vertex.mask[nested.firstWord-first+i]|=nested.mask[i];scan.pay(1);}
            }
            for(int i=0;i<vertex.mask.length;i++){
                long bits=vertex.mask[i];scan.pay(1);
                while(bits!=0){int bit=Long.numberOfTrailingZeros(bits);var member=rows.get(((first+i)<<6)+bit);
                    vertex.aggregateNodes=Math.addExact(vertex.aggregateNodes,member.nodes);
                    vertex.aggregateCharacters=Math.addExact(vertex.aggregateCharacters,member.characters);
                    vertex.aggregateReferences=Math.addExact(vertex.aggregateReferences,member.references);
                    bits&=bits-1;scan.pay(4);
                }
            }
            return true;
        } finally {vertex.building=false;scan.pay(1);}
    }
    private void prepare(InventoryScan scan){for(var row:rows){scan.pay(1);if(row!=null && !row.active)closure(row,scan);}}
    private long remove(int id){
        var row=rows.get(id);if(row==null)return 0;
        index.remove(row.key);liveRows--;long work=2;
        if(row.children!=null){childSlots-=row.children.length;childArrays--;work++;}
        if(row.mask!=null){words-=row.mask.length;maskArrays--;work++;}
        row.key=null;row.children=null;row.mask=null;rows.set(id,null);return Math.addExact(work,4);
    }
    private long prune(long[] primary,boolean success){
        long work=0;
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);work=Math.addExact(work,1);if(row==null)continue;
            if(!success || !marked(primary,i) || row.mask==null)work=Math.addExact(work,remove(i));
        }
        return work;
    }
    private long activate(){
        long work=0;
        for(var row:rows){work=Math.addExact(work,1);if(row!=null && !row.active){row.active=true;work=Math.addExact(work,1);}}
        return work;
    }
    long close(){long work=prune(null,false);work=Math.addExact(work,rows.size()+2L);rows.clear();index.clear();return work;}
    private long metadataReferences(){return Math.addExact(5L,Math.addExact(2L*index.size()+rows.size(),3L*liveRows+childSlots+words));}
    private static boolean marked(long[] mask,int id){return (id>>>6)<mask.length && (mask[id>>>6]&(1L<<(id&63)))!=0;}

    private static final class InventoryScan extends RetainedGraph.Scan {
        final ImmutableRetentionInventory inventory;final RetainedGraph.Inventory owner;final RetainedGraph.Usage limits;
        long[] primary,cacheOnly;boolean cachePhase,ownerReached,settled,finished;
        ArrayList<Object> capture;long temporarySlots,combinedAuxiliaryPeak;
        long extraNodes,extraCharacters,extraReferences,extraObjects,hitObjects;
        long metadataReferencesBefore,metadataObjectsBefore;
        Object rejected;boolean failed;
        InventoryScan(ImmutableRetentionInventory inventory,RetainedGraph.Inventory owner,RetainedGraph.Usage limits){
            this.inventory=inventory;this.owner=owner;this.limits=Objects.requireNonNull(limits);
            int length=Math.toIntExact((inventory.vertexLimit+63L)>>>6);primary=new long[length];cacheOnly=new long[length];pay(2L*length+2);accountingPeak();
        }
        void pay(long units){work=Math.addExact(work,units);}
        void markPrimary(int id){primary[id>>>6]|=1L<<(id&63);pay(1);}
        @Override void accountingPeak(){
            super.accountingPeak();
            if(inventory!=null && primary!=null)combinedAuxiliaryPeak=Math.max(combinedAuxiliaryPeak,
                Math.addExact(inventory.metadataReferences(),Math.addExact(accountingReferences,7L+primary.length+cacheOnly.length+temporarySlots)));
        }
        @Override public void reference(Object value){
            if(capture!=null && value!=null && !(value instanceof Enum<?>)){
                capture.add(value);temporarySlots=capture.size()+1L;pay(1);
            }
            super.reference(value);
        }
        private boolean hit(Object value){
            pay(1);var vertex=inventory.index.get(value);if(vertex==null || !vertex.active)return false;
            if(marked(primary,vertex.id) || cachePhase && marked(cacheOnly,vertex.id)){pay(1);return true;}
            long[] target=cachePhase?cacheOnly:primary;
            boolean disjoint=true;
            for(int i=0;i<vertex.mask.length;i++){
                int word=vertex.firstWord+i;pay(1);
                if((vertex.mask[i]&(primary[word]|(cachePhase?cacheOnly[word]:0L)))!=0){disjoint=false;break;}
            }
            if(disjoint){
                long members=0;
                for(int i=0;i<vertex.mask.length;i++){
                    target[vertex.firstWord+i]|=vertex.mask[i];members+=Long.bitCount(vertex.mask[i]);pay(2);
                }
                nodes=Math.addExact(nodes,vertex.aggregateNodes);characters=Math.addExact(characters,vertex.aggregateCharacters);references=Math.addExact(references,vertex.aggregateReferences);
                hitObjects=Math.addExact(hitObjects,members);pay(4);
                if(cachePhase){extraNodes=Math.addExact(extraNodes,vertex.aggregateNodes);extraCharacters=Math.addExact(extraCharacters,vertex.aggregateCharacters);extraReferences=Math.addExact(extraReferences,vertex.aggregateReferences);extraObjects=Math.addExact(extraObjects,members);pay(4);}
                accountingPeak();return true;
            }
            for(int i=0;i<vertex.mask.length;i++){
                int word=vertex.firstWord+i;long known=primary[word]|(cachePhase?cacheOnly[word]:0L);
                long fresh=vertex.mask[i]&~known;target[word]|=cachePhase?vertex.mask[i]&~primary[word]:vertex.mask[i];pay(2);
                while(fresh!=0){int bit=Long.numberOfTrailingZeros(fresh);var member=inventory.rows.get((word<<6)+bit);
                    nodes=Math.addExact(nodes,member.nodes);characters=Math.addExact(characters,member.characters);references=Math.addExact(references,member.references);hitObjects++;
                    if(cachePhase){extraNodes=Math.addExact(extraNodes,member.nodes);extraCharacters=Math.addExact(extraCharacters,member.characters);extraReferences=Math.addExact(extraReferences,member.references);extraObjects++;}
                    fresh&=fresh-1;pay(4);
                }
            }
            accountingPeak();return true;
        }
        private void drain(){
            while(!pending.isEmpty()){
                Object value=pending.removeFirst();pay(1);
                if(value==owner){
                    ownerReached|=!cachePhase;
                    if(seen.put(value,Boolean.TRUE)==null)pay(1);
                    accountingPeak();continue;
                }
                if(hit(value))continue;
                if(seen.put(value,Boolean.TRUE)!=null)continue;
                pay(1);accountingPeak();
                long beforeNodes=nodes,beforeCharacters=characters,beforeReferences=references;
                boolean staged=!cachePhase && inventory.eligible(value);
                if(staged){capture=new ArrayList<>();pay(1);temporarySlots=1;accountingPeak();}
                try {
                    inspect(value);
                    if(staged)inventory.stage(value,capture,nodes-beforeNodes,characters-beforeCharacters,references-beforeReferences,this);
                    if(cachePhase){metadataReferencesBefore=Math.addExact(metadataReferencesBefore,references-beforeReferences);metadataObjectsBefore++;}
                }catch(RetainedGraph.Unmeasured unknown){if(rejected==null)rejected=value;failed=true;}
                finally {if(capture!=null){pay(capture.size()+1L);capture.clear();capture=null;temporarySlots=0;}}
            }
        }
        RetainedGraph.Observation measure(Object root){
            try {
                reference(root);drain();
                if(!ownerReached)failed=true;
                if(!failed)inventory.prepare(this);
                cachePhase=true;
                long ownerFields=references;owner.retainedReferences(this);
                metadataReferencesBefore=Math.addExact(metadataReferencesBefore,references-ownerFields);drain();
                long unionNodes=nodes,unionCharacters=characters,unionReferences=references;
                long primaryReferences=unionReferences-extraReferences-metadataReferencesBefore;
                var peak=new RetainedGraph.Usage(unionNodes,Math.addExact(unionCharacters,temporaryCharacters),
                    Math.addExact(unionReferences-metadataReferencesBefore,combinedAuxiliaryPeak));
                boolean within=peak.nodes()<=limits.nodes() && peak.characters()<=limits.characters() && peak.references()<=limits.references();
                pay(inventory.prune(primary,!failed && within));
                settle();
                var metadata=new MetadataScan(inventory);var remaining=metadata.measure(owner);pay(remaining.work());
                var retained=new RetainedGraph.Usage(unionNodes-extraNodes,unionCharacters-extraCharacters,Math.addExact(primaryReferences,remaining.retained().references()));
                peak=peak.maximum(new RetainedGraph.Usage(retained.nodes(),retained.characters(),Math.addExact(primaryReferences,remaining.peak().references())));
                long objects=seenObjectsBeforeSettle+hitObjects-extraObjects-metadataObjectsBefore+remaining.objects();
                // The post-prune bookkeeping sweep is part of the whole inspection too.
                // No staged entry becomes usable before that final peak has passed.
                if(peak.nodes()>limits.nodes() || peak.characters()>limits.characters() || peak.references()>limits.references()){
                    long removedObjects=inventory.liveRows+inventory.childArrays+inventory.maskArrays;
                    pay(inventory.prune(null,false));
                    retained=new RetainedGraph.Usage(retained.nodes(),retained.characters(),Math.addExact(primaryReferences,inventory.metadataReferences()));
                    objects-=removedObjects;
                } else if(!failed)pay(inventory.activate());
                var result=new RetainedGraph.Observation(retained,peak,work,objects);
                finished=true;
                if(rejected!=null)throw new RetainedGraph.Unmeasured(rejected,result);
                if(!ownerReached)throw new RetainedGraph.InventoryFailure("ownership root must reference its immutable inventory",result);
                return result;
            }finally{
                if(!finished)pay(inventory.prune(null,false));
                if(!settled)settle();
            }
        }
        long seenObjectsBeforeSettle;
        private void settle(){
            if(settled)return;seenObjectsBeforeSettle=seen.size();pay(2L*seen.size()+pending.size()+2);seen.clear();pending.clear();primary=null;cacheOnly=null;settled=true;
        }
    }
    /** Paid metadata-only sweep after pruning; immutable keys are already part of the primary union. */
    private static final class MetadataScan extends RetainedGraph.Scan {
        private final ImmutableRetentionInventory inventory;
        MetadataScan(ImmutableRetentionInventory inventory){this.inventory=inventory;}
        @Override public void reference(Object value){
            references=Math.addExact(references,1);work=Math.addExact(work,2);
            if(value!=null && !inventory.index.containsKey(value))pending.addLast(value);
            accountingPeak();
        }
        RetainedGraph.Observation measure(RetainedGraph.Inventory owner){
            try {
                seen.put(owner,Boolean.TRUE);work=Math.addExact(work,1);owner.retainedReferences(this);
                while(!pending.isEmpty()){
                    Object value=pending.removeFirst();work=Math.addExact(work,1);
                    if(seen.put(value,Boolean.TRUE)!=null)continue;
                    work=Math.addExact(work,1);accountingPeak();inspect(value);
                }
                var measured=observation();return new RetainedGraph.Observation(measured.retained(),measured.peak(),measured.work(),measured.objects()-1);
            } finally {pending.clear();seen.clear();}
        }
    }
}
