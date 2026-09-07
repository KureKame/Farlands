package dev.kurekame.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGpuTexture.class)
public interface VulkanGpuTextureMixinAccessor {
    @Accessor("device")
    VulkanDevice farlands$getVulkanDevice();
}
