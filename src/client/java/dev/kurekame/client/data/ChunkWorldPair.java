package dev.kurekame.client.data;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public record ChunkWorldPair(LevelChunk chunk, Level world) {}
