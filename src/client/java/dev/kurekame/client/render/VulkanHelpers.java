package dev.kurekame.client.render;

import dev.kurekame.client.lod.VisibleChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

public class VulkanHelpers {

    private static int findMemoryType(int typeFilter, int requiredProperties) {
        for (int i = 0; i < VulkanState.getMemoryProperties().memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 &&
                    (VulkanState.getMemoryProperties().memoryTypes(i).propertyFlags() & requiredProperties) == requiredProperties) {
                return i;
            }
        }
        throw new RuntimeException("Failed to find suitable Vulkan memory type!");
    }

    private static long[] createVulkanBuffer(long size, int usage, int properties) {
        long allocationSize = Math.max(size, 16L);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
            bufferInfo.size(allocationSize);
            bufferInfo.usage(usage);
            bufferInfo.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (VK10.vkCreateBuffer(VulkanState.getDevice(), bufferInfo, null, pBuffer) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkBuffer!");
            }
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(VulkanState.getDevice(), buffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocInfo.allocationSize(memReqs.size());
            allocInfo.memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), properties));

            LongBuffer pMemory = stack.mallocLong(1);
            if (VK10.vkAllocateMemory(VulkanState.getDevice(), allocInfo, null, pMemory) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate VkDeviceMemory!");
            }
            long memory = pMemory.get(0);

            VK10.vkBindBufferMemory(VulkanState.getDevice(), buffer, memory, 0);

            return new long[]{buffer, memory};
        }
    }

    public static void updateBlockLibrary(float[] colours, int count) {
        int size = Math.max(count * 16, 16);

        long previousBuffer = VulkanState.getBlockLibraryBuffer();
        long previousMemory = VulkanState.getBlockLibraryMemory();
        if (previousBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousBuffer, null);
        }
        if (previousMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousMemory, null);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] staging = createVulkanBuffer(
                    size,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long bufferHandle = staging[0];
            long memoryHandle = staging[1];

            PointerBuffer pData = stack.mallocPointer(1);
            int mapResult = VK10.vkMapMemory(VulkanState.getDevice(), memoryHandle, 0, size, 0, pData);
            if (mapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + mapResult);
            }

            ByteBuffer byteBuf = pData.getByteBuffer(0, size);
            FloatBuffer floatBuf = byteBuf.asFloatBuffer();

            for (int i = 0; i < count; i++) {
                floatBuf.put(colours, i * 4, 4);
            }

            VK10.vkUnmapMemory(VulkanState.getDevice(), memoryHandle);
            VulkanState.setBlockLibraryBuffer(bufferHandle);
            VulkanState.setBlockLibraryMemory(memoryHandle);
        }
    }

    public static void updateDagBuffer(DagGraphFlattener.FlattenedBuffers flattened, int numRoots, List<VisibleChunk> chunks) {
        int[] headers = flattened.headers();
        int[] seconds = flattened.seconds();
        int[] childPool = flattened.childPool();

        long previousHeaderBuffer = VulkanState.getDagHeaderBuffer();
        long previousHeaderMemory = VulkanState.getDagHeaderMemory();
        if (previousHeaderBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousHeaderBuffer, null);
        }
        if (previousHeaderMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousHeaderMemory, null);
        }

        long previousSecondBuffer = VulkanState.getDagSecondBuffer();
        long previousSecondMemory = VulkanState.getDagSecondMemory();
        if (previousSecondBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousSecondBuffer, null);
        }
        if (previousSecondMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousSecondMemory, null);
        }

        long previousChildPoolBuffer = VulkanState.getDagChildPoolBuffer();
        long previousChildPoolMemory = VulkanState.getDagChildPoolMemory();
        if (previousChildPoolBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousChildPoolBuffer, null);
        }
        if (previousChildPoolMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousChildPoolMemory, null);
        }

        long previousChunkListBuffer = VulkanState.getChunkListBuffer();
        long previousChunkListMemory = VulkanState.getChunkListMemory();
        if (previousChunkListBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousChunkListBuffer, null);
        }
        if (previousChunkListMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousChunkListMemory, null);
        }

        long previousVisibleChunksBuffer = VulkanState.getVisibleChunksBuffer();
        long previousVisibleChunksMemory = VulkanState.getVisibleChunksMemory();
        if (previousVisibleChunksBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousVisibleChunksBuffer, null);
        }
        if (previousVisibleChunksMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousVisibleChunksMemory, null);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int headerSize = Math.max(4 * headers.length, 16);

            long[] headerStaging = createVulkanBuffer(
                    headerSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long headerBufferHandle = headerStaging[0];
            long headerMemoryHandle = headerStaging[1];

            PointerBuffer pHeaderData = stack.mallocPointer(1);
            int headerMapResult = VK10.vkMapMemory(VulkanState.getDevice(), headerMemoryHandle, 0, headerSize, 0, pHeaderData);
            if (headerMapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + headerMapResult);
            }

            ByteBuffer headerByteBuf = pHeaderData.getByteBuffer(0, headerSize);
            IntBuffer headerIntBuf = headerByteBuf.asIntBuffer();
            headerIntBuf.put(headers);
            VK10.vkUnmapMemory(VulkanState.getDevice(), headerMemoryHandle);

            VulkanState.setDagHeaderBuffer(headerBufferHandle);
            VulkanState.setDagHeaderMemory(headerMemoryHandle);

            int secondSize = Math.max(4 * seconds.length, 16);

            long[] secondStaging = createVulkanBuffer(
                    secondSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long secondBufferHandle = secondStaging[0];
            long secondMemoryHandle = secondStaging[1];

            PointerBuffer pSecondData = stack.mallocPointer(1);
            int secondMapResult = VK10.vkMapMemory(VulkanState.getDevice(), secondMemoryHandle, 0, secondSize, 0, pSecondData);
            if (secondMapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + secondMapResult);
            }

            ByteBuffer secondByteBuf = pSecondData.getByteBuffer(0, secondSize);
            IntBuffer secondIntBuf = secondByteBuf.asIntBuffer();
            secondIntBuf.put(seconds);
            VK10.vkUnmapMemory(VulkanState.getDevice(), secondMemoryHandle);

            VulkanState.setDagSecondBuffer(secondBufferHandle);
            VulkanState.setDagSecondMemory(secondMemoryHandle);

            int childPoolSize = Math.max(4 * childPool.length, 16);

            long[] childPoolStaging = createVulkanBuffer(
                    childPoolSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long childPoolBufferHandle = childPoolStaging[0];
            long childPoolMemoryHandle = childPoolStaging[1];

            PointerBuffer pChildPoolData = stack.mallocPointer(1);
            int childPoolMapResult = VK10.vkMapMemory(VulkanState.getDevice(), childPoolMemoryHandle, 0, childPoolSize, 0, pChildPoolData);
            if (childPoolMapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + childPoolMapResult);
            }

            ByteBuffer childPoolByteBuf = pChildPoolData.getByteBuffer(0, childPoolSize);
            IntBuffer childPoolIntBuf = childPoolByteBuf.asIntBuffer();
            childPoolIntBuf.put(childPool);
            VK10.vkUnmapMemory(VulkanState.getDevice(), childPoolMemoryHandle);

            VulkanState.setDagChildPoolBuffer(childPoolBufferHandle);
            VulkanState.setDagChildPoolMemory(childPoolMemoryHandle);

            int chunkListSize = Math.max(20 * numRoots, 16);

            long[] chunkListStaging = createVulkanBuffer(
                    chunkListSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long chunkListBufferHandle = chunkListStaging[0];
            long chunkListMemoryHandle = chunkListStaging[1];

            PointerBuffer pChunkListData = stack.mallocPointer(1);
            int chunkListMapResult = VK10.vkMapMemory(VulkanState.getDevice(), chunkListMemoryHandle, 0, chunkListSize, 0, pChunkListData);
            if (chunkListMapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + chunkListMapResult);
            }

            ByteBuffer chunkListByteBuf = pChunkListData.getByteBuffer(0, chunkListSize);
            IntBuffer chunkListIntBuf = chunkListByteBuf.asIntBuffer();

            for (int i = 0; i < chunks.size(); i++) {
                if (i < flattened.rootGpuIndices().length) {
                    VisibleChunk chunk = chunks.get(i);

                    int base = i * 5;
                    chunkListIntBuf.put(base, flattened.rootGpuIndices()[i]);
                    chunkListIntBuf.put(base + 1, Float.floatToRawIntBits(chunk.originX()));
                    chunkListIntBuf.put(base + 2, Float.floatToRawIntBits(chunk.originY()));
                    chunkListIntBuf.put(base + 3, Float.floatToRawIntBits(chunk.originZ()));
                    chunkListIntBuf.put(base + 4, Float.floatToRawIntBits(chunk.size()));
                }
            }

            VK10.vkUnmapMemory(VulkanState.getDevice(), chunkListMemoryHandle);

            VulkanState.setChunkListBuffer(chunkListBufferHandle);
            VulkanState.setChunkListMemory(chunkListMemoryHandle);

            long[] visibleChunksStaging = createVulkanBuffer(
                    chunkListSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            long visibleChunksBufferHandle = visibleChunksStaging[0];
            long visibleChunksMemoryHandle = visibleChunksStaging[1];

            VulkanState.setVisibleChunksBuffer(visibleChunksBufferHandle);
            VulkanState.setVisibleChunksMemory(visibleChunksMemoryHandle);
        }
    }

    public static void createVisibleChunkCountBuffer() {
        long[] visibleChunkCountStaging = createVulkanBuffer(
                4,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        long visibleChunkCountBufferHandle = visibleChunkCountStaging[0];
        long visibleChunkCountMemoryHandle = visibleChunkCountStaging[1];

        VulkanState.setVisibleChunkCountBuffer(visibleChunkCountBufferHandle);
        VulkanState.setVisibleChunkCountMemory(visibleChunkCountMemoryHandle);
    }

    public static void updateTileSelectionBuffers(int width, int height) {
        long previousIndicesBuffer = VulkanState.getTileSelectionIndicesBuffer();
        long previousIndicesMemory = VulkanState.getTileSelectionIndicesMemory();
        if (previousIndicesBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousIndicesBuffer, null);
        }
        if (previousIndicesMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousIndicesMemory, null);
        }

        long previousCountBuffer = VulkanState.getTileSelectionCountBuffer();
        long previousCountMemory = VulkanState.getTileSelectionCountMemory();
        if (previousCountBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousCountBuffer, null);
        }
        if (previousCountMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousCountMemory, null);
        }

        long previousDepthBuffer = VulkanState.getTileSelectionDepthBuffer();
        long previousDepthMemory = VulkanState.getTileSelectionDepthMemory();
        if (previousDepthBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(VulkanState.getDevice(), previousDepthBuffer, null);
        }
        if (previousDepthMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(VulkanState.getDevice(), previousDepthMemory, null);
        }

        int tiles = Math.ceilDiv(width, 8) * Math.ceilDiv(height, 8);

        long[] indicesStaging = createVulkanBuffer(
                tiles * 512 * 4,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        long indicesBufferHandle = indicesStaging[0];
        long indicesMemoryHandle = indicesStaging[1];

        VulkanState.setTileSelectionIndicesBuffer(indicesBufferHandle);
        VulkanState.setTileSelectionIndicesMemory(indicesMemoryHandle);

        long[] countStaging = createVulkanBuffer(
                tiles * 4,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        long countBufferHandle = countStaging[0];
        long countMemoryHandle = countStaging[1];

        VulkanState.setTileSelectionCountBuffer(countBufferHandle);
        VulkanState.setTileSelectionCountMemory(countMemoryHandle);

        long[] depthStaging = createVulkanBuffer(
                tiles * 4,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        long depthBufferHandle = depthStaging[0];
        long depthMemoryHandle = depthStaging[1];

        VulkanState.setTileSelectionDepthBuffer(depthBufferHandle);
        VulkanState.setTileSelectionDepthMemory(depthMemoryHandle);
    }

    public static void createFrameUniform() {
        long[] uniformStaging = createVulkanBuffer(
                256,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        long uniformBufferHandle = uniformStaging[0];
        long uniformMemoryHandle = uniformStaging[1];

        VulkanState.setFrameUniformBuffer(uniformBufferHandle);
        VulkanState.setFrameUniformMemory(uniformMemoryHandle);
    }

    public static void updateFrameUniform(Matrix4f invViewProj, Matrix4f viewProj, Vector3f cameraPos, int chunkCount, int viewportWidth, int viewportHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pUniformData = stack.mallocPointer(1);
            int uniformMapResult = VK10.vkMapMemory(VulkanState.getDevice(), VulkanState.getFrameUniformMemory(), 0, 256, 0, pUniformData);
            if (uniformMapResult != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for block library upload, result: " + uniformMapResult);
            }

            ByteBuffer uniformByteBuf = pUniformData.getByteBuffer(0, 256);
            FloatBuffer uniformFloatBuf = uniformByteBuf.asFloatBuffer();

            float[] invViewProjArr = new float[16];
            invViewProj.get(invViewProjArr);
            uniformFloatBuf.put(invViewProjArr);

            float[] viewProjArr = new float[16];
            viewProj.get(viewProjArr);
            uniformFloatBuf.put(viewProjArr);

            uniformFloatBuf.put(cameraPos.x);
            uniformFloatBuf.put(cameraPos.y);
            uniformFloatBuf.put(cameraPos.z);
            uniformFloatBuf.put(0.0f);

            IntBuffer uniformIntBuf = uniformByteBuf.asIntBuffer();
            uniformIntBuf.put(36, chunkCount);
            uniformIntBuf.put(37, viewportWidth);
            uniformIntBuf.put(38, viewportHeight);

            int planeFloatOffset = 40;
            Vector4f[] planes = new Vector4f[6];

            planes[0] = new Vector4f(
                    viewProj.m03() + viewProj.m00(),
                    viewProj.m13() + viewProj.m10(),
                    viewProj.m23() + viewProj.m20(),
                    viewProj.m33() + viewProj.m30()
            );

            planes[1] = new Vector4f(
                    viewProj.m03() - viewProj.m00(),
                    viewProj.m13() - viewProj.m10(),
                    viewProj.m23() - viewProj.m20(),
                    viewProj.m33() - viewProj.m30()
            );

            planes[2] = new Vector4f(
                    viewProj.m03() + viewProj.m01(),
                    viewProj.m13() + viewProj.m11(),
                    viewProj.m23() + viewProj.m21(),
                    viewProj.m33() + viewProj.m31()
            );

            planes[3] = new Vector4f(
                    viewProj.m03() - viewProj.m01(),
                    viewProj.m13() - viewProj.m11(),
                    viewProj.m23() - viewProj.m21(),
                    viewProj.m33() - viewProj.m31()
            );

            planes[4] = new Vector4f(
                    viewProj.m02(),
                    viewProj.m12(),
                    viewProj.m22(),
                    viewProj.m32()
            );

            planes[5] = new Vector4f(
                    viewProj.m03() - viewProj.m02(),
                    viewProj.m13() - viewProj.m12(),
                    viewProj.m23() - viewProj.m22(),
                    viewProj.m33() - viewProj.m32()
            );

            for (int i = 0; i < 6; i++) {
                float length = (float) Math.sqrt(planes[i].x * planes[i].x + planes[i].y * planes[i].y + planes[i].z * planes[i].z);

                if (length > 0.0f) {
                    planes[i].x /= length;
                    planes[i].y /= length;
                    planes[i].z /= length;
                    planes[i].w /= length;
                }
            }

            for (int i = 0; i < 6; i++) {
                Vector4f plane = planes[i];
                uniformFloatBuf.put(planeFloatOffset,     plane.x);
                uniformFloatBuf.put(planeFloatOffset + 1, plane.y);
                uniformFloatBuf.put(planeFloatOffset + 2, plane.z);
                uniformFloatBuf.put(planeFloatOffset + 3, plane.w);
                planeFloatOffset += 4;
            }

            VK10.vkUnmapMemory(VulkanState.getDevice(), VulkanState.getFrameUniformMemory());
        }
    }
}
