package dev.kurekame.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderMixinAccessor {
    @Accessor("currentCommandBuffer")
    VkCommandBuffer farlands$getVkCommandBuffer();
}
