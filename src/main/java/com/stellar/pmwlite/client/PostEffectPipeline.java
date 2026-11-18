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
 *
 * 阶段与先后顺序（v0.4 明确化）
 * ------------------------------
 * 一帧里实际发生的顺序是固定的，改 shader 时必须照这个次序推演：
 *
 *   1. {@code ViewportEvent.RenderFog / ComputeFogColor}  —— 地形着色器内部，先于任何屏幕 pass；
 *      由 {@link AtmosphereLayer} 按 {@link LightingState} 写雾距与雾色。
 *   2. {@code PostPass #1  pmwlite:storm_haze}  main → temp：
 *      **Stage A** 光照（太阳屏幕方向的空气光/前向散射）→
 *      **Stage B** 阴影幕（强度/软化/分辨率/偏移）→
 *      **Stage C** 风暴雾与凝结 → **Stage D** 闪电 → **Stage E** 暗角/色调。
 *      这五步写在同一个 fragment 里，顺序即 shader 源码顺序——它们共享同一像素，
 *      拆成多个 pass 只会白多一次全屏采样。
 *   3. {@code PostPass #2  pmwlite:storm_blit}  temp → main：唯一一次拷贝，恢复主缓冲可读性。
 *   4. {@code RenderGuiEvent.Post} —— 2D 合成（雨幕/光照幕/HUD），在所有 3D 后处理之后。
 *
 * 仍然只有 **2 个 pass**（main→temp→main）；光照联动是往已有 pass 的 uniform 里写数，
 * 不是再加 pass。这是本版本最重要的性能不变量。
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

            // 光影联动：每帧重新写一次，和风暴强度用同一份"当前事实"。
            // 名字必须与 program/storm_haze.json 以及 core/storm_haze.fsh 完全一致，
            // 否则 safeGetUniform 返回 null，整个管线会按设计回退到雾+叠加层。
            eff.safeGetUniform("SunScreen").set(
                    LightingState.screenSunX, LightingState.screenSunY, LightingState.screenSunFacing);
            eff.safeGetUniform("SunColor").set(
                    LightingState.sunR, LightingState.sunG, LightingState.sunB);
            eff.safeGetUniform("AmbientColor").set(
                    LightingState.ambR, LightingState.ambG, LightingState.ambB);
            eff.safeGetUniform("Sunlight").set(LightingState.sunlight);
            eff.safeGetUniform("Ambient").set(LightingState.ambient);
            eff.safeGetUniform("ShadowStrength").set(LightingState.shadowStrength);
            eff.safeGetUniform("ShadowSoftness").set(LightingState.shadowSoftness);
            eff.safeGetUniform("ShadowResolution").set(LightingState.shadowResolution);
            eff.safeGetUniform("ShadowOffset").set(
                    LightingState.shadowOffsetX, LightingState.shadowOffsetY);
            eff.safeGetUniform("Daylight").set(LightingState.daylight);
            eff.safeGetUniform("Dusk").set(LightingState.dusk);
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
