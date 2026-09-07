package dev.kurekame.client.lod;

import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.data.BlockGrid;
import dev.kurekame.client.data.BlockLibrary;
import dev.kurekame.client.render.DagGraphFlattener;
import dev.kurekame.client.render.VulkanState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class LodSelector {
    private static final int[] lodThresholds = {16 * 16, 16 * 32, 16 * 64};
    private final int empty;

    private final Thread thread;
    private boolean update = false;
    private volatile boolean running = true;
    private FarlandsClient modInstance;

    public LodSelector() {
        Optional<Holder.Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

        if (block.isPresent()) {
            this.empty = BlockLibrary.getId(block.get().value());
        } else {
            this.empty = 0;
        }

        this.thread = new Thread(this::lodThread, "Farlands LOD Selector Thread");
        this.thread.setDaemon(true);
        this.thread.start();
        this.modInstance = FarlandsClient.getInstance();
    }

    private void lodThread() {
        while (running) {
            synchronized (this) {
                while (!update && running) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }

                update = false;
            }

            try {
                selectLods();
            } catch (Exception e) {
                FarlandsClient.LOGGER.error("LOD selection pass failed", e);
            }
        }
    }

    public void update() {
        synchronized (this) {
            update = true;
            this.notify();
        }
    }

    private void selectLods() {
        if (Minecraft.getInstance().player != null) {
            int playerX = Minecraft.getInstance().player.chunkPosition().x();
            int playerZ = Minecraft.getInstance().player.chunkPosition().z();

            int vanillaRenderDistanceChunks = Minecraft.getInstance().options.renderDistance().get();
            double vanillaEdgeBlocks = vanillaRenderDistanceChunks * 16.0;

            List<VisibleChunk> chunksToRender = new ArrayList<>();

            for (int lodLevel = 0; lodLevel < lodThresholds.length; lodLevel++) {
                double innerBound = (lodLevel == 0) ? vanillaEdgeBlocks : lodThresholds[lodLevel - 1] + vanillaEdgeBlocks;
                double upperBound = lodThresholds[lodLevel] + vanillaEdgeBlocks;

                int regionChunkSpan = 2 * (1 << lodLevel);
                float regionSizeBlocks = regionChunkSpan * 16.0f;

                int playerRegionX = Math.floorDiv(playerX, regionChunkSpan);
                int playerRegionZ = Math.floorDiv(playerZ, regionChunkSpan);

                int regionRadius = (int) Math.ceil(upperBound / regionSizeBlocks) + 1;

                int minSectionY = Math.floorDiv(-64, 16);
                int maxSectionY = Math.floorDiv(320, 16);
                int minY = Math.floorDiv(minSectionY, regionChunkSpan);
                int maxY = Math.floorDiv(maxSectionY, regionChunkSpan);

                double playerBlockX = playerX * 16.0;
                double playerBlockZ = playerZ * 16.0;

                for (int rz = -regionRadius; rz <= regionRadius; rz++) {
                    for (int rx = -regionRadius; rx <= regionRadius; rx++) {

                        int regionX = playerRegionX + rx;
                        int regionZ = playerRegionZ + rz;

                        double regionMinX = regionX * regionSizeBlocks;
                        double regionMaxX = regionMinX + regionSizeBlocks;
                        double regionMinZ = regionZ * regionSizeBlocks;
                        double regionMaxZ = regionMinZ + regionSizeBlocks;

                        double nearestDist = closestDistanceToRegion(playerBlockX, playerBlockZ, regionMinX, regionMaxX, regionMinZ, regionMaxZ);
                        double farthestDist = farthestDistanceToRegion(playerBlockX, playerBlockZ, regionMinX, regionMaxX, regionMinZ, regionMaxZ);

                        boolean tooClose = farthestDist < innerBound;
                        boolean tooFar = nearestDist >= upperBound;

                        if (tooClose || tooFar) {
                            continue;
                        }

                        for (int regionY = minY; regionY <= maxY; regionY++) {
                            ChunkKey key = new ChunkKey(regionX, regionY, regionZ, lodLevel);
                            Integer root = modInstance.getChunkRegistry().get(key);

                            if (root != null && root != -1) {
                                float originX = regionX * regionSizeBlocks;
                                float originY = regionY * regionSizeBlocks;
                                float originZ = regionZ * regionSizeBlocks;
                                chunksToRender.add(new VisibleChunk(root, originX, originY, originZ, regionSizeBlocks));
                            } else if (lodLevel > 0) {
                                tryGenerateLod(key);
                            }
                        }
                    }
                }
            }

            Vector3f cameraPos = Minecraft.getInstance().gameRenderer.mainCamera().position().toVector3f();

            chunksToRender.sort(Comparator.comparingDouble(c -> {
                float cx = c.originX() + c.size() * 0.5f - cameraPos.x;
                float cy = c.originY() + c.size() * 0.5f - cameraPos.y;
                float cz = c.originZ() + c.size() * 0.5f - cameraPos.z;
                return (double) (cx * cx + cy * cy + cz * cz);
            }));

            synchronized (VulkanState.bufferLock) {
                modInstance.chunks = chunksToRender;
                modInstance.flattened = DagGraphFlattener.flatten(modInstance.chunks, modInstance.getNodePool());
                VulkanState.dagDirty = true;
            }
        }
    }

    private void tryGenerateLod(ChunkKey key) {
        int lodLevel = key.lodLevel();
        if (lodLevel == 0) {
            return;
        }

        int groupSize = 2 * (1 << lodLevel);

        BlockGrid combined = modInstance.getChunkRegistry().combine(groupSize, key.x(), key.y(), key.z(), empty);
        if (combined == null) {
            return;
        }

        modInstance.getTreeBuilder().submit(key, combined, 16 * groupSize);
    }


    private static double closestDistanceToRegion(double px, double pz, double minX, double maxX, double minZ, double maxZ) {
        double closestX = Math.max(minX, Math.min(px, maxX));
        double closestZ = Math.max(minZ, Math.min(pz, maxZ));
        return Math.hypot(closestX - px, closestZ - pz);
    }

    private static double farthestDistanceToRegion(double px, double pz, double minX, double maxX, double minZ, double maxZ) {
        double farthestX = (Math.abs(minX - px) > Math.abs(maxX - px)) ? minX : maxX;
        double farthestZ = (Math.abs(minZ - pz) > Math.abs(maxZ - pz)) ? minZ : maxZ;
        return Math.hypot(farthestX - px, farthestZ - pz);
    }

}
