package com.geotracker.index;

import com.geotracker.model.Position;

import java.util.ArrayList;
import java.util.List;

public class HamtIndex {
    private static final int BRANCHING_FACTOR = 32;
    private static final int BITS_PER_LEVEL = 5;
    private static final int MAX_DEPTH = 6;

    private volatile Node root;
    private final Node emptyRoot;

    public HamtIndex() {
        this.emptyRoot = new BranchNode(new Node[BRANCHING_FACTOR]);
        this.root = emptyRoot;
    }

    public synchronized void put(long vehicleId, Position position) {
        root = root.put(vehicleId, position, 0);
    }

    public Position get(long vehicleId) {
        Node node = root;
        int hash = Long.hashCode(vehicleId);
        for (int level = 0; level < MAX_DEPTH; level++) {
            if (node instanceof LeafNode leaf) {
                for (Entry entry : leaf.entries()) {
                    if (entry.key() == vehicleId) {
                        return entry.value();
                    }
                }
                return null;
            }
            BranchNode branch = (BranchNode) node;
            int index = (hash >>> (level * BITS_PER_LEVEL)) & (BRANCHING_FACTOR - 1);
            Node child = branch.children()[index];
            if (child == null) {
                return null;
            }
            node = child;
        }
        if (node instanceof LeafNode leaf) {
            for (Entry entry : leaf.entries()) {
                if (entry.key() == vehicleId) {
                    return entry.value();
                }
            }
        }
        return null;
    }

    public synchronized void publish() {
        root = deepCopy(root);
    }

    private Node deepCopy(Node node) {
        if (node instanceof LeafNode leaf) {
            java.util.List<Entry> entries = new java.util.ArrayList<>(java.util.Arrays.asList(leaf.entries()));
            return new LeafNode(entries.toArray(new Entry[0]));
        }
        BranchNode branch = (BranchNode) node;
        Node[] children = new Node[BRANCHING_FACTOR];
        for (int i = 0; i < BRANCHING_FACTOR; i++) {
            if (branch.children()[i] != null) {
                children[i] = deepCopy(branch.children()[i]);
            }
        }
        return new BranchNode(children);
    }

    public int size() {
        return count(root);
    }

    private int count(Node node) {
        if (node instanceof LeafNode leaf) {
            return leaf.entries().length;
        }
        BranchNode branch = (BranchNode) node;
        int total = 0;
        for (Node child : branch.children()) {
            if (child != null) {
                total += count(child);
            }
        }
        return total;
    }

    private sealed interface Node {
        Node put(long key, Position value, int level);
    }

    private record Entry(long key, Position value) {}

    private record LeafNode(Entry[] entries) implements Node {
        public LeafNode(Entry[] entries) {
            this.entries = entries;
        }

        public Entry[] entries() {
            return java.util.Arrays.copyOf(entries, entries.length);
        }

        @Override
        public Node put(long key, Position value, int level) {
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].key() == key) {
                    Entry[] newEntries = java.util.Arrays.copyOf(entries, entries.length);
                    newEntries[i] = new Entry(key, value);
                    return new LeafNode(newEntries);
                }
            }
            Entry[] newEntries = java.util.Arrays.copyOf(entries, entries.length + 1);
            newEntries[entries.length] = new Entry(key, value);
            return new LeafNode(newEntries);
        }
    }

    private record BranchNode(Node[] children) implements Node {
        public BranchNode(Node[] children) {
            this.children = children;
        }

        public Node[] children() {
            return java.util.Arrays.copyOf(children, children.length);
        }

        @Override
        public Node put(long key, Position value, int level) {
            if (level >= MAX_DEPTH) {
                return putInLeaf(key, value);
            }
            int hash = Long.hashCode(key);
            int index = (hash >>> (level * BITS_PER_LEVEL)) & (BRANCHING_FACTOR - 1);
            Node[] newChildren = children();
            Node child = newChildren[index];
            if (child == null) {
                newChildren[index] = new LeafNode(new Entry[]{new Entry(key, value)});
            } else {
                newChildren[index] = child.put(key, value, level + 1);
            }
            return new BranchNode(newChildren);
        }

        private Node putInLeaf(long key, Position value) {
            for (int i = 0; i < children.length; i++) {
                if (children[i] instanceof LeafNode leaf) {
                    for (Entry entry : leaf.entries()) {
                        if (entry.key() == key) {
                            Entry[] newEntries = java.util.Arrays.copyOf(leaf.entries(), leaf.entries().length);
                            newEntries[i] = new Entry(key, value);
                            Node[] newChildren = children();
                            newChildren[i] = new LeafNode(newEntries);
                            return new BranchNode(newChildren);
                        }
                    }
                }
            }
            for (int i = 0; i < children.length; i++) {
                if (children[i] == null) {
                    Node[] newChildren = children();
                    newChildren[i] = new LeafNode(new Entry[]{new Entry(key, value)});
                    return new BranchNode(newChildren);
                }
            }
            throw new IllegalStateException("HAMT collision at max depth");
        }
    }
}
