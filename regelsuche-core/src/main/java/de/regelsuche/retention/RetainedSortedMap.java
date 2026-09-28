package de.regelsuche.retention;

import java.util.*;

/** Immutable natural-order map whose actual backing owner is explicitly inspectable. */
public final class RetainedSortedMap<K,V> implements Map<K,V>,RetainedGraph.View {
    private final TreeMap<K,V> values;
    private RetainedSortedMap(Map<? extends K,? extends V> source){values=new TreeMap<>();values.putAll(source);}
    public static <K,V> RetainedSortedMap<K,V> copyOf(Map<? extends K,? extends V> source){return new RetainedSortedMap<>(source);}
    @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(values);}
    @Override public int size(){return values.size();}
    @Override public boolean isEmpty(){return values.isEmpty();}
    @Override public boolean containsKey(Object key){return values.containsKey(key);}
    @Override public boolean containsValue(Object value){return values.containsValue(value);}
    @Override public V get(Object key){return values.get(key);}
    @Override public Set<K> keySet(){return Collections.unmodifiableSet(values.keySet());}
    @Override public Collection<V> values(){return Collections.unmodifiableCollection(values.values());}
    @Override public Set<Entry<K,V>> entrySet(){return Collections.unmodifiableMap(values).entrySet();}
    @Override public V put(K key,V value){throw new UnsupportedOperationException("immutable map");}
    @Override public V remove(Object key){throw new UnsupportedOperationException("immutable map");}
    @Override public void putAll(Map<? extends K,? extends V> other){throw new UnsupportedOperationException("immutable map");}
    @Override public void clear(){throw new UnsupportedOperationException("immutable map");}
    @Override public boolean equals(Object other){return values.equals(other);}
    @Override public int hashCode(){return values.hashCode();}
    @Override public String toString(){return values.toString();}
}
