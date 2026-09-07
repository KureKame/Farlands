package dev.kurekame.client.data.svo;

public class SvoNode {
    public final boolean leaf;
    public final int block;
    public final SvoNode[] children;

    private SvoNode(boolean leaf, int block, SvoNode[] children) {
        this.leaf = leaf;
        this.block = block;
        this.children = children;
    }

    public static SvoNode leaf(int block) {
        return new SvoNode(true, block, null);
    }

    public static SvoNode branch(SvoNode[] children) {
        assert(children.length == 8);

        return new SvoNode(false, 0, children);
    }

    public static int index(int x, int y, int z) {
        return x | (y << 1) | (z << 2);
    }
}
