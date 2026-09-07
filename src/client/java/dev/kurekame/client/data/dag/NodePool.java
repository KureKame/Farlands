package dev.kurekame.client.data.dag;

import dev.kurekame.client.data.BlockLibrary;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.*;

public class NodePool {
    private static class Node {
        DagNode node;
        int references;

        private Node(DagNode node) {
            this.node = node;
            this.references = 0;
        }
    }

    private static class BranchKey {
        final int childMask;
        final int[] children;

        BranchKey(int childMask, int[] children) {
            this.childMask = childMask;
            this.children = children;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BranchKey other)) return false;
            return childMask == other.childMask && Arrays.equals(children, other.children);
        }

        @Override
        public int hashCode() {
            return 31 * childMask + Arrays.hashCode(children);
        }
    }

    private final int empty;

    public final ArrayList<Node> nodes;
    private final ArrayDeque<Integer> freedIndices;

    private final Map<Integer, Integer> leaves;
    private final Map<BranchKey, Integer> branches;

    public NodePool() {
        Optional<Holder.Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

        this.nodes = new ArrayList<>();
        this.freedIndices = new ArrayDeque<>();
        this.leaves = new HashMap<>();
        this.branches = new HashMap<>();

        if (block.isPresent()) {
            this.empty = BlockLibrary.getId(block.get().value());
        } else {
            this.empty = 0;
        }
    }

    public int getOrCreateLeafIndex(int block) {
        if (leaves.containsKey(block)) {
            return leaves.get(block);
        }

        int index = insert(DagNode.leaf(block));
        leaves.put(block, index);
        return index;
    }

    public int getOrCreateBranchIndex(int childMask, int[] children) {
        BranchKey key = new BranchKey(childMask, children);

        if (branches.containsKey(key)) {
            return branches.get(key);
        }

        int index = insert(DagNode.branch(this.empty, childMask, children));
        branches.put(key, index);
        return index;
    }

    private int insert(DagNode node) {
        if (!freedIndices.isEmpty()) {
            int index = freedIndices.pop();
            nodes.set(index, new Node(node));
            return index;
        }

        nodes.add(new Node(node));
        return nodes.size() - 1;
    }

    public void addReference(int index) {
        if (index >= 0) {
            nodes.get(index).references++;
        }
    }

    public void release(int index) {
        if (index < 0 || !nodes.contains(index)) {
            return;
        }

        Node node = nodes.get(index);

        node.references--;

        if (node.references == 0) {
            DagNode dag = node.node;

            if (!dag.leaf) {
                for (int child : dag.children) {
                    release(child);
                }
            }

            if (dag.leaf) {
                leaves.remove(dag.block);
            } else {
                branches.remove(new BranchKey(dag.childMask, dag.children));
            }

            nodes.set(index, null);
            freedIndices.add(index);
        }
    }

    public DagNode get(int index) {
        return nodes.get(index).node;
    }
}
