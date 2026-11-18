package com.stellar.pmwlite.client;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * 大气表现层 / Atmosphere layer — the shader-compatible half of the "visual difference" fix.
 *
 * PMWeather pulled the horizon in with a volumetric pass. The equivalent that survives every
 * renderer is the vanilla fog event pair: it is applied by the terrain shader itself, costs nothing,
 * and is honoured by vanilla, Embeddium and OptiFine's non-shader path.
 *
 * Under an active shader pack this layer steps aside completely (Iris computes its own fog and
 * ignores these events; forcing values there produces the exact "double fog" artefact we are
 * trying to avoid). {@link ShaderCompatLayer#fogEventsAllowed()} is the single switch for that.
 *
 * Everything here is a multiplication of values Minecraft already computed, so the effect scales
 * with time of day, dimension and biome instead of overriding them.
 */
public final class AtmosphereLayer {

    private AtmosphereLayer() {}

    /** Pull the far plane in as the storm intensifies (1.0 = untouched). */
    public static void applyFog(ViewportEvent.RenderFog e) {
        if (!ShaderCompatLayer.fogEventsAllowed()) return;
        if (e.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
        if (e.getType() != FogType.NONE) return;          // never fight water/lava/powder snow

        float scale = ClientVisualState.fogScale();
        if (scale >= 0.995f) return;

        // v0.4: 阴影越强/太阳越低，空气越"稠"，可视距离再收一点。
        // 幅度刻意很小（≤11%），远 plane 的 82% 缩减仍由风暴强度主导，
        // 防止黄昏时把地图糊成一团。
        float lightScale = 1f - LightingState.shadowStrength * 0.06f - LightingState.dusk * 0.05f;
        lightScale = Mth.clamp(lightScale, 0.89f, 1f);

        // CO₂ 污染再收一档能见度（最多额外 -45%），独立于风暴强度——机器密集区
        // 即使晴天也会灰蒙蒙，这正是"环境污染影响外观"的可视化。
        float pollution = ClientVisualState.pollution();
        float hazeScale = 1f - pollution * 0.45f;

        e.setFarPlaneDistance(Math.max(e.getFarPlaneDistance() * scale * lightScale * hazeScale, 12f));
        e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance() * scale * lightScale, 6f));
        e.setFogShape(FogShape.SPHERE);
    }

    /**
     * Tint the fog toward a precipitation core, **through the ambient sky colour**.
     *
     * 以前这里写死板岩灰，黄昏和正午的暴雨一个颜色。现在基色取
     * {@link LightingState} 的同一份天空色：夜晚自然偏暗蓝、白天偏亮，
     * 再按天气混合冷/暖核色。下游后处理读的是同一份快照，所以雾与 pass 不会互相打架。
     */
    public static void applyFogColor(ViewportEvent.ComputeFogColor e) {
        if (!ShaderCompatLayer.fogEventsAllowed()) return;
        float i = ClientVisualState.intensity();
        float pollution = ClientVisualState.pollution();
        // 风暴或污染任一存在才需要改色；污染单独也能让天空发黄。
        if (i < 0.30f && pollution < 0.02f) return;

        if (pollution > 0.02f) {
            // 污染天幕：低饱和的黄褐色（硫/碳混合烟霾），强度随 CO₂ 上升
            float pt = pollution * 0.5f;
            float hazeR = 0.62f, hazeG = 0.56f, hazeB = 0.38f;
            e.setRed(Mth.lerp(pt, e.getRed(), hazeR));
            e.setGreen(Mth.lerp(pt, e.getGreen(), hazeG));
            e.setBlue(Mth.lerp(pt, e.getBlue(), hazeB));
        }
        if (i < 0.30f) return;

        float t = Mth.clamp((i - 0.30f) / 0.70f, 0f, 1f) * 0.55f;
        // cold biomes read blue-white (snow core), warm ones read green-grey (rain core)
        float stormR = ClientVisualState.cold() ? 0.72f : 0.40f;
        float stormG = ClientVisualState.cold() ? 0.76f : 0.45f;
        float stormB = ClientVisualState.cold() ? 0.82f : 0.53f;

        // 白昼天气核占主导，夜晚把天空色权重拉回来，避免"半夜发光的灰雾"
        float coreWeight = Mth.lerp(LightingState.daylight, 0.35f, 0.80f);
        float tr = Mth.lerp(coreWeight, LightingState.ambR * 0.85f, stormR);
        float tg = Mth.lerp(coreWeight, LightingState.ambG * 0.85f, stormG);
        float tb = Mth.lerp(coreWeight, LightingState.ambB * 0.85f, stormB);

        e.setRed(Mth.lerp(t, e.getRed(), tr));
        e.setGreen(Mth.lerp(t, e.getGreen(), tg));
        e.setBlue(Mth.lerp(t, e.getBlue(), tb));
    }
}
