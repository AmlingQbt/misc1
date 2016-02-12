package misc1.commons.ds;

import com.google.common.collect.Iterators;
import java.util.Iterator;
import java.util.Map;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;

public class ImmutableSalvagingSet<T> implements Iterable<T> {
    private final MapNode<T, ObjectUtils.Null> root;

    public ImmutableSalvagingSet() {
        this.root = null;
    }

    private ImmutableSalvagingSet(MapNode<T, ObjectUtils.Null> root) {
        this.root = root;
    }

    public int size() {
        return MapNode.entrySizeOf(root);
    }

    public boolean isEmpty() {
        return root == null;
    }

    public boolean contains(T key) {
        return MapNode.containsKey(root, key);
    }

    public ImmutableSalvagingSet<T> add(T key) {
        Pair<MapNode<T, ObjectUtils.Null>, ObjectUtils.Null> pair = MapNode.put(root, key, ObjectUtils.NULL);
        return new ImmutableSalvagingSet<>(pair.getLeft());
    }

    public ImmutableSalvagingSet<T> remove(T key) {
        Pair<MapNode<T, ObjectUtils.Null>, ObjectUtils.Null> pair = MapNode.remove(root, key);
        return new ImmutableSalvagingSet<>(pair.getLeft());
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.transform(MapNode.entries(root).iterator(), Map.Entry<T, ObjectUtils.Null>::getKey);
    }

    public static <T> ImmutableSalvagingSet<T> of() {
        return new ImmutableSalvagingSet<>();
    }

    @Override
    public int hashCode() {
        return MapNode.hashCode(root);
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof ImmutableSalvagingSet)) {
            return false;
        }
        ImmutableSalvagingSet<T> other = (ImmutableSalvagingSet<T>)obj;
        return MapNode.equals(root, other.root);
    }
}
