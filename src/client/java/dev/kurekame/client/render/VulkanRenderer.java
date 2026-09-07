package dev.kurekame.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.kurekame.client.FarlandsClient;
import dev.kurekame.client.data.BlockLibrary;
import dev.kurekame.client.mixin.VulkanGpuTextureMixinAccessor;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;


public class VulkanRenderer {
    private int tileBufferWidth = 0;
    private int tileBufferHeight = 0;

    private long chunkSelectionPipeline;
    private long chunkSelectionPipelineLayout;
    private long chunkSelectionDescriptorPool;
    private long chunkSelectionDescriptorSetLayout;
    private long chunkSelectionDescriptorSet;

    private long tileSelectionPipeline;
    private long tileSelectionPipelineLayout;
    private long tileSelectionDescriptorPool;
    private long tileSelectionDescriptorSetLayout;
    private long tileSelectionDescriptorSet;

    private long traversalPipeline;
    private long traversalPipelineLayout;
    private long traversalDescriptorPool;
    private long traversalDescriptorSetLayout;
    private long traversalDescriptorSet;

    private long cachedColorImage;
    private long cachedColorImageView;

    private long cachedDepthImage;
    private long cachedDepthImageView;
    private long depthSampler;

    private boolean isInitialized = false;

    public void insertIntoFrame(VkCommandBuffer vkCommandBuffer, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, int viewportWidth, int viewportHeight) {
        synchronized (VulkanState.bufferLock) {
            RenderTarget mainRenderTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            VulkanGpuTexture colorTexture = (VulkanGpuTexture) mainRenderTarget.getColorTexture();
            VulkanGpuTexture depthTexture = (VulkanGpuTexture) mainRenderTarget.getDepthTexture();

            if (!isInitialized) {
                VkDevice device = ((VulkanGpuTextureMixinAccessor) colorTexture).farlands$getVulkanDevice().vkDevice();
                VulkanState.setDevice(device);
                createTraversalPipeline(VulkanState.getDevice());
                createTileSelectionPipeline(VulkanState.getDevice());
                createChunkSelectionPipeline(VulkanState.getDevice());
                BlockLibrary blockLibrary = FarlandsClient.getInstance().getBlockLibrary();
                VulkanHelpers.updateBlockLibrary(blockLibrary.colours, blockLibrary.count);
                VulkanHelpers.createFrameUniform();
                VulkanHelpers.createVisibleChunkCountBuffer();
                isInitialized = true;
            }

            if (VulkanState.dagDirty && FarlandsClient.getInstance().flattened != null) {
                VulkanState.dagDirty = false;
                VK10.vkDeviceWaitIdle(VulkanState.getDevice());
                DagGraphFlattener.FlattenedBuffers flattened = FarlandsClient.getInstance().flattened;

                VulkanHelpers.updateDagBuffer(flattened, FarlandsClient.getInstance().chunks.size(), FarlandsClient.getInstance().chunks);
            }

            if (VulkanState.blockLibraryDirty && FarlandsClient.getInstance().getBlockLibrary() != null) {
                VulkanState.blockLibraryDirty = false;
                VK10.vkDeviceWaitIdle(VulkanState.getDevice());

                VulkanHelpers.updateBlockLibrary(FarlandsClient.getInstance().getBlockLibrary().colours, FarlandsClient.getInstance().getBlockLibrary().count);
            }

            if (tileBufferWidth != viewportWidth || tileBufferHeight != viewportHeight) {
                VulkanHelpers.updateTileSelectionBuffers(viewportWidth, viewportHeight);
                tileBufferWidth = viewportWidth;
                tileBufferHeight = viewportHeight;
            }

            if (VulkanState.blockLibraryInit && VulkanState.dagBufferInit && FarlandsClient.getInstance().flattened != null) {
                VK10.vkDeviceWaitIdle(VulkanState.getDevice());
                long colorImageView = getOrCreateColorImageView(colorTexture.vkImage());
                long depthImageView = getOrCreateDepthImageView(depthTexture.vkImage());
                Vector3f cameraPos = Minecraft.getInstance().gameRenderer.mainCamera().position().toVector3f();

                VK10.vkCmdFillBuffer(vkCommandBuffer, VulkanState.getVisibleChunkCountBuffer(), 0, 4, 0);

                Matrix4f viewProj = new Matrix4f(projectionMatrix).mul(modelViewMatrix);
                Matrix4f invViewProj = new Matrix4f(viewProj).invert();
                VulkanHelpers.updateFrameUniform(invViewProj, viewProj, cameraPos, FarlandsClient.getInstance().chunks.size(), viewportWidth, viewportHeight);

                updateChunkSelectionDescriptorSet();
                updateTileSelectionDescriptorSet(depthImageView);
                updateTraversalDescriptorSet(colorImageView, depthImageView);

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getVisibleChunkCountBuffer(),
                            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT
                    );

                    VK10.vkCmdBindPipeline(vkCommandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, chunkSelectionPipeline);
                    VK10.vkCmdBindDescriptorSets(
                            vkCommandBuffer,
                            VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                            chunkSelectionPipelineLayout,
                            0,
                            stack.longs(chunkSelectionDescriptorSet),
                            null
                    );

                    VK10.vkCmdDispatch(vkCommandBuffer, Math.max(Math.ceilDiv(FarlandsClient.getInstance().chunks.size(), 64), 1), 1, 1);

                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getVisibleChunksBuffer(),
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getVisibleChunkCountBuffer(),
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    imageBarrier(
                            vkCommandBuffer, colorTexture.vkImage(),
                            VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                            VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT
                    );

                    imageBarrier(
                            vkCommandBuffer, depthTexture.vkImage(),
                            VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                            VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
                            VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    VK10.vkCmdBindPipeline(vkCommandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, tileSelectionPipeline);

                    VK10.vkCmdBindDescriptorSets(
                            vkCommandBuffer,
                            VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                            tileSelectionPipelineLayout,
                            0,
                            stack.longs(tileSelectionDescriptorSet),
                            null
                    );

                    int tileGridX = Math.ceilDiv(viewportWidth, 8);
                    int tileGridY = Math.ceilDiv(viewportHeight, 8);
                    int tileWorkgroupsX = Math.ceilDiv(tileGridX, 4);
                    int tileWorkgroupsY = Math.ceilDiv(tileGridY, 4);
                    VK10.vkCmdDispatch(vkCommandBuffer, tileWorkgroupsX, tileWorkgroupsY, 1);

                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getTileSelectionIndicesBuffer(),
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getTileSelectionCountBuffer(),
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    bufferBarrier(
                            vkCommandBuffer,
                            VulkanState.getTileSelectionDepthBuffer(),
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
                    );

                    VK10.vkCmdBindPipeline(vkCommandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, traversalPipeline);

                    VK10.vkCmdBindDescriptorSets(
                            vkCommandBuffer,
                            VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                            traversalPipelineLayout,
                            0,
                            stack.longs(traversalDescriptorSet),
                            null
                    );

                    int traversalWorkgroupsX = (viewportWidth + 7) / 8;
                    int traversalWorkgroupsY = (viewportHeight + 7) / 8;
                    VK10.vkCmdDispatch(vkCommandBuffer, traversalWorkgroupsX, traversalWorkgroupsY, 1);

                    imageBarrier(
                            vkCommandBuffer, colorTexture.vkImage(),
                            VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                    );

                    imageBarrier(
                            vkCommandBuffer, depthTexture.vkImage(),
                            VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                            VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                            VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    );
                }
            }
        }
    }

    private long getOrCreateColorImageView(long colorImage) {
        if (this.cachedColorImage == colorImage && this.cachedColorImageView != 0L) {
            return this.cachedColorImageView;
        }
        if (this.cachedColorImageView != 0L) {
            VK10.vkDestroyImageView(VulkanState.getDevice(), this.cachedColorImageView, null);
        }
        this.cachedColorImage = colorImage;
        this.cachedColorImageView = createVkImageView(VulkanState.getDevice(), colorImage, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
        return this.cachedColorImageView;
    }

    private long getOrCreateDepthImageView(long depthImage) {
        if (this.cachedDepthImage == depthImage && this.cachedDepthImageView != 0L) {
            return this.cachedDepthImageView;
        }
        if (this.cachedDepthImageView != 0L) {
            VK10.vkDestroyImageView(VulkanState.getDevice(), this.cachedDepthImageView, null);
        }
        this.cachedDepthImage = depthImage;
        this.cachedDepthImageView = createVkImageView(VulkanState.getDevice(), depthImage, VK10.VK_FORMAT_D32_SFLOAT, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
        return this.cachedDepthImageView;
    }

    private long createDepthSampler(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default();
            samplerInfo.magFilter(VK10.VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK10.VK_FILTER_NEAREST);
            samplerInfo.addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST);

            LongBuffer pSampler = stack.mallocLong(1);
            if (VK10.vkCreateSampler(device, samplerInfo, null, pSampler) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create depth sampler");
            }
            return pSampler.get(0);
        }
    }

    private void createChunkSelectionPipeline(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);

            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create chunk selection DescriptorSetLayout");
            }
            this.chunkSelectionDescriptorSetLayout = pLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.chunkSelectionDescriptorSetLayout));

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            if (VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create chunk selection PipelineLayout");
            }
            this.chunkSelectionPipelineLayout = pPipelineLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);

            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default();
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create chunk selection DescriptorPool");
            }
            this.chunkSelectionDescriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default();
            allocInfo.descriptorPool(this.chunkSelectionDescriptorPool);
            allocInfo.pSetLayouts(stack.longs(this.chunkSelectionDescriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            if (VK10.vkAllocateDescriptorSets(device, allocInfo, pSet) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate chunk selection DescriptorSet");
            }
            this.chunkSelectionDescriptorSet = pSet.get(0);

            ByteBuffer shaderCode = loadShader("shaders/chunk_selection.comp.spv");

            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            moduleCreateInfo.pCode(shaderCode);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (VK10.vkCreateShaderModule(device, moduleCreateInfo, null, pShaderModule) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create chunk selection ShaderModule");
            }
            long shaderModule = pShaderModule.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
            pipelineInfo.get(0).sType$Default();
            pipelineInfo.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            pipelineInfo.get(0).layout(this.chunkSelectionPipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create chunk selection ComputePipeline");
            }
            this.chunkSelectionPipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    private void createTileSelectionPipeline(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(7, stack);

            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // mc depth
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // frame
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // chunks
            bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // chunk count
            bindings.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // indices
            bindings.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // count
            bindings.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // depth

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create tile selection DescriptorSetLayout");
            }
            this.tileSelectionDescriptorSetLayout = pLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.tileSelectionDescriptorSetLayout));

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            if (VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create tile selection PipelineLayout");
            }
            this.tileSelectionPipelineLayout = pPipelineLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default();
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create tile selection DescriptorPool");
            }
            this.tileSelectionDescriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default();
            allocInfo.descriptorPool(this.tileSelectionDescriptorPool);
            allocInfo.pSetLayouts(stack.longs(this.tileSelectionDescriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            if (VK10.vkAllocateDescriptorSets(device, allocInfo, pSet) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate tile selection DescriptorSet");
            }
            this.tileSelectionDescriptorSet = pSet.get(0);

            ByteBuffer shaderCode = loadShader("shaders/tile_selection.comp.spv");

            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            moduleCreateInfo.pCode(shaderCode);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (VK10.vkCreateShaderModule(device, moduleCreateInfo, null, pShaderModule) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create tile selection ShaderModule");
            }
            long shaderModule = pShaderModule.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
            pipelineInfo.stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            pipelineInfo.layout(this.tileSelectionPipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create tile selection ComputePipeline");
            }
            this.tileSelectionPipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    private void createTraversalPipeline(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(11, stack);

            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // colour output
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // vanilla depth
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT); // frame uniforms
            bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(7).binding(7).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(8).binding(8).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(9).binding(9).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(10).binding(10).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan DescriptorSetLayout");
            }
            this.traversalDescriptorSetLayout = pLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.traversalDescriptorSetLayout));

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            if (VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan PipelineLayout");
            }
            this.traversalPipelineLayout = pPipelineLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(8);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default();
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan DescriptorPool");
            }
            this.traversalDescriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default();
            allocInfo.descriptorPool(this.traversalDescriptorPool);
            allocInfo.pSetLayouts(stack.longs(this.traversalDescriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            if (VK10.vkAllocateDescriptorSets(device, allocInfo, pSet) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Vulkan DescriptorSet");
            }
            this.traversalDescriptorSet = pSet.get(0);

            this.depthSampler = createDepthSampler(device);

            ByteBuffer shaderCode = loadShader("shaders/traversal.comp.spv");

            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            moduleCreateInfo.pCode(shaderCode);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (VK10.vkCreateShaderModule(device, moduleCreateInfo, null, pShaderModule) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Shader Module");
            }
            long shaderModule = pShaderModule.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
            pipelineInfo.stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            pipelineInfo.layout(this.traversalPipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Compute Pipeline");
            }
            this.traversalPipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    private ByteBuffer loadShader(String resourcePath) {
        String formattedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream is = VulkanRenderer.class.getResourceAsStream(formattedPath)) {
            if (is == null) throw new RuntimeException("Could not find shader resource at path: " + resourcePath);
            byte[] bytes = is.readAllBytes();
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load SPIR-V shader file: " + resourcePath, e);
        }
    }

    private void updateChunkSelectionDescriptorSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);

            long[] buffers = {VulkanState.getFrameUniformBuffer(), VulkanState.getChunkListBuffer(), VulkanState.getVisibleChunksBuffer(), VulkanState.getVisibleChunkCountBuffer()};
            int[] types = {
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
            };

            for (int i = 0; i < 4; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(buffers[i]).offset(0).range(VK10.VK_WHOLE_SIZE);

                writes.get(i).sType$Default()
                        .dstSet(chunkSelectionDescriptorSet)
                        .dstBinding(i)
                        .descriptorType(types[i])
                        .descriptorCount(1)
                        .pBufferInfo(bufferInfo);
            }

            VK10.vkUpdateDescriptorSets(VulkanState.getDevice(), writes, null);
        }
    }

    private void updateTileSelectionDescriptorSet(long depthImageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(7, stack);

            VkDescriptorImageInfo.Buffer depthImageInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthImageInfo.sampler(depthSampler);
            depthImageInfo.imageView(depthImageView);
            depthImageInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            writes.get(0).sType$Default()
                    .dstSet(tileSelectionDescriptorSet).dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(depthImageInfo);

            long[] buffers = {VulkanState.getFrameUniformBuffer(), VulkanState.getVisibleChunksBuffer(), VulkanState.getVisibleChunkCountBuffer(), VulkanState.getTileSelectionIndicesBuffer(), VulkanState.getTileSelectionCountBuffer(), VulkanState.getTileSelectionDepthBuffer()};
            int[] types = {
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
            };

            for (int i = 1; i < 7; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(buffers[i - 1]).offset(0).range(VK10.VK_WHOLE_SIZE);

                writes.get(i).sType$Default()
                        .dstSet(tileSelectionDescriptorSet).dstBinding(i)
                        .descriptorType(types[i - 1])
                        .descriptorCount(1)
                        .pBufferInfo(bufferInfo);
            }

            VK10.vkUpdateDescriptorSets(VulkanState.getDevice(), writes, null);
        }
    }

    private void updateTraversalDescriptorSet(long colorImageView, long depthImageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(11, stack);

            VkDescriptorImageInfo.Buffer colorImageInfo = VkDescriptorImageInfo.calloc(1, stack);
            colorImageInfo.imageView(colorImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(0).sType$Default()
                    .dstSet(traversalDescriptorSet).dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(colorImageInfo);

            VkDescriptorImageInfo.Buffer depthImageInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthImageInfo.sampler(depthSampler);
            depthImageInfo.imageView(depthImageView);
            depthImageInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            writes.get(1).sType$Default()
                    .dstSet(traversalDescriptorSet).dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(depthImageInfo);

            long[] buffers = {VulkanState.getFrameUniformBuffer(), VulkanState.getBlockLibraryBuffer(), VulkanState.getVisibleChunksBuffer(), VulkanState.getDagHeaderBuffer(), VulkanState.getDagSecondBuffer(), VulkanState.getDagChildPoolBuffer(), VulkanState.getTileSelectionIndicesBuffer(), VulkanState.getTileSelectionCountBuffer(), VulkanState.getTileSelectionDepthBuffer()};
            int[] types = {
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            };

            for (int i = 2; i < 11; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(buffers[i - 2]).offset(0).range(VK10.VK_WHOLE_SIZE);

                writes.get(i).sType$Default()
                        .dstSet(traversalDescriptorSet).dstBinding(i)
                        .descriptorType(types[i - 2])
                        .descriptorCount(1)
                        .pBufferInfo(bufferInfo);
            }

            VK10.vkUpdateDescriptorSets(VulkanState.getDevice(), writes, null);
        }
    }

    private void imageBarrier(
            VkCommandBuffer cmd, long image, int oldLayout, int newLayout, int aspectMask,
            int srcStage, int dstStage, int srcAccess, int dstAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image);
            barrier.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            barrier.srcAccessMask(srcAccess);
            barrier.dstAccessMask(dstAccess);

            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    private void bufferBarrier(VkCommandBuffer cmd, long buffer, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            barrier.buffer(buffer);
            barrier.offset(0);
            barrier.size(VK10.VK_WHOLE_SIZE);
            barrier.srcAccessMask(srcAccess);
            barrier.dstAccessMask(dstAccess);

            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, barrier, null);
        }
    }

    private long createVkImageView(VkDevice device, long image, int format, int aspectMask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            viewInfo.image(image);
            viewInfo.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            if (VK10.vkCreateImageView(device, viewInfo, null, pView) != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkImageView");
            }
            return pView.get(0);
        }
    }
}