package com.stellar.pmwlite.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.stellar.pmwlite.PMWLite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;

/**
 * 后处理管线 / Post-processing pipeline.
 *
 * WHAT IT DOES
 * ------------
 * Adds one full-screen pass on top of the finished level buffer during heavy weather:
 * a cool desaturating haze plus a radial vignette, so a supercell reads as "thick air" the way
 * PMWeather's volumetric pass did, without raymarching a second atmosphere.
 *
 * WHY IT IS BUILT THIS WAY
 * ------------------------
 * {@code PostChain} keeps its passes private, so a chain loaded from JSON cannot have its uniforms
 * updated per frame -- the look would be frozen at whatever the JSON declared. This class therefore
 * loads a chain that declares **no passes** and then adds its own via the public
 * {@code addPass(name, in, out)}, which hands back the {@link PostPass}. That gives us a handle on
 * {@code EffectInstance.safeGetUniform(...)} and lets the haze react to the live storm intensity.
 *
 * FALLBACK / STABILITY
 * --------------------
 *  - never runs when a shader pack owns the post chain (see {@link ShaderCompatLayer});
 *  - never runs when the player turned it off ({@code /pmwlite fx off});
 *  - any failure (missing shader asset, GL error, driver quirk) disables the pipeline for the rest
 *    of the session and logs once -- the fog + overlay layers already carry the look, so the
 *    player only loses the extra tint, never the game.
 */
public final class PostEffectPipeline {
    /**
     * PostChain opens this location verbatim (verified in {@code PostChain#load}: it calls
     * {@code getResourceOrThrow} with the ctor argument), so the full json path has to be given --
     * exactly what {@code GameRenderer#loadEffect} does for vanilla effects.
     */
    private static final ResourceLocation CHAIN_ID =
            new ResourceLocation(PMWLite.MOD_ID, "shaders/post/storm_haze.json");
    /** Declared as a full-size target in the chain json so PostChain owns and resizes it. */
    private static final String TEMP_TARGET = "pmwlite_haze";

    private static PostChain chain;
    private static PostPass hazePass;
    private static PostPass blitPass;
    private static int builtWidth = -1;
    private static int builtHeight = -1;
    private static boolean disabled = false;
    private static boolean loggedFailure = false;
    private static boolean loggedSuccess = false;

    private PostEffectPipeline() {}

    public static boolean active() {
        return !disabled && chain != null && hazePass != null;
    }

    public static String status() {
        if (disabled) return "§c不可用（已回退到雾+叠加层）";
        if (!ShaderCompatLayer.postEffectsAllowed()) return "§e已交由光影处理";
        return active() ? "§a运行中" : "§7未初始化";
    }

    /** Force a rebuild on the next frame (resource reload / dimension change). */
    public static void invalidate() {
        close();
        builtWidth = -1;
        builtHeight = -1;
    }

    /**
     * @param partialTick frame delta, forwarded to the chain's {@code Time} uniform
     */
    public static void render(float partialTick) {
        if (disabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (!ShaderCompatLayer.postEffectsAllowed()) return;

        float intensity = ClientVisualState.intensity();
        if (intensity < 0.10f) {
            // Below a drizzle there is nothing to composite; keep the chain warm but skip the pass.
            return;
        }

        try {
            ensure(mc);
            if (chain == null || hazePass == null) return;

            var eff = hazePass.getEffect();
            // Values are re-applied every frame: cheap, and it means a pack reload or a window
            // resize can never leave a stale uniform behind.
            eff.safeGetUniform("OutSize").set((float) builtWidth, (float) builtHeight);
            eff.safeGetUniform("StormHaze").set(intensity);
            eff.safeGetUniform("Cold").set(ClientVisualState.cold() ? 1f : 0f);
            eff.safeGetUniform("Flash").set(ClientVisualState.flash() * 0.45f);
            if (blitPass != null) {
                blitPass.getEffect().safeGetUniform("OutSize")
                        .set((float) builtWidth, (float) builtHeight);
            }

            chain.process(partialTick);

            if (!loggedSuccess) {
                loggedSuccess = true;
                PMWLite.LOGGER.info("[PMWLite] 后处理管线已启用 (storm_haze @ {}x{})", builtWidth, builtHeight);
            }
        } catch (Throwable t) {
            disabled = true;
            if (!loggedFailure) {
                loggedFailure = true;
                PMWLite.LOGGER.warn("[PMWLite] 后处理管线不可用，已回退到雾效+叠加层: {}", t.toString());
            }
            close();
        }
    }

    private static void ensure(Minecraft mc) throws Exception {
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;
        if (chain != null && builtWidth == main.width && builtHeight == main.height) return;

        close();
        builtWidth = main.width;
        builtHeight = main.height;

        PostChain c = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN_ID);
        RenderTarget temp = c.getTempTarget(TEMP_TARGET);
        if (temp == null) {
            c.close();
            throw new IllegalStateException("temp target missing from chain json");
        }
        PostPass haze = c.addPass(PMWLite.MOD_ID + ":storm_haze", main, temp);
        PostPass blit = c.addPass(PMWLite.MOD_ID + ":storm_blit", temp, main);
        chain = c;
        hazePass = haze;
        blitPass = blit;
    }

    private static void close() {
        hazePass = null;
        blitPass = null;
        if (chain != null) {
            try {
                chain.close();
            } catch (Throwable ignored) {
            }
            chain = null;
        }
    }
}
