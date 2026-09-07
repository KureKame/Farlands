package dev.kurekame.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.kurekame.client.render.VulkanRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Unique
    private final VulkanRenderer renderer = new VulkanRenderer();

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                    ordinal = 3
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onBeforeFrameGraphExecute(
            GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker,
            boolean renderOutline,
            CameraRenderState cameraState,
            Matrix4fc modelViewMatrix,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci,
            @Local FrameGraphBuilder frame
    ) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();

        FramePass farlandsPass = frame.addPass("farlands");
        this.targets.main = farlandsPass.readsAndWrites(this.targets.main);
        Matrix4f modelView = new Matrix4f(modelViewMatrix);

        GameRenderer gameRenderer = mc.gameRenderer;
        float fov = (float) gameRenderer.mainCamera().getFov();

        float aspectRatio = (float) window.getWidth() / (float) window.getHeight();
        float nearPlane = 0.05F;
        float farPlane = mc.options.getEffectiveRenderDistance() * 16.0F * 50.0F;

        Matrix4f projectionMatrix = new Matrix4f().setPerspective(
                (float) Math.toRadians(fov),
                aspectRatio,
                farPlane,
                nearPlane,
                true
        );
        projectionMatrix.m11(-projectionMatrix.m11());

        int width = window.getWidth();
        int height = window.getHeight();

        farlandsPass.executes(() -> {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            CommandEncoderMixinAccessor encoderAccessor = (CommandEncoderMixinAccessor) encoder;
            VulkanCommandEncoderMixinAccessor vulkanBackend = (VulkanCommandEncoderMixinAccessor) encoderAccessor.farlands$getCommandEncoderBackend();

            VkCommandBuffer vkCommandBuffer = vulkanBackend.farlands$getVkCommandBuffer();

            if (vkCommandBuffer != null && vkCommandBuffer.address() != 0L) {
                renderer.insertIntoFrame(
                        vkCommandBuffer,
                        modelView,
                        projectionMatrix,
                        width,
                        height
                );
            }
        });
    }
}
