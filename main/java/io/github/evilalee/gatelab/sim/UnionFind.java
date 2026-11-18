package io.github.evilalee.gatelab.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Disjoint-set structure used to rebuild electrical nets from wire topology. */
final class UnionFind<T> {
    private final Map<T, T> parents = new HashMap<>();
    private final Map<T, Integer> ranks = new HashMap<>();

    void add(T item) {
        parents.putIfAbsent(Objects.requireNonNull(item, "item"), item);
        ranks.putIfAbsent(item, 0);
    }

    T find(T item) {
        T parent = parents.get(item);
        if (parent == null) {
            throw new IllegalArgumentException("unknown union-find item: " + item);
        }
        if (!parent.equals(item)) {
            parent = find(parent);
            parents.put(item, parent);
        }
        return parent;
    }

    void union(T first, T second) {
        T rootA = find(first);
        T rootB = find(second);
        if (rootA.equals(rootB)) {
            return;
        }

        int rankA = ranks.get(rootA);
        int rankB = ranks.get(rootB);
        if (rankA < rankB) {
            parents.put(rootA, rootB);
        } else if (rankA > rankB) {
            parents.put(rootB, rootA);
        } else {
            parents.put(rootB, rootA);
            ranks.put(rootA, rankA + 1);
        }
    }
}
