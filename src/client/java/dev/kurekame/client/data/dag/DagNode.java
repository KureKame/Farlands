package dev.kurekame.client.data.dag;

public class DagNode {
    public final boolean leaf;
    public final int block;
    public final int childMask;
    public final int[] children;
    public int refCount;

    private DagNode(boolean leaf, int block, int childMask, int[] children) {
        this.leaf = leaf;
        this.block = block;
        this.childMask = childMask;
        this.children = children;
        this.refCount = 0;
    }

    public static DagNode leaf(int block) {
        return new DagNode(true, block, 0, null);
    }

    public static DagNode branch(int empty, int childMask, int[] children) {
        return new DagNode(false, empty, childMask, children);
    }
}
