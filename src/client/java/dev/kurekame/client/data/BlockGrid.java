package dev.kurekame.client.data;

public class BlockGrid {
    private final int[] blocks;

    public BlockGrid(int[] blocks) {
        this.blocks = blocks;
    }

    public int at(int x, int y, int z) {
        return blocks[x + 16 * (y + 16 * z)];
    }

    public int[] getBlocks() {
        return this.blocks;
    }
}
