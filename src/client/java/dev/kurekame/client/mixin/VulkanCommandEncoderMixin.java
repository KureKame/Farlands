package dev.kurekame.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.kurekame.client.render.VulkanState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onConstruct(CallbackInfo ci) {
        VulkanCommandEncoder encoder = (VulkanCommandEncoder) (Object) this;

        var buffer = ((VulkanCommandEncoderMixinAccessor) encoder).farlands$getVkCommandBuffer();
        VulkanState.setActiveCommandBuffer(buffer);
    }
}