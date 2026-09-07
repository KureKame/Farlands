package dev.kurekame.client.data.dag;

import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.data.BlockLibrary;
import dev.kurekame.client.data.svo.SvoNode;
import dev.kurekame.client.data.svo.TreeBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class GraphBuilder {
    private final int empty;
    private final Thread thread;
    private final BlockingQueue<TreeBuilder.SvoResult> queue;
    private volatile boolean running = true;
    private final FarlandsClient modInstance;

    public GraphBuilder() {
        Optional<Holder.Reference<Block>> block = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace("air"));

        if (block.isPresent()) {
            this.empty = BlockLibrary.getId(block.get().value());
        } else {
            this.empty = 0;
        }

        this.queue = new LinkedBlockingQueue<>();
        this.thread = new Thread(this::mergeThread, "Farlands DAG Merge Thread");
        this.thread.setDaemon(true);
        this.thread.start();
        this.modInstance = FarlandsClient.getInstance();
    }

    private void mergeThread() {
        while (running) {
            try {
                TreeBuilder.SvoResult svo = this.queue.poll(200, TimeUnit.MILLISECONDS);

                if (svo != null) {
                    int index = this.merge(svo.node());
                    modInstance.getChunkRegistry().addChunk(svo.key(), index, modInstance.getNodePool());
                    modInstance.getNodePool().addReference(index);
                    modInstance.getLodSelector().update();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void addToQueue(TreeBuilder.SvoResult svo) {
        this.queue.add(svo);
    }

    private int merge(SvoNode node) {
        int index = this.mergeRecursive(node);

        return index;
    }

    private int mergeRecursive(SvoNode node) {
        NodePool nodePool = FarlandsClient.getInstance().getNodePool();

        if (node.leaf) {
            if (node.block == this.empty) {
                return -1;
            }

            return nodePool.getOrCreateLeafIndex(node.block);
        }

        int childMask = 0;
        int[] children = new int[8];
        int numChildren = 0;

        for (int i = 0; i < 8; i++) {
            int childIndex = this.mergeRecursive(node.children[i]);

            if (childIndex >= 0) {
                childMask |= (1 << i);
                children[numChildren] = childIndex;
                numChildren++;
            }
        }

        children = Arrays.copyOf(children, numChildren);

        return nodePool.getOrCreateBranchIndex(childMask, children);
    }
}
