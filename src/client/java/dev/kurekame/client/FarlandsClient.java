package dev.kurekame.client;

import dev.kurekame.client.data.BlockGrid;
import dev.kurekame.client.data.BlockLibrary;
import dev.kurekame.client.data.ChunkDigest;
import dev.kurekame.client.data.dag.DagNode;
import dev.kurekame.client.data.dag.GraphBuilder;
import dev.kurekame.client.data.dag.NodePool;
import dev.kurekame.client.data.svo.SvoNode;
import dev.kurekame.client.data.svo.TreeBuilder;
import dev.kurekame.client.lod.ChunkKey;
import dev.kurekame.client.lod.ChunkRegistry;
import dev.kurekame.client.lod.LodSelector;
import dev.kurekame.client.lod.VisibleChunk;
import dev.kurekame.client.render.DagGraphFlattener;
import dev.kurekame.client.render.VulkanHelpers;
import dev.kurekame.client.render.VulkanState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class FarlandsClient implements ClientModInitializer {
	private static FarlandsClient instance;
	public static final String MOD_ID = "Farlands";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private TreeBuilder treeBuilder;
	private GraphBuilder graphBuilder;
	private ChunkRegistry chunkRegistry;
	private NodePool nodePool;
	private LodSelector lodSelector;
	private ChunkDigest chunkDigest;

	public List<VisibleChunk> chunks;
	public DagGraphFlattener.FlattenedBuffers flattened;

	private BlockLibrary blockLibrary;

	@Override
	public void onInitializeClient() {
		instance = this;

		assert (Minecraft.getInstance().options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN);

		treeBuilder = new TreeBuilder();
		graphBuilder = new GraphBuilder();
		chunks = new ArrayList<>();
		nodePool = new NodePool();
		lodSelector = new LodSelector();
		chunkDigest = new ChunkDigest();

		int empty;

		Optional<Holder.Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

		if (block.isPresent()) {
			empty = BlockLibrary.getId(block.get().value());
		} else {
			empty = 0;
		}

		InvalidateRenderStateCallback.EVENT.register(() -> {
			this.blockLibrary = BlockLibrary.buildLibrary();
			if (VulkanState.getDevice() != null) {
				VulkanHelpers.updateBlockLibrary(this.blockLibrary.colours, this.blockLibrary.count);
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			IntegratedServer server = client.getSingleplayerServer();

			if (chunkRegistry != null) {
				try {
					chunkRegistry.regions.close();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			chunkRegistry = new ChunkRegistry(server.getWorldPath(LevelResource.ROOT).resolve("farlands"));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (chunkRegistry != null) {
                try {
                    chunkRegistry.regions.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
		});

		ClientChunkEvents.CHUNK_UNLOAD.register((ClientLevel world, LevelChunk chunk) -> {
			chunkDigest.addToQueue(chunk, world);
		});

		ClientChunkEvents.CHUNK_LOAD.register((ClientLevel world, LevelChunk chunk) -> {
			chunkDigest.addToQueue(chunk, world);
		});
	}

	public BlockLibrary getBlockLibrary() {
		return this.blockLibrary;
	}

	public ChunkRegistry getChunkRegistry() {
		return this.chunkRegistry;
	}

	public NodePool getNodePool() {
		return this.nodePool;
	}

	public GraphBuilder getGraphBuilder() {
		return this.graphBuilder;
	}

	public LodSelector getLodSelector() {
		return this.lodSelector;
	}

	public TreeBuilder getTreeBuilder() {
		return treeBuilder;
	}

	public static FarlandsClient getInstance() {
		return instance;
	}
}