package dev.kurekame.client.lod;

import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.data.BlockGrid;
import dev.kurekame.client.data.dag.NodePool;
import dev.kurekame.client.data.serialisation.RegionSerialiser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ChunkRegistry {
    public final Map<ChunkKey, Integer> chunkLods = new HashMap<>();
    public RegionSerialiser regions;

    public ChunkRegistry(Path worldFolder) {
        try {
            this.regions = new RegionSerialiser(worldFolder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addChunk(ChunkKey key, int index, NodePool nodePool) {
        Integer previousIndex = chunkLods.get(key);
        if (previousIndex != null && !previousIndex.equals(index)) {
            nodePool.release(previousIndex);
        }
        chunkLods.put(key, index);
    }

    public void addChunkBlockGrid(int x, int y, int z, BlockGrid blocks) {
        int sectionY = y >> 4;
        try {
            regions.writeGrid(x, sectionY, z, blocks);
        } catch (IOException e) {
            FarlandsClient.LOGGER.error("Failed to write chunk grid to region file at (" + x + "," + y + "," + z + ")", e);
        }
    }

    public BlockGrid getBlocks(int x, int y, int z) {
        int sectionY = y >> 4;
        try {
            return regions.readGrid(x, sectionY, z);
        } catch (IOException e) {
            FarlandsClient.LOGGER.error("Failed to read chunk grid from region file at (" + x + "," + y + "," + z + ")", e);
            return null;
        }
    }


    public void removeChunk(ChunkKey key, NodePool nodePool) {
        nodePool.release(chunkLods.get(key));
        chunkLods.remove(key);
    }

    public Integer get(ChunkKey key) {
        return chunkLods.get(key);
    }

    public int size() {
        return chunkLods.size();
    }

    public BlockGrid combine(int groupSize, int regionX, int regionY, int regionZ, int emptyBlockId) {
        int cellCount = groupSize * groupSize * groupSize;
        int[][] rawArrays = new int[cellCount][];
        boolean anyPresent = false;

        for (int dz = 0; dz < groupSize; dz++) {
            for (int dy = 0; dy < groupSize; dy++) {
                for (int dx = 0; dx < groupSize; dx++) {
                    int chunkX = regionX * groupSize + dx;
                    int chunkZ = regionZ * groupSize + dz;
                    int sectionIndex = regionY * groupSize + dy;
                    int worldY = sectionIndex * 16;

                    int index = dx + groupSize * (dy + groupSize * dz);

                    BlockGrid grid = getBlocks(chunkX, worldY, chunkZ);
                    if (grid != null) {
                        rawArrays[index] = grid.getBlocks();
                        anyPresent = true;
                    } else {
                        int[] emptyArray = new int[16 * 16 * 16];
                        java.util.Arrays.fill(emptyArray, emptyBlockId);
                        rawArrays[index] = emptyArray;
                    }
                }
            }
        }

        if (!anyPresent) {
            return null;
        }

        int combinedSize = 16 * groupSize;
        int[] combined = new int[combinedSize * combinedSize * combinedSize];

        for (int gz = 0; gz < groupSize; gz++) {
            for (int gy = 0; gy < groupSize; gy++) {
                for (int gx = 0; gx < groupSize; gx++) {
                    int groupIndex = gx + groupSize * (gy + groupSize * gz);
                    int[] child = rawArrays[groupIndex];
                    if (child == null) {
                        continue;
                    }

                    int offsetX = gx * 16;
                    int offsetY = gy * 16;
                    int offsetZ = gz * 16;

                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                int srcIndex = x + 16 * (y + 16 * z);
                                int dstX = offsetX + x;
                                int dstY = offsetY + y;
                                int dstZ = offsetZ + z;
                                int dstIndex = dstX + combinedSize * (dstY + combinedSize * dstZ);
                                combined[dstIndex] = child[srcIndex];
                            }
                        }
                    }
                }
            }
        }

        return new BlockGrid(combined);
    }
}
