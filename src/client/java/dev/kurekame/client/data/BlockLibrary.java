package dev.kurekame.client.data;

import dev.kurekame.client.render.VulkanState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import dev.kurekame.client.mixin.SpriteContentsMixinAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockLibrary {
    public float[] colours;
    public int count;

    private final Map<TintedBlock, Integer> tintedIds = new HashMap<>();

    private BlockLibrary(float[] colours, int count) {
        this.colours = colours;
        this.count = count;
    }

    public static BlockLibrary buildLibrary() {
        float[] colours = new float[BuiltInRegistries.BLOCK.size() * 4];
        int blocks = 0;

        ModelManager modelManager = Minecraft.getInstance().getModelManager();

        for (Block block : BuiltInRegistries.BLOCK) {
            BlockStateModel model = modelManager.getBlockStateModelSet().get(block.defaultBlockState());
            SpriteContents texture = model.particleMaterial().sprite().contents();

            try {
                List<BlockStateModelPart> parts = new ArrayList<>();
                model.collectParts(RandomSource.createThreadLocalInstance(), parts);

                for (BlockStateModelPart part : parts) {
                    var quads = part.getQuads(Direction.UP);
                    if (quads != null && !quads.isEmpty()) {
                        TextureAtlasSprite sprite = quads.get(0).materialInfo().sprite();
                        if (sprite != null) {
                            texture =  sprite.contents();
                        }
                    }
                }
            } catch (Exception e) {
                texture = model.particleMaterial().sprite().contents();
            }


            blocks++;

            int width = texture.width();
            int height = texture.height();

            float r = 0, g = 0, b = 0, a = 0;
            int count = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = ((SpriteContentsMixinAccessor) texture).farlands$getOriginalImage().getPixel(x, y);

                    float pixelAlpha = ((pixel >> 24) & 0xFF) / 255f;
                    r += (((pixel >> 16) & 0xFF) / 255f) * pixelAlpha;
                    g += (((pixel >> 8) & 0xFF) / 255f) * pixelAlpha;
                    b += ((pixel & 0xFF) / 255f) * pixelAlpha;
                    a += pixelAlpha;

                    count++;
                }
            }

            int index = getId(block) * 4;

            if (count != 0 && a > 0) {
                colours[index] = r / a;
                colours[index + 1] = g / a;
                colours[index + 2] = b / a;
                colours[index + 3] = a / count;
            }
        }

        return new BlockLibrary(colours, blocks);
    }

    public static int getId(Block block) {
        return BuiltInRegistries.BLOCK.getId(block);
    }

    public synchronized int getOrCreateTintedId(TintedBlock tintedBlock) {
        Integer existing = tintedIds.get(tintedBlock);
        if (existing != null) {
            return existing;
        }

        int newId = count;
        count++;
        ensureCapacity(count);

        int index = newId * 4;
        colours[index] = tintedBlock.r();
        colours[index + 1] = tintedBlock.g();
        colours[index + 2] = tintedBlock.b();
        colours[index + 3] = 1.0f;

        tintedIds.put(tintedBlock, newId);

        VulkanState.blockLibraryDirty = true;

        return newId;
    }

    public int getOrCreateTintedId(int baseBlockId, float r, float g, float b) {
        return getOrCreateTintedId(new TintedBlock(baseBlockId, r, g, b));
    }

    private void ensureCapacity(int requiredCount) {
        int requiredLength = requiredCount * 4;
        if (requiredLength <= colours.length) {
            return;
        }

        int newLength = Math.max(requiredLength, colours.length * 2);
        float[] newColours = new float[newLength];
        System.arraycopy(colours, 0, newColours, 0, colours.length);
        colours = newColours;
    }

    public float getColour(int id, int channel) {
        return colours[id * 4 + channel];
    }
}
