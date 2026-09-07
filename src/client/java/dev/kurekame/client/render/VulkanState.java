package dev.kurekame.client.render;

import org.lwjgl.vulkan.*;

public class VulkanState {
    private static VkCommandBuffer activeCommandBuffer = null;
    private static VkDevice device = null;
    private static VkPhysicalDeviceMemoryProperties memoryProperties = null;

    private static long frameUniformBuffer;
    private static long frameUniformMemory;

    private static long blockLibraryBuffer;
    private static long blockLibraryMemory;

    private static long dagHeaderBuffer;
    private static long dagHeaderMemory;
    private static long dagSecondBuffer;
    private static long dagSecondMemory;
    private static long dagChildPoolBuffer;
    private static long dagChildPoolMemory;
    private static long chunkListBuffer;
    private static long chunkListMemory;
    private static long visibleChunksBuffer;
    private static long visibleChunksMemory;
    private static long visibleChunkCountBuffer;
    private static long visibleChunkCountMemory;

    private static long tileSelectionIndicesBuffer;
    private static long tileSelectionIndicesMemory;
    private static long tileSelectionCountBuffer;
    private static long tileSelectionCountMemory;
    private static long tileSelectionDepthBuffer;
    private static long tileSelectionDepthMemory;

    public static boolean dagDirty = false;
    public static boolean blockLibraryDirty = false;
    public static boolean blockLibraryInit = false;
    public static boolean dagBufferInit = false;

    public static Object bufferLock = new Object();

    public static void setActiveCommandBuffer(VkCommandBuffer buffer) {
        activeCommandBuffer = buffer;
    }

    public static VkCommandBuffer getActiveCommandBuffer() {
        return activeCommandBuffer;
    }

    public static void setFrameUniformBuffer(long buffer) {
        VulkanState.frameUniformBuffer = buffer;
    }

    public static long getFrameUniformBuffer() {
        return frameUniformBuffer;
    }

    public static void setFrameUniformMemory(long memory) {
        VulkanState.frameUniformMemory = memory;
    }

    public static long getFrameUniformMemory() {
        return frameUniformMemory;
    }

    public static void setBlockLibraryBuffer(long buffer) {
        VulkanState.blockLibraryBuffer = buffer;
        VulkanState.blockLibraryInit = true;
    }

    public static void setBlockLibraryMemory(long memory) {
        VulkanState.blockLibraryMemory = memory;
    }

    public static long getBlockLibraryBuffer() {
        return blockLibraryBuffer;
    }

    public static long getBlockLibraryMemory() {
        return blockLibraryMemory;
    }

    public static void setDagHeaderBuffer(long buffer) {
        VulkanState.dagHeaderBuffer = buffer;
    }

    public static long getDagHeaderBuffer() {
        return dagHeaderBuffer;
    }

    public static void setDagHeaderMemory(long memory) {
        VulkanState.dagHeaderMemory = memory;
    }

    public static long getDagHeaderMemory() {
        return dagHeaderMemory;
    }

    public static void setDagSecondBuffer(long buffer) {
        VulkanState.dagSecondBuffer = buffer;
    }

    public static long getDagSecondBuffer() {
        return dagSecondBuffer;
    }

    public static void setDagSecondMemory(long memory) {
        VulkanState.dagSecondMemory = memory;
    }

    public static long getDagSecondMemory() {
        return dagSecondMemory;
    }

    public static void setDagChildPoolBuffer(long buffer) {
        VulkanState.dagChildPoolBuffer = buffer;
    }

    public static long getDagChildPoolBuffer() {
        return dagChildPoolBuffer;
    }

    public static void setDagChildPoolMemory(long memory) {
        VulkanState.dagChildPoolMemory = memory;
        VulkanState.dagBufferInit = true;
    }

    public static long getDagChildPoolMemory() {
        return dagChildPoolMemory;
    }

    public static void setChunkListBuffer(long buffer) {
        VulkanState.chunkListBuffer = buffer;
    }

    public static long getChunkListBuffer() {
        return chunkListBuffer;
    }

    public static void setChunkListMemory(long memory) {
        VulkanState.chunkListMemory = memory;
    }

    public static long getChunkListMemory() {
        return chunkListMemory;
    }

    public static void setVisibleChunksBuffer(long buffer) {
        VulkanState.visibleChunksBuffer = buffer;
    }

    public static long getVisibleChunksBuffer() {
        return visibleChunksBuffer;
    }

    public static void setVisibleChunksMemory(long memory) {
        VulkanState.visibleChunksMemory = memory;
    }

    public static long getVisibleChunksMemory() {
        return visibleChunksMemory;
    }

    public static void setVisibleChunkCountBuffer(long buffer) {
        VulkanState.visibleChunkCountBuffer = buffer;
    }

    public static long getVisibleChunkCountBuffer() {
        return visibleChunkCountBuffer;
    }

    public static void setVisibleChunkCountMemory(long memory) {
        VulkanState.visibleChunkCountMemory = memory;
    }

    public static long getVisibleChunkCountMemory() {
        return visibleChunkCountMemory;
    }

    public static void setTileSelectionIndicesBuffer(long buffer) {
        VulkanState.tileSelectionIndicesBuffer = buffer;
    }

    public static long getTileSelectionIndicesBuffer() {
        return tileSelectionIndicesBuffer;
    }

    public static void setTileSelectionIndicesMemory(long memory) {
        VulkanState.tileSelectionIndicesMemory = memory;
    }

    public static long getTileSelectionIndicesMemory() {
        return tileSelectionIndicesMemory;
    }

    public static void setTileSelectionCountBuffer(long buffer) {
        VulkanState.tileSelectionCountBuffer = buffer;
    }

    public static long getTileSelectionCountBuffer() {
        return tileSelectionCountBuffer;
    }

    public static void setTileSelectionCountMemory(long memory) {
        VulkanState.tileSelectionCountMemory = memory;
    }

    public static long getTileSelectionCountMemory() {
        return tileSelectionCountMemory;
    }

    public static void setTileSelectionDepthBuffer(long buffer) {
        VulkanState.tileSelectionDepthBuffer = buffer;
    }

    public static long getTileSelectionDepthBuffer() {
        return tileSelectionDepthBuffer;
    }

    public static void setTileSelectionDepthMemory(long memory) {
        VulkanState.tileSelectionDepthMemory = memory;
    }

    public static long getTileSelectionDepthMemory() {
        return tileSelectionDepthMemory;
    }

    public static void setDevice(VkDevice device) {
        VulkanState.device = device;

        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.create();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memProps);
        VulkanState.memoryProperties = memProps;
    }

    public static VkDevice getDevice() {
        return device;
    }

    public static VkPhysicalDeviceMemoryProperties getMemoryProperties() {
        return memoryProperties;
    }
}