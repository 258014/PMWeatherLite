package com.stellar.pmwlite.client;

import com.stellar.pmwlite.atmos.OpticalField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 环境光学的屏幕合成层 / Screen-space optical phenomena layer.
 *
 * 全程走 {@code GuiGraphics}（2D 管线），因此在原版、Embeddium、OptiFine 和任意
 * Oculus/Iris 光影包下表现一致——不碰光影包的后链，不写它的 uniform，不会"双天空"。
 * 这正是本 mod 一贯的兼容策略（见 docs/09）。
 *
 * 画面是**示意性的**：用有限条带程序化画出光幕/光斑的方位与配色，而不是物理光线追踪。
 * 配色遵循现实依据：
 *  - 极光 绿(557.7nm 氧) 为主，强时顶部混红(630nm 氧)、底缘混蓝紫(427.8nm 氮)；
 *  - 幻日/日晕 内红外紫（色散，与彩虹相反）；
 *  - 霞光 暖红褐（气溶胶滤短波）。
 *
 * 成本上限固定：极光 ≤10 条带、幻日 2 光斑、日晕 1 环、HUD 数行，无分配。
 */
public final class OpticsOverlayLayer {
    /** 极光帘幕条数（固定，保证成本有界）。 */
    private static final int AURORA_BANDS = 10;

    private OpticsOverlayLayer() {}

    public static void render(GuiGraphics g, float partialTick) {
        if (!ClientVisualState.overlayEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;

        int w = g.guiWidth(), h = g.guiHeight();
        long t = mc.level.getGameTime();

        // 艺术化极光：只有主导或分项强度足够才画，光影包下也保留（2D 安全）
        float aurora = ClientVisualState.aurora();
        if (aurora > 0.12f) aurora(g, w, h, aurora, t, partialTick);

        // 幻日：太阳左右两枚光斑（近太阳红、外侧蓝紫）
        float parhelion = ClientVisualState.parhelion();
        if (parhelion > 0.12f) parhelion(g, w, h, parhelion);

        // 22° 日晕：以屏幕上方太阳为中心的椭圆弧
        float halo = ClientVisualState.halo();
        if (halo > 0.12f) halo(g, w, h, halo);

        // 雾虹：屏幕下部柔弧
        float fogbow = ClientVisualState.fogbow();
        if (fogbow > 0.12f) fogbow(g, w, h, fogbow);

        // 污染霞光：底部暖色渐变
        float afterglow = ClientVisualState.afterglow();
        if (afterglow > 0.12f) afterglow(g, w, h, afterglow);

        // HUD：现象名 + 科学/艺术标记 + 持续时间 + 多气体读数
        opticsHud(g, w);
    }

    /** 极光帘幕：从屏幕顶部垂下的竖条，绿→红/紫按强度分层，随时间横向漂移。 */
    private static void aurora(GuiGraphics g, int w, int h, float s, long t, float pt) {
        int top = 0;
        int curtainH = (int) (h * (0.18f + 0.22f * s));
        int bandW = Math.max(4, w / AURORA_BANDS / 2);
        float phase = (t + pt) * 0.03f;
        for (int k = 0; k < AURORA_BANDS; k++) {
            float p = k / (float) (AURORA_BANDS - 1);
            // 每列的"帘幕长度"由正弦叠加确定，形成不整齐的下缘
            float wave = 0.55f + 0.45f * (float) Math.sin(phase + k * 0.9f)
                    * (float) Math.cos(phase * 0.6f + k * 0.37f);
            int len = (int) (curtainH * (0.35f + 0.65f * Math.abs(wave)));
            int x = (int) (p * w + Math.sin(phase * 0.5f + k) * bandW * 1.5f);
            if (len < 2) continue;

            // 主体绿（氧 557.7nm）
            int a = (int) (Mth.clamp(s, 0f, 1f) * (40f + 55f * Math.abs(wave)));
            int colGreen = (a << 24) | (0x3A << 16) | (0xE0 << 8) | 0x88;
            g.fill(x, top, x + bandW, top + len, colGreen);
            // 顶部红（630nm 氧）——只在强光幕出现
            if (s > 0.55f) {
                int ar = (int) ((s - 0.55f) * 90f);
                int redLen = (int) (len * 0.28f);
                g.fill(x, top, x + bandW, top + redLen,
                        (ar << 24) | (0xC8 << 16) | (0x42 << 8) | 0x5C);
            }
            // 底缘蓝紫（427.8nm 氮）
            if (s > 0.7f) {
                int ab = (int) ((s - 0.7f) * 110f);
                int blueTop = top + len - Math.max(2, len / 6);
                g.fill(x, blueTop, x + bandW, top + len,
                        (ab << 24) | (0x6A << 16) | (0x7C << 8) | 0xE8);
            }
        }
    }

    /** 柔性光斑：叠加半透明矩形近似径向衰减（不做纹理，成本固定）。 */
    private static void glowBlob(GuiGraphics g, int cx, int cy, int radius, int argb, int layers) {
        for (int l = layers; l >= 1; l--) {
            int r = radius * l / layers;
            int a = ((argb >>> 24) * (layers - l + 1)) / (layers * layers);
            int col = (a << 24) | (argb & 0x00FFFFFF);
            g.fill(cx - r, cy - r, cx + r, cy + r, col);
        }
    }

    /** 幻日：真太阳两侧约 22° 处两枚光斑，靠内侧红、外侧蓝紫。 */
    private static void parhelion(GuiGraphics g, int w, int h, float s) {
        int sunX = w / 2, sunY = (int) (h * 0.22f);
        // 22° 在屏幕上的近似水平偏移（按 FOV 70° 估算：22/70 * 半宽）
        int off = (int) (w * 0.16f);
        int r = Math.max(6, (int) (10 + 14 * s));
        int layers = 4;
        for (int side = -1; side <= 1; side += 2) {
            int bx = sunX + side * off;
            // 靠太阳一侧偏红
            int innerX = bx - side * (r / 2);
            glowBlob(g, innerX, sunY, r, (int) (s * 150) << 24 | 0xE86A4A, layers);
            // 外侧偏蓝紫
            int outerX = bx + side * (r / 2);
            glowBlob(g, outerX, sunY, r, (int) (s * 120) << 24 | 0x6A8AE8, layers);
        }
    }

    /** 22° 日晕：以太阳为中心画一圈椭圆弧（用分段矩形近似）。 */
    private static void halo(GuiGraphics g, int w, int h, float s) {
        int cx = w / 2, cy = (int) (h * 0.22f);
        int rx = (int) (w * 0.16f), ry = (int) (rx * 0.9f);
        int seg = 48;
        int a = (int) (s * 90);
        // 内红外紫：画两层，内层半径略小偏红
        for (int pass = 0; pass < 2; pass++) {
            int rrX = rx - pass * 3, rrY = ry - pass * 3;
            int col = pass == 0
                    ? (a << 24) | 0xE88A5A
                    : ((a / 2) << 24) | 0x8A7AE8;
            for (int i = 0; i < seg; i++) {
                double ang = i / (double) seg * Math.PI * 2.0;
                int x = cx + (int) (Math.cos(ang) * rrX);
                int y = cy + (int) (Math.sin(ang) * rrY);
                g.fill(x - 1, y - 1, x + 1, y + 1, col);
            }
        }
    }

    /** 雾虹：屏幕下部一道柔弧，色序内紫外红（雾滴主虹）。 */
    private static void fogbow(GuiGraphics g, int w, int h, float s) {
        int cx = w / 2, cy = h;
        int r = (int) (h * 0.55f);
        int a = (int) (s * 70);
        int seg = 40;
        double start = Math.PI * 1.15, end = Math.PI * 1.85;
        for (int i = 0; i < seg; i++) {
            double ang = start + (end - start) * i / (double) seg;
            int x = cx + (int) (Math.cos(ang) * r);
            int y = cy + (int) (Math.sin(ang) * r);
            int col = (a << 24) | 0xE8D0A0;
            g.fill(x - 2, y - 2, x + 2, y + 2, col);
        }
    }

    /** 污染霞光：屏幕底部暖红褐渐变（气溶胶散射长波）。 */
    private static void afterglow(GuiGraphics g, int w, int h, float s) {
        int bands = 6;
        int bandH = Math.max(2, h / 12);
        for (int k = 0; k < bands; k++) {
            int a = (int) (s * (1f - k / (float) bands) * 42f);
            if (a <= 1) continue;
            int col = (a << 24) | (0xD0 << 16) | (0x70 << 8) | 0x3A;
            int y = h - (k + 1) * bandH;
            g.fill(0, y, w, y + bandH, col);
        }
    }

    /** HUD：现象名（科学/艺术标记）+ 持续秒数，附一行多气体读数。 */
    private static void opticsHud(GuiGraphics g, int w) {
        int kind = ClientVisualState.opticsKind();
        if (kind <= 0 || kind >= OpticalField.KIND_COUNT) return;
        Minecraft mc = Minecraft.getInstance();
        OpticalField.Kind k = OpticalField.Kind.values()[kind];
        String tag = ClientVisualState.opticsArtistic() ? "§d艺术化" : "§b科学";
        String l1 = String.format("§7光学：%s%s§7 · %ds · %s",
                k.label, tag, ClientVisualState.opticsDuration(),
                ClientVisualState.opticsArtistic() ? "演绎" : "可解释");
        // 多气体读数（ppm；PM 用指数百分比）
        float ppm = 1e6f;
        String l2 = String.format("§7SO₂ §f%.0f §7NO₂ §f%.0f §7CO §f%.0f §8ppm §7PM §f%.0f%%",
                ClientVisualState.so2() * ppm, ClientVisualState.no2() * ppm,
                ClientVisualState.co() * ppm, ClientVisualState.pm() * 100f);
        int tw = Math.max(mc.font.width(l1), mc.font.width(l2)) + 8;
        int x = w - tw - 6;
        int y = 46;
        g.fill(x - 2, y - 3, w - 4, y + 22, 0x55101418);
        g.drawString(mc.font, l1, x, y, 0xFFFFFF, false);
        g.drawString(mc.font, l2, x, y + 10, 0xFFFFFF, false);
    }
}
