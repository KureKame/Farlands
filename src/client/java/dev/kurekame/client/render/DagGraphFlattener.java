package dev.kurekame.client.render;

import dev.kurekame.client.data.dag.DagNode;
import dev.kurekame.client.data.dag.NodePool;
import dev.kurekame.client.lod.VisibleChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class DagGraphFlattener {
    public record FlattenedBuffers(int[] headers, int[] seconds, int[] childPool, int[] rootGpuIndices) {}

    private static final int NULL_INDEX = -1;

    private DagGraphFlattener() {}

    public static FlattenedBuffers flatten(List<VisibleChunk> visibleChunks, NodePool pool) {
        Map<Integer, Integer> poolIndexToGpuIndex = new HashMap<>();
        List<Integer> headers = new ArrayList<>();
        List<Integer> seconds = new ArrayList<>();
        List<Integer> childPool = new ArrayList<>();

        int[] rootGpuIndices = new int[visibleChunks.size()];

        for (int i = 0; i < visibleChunks.size(); i++) {
            int rootPoolIndex = visibleChunks.get(i).rootPoolIndex();
            if (rootPoolIndex == NULL_INDEX) {
                rootGpuIndices[i] = 0xFFFFFFFF;
                continue;
            }
            rootGpuIndices[i] = visit(rootPoolIndex, pool, poolIndexToGpuIndex, headers, seconds, childPool);
        }

        return new FlattenedBuffers(toIntArray(headers), toIntArray(seconds), toIntArray(childPool), rootGpuIndices);
    }

    private static int visit(int poolIndex, NodePool pool, Map<Integer, Integer> mapping, List<Integer> headers, List<Integer> seconds, List<Integer> childPool) {
        Integer existing = mapping.get(poolIndex);
        if (existing != null) {
            return existing;
        }

        DagNode node = pool.get(poolIndex);

        if (node.leaf) {
            int gpuIndex = headers.size();
            headers.add(0);
            seconds.add(node.block);
            mapping.put(poolIndex, gpuIndex);
            return gpuIndex;
        }

        int[] childGpuIndices = new int[node.children.length];
        for (int i = 0; i < node.children.length; i++) {
            childGpuIndices[i] = visit(node.children[i], pool, mapping, headers, seconds, childPool);
        }

        int firstChildOffset = childPool.size();
        for (int childGpuIndex : childGpuIndices) {
            childPool.add(childGpuIndex);
        }

        int gpuIndex = headers.size();
        headers.add(node.childMask);
        seconds.add(firstChildOffset);
        mapping.put(poolIndex, gpuIndex);
        return gpuIndex;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];

        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }

        return out;
    }
}