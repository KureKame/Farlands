package dev.kurekame.client.data;

import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.lod.ChunkKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ChunkDigest {
    private int empty;

    private final Thread thread;
    private final BlockingQueue<ChunkWorldPair> chunks;
    private volatile boolean running = true;
    private final FarlandsClient modInstance;

    public ChunkDigest() {
        Optional<Holder.Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

        if (block.isPresent()) {
            this.empty = BlockLibrary.getId(block.get().value());
        } else {
            this.empty = 0;
        }

        this.chunks = new LinkedBlockingQueue<>();
        this.thread = new Thread(this::chunkThread, "Farlands Chunk Digest");
        this.thread.setDaemon(true);
        this.thread.start();
        this.modInstance = FarlandsClient.getInstance();
    }

    private void chunkThread() {
        while (running) {
            try {
                ChunkWorldPair pair = this.chunks.poll(200, TimeUnit.MILLISECONDS);

                if (pair != null) {
                    LevelChunk chunk = pair.chunk();
                    Level world = pair.world();

                    LevelChunkSection[] sections = chunk.getSections();

                    BlockPos chunkRepresentativePos = new BlockPos(4096 * 16 + 8, 64, 4096 * 16 + 8);
                    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

                    java.util.Set<Integer> lod0RegionYs = new java.util.HashSet<>();
                    java.util.Set<Integer> lod1RegionYs = new java.util.HashSet<>();
                    java.util.Set<Integer> lod2RegionYs = new java.util.HashSet<>();

                    Map<Block, Integer> chunkTintCache = new HashMap<>();
                    Map<Long, Integer> finalIdCache = new HashMap<>();


                    for (int i = 0; i < sections.length; i++) {
                        if (!sections[i].hasOnlyAir()) {
                            BlockGrid blocks = ChunkDigest.digestChunkSection(sections[i], world, chunkRepresentativePos, blockColors, chunkTintCache, finalIdCache);
                            modInstance.getChunkRegistry().addChunkBlockGrid(chunk.getPos().x(), -64 + (i * 16), chunk.getPos().z(), blocks);
                        }

                        int sectionIndex = -4 + i;
                        lod0RegionYs.add(Math.floorDiv(sectionIndex, 2));
                        lod1RegionYs.add(Math.floorDiv(sectionIndex, 4));
                        lod2RegionYs.add(Math.floorDiv(sectionIndex, 8));
                    }

                    int chunkX = chunk.getPos().x();
                    int chunkZ = chunk.getPos().z();

                    int lod0X = Math.floorDiv(chunkX, 2);
                    int lod0Z = Math.floorDiv(chunkZ, 2);

                    for (int lod0Y : lod0RegionYs) {
                        ChunkKey lod0Key = new ChunkKey(lod0X, lod0Y, lod0Z, 0);

                        BlockGrid combined = modInstance.getChunkRegistry().combine(2, lod0X, lod0Y, lod0Z, empty);
                        if (combined == null) {
                            continue;
                        }
                        modInstance.getTreeBuilder().submit(lod0Key, combined, 32);

                    }

                    int lod1X = Math.floorDiv(chunkX, 4);
                    int lod1Z = Math.floorDiv(chunkZ, 4);
                    for (int lod1Y : lod1RegionYs) {
                        ChunkKey lod1Key = new ChunkKey(lod1X, lod1Y, lod1Z, 1);
                        Integer existingRoot = modInstance.getChunkRegistry().get(lod1Key);
                        if (existingRoot != null && existingRoot != -1) {
                            modInstance.getChunkRegistry().removeChunk(lod1Key, modInstance.getNodePool());
                        }
                    }

                    int lod2X = Math.floorDiv(chunkX, 8);
                    int lod2Z = Math.floorDiv(chunkZ, 8);
                    for (int lod2Y : lod2RegionYs) {
                        ChunkKey lod2Key = new ChunkKey(lod2X, lod2Y, lod2Z, 2);
                        Integer existingRoot = modInstance.getChunkRegistry().get(lod2Key);
                        if (existingRoot != null && existingRoot != -1) {
                            modInstance.getChunkRegistry().removeChunk(lod2Key, modInstance.getNodePool());
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void addToQueue(LevelChunk chunk, Level world) {
        this.chunks.add(new ChunkWorldPair(chunk, world));
    }

    public static BlockGrid digestChunkSection(LevelChunkSection section, Level level, BlockPos chunkRepresentativePos, BlockColors blockColors, Map<Block, Integer> chunkTintCache, Map<Long, Integer> finalIdCache) {
        int[] blocks = new int[16 * 16 * 16];

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Block block = state.getBlock();
                    int baseId = BlockLibrary.getId(block);

                    int tint = chunkTintCache.computeIfAbsent(block, b -> {
                        if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
                            return BiomeColors.getAverageWaterColor((BlockAndTintGetter) level, chunkRepresentativePos);
                        }


                        BlockTintSource tintSource = blockColors.getTintSource(state, 0);
                        if (tintSource == null) {
                            return -1;
                        }
                        return tintSource.colorInWorld(state, (BlockAndTintGetter) level, chunkRepresentativePos);
                    });

                    int finalId;
                    if (tint == -1) {
                        finalId = baseId;
                    } else {
                        long key = ((long) baseId << 32) | (tint & 0xFFFFFFFFL);
                        finalId = finalIdCache.computeIfAbsent(key, k -> {
                            float baseR = FarlandsClient.getInstance().getBlockLibrary().getColour(baseId, 0);
                            float baseG = FarlandsClient.getInstance().getBlockLibrary().getColour(baseId, 1);
                            float baseB = FarlandsClient.getInstance().getBlockLibrary().getColour(baseId, 2);

                            float tintR = ((tint >> 16) & 0xFF) / 255.0f;
                            float tintG = ((tint >> 8) & 0xFF) / 255.0f;
                            float tintB = (tint & 0xFF) / 255.0f;

                            return FarlandsClient.getInstance().getBlockLibrary().getOrCreateTintedId(baseId, baseR * tintR, baseG * tintG, baseB * tintB);
                        });
                    }

                    blocks[x + 16 * (y + 16 * z)] = finalId;
                }
            }
        }


        return new BlockGrid(blocks);
    }
}
