package com.stellar.pmwlite.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 屏幕合成层 / Screen-space compositing layer.
 *
 * This is the "post" half that cannot break: it draws through {@code GuiGraphics} (the 2D pipeline
 * every renderer runs identically), so it looks the same in vanilla, with Embeddium, with OptiFine
 * and under any Oculus/Iris shader pack. When a pack is active we drop the layers it already
 * provides (rain streaks, lightning flash) and keep only the subtle vignette, which avoids the
 * "double rain" look that makes a port feel wrong.
 *
 * Cost: a fixed, small number of quads per frame (see {@link #STREAKS}); no allocation beyond one
 * reused {@link RandomSource}.
 */
public final class StormOverlayLayer {
    /** Screen-space rain streak count; deliberately fixed so the cost is bounded. */
    private static final int STREAKS = 54;

    private static final RandomSource RANDOM = RandomSource.create(0xC0FFEEL);

    private StormOverlayLayer() {}

    public static void render(GuiGraphics g, float partialTick) {
        if (!ClientVisualState.overlayEnabled()) return;
        float i = ClientVisualState.intensity();
        if (i < 0.35f) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.options.hideGui) return;

        int w = g.guiWidth();
        int h = g.guiHeight();

        float t = Mth.clamp((i - 0.35f) / 0.65f, 0f, 1f);

        // 1) 光照幕 — 光影包开启时我们唯一还能做的"后处理"，读同一份 LightingState
        lightVeil(g, w, h, t);

        // 2) vignette — always on; it is what sells "the storm is around you"
        vignette(g, w, h, t);

        // 3) rain / hail streaks — only when no shader pack is drawing its own weather
        if (ShaderCompatLayer.fogEventsAllowed()) {
            streaks(g, w, h, t, mc.level.getGameTime(), partialTick);
        }

        // 4) lightning flash — 颜色随日光色温走，黄昏不再闪冷白
        float flash = ClientVisualState.flash();
        if (flash > 0.01f && ShaderCompatLayer.fogEventsAllowed()) {
            int fr = (int) Mth.clamp(216f * (0.75f + 0.25f * LightingState.sunR), 0f, 255f);
            int fg = (int) Mth.clamp(232f * (0.75f + 0.25f * LightingState.sunG), 0f, 255f);
            int fb = (int) Mth.clamp(255f * (0.75f + 0.25f * LightingState.sunB), 0f, 255f);
            g.fill(0, 0, w, h, ((int) (flash * 90) << 24) | (fr << 16) | (fg << 8) | fb);
        }

        // 5) compact HUD badge so the state is readable without opening the radar
        if (i > 0.55f) badge(g, w, i);
        // 6) 大气读数（v0.3）：气压/气温/湿度/氧气/体感，仅在有数据时显示
        atmosHud(g, w);
    }

    /**
     * 右上角大气读数。数字直接来自服务端探针包，所以屏幕上看到的就是判定用的那份，
     * 不会出现"显示 21% 氧气却已经在扣血"这类不一致。
     */
    private static void atmosHud(GuiGraphics g, int w) {
        var probe = ClientRadarCache.probe();
        if (probe == null || !probe.valid() || !ClientRadarCache.probeFresh()) return;
        Minecraft mc = Minecraft.getInstance();
        int y = 20;
        String l1 = String.format("§7%.1f§8hPa  §7%.1f§8°C  §7%.0f%%RH",
                probe.pressureHpa(), probe.tempC(), probe.humidity() * 100f);
        String l2 = String.format("§7O₂ §f%.1f%%  §7风 §f%.1fm/s  §7体感 §f%.1f§8°C",
                probe.oxygen() * 100f, probe.windSpeed(), probe.apparentC());
        int wpx = Math.max(mc.font.width(l1), mc.font.width(l2)) + 8;
        int x = w - wpx - 6;
        g.fill(x - 2, y - 3, w - 4, y + 20, 0x55101418);
        g.drawString(mc.font, l1, x, y, 0xFFFFFF, false);
        g.drawString(mc.font, l2, x, y + 10, 0xFFFFFF, false);

        if (probe.oxygen() < 0.145f) {
            g.drawString(mc.font, "§c⚠ 空气稀薄", x, y + 22, 0xFF6666, false);
        }
    }

    /**
     * 屏幕空间光照幕（v0.4）。
     *
     * 这是光影包激活时仍然生效的一层：不碰它的后链，只用 2D 管线画 6 条极淡的带子，
     * 让"太阳低了 / 阴影被云摊开"在画面底部仍看得出来。上限 alpha 18（7%），
     * 所以和光影包自己的阴影叠在一起也不会变成黑边。
     *
     * 方向来自 {@link LightingState#shadowOffsetX}/{@code shadowOffsetY}：
     * 太阳在左，暗部就往右挪一点，和 3D pass 的 Stage B 用同一个偏移。
     */
    private static void lightVeil(GuiGraphics g, int w, int h, float t) {
        float strength = LightingState.shadowStrength * (0.35f + 0.65f * t);
        if (strength < 0.06f) return;

        int bands = 6;
        int bandH = Math.max(1, h / (bands * 3));
        int shiftX = (int) (LightingState.shadowOffsetX * w * 2f);
        int shiftY = (int) (-LightingState.shadowOffsetY * h * 2f);
        int r = (int) Mth.clamp(12f + LightingState.ambR * 18f, 0f, 255f);
        int gc = (int) Mth.clamp(16f + LightingState.ambG * 18f, 0f, 255f);
        int b = (int) Mth.clamp(24f + LightingState.ambB * 18f, 0f, 255f);
        int colBase = (r << 16) | (gc << 8) | b;

        for (int k = 0; k < bands; k++) {
            float f = (k + 1f) / bands;
            int a = (int) (f * f * strength * 18f);
            if (a <= 0) continue;
            int y1 = h - (k + 1) * bandH + shiftY;
            int y0 = h - k * bandH + shiftY;
            g.fill(shiftX, y1, w + shiftX, y0, (a << 24) | colBase);
        }
    }

    private static void vignette(GuiGraphics g, int w, int h, float t) {
        int bands = 10;
        int edge = Math.max(24, Math.min(w, h) / 4);
        int step = Math.max(1, edge / bands);
        for (int k = 0; k < bands; k++) {
            float f = 1f - k / (float) bands;
            int a = (int) (f * f * t * 46f);
            if (a <= 1) continue;
            // 暗角颜色跟随环境光：夜里偏蓝黑、雪原偏亮蓝，不再是全场景同一个灰
            int vr = (int) Mth.clamp(10f + LightingState.ambR * 14f, 0f, 255f);
            int vg = (int) Mth.clamp(16f + LightingState.ambG * 14f, 0f, 255f);
            int vb = (int) Mth.clamp(24f + LightingState.ambB * 18f, 0f, 255f);
            int col = (a << 24) | (vr << 16) | (vg << 8) | vb;
            int inset = k * step;
            g.fill(0, inset, w, inset + step, col);                 // top
            g.fill(0, h - inset - step, w, h - inset, col);         // bottom
            g.fill(inset, 0, inset + step, h, col);                 // left
            g.fill(w - inset - step, 0, w - inset, h, col);         // right
        }
    }

    /**
     * Deterministic streaks: each streak owns a fixed lane and phase, and only its vertical
     * position depends on time, so the animation is smooth without storing per-streak state.
     */
    private static void streaks(GuiGraphics g, int w, int h, float t, long gameTime, float partialTick) {
        RANDOM.setSeed(0x5EEDL);
        float scroll = (gameTime + partialTick) * (18f + t * 26f);
        int alpha = (int) (t * 130f);
        if (alpha < 8) return;
        int col = (alpha << 24) | 0xAFC8E8;
        for (int k = 0; k < STREAKS; k++) {
            int x = RANDOM.nextInt(Math.max(w, 1));
            int speed = 24 + RANDOM.nextInt(40);
            int len = 5 + RANDOM.nextInt(9);
            int offset = RANDOM.nextInt(Math.max(h + len, 1));
            int y = (int) ((offset + scroll * (speed / 32f)) % (h + len)) - len;
            int slant = 1 + (int) (t * 2f);
            g.fill(x, y, x + 1, y + len, col);
            g.fill(x + slant, y + 2, x + slant + 1, y + len + 2, col);
        }
    }

    private static void badge(GuiGraphics g, int w, float intensity) {
        Minecraft mc = Minecraft.getInstance();
        String label = "⚠ 风暴 " + Math.round(intensity * 100f) + "%";
        int tw = mc.font.width(label);
        int x = w - tw - 10;
        int y = 8;
        g.fill(x - 4, y - 2, w - 4, y + 10, 0x66101418);
        g.drawString(mc.font, label, x, y, 0xFFD0A0, false);
    }
}
