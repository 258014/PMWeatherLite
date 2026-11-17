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

        e.setFarPlaneDistance(Math.max(e.getFarPlaneDistance() * scale, 12f));
        e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance() * scale, 6f));
        e.setFogShape(FogShape.SPHERE);
    }

    /** Tint the fog toward the slate grey of a heavy precipitation core. */
    public static void applyFogColor(ViewportEvent.ComputeFogColor e) {
        if (!ShaderCompatLayer.fogEventsAllowed()) return;
        float i = ClientVisualState.intensity();
        if (i < 0.30f) return;

        float t = Mth.clamp((i - 0.30f) / 0.70f, 0f, 1f) * 0.55f;
        // cold biomes read blue-white (snow core), warm ones read green-grey (rain core)
        float tr = ClientVisualState.cold() ? 0.72f : 0.40f;
        float tg = ClientVisualState.cold() ? 0.76f : 0.45f;
        float tb = ClientVisualState.cold() ? 0.82f : 0.53f;

        e.setRed(Mth.lerp(t, e.getRed(), tr));
        e.setGreen(Mth.lerp(t, e.getGreen(), tg));
        e.setBlue(Mth.lerp(t, e.getBlue(), tb));
    }
}
