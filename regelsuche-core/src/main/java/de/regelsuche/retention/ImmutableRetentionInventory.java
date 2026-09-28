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
    private int firstLive=-1,lastLive=-1;
    private long metadataSlots=5,metadataObjects=3;
    ImmutableRetentionInventory(int vertexLimit,int wordLimit,int childLimit){
        if(vertexLimit<1 || wordLimit<1 || childLimit<1)throw new IllegalArgumentException("nonpositive immutable inventory limit");
        this.vertexLimit=vertexLimit;this.wordLimit=wordLimit;this.childLimit=childLimit;
    }
    @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(index);visitor.reference(rows);}
    int cachedVertices(){
        int count=0;
        for(int id=firstLive;id>=0;){var row=rows.get(id);id=row.nextLive;if(row.active)count++;}
        return count;
    }
    RetainedGraph.Observation measure(Object root,RetainedGraph.Inventory owner,RetainedGraph.Usage limits){
        return new InventoryScan(this,owner,limits).measure(root);
    }
    private static final class Vertex implements RetainedGraph.View {
        final int id;Object key;int[] children;long[] mask;int firstWord;
        int previousLive=-1,nextLive=-1;
        long nodes,characters,references,aggregateNodes,aggregateCharacters,aggregateReferences;
        boolean ready,active,building;
        Vertex(int id,Object key){this.id=id;this.key=key;}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(key);visitor.reference(children);visitor.reference(mask);}
    }
    private Vertex reserve(Object value,InventoryScan scan){
        scan.pay(1);var previous=index.get(value);if(previous!=null)return previous;
        if(rows.size()>=vertexLimit)return null;
        var vertex=new Vertex(rows.size(),value);index.put(value,vertex);rows.add(vertex);liveRows++;
        vertex.previousLive=lastLive;scan.pay(1);
        if(lastLive<0){firstLive=vertex.id;scan.pay(1);}
        else {rows.get(lastLive).nextLive=vertex.id;scan.pay(2);}
        lastLive=vertex.id;scan.pay(1);
        scan.pay(3+metadataDelta(6,1));scan.accountingPeak();return vertex;
    }
    private boolean known(Object value){
        return value instanceof Expr || value instanceof ExactRational || value instanceof String
            || value!=null && value.getClass()==BigInteger.class || value instanceof SymbolId || value instanceof UUID;
    }
    private boolean ownsMetadata(Object value,InventoryScan scan){
        scan.pay(1);
        if(value==this || value==index || value==rows)return true;
        if(value instanceof Vertex vertex){scan.pay(1);return vertex.id<rows.size() && rows.get(vertex.id)==vertex;}
        if(value instanceof int[] || value instanceof long[]){
            for(int id=firstLive;id>=0;){
                var row=rows.get(id);id=row.nextLive;scan.pay(2);
                if(value==row.children || value==row.mask)return true;
            }
        }
        return false;
    }
    private boolean eligible(Object value,InventoryScan scan){
        if(known(value))return true;
        scan.pay(1);var vertex=index.get(value);return vertex!=null && vertex.key instanceof List<?>;
    }
    private void stage(Object value,ArrayList<Object> children,long nodes,long characters,long references,InventoryScan scan){
        var vertex=reserve(value,scan);if(vertex==null || vertex.ready)return;
        scan.markPrimary(vertex.id);
        if(children.size()>childLimit-childSlots)return;
        int[] ids=new int[children.size()];scan.temporarySlots=children.size()+2L;scan.pay(ids.length+1L);
        vertex.children=ids;childSlots+=ids.length;childArrays++;scan.pay(metadataDelta(ids.length,1));scan.accountingPeak();
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
        vertex.building=true;scan.closureSlots+=3;scan.pay(4);scan.accountingPeak();
        try {
            int first=vertex.id>>>6,last=first;
            for(int child:vertex.children){
                scan.pay(1);if(child<0 || !closure(rows.get(child),scan))return false;
                var nested=rows.get(child);first=Math.min(first,nested.firstWord);last=Math.max(last,nested.firstWord+nested.mask.length-1);
            }
            int length=last-first+1;if(length>wordLimit-words)return false;
            vertex.mask=new long[length];vertex.firstWord=first;words+=length;maskArrays++;
            scan.pay(length+2L+metadataDelta(length,1));scan.accountingPeak();
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
        } finally {vertex.building=false;scan.closureSlots-=3;scan.pay(4);}
    }
    private void prepare(InventoryScan scan){
        for(int id=firstLive;id>=0;){
            var row=rows.get(id);id=row.nextLive;scan.pay(2);
            if(!row.active)closure(row,scan);
        }
    }
    private long unlink(Vertex row){
        int previous=row.previousLive,next=row.nextLive;long work=2;
        if(previous<0){firstLive=next;work++;}
        else {rows.get(previous).nextLive=next;work+=2;}
        if(next<0){lastLive=previous;work++;}
        else {rows.get(next).previousLive=previous;work+=2;}
        row.previousLive=-1;row.nextLive=-1;return work+2;
    }
    private long remove(int id){
        var row=rows.get(id);if(row==null)return 0;
        index.remove(row.key);liveRows--;long work=2,removedSlots=5,removedObjects=1;
        if(row.children!=null){childSlots-=row.children.length;childArrays--;removedSlots+=row.children.length;removedObjects++;work+=3;}
        if(row.mask!=null){words-=row.mask.length;maskArrays--;removedSlots+=row.mask.length;removedObjects++;work+=3;}
        work=Math.addExact(work,unlink(row));
        row.key=null;row.children=null;row.mask=null;rows.set(id,null);
        return Math.addExact(work,4+metadataDelta(-removedSlots,-removedObjects));
    }
    private long prune(long[] primary,boolean success){
        long work=0;
        for(int id=firstLive;id>=0;){
            var row=rows.get(id);int next=row.nextLive;work=Math.addExact(work,2);
            if(!success || !marked(primary,id) || row.mask==null)work=Math.addExact(work,remove(id));
            id=next;
        }
        return work;
    }
    private long activate(){
        long work=0;
        for(int id=firstLive;id>=0;){
            var row=rows.get(id);id=row.nextLive;work=Math.addExact(work,2);
            if(!row.active){row.active=true;work=Math.addExact(work,1);}
        }
        return work;
    }
    long close(){
        long work=prune(null,false);work=Math.addExact(work,rows.size()+2L+metadataDelta(-rows.size(),0));
        rows.clear();index.clear();return work;
    }
    private long metadataDelta(long slots,long objects){
        metadataSlots=Math.addExact(metadataSlots,slots);metadataObjects=Math.addExact(metadataObjects,objects);return 6; // two reads, checked additions and writes
    }
    private long metadataReferences(){return metadataSlots;}
    /** Paid independent counter/formula check at phase boundaries, never on the per-reference hot path. */
    private void checkMetadataTotals(InventoryScan scan){
        long live=liveRows,allocated=rows.size(),edges=childSlots,maskWords=words,children=childArrays,masks=maskArrays;
        long slots=Math.addExact(5,Math.addExact(5L*live,Math.addExact(allocated,Math.addExact(edges,maskWords))));
        long objects=Math.addExact(3,Math.addExact(live,Math.addExact(children,masks)));
        scan.pay(18); // six counter reads, size consistency read, eight arithmetic operations, three comparisons
        if(slots!=metadataSlots || objects!=metadataObjects || index.size()!=live)
            throw new IllegalStateException("immutable inventory metadata counters disagree");
        scan.pay(6);
        if((firstLive<0)!=(liveRows==0) || (lastLive<0)!=(liveRows==0))
            throw new IllegalStateException("immutable inventory live chain disagrees");
    }
    private boolean cacheKeys(InventoryScan scan){
        for(int id=firstLive;id>=0;){
            var row=rows.get(id);id=row.nextLive;scan.pay(2);
            Object key=row.key;scan.pay(3); // key read and both incoming key edges, already included in M
            scan.temporarySlots=2;scan.accountingPeak();
            try {
                if(row.active){scan.hit(key);}
                else {scan.pay(1);if(!scan.seen.containsKey(key))return false;}
            }finally{scan.temporarySlots=0;scan.pay(2);}
        }
        return true;
    }
    private static boolean marked(long[] mask,int id){return (id>>>6)<mask.length && (mask[id>>>6]&(1L<<(id&63)))!=0;}

    private static final class InventoryScan extends RetainedGraph.Scan implements RetainedGraph.View {
        final ImmutableRetentionInventory inventory;final RetainedGraph.Inventory owner;final RetainedGraph.Usage limits;
        long[] primary,cacheOnly;boolean cachePhase,ownerReached,settled,finished,metadataAlias;
        ArrayList<Object> capture;long temporarySlots,closureSlots,combinedAuxiliaryPeak,scratchPeak;
        long extraNodes,extraCharacters,extraReferences,extraObjects,hitObjects;
        Object rejected;boolean failed;
        InventoryScan(ImmutableRetentionInventory inventory,RetainedGraph.Inventory owner,RetainedGraph.Usage limits){
            this.inventory=inventory;this.owner=owner;this.limits=Objects.requireNonNull(limits);
            int length=Math.toIntExact((inventory.vertexLimit+63L)>>>6);primary=new long[length];cacheOnly=new long[length];pay(2L*length+2);accountingPeak();
        }
        void pay(long units){work=Math.addExact(work,units);}
        void markPrimary(int id){primary[id>>>6]|=1L<<(id&63);pay(1);}
        @Override void accountingPeak(){
            super.accountingPeak();
            if(inventory!=null && primary!=null){
                scratchPeak=Math.max(scratchPeak,accountingReferences+7L+primary.length+cacheOnly.length+temporarySlots+closureSlots);
                combinedAuxiliaryPeak=Math.max(combinedAuxiliaryPeak,
                Math.addExact(inventory.metadataReferences(),Math.addExact(accountingReferences,7L+primary.length+cacheOnly.length+temporarySlots+closureSlots)));
            }
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
                if(!cachePhase && inventory.ownsMetadata(value,this))metadataAlias=true;
                if(hit(value))continue;
                if(seen.put(value,Boolean.TRUE)!=null)continue;
                pay(1);accountingPeak();
                long beforeNodes=nodes,beforeCharacters=characters,beforeReferences=references;
                boolean staged=!cachePhase && !metadataAlias && inventory.eligible(value,this);
                if(staged){capture=new ArrayList<>();pay(1);temporarySlots=1;accountingPeak();}
                try {
                    inspect(value);
                    if(staged)inventory.stage(value,capture,nodes-beforeNodes,characters-beforeCharacters,references-beforeReferences,this);
                }catch(RetainedGraph.Unmeasured unknown){if(rejected==null)rejected=value;failed=true;}
                finally {if(capture!=null){pay(capture.size()+1L);capture.clear();capture=null;temporarySlots=0;}}
            }
        }
        RetainedGraph.Observation measure(Object root){
            try {
                reference(root);drain();
                if(metadataAlias)return freshAliasFallback(root);
                if(!ownerReached)failed=true;
                if(!failed)inventory.prepare(this);
                inventory.checkMetadataTotals(this);
                cachePhase=true;
                if(!inventory.cacheKeys(this))return freshAliasFallback(root);
                long unionNodes=nodes,unionCharacters=characters,unionReferences=references;
                var peak=new RetainedGraph.Usage(unionNodes,Math.addExact(unionCharacters,temporaryCharacters),
                    Math.addExact(unionReferences,combinedAuxiliaryPeak));
                boolean within=peak.nodes()<=limits.nodes() && peak.characters()<=limits.characters() && peak.references()<=limits.references();
                pay(inventory.prune(primary,!failed && within));
                inventory.checkMetadataTotals(this);
                var retained=new RetainedGraph.Usage(unionNodes-extraNodes,unionCharacters-extraCharacters,
                    Math.addExact(unionReferences-extraReferences,inventory.metadataSlots));
                long objects=seen.size()+hitObjects-extraObjects+inventory.metadataObjects;
                if(!failed && within)pay(inventory.activate());
                settle();
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
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){
            visitor.reference(seen);visitor.reference(pending);visitor.reference(null); // base current-object slot
            visitor.reference(inventory);visitor.reference(owner);visitor.reference(limits);
            visitor.reference(primary);visitor.reference(cacheOnly);visitor.reference(capture);visitor.reference(rejected);
        }
        private record AliasUnion(Object root,RetainedGraph.Inventory owner,InventoryScan scanner) implements RetainedGraph.View {
            @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(root);visitor.reference(owner);visitor.reference(scanner);}
        }
        private RetainedGraph.Observation fresh(Object root){
            try{return RetainedGraph.measure(root);}
            catch(RetainedGraph.Unmeasured unknown){failed=true;return unknown.attempted();}
        }
        /** Rare public metadata aliases use the unchanged reference scanner, including its real extra cost. */
        private RetainedGraph.Observation freshAliasFallback(Object root){
            // Fresh reference scans can stop earlier at an unknown payload. They cannot
            // erase the nodes/text/slots already visited by the continuing primary scan.
            long observedNodes=nodes,observedCharacters=Math.addExact(characters,temporaryCharacters);
            long observedReferences=Math.addExact(references,scratchPeak);pay(5);
            pay(4);var union=fresh(new AliasUnion(root,owner,this));pay(union.work());
            long currentScratch=12L+2L*seen.size()+pending.size()+primary.length+cacheOnly.length;
            var peak=union.peak().maximum(new RetainedGraph.Usage(union.retained().nodes(),union.retained().characters(),
                Math.addExact(Math.max(0,union.retained().references()-currentScratch),scratchPeak)));
            peak=peak.maximum(new RetainedGraph.Usage(observedNodes,observedCharacters,observedReferences));
            union=null;pay(1);
            pay(inventory.prune(null,false));settle();
            var remaining=fresh(root);pay(remaining.work());
            // The first scanner's empty map/queue/backings and seven fields still exist.
            peak=peak.maximum(new RetainedGraph.Usage(remaining.peak().nodes(),remaining.peak().characters(),Math.addExact(12,remaining.peak().references())));
            var result=new RetainedGraph.Observation(remaining.retained(),peak,work,remaining.objects());finished=true;
            if(rejected!=null)throw new RetainedGraph.Unmeasured(rejected,result);
            if(!ownerReached)throw new RetainedGraph.InventoryFailure("ownership root must reference its immutable inventory",result);
            if(failed)throw new RetainedGraph.InventoryFailure("unsupported payload during fresh metadata-alias observation",result);
            return result;
        }
        long seenObjectsBeforeSettle;
        private void settle(){
            if(settled)return;seenObjectsBeforeSettle=seen.size();pay(2L*seen.size()+pending.size()+2);seen.clear();pending.clear();primary=null;cacheOnly=null;settled=true;
        }
    }
}
