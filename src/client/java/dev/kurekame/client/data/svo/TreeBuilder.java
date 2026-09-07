package dev.kurekame.client.data.svo;

import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.data.BlockGrid;
import dev.kurekame.client.data.BlockLibrary;
import dev.kurekame.client.lod.ChunkKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Holder.Reference;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TreeBuilder {
    private final int empty;
    private final ExecutorService threadPool;
    private final FarlandsClient modInstance;

    public record SvoResult(ChunkKey key, SvoNode node) {}

    public TreeBuilder() {
        Optional<Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

        if (block.isPresent()) {
            this.empty = BlockLibrary.getId(block.get().value());
        } else {
            this.empty = 0;
        }

        this.threadPool = Executors.newFixedThreadPool(16);
        this.modInstance = FarlandsClient.getInstance();
    }

    public void submit(ChunkKey key, BlockGrid blocks, int size) {
        this.threadPool.submit(() -> {
            int currentSize = size;
            int[] currentGrid = blocks.getBlocks();

            while (currentSize > 16) {
                int coarseSize = currentSize / 2;
                int[] coarseGrid = new int[coarseSize * coarseSize * coarseSize];

                for (int cz = 0; cz < coarseSize; cz++) {
                    for (int cy = 0; cy < coarseSize; cy++) {
                        for (int cx = 0; cx < coarseSize; cx++) {
                            int fx = cx * 2;
                            int fy = cy * 2;
                            int fz = cz * 2;

                            java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
                            int emptyCount = 0;

                            for (int dz = 0; dz < 2; dz++) {
                                for (int dy = 0; dy < 2; dy++) {
                                    for (int dx = 0; dx < 2; dx++) {
                                        int id = currentGrid[(fx + dx) + currentSize * ((fy + dy) + currentSize * (fz + dz))];
                                        if (id == this.empty) {
                                            emptyCount++;
                                        } else {
                                            counts.merge(id, 1, Integer::sum);
                                        }
                                    }
                                }
                            }

                            int value;
                            if (emptyCount >= 5) {
                                value = this.empty;
                            } else {
                                int bestId = Integer.MAX_VALUE;
                                int bestCount = -1;
                                for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                                    if (entry.getValue() > bestCount
                                            || (entry.getValue() == bestCount && entry.getKey() < bestId)) { // deterministic tie-break
                                        bestCount = entry.getValue();
                                        bestId = entry.getKey();
                                    }
                                }
                                value = bestId;
                            }

                            coarseGrid[cx + coarseSize * (cy + coarseSize * cz)] = value;
                        }
                    }
                }

                currentGrid = coarseGrid;
                currentSize = coarseSize;
            }

            BlockGrid finalGrid = new BlockGrid(currentGrid);
            modInstance.getGraphBuilder().addToQueue(new SvoResult(key, this.build(finalGrid)));
        });

    }

    private SvoNode build(BlockGrid blocks) {
        SvoNode ret = this.buildRecursive(blocks, 0, 0, 0, 16);

        return ret;
    }

    private SvoNode buildRecursive(BlockGrid blocks, int x, int y, int z, int nodeSize) {
        if (nodeSize == 1) {
            return SvoNode.leaf(blocks.at(x, y, z));
        }

        int half = nodeSize / 2;
        SvoNode[] children = new SvoNode[8];

        boolean uniform = true;
        int uniformBlock = this.empty;

        for (int treeZ = 0; treeZ < 2; treeZ++) {
            for (int treeY = 0; treeY < 2; treeY++) {
                for (int treeX = 0; treeX < 2; treeX++) {
                    int index = SvoNode.index(treeX, treeY, treeZ);
                    int childX = x + treeX * half;
                    int childY = y + treeY * half;
                    int childZ = z + treeZ * half;

                    SvoNode child = this.buildRecursive(blocks, childX, childY, childZ, half);
                    children[index] = child;

                    if (uniform) {
                        if (!child.leaf) {
                            uniform = false;
                        } else if (index == 0) {
                            uniformBlock = child.block;
                        } else if (child.block != uniformBlock) {
                            uniform = false;
                        }
                    }
                }
            }
        }

        if (uniform) {
            return SvoNode.leaf(uniformBlock);
        } else {
            return SvoNode.branch(children);
        }
    }
}
