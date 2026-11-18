package com.stellar.pmwlite.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.stellar.pmwlite.network.AtmosLayerPacket;
import com.stellar.pmwlite.network.AtmosProbePacket;
import com.stellar.pmwlite.network.RadarSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 气象雷达 / Weather Radar GUI。
 *
 * v0.3 起它不只是"看风暴在哪"，而是一个**大气观测台**：8 个图层（云量/气压/气温/湿度/氧气/
 * 风场/体感温度）+ 风暴与固定资产标记。数据来自服务端的本地大气网格，不是客户端猜的。
 *
 * 全部通过 {@code GuiGraphics} 绘制，所以光影开不开都一样。
 */
public class RadarScreen extends Screen {
    private static final int SCOPE = 196;
    private static final int PANEL_W = 404;
    private static final int PANEL_H = 236;

    private static final ResourceLocation GRID_ID = new ResourceLocation("pmwlite", "dynamic/radar_grid");
    private static boolean gridReady = false;

    private long lastRequest = 0L;
    private long lastLayerRequest = 0L;

    public RadarScreen() {
        super(Component.literal("气象雷达"));
    }

    @Override
    protected void init() {
        ClientRadarCache.request();
        ClientRadarCache.requestLayer(ClientRadarCache.selectedLayer());
        lastRequest = System.currentTimeMillis();
        lastLayerRequest = lastRequest;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 1..8 直接选图层；左右方向键循环
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_8) {
            ClientRadarCache.setSelectedLayer(keyCode - GLFW.GLFW_KEY_1);
            lastLayerRequest = System.currentTimeMillis();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            ClientRadarCache.setSelectedLayer(ClientRadarCache.selectedLayer() + 1);
            lastLayerRequest = System.currentTimeMillis();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            ClientRadarCache.setSelectedLayer(ClientRadarCache.selectedLayer() - 1);
            lastLayerRequest = System.currentTimeMillis();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        long now = System.currentTimeMillis();
        if (now - lastRequest > 1000L) {
            lastRequest = now;
            ClientRadarCache.request();
        }
        if (now - lastLayerRequest > 1000L) {
            lastLayerRequest = now;
            ClientRadarCache.requestLayer(ClientRadarCache.selectedLayer());
        }

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, 0xFF1B2027);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xE6101418);
        g.fill(left, top, left + PANEL_W, top + 14, 0xFF232A33);
        g.drawString(this.font, "§b大气观测 §7· §fRADAR §8| §7" + ClientVisualState.intensityPercent(),
                left + 6, top + 3, 0xFFFFFF, false);

        int scopeX = left + 14;
        int scopeY = top + 26;

        drawScope(g, scopeX, scopeY);
        drawLayer(g, scopeX, scopeY);
        drawSweep(g, scopeX, scopeY);

        RadarSyncPacket p = ClientRadarCache.snapshot();
        if (p != null) drawBlips(g, scopeX, scopeY, p);

        int infoX = scopeX + SCOPE + 16;
        if (p == null) {
            g.drawString(this.font, "§7等待服务器数据…", infoX, scopeY + 8, 0xFFFFFF, false);
            g.drawString(this.font, "§8若长时间无响应，请确认服务端已安装本模组",
                    infoX, scopeY + 22, 0x888888, false);
        } else {
            drawInfo(g, infoX, scopeY, p);
            drawAtmos(g, infoX, scopeY + 150);
        }
        drawLayerBar(g, left, top);
        drawFooter(g, left, top);
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ---------------- scope ----------------

    private void drawScope(GuiGraphics g, int x, int y) {
        ensureGrid();
        g.fill(x - 2, y - 2, x + SCOPE + 2, y + SCOPE + 2, 0xFF2C3742);
        g.fill(x - 1, y - 1, x + SCOPE + 1, y + SCOPE + 1, 0xFF070A0D);
        if (gridReady) {
            g.blit(GRID_ID, x, y, 0, 0, SCOPE, SCOPE, SCOPE, SCOPE);
        }
    }

    /** 把服务端采样的 16×16 图层画成热力网格（或风场箭头）。 */
    private void drawLayer(GuiGraphics g, int sx, int sy) {
        AtmosLayerPacket l = ClientRadarCache.layer();
        if (l == null || l.gridSize() <= 0 || l.cells().length == 0) return;
        if (l.kind() != ClientRadarCache.selectedLayer()) return;

        int n = l.gridSize();
        float cell = SCOPE / (float) n;

        if (l.kind() == AtmosLayerPacket.LAYER_WIND && l.aux() != null) {
            // 风场：每 2 格画一支箭头，方向来自角度字节
            for (int z = 0; z < n; z += 2) {
                for (int x = 0; x < n; x += 2) {
                    byte b = l.cells()[z * n + x];
                    if (b < 0) continue;
                    int ang = (l.aux()[z * n + x] & 0xFF);
                    double rad = ang / 255.0 * Math.PI * 2 - Math.PI;
                    float cx = sx + (x + 0.5f) * cell;
                    float cy = sy + (z + 0.5f) * cell;
                    float len = 3f + b / 255f * 5f;
                    for (int s = 0; s <= 4; s++) {
                        float t = s / 4f;
                        int px = (int) (cx + Math.cos(rad) * len * t);
                        int py = (int) (cy + Math.sin(rad) * len * t);
                        g.fill(px, py, px + 2, py + 2, 0xC07FE8C0);
                    }
                }
            }
            return;
        }

        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                byte b = l.cells()[z * n + x];
                if (b < 0) continue;
                float t = (b & 0xFF) / 255f;
                int col = heat(t);
                int x0 = sx + (int) (x * cell);
                int y0 = sy + (int) (z * cell);
                int x1 = sx + (int) ((x + 1) * cell) + 1;
                int y1 = sy + (int) ((z + 1) * cell) + 1;
                g.fill(x0, y0, x1, y1, (col & 0x00FFFFFF) | 0xAA000000);
            }
        }
        // 图例
        String legend = "§8" + l.label() + " §7" + fmt(l.min()) + " ~ " + fmt(l.max()) + " " + l.unit();
        g.drawString(this.font, legend, sx + 2, sy + SCOPE - 10, 0xFFFFFF, false);
        if (ClientRadarCache.layerStale()) {
            g.drawString(this.font, "§c图层过期", sx + SCOPE - 46, sy + SCOPE - 10, 0xFF5555, false);
        }
    }

    private static String fmt(float v) {
        return Math.abs(v) >= 100f ? String.format("%.0f", v) : String.format("%.1f", v);
    }

    /** 蓝 → 青 → 绿 → 黄 → 红 的分段线性映射。 */
    private static int heat(double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        double r, gg, b;
        if (t < 0.25) {
            r = 0; gg = t / 0.25; b = 1;
        } else if (t < 0.5) {
            r = 0; gg = 1; b = 1 - (t - 0.25) / 0.25;
        } else if (t < 0.75) {
            r = (t - 0.5) / 0.25; gg = 1; b = 0;
        } else {
            r = 1; gg = 1 - (t - 0.75) / 0.25; b = 0;
        }
        int ri = (int) (r * 255), gi = (int) (gg * 255), bi = (int) (b * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Blips: kind 0 = storm, 1 = weather event, 2 = fixed asset. */
    private void drawBlips(GuiGraphics g, int sx, int sy, RadarSyncPacket p) {
        int cx = sx + SCOPE / 2;
        int cy = sy + SCOPE / 2;
        float half = SCOPE / 2f;

        List<RadarSyncPacket.Blip> blips = p.blips();
        for (RadarSyncPacket.Blip b : blips) {
            float nx = b.dx() / com.stellar.pmwlite.radar.RadarService.RANGE;
            float nz = b.dz() / com.stellar.pmwlite.radar.RadarService.RANGE;
            int px = cx + (int) (nx * half);
            int py = cy + (int) (nz * half);
            if (px < sx || px > sx + SCOPE || py < sy || py > sy + SCOPE) continue;

            switch (b.kind()) {
                case 0 -> {
                    int col = b.intensity() > 0.66f ? 0xFFFF4A3A
                            : b.intensity() > 0.33f ? 0xFFFF7A3A : 0xFFB06020;
                    int halo = 3 + (int) (b.radius() / 60f);
                    g.fill(px - halo, py - halo, px + halo, py + halo, (col & 0x00FFFFFF) | 0x33000000);
                    g.fill(px - 2, py - 2, px + 2, py + 2, col);
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
                }
                case 1 -> {
                    int col = b.intensity() > 0.6f ? 0xFFFFC24A : 0xFFD89A2A;
                    g.fill(px - 4, py - 4, px + 4, py + 4, (col & 0x00FFFFFF) | 0x22000000);
                    g.fill(px - 2, py - 2, px + 2, py + 2, col);
                }
                default -> {
                    g.fill(px - 2, py - 2, px + 2, py + 2, 0xFF1A3A44);
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF4AD8FF);
                }
            }
        }
    }

    private void drawSweep(GuiGraphics g, int sx, int sy) {
        int cx = sx + SCOPE / 2;
        int cy = sy + SCOPE / 2;
        float phase = (System.currentTimeMillis() % 4000L) / 4000f;
        double ang = phase * Math.PI * 2.0;
        int segs = 26;
        for (int i = 0; i < segs; i++) {
            double r = (SCOPE / 2.0) * (i / (double) segs);
            int px = cx + (int) (Math.cos(ang) * r);
            int py = cy + (int) (Math.sin(ang) * r);
            g.fill(px, py, px + 2, py + 2, 0x5520FF9A);
        }
    }

    // ---------------- info panels ----------------

    private void drawLayerBar(GuiGraphics g, int left, int top) {
        int y = top + PANEL_H - 24;
        int x = left + 6;
        int sel = ClientRadarCache.selectedLayer();
        for (int i = 0; i < AtmosLayerPacket.LAYER_COUNT; i++) {
            String label = (i + 1) + " " + AtmosLayerPacket.NAMES[i];
            boolean on = i == sel;
            int w = this.font.width(label) + 6;
            g.fill(x - 2, y - 2, x + w, y + 10, on ? 0xFF3A6FA0 : 0x55101618);
            g.drawString(this.font, (on ? "§f" : "§8") + label, x, y, 0xFFFFFF, false);
            x += w + 3;
        }
    }

    private void drawInfo(GuiGraphics g, int x, int y, RadarSyncPacket p) {
        String tier = switch (p.alertLevel()) {
            case 4 -> "§c红色预警";
            case 3 -> "§6橙色预警";
            case 2 -> "§e黄色预警";
            case 1 -> "§9蓝色预警";
            default -> "§a无预警";
        };
        g.drawString(this.font, tier, x, y, 0xFFFFFF, false);
        String head = p.headline() == null ? "" : p.headline();
        g.drawString(this.font, "§f" + trim(head, 22), x, y + 12, 0xFFFFFF, false);

        int row = y + 30;
        row = kv(g, x, row, "风险指数", String.format("§f%.2f", p.risk()));
        row = kv(g, x, row, "预计影响", p.etaSeconds() < 0 ? "§8—" : "§f" + p.etaSeconds() + " 秒后");
        row = kv(g, x, row, "预报置信", "§f" + p.confidence() + "%"
                + (p.precise() ? " §b(精报)" : ""));
        row += 3;
        g.drawString(this.font, "§7风暴 §f" + p.stormCount()
                + "   §7天气 §f" + p.eventCount()
                + "   §7设施 §f" + p.assetCount(), x, row, 0xFFFFFF, false);
        row += 11;
        g.drawString(this.font, "§7已加固 §f" + p.reinforcedCount(), x, row, 0xFFFFFF, false);
    }

    /** 大气探针读数：气压/气温/湿度/氧气/体感。 */
    private void drawAtmos(GuiGraphics g, int x, int y) {
        AtmosProbePacket a = ClientRadarCache.probe();
        g.drawString(this.font, "§b本地大气", x, y, 0xFFFFFF, false);
        if (a == null || !a.valid()) {
            g.drawString(this.font, "§8初始化中…", x, y + 12, 0x777777, false);
            return;
        }
        int row = y + 12;
        g.drawString(this.font, String.format("§7气压 §f%.1f §8hPa   §7气温 §f%.1f §8°C",
                a.pressureHpa(), a.tempC()), x, row, 0xFFFFFF, false);
        row += 11;
        g.drawString(this.font, String.format("§7湿度 §f%.0f%%   §7露点 §f%.1f §8°C",
                a.humidity() * 100f, a.dewPointC()), x, row, 0xFFFFFF, false);
        row += 11;
        String o2col = a.oxygen() < 0.145f ? "§c" : (a.oxygen() < 0.19f ? "§e" : "§a");
        g.drawString(this.font, String.format("§7氧气 %s%.1f%% §8| §7风 §f%.1f §8m/s",
                o2col, a.oxygen() * 100f, a.windSpeed()), x, row, 0xFFFFFF, false);
        row += 11;
        float ppm = a.co2() * 1e6f;
        String co2col = ppm > 30000f ? "§c" : (ppm > 10000f ? "§e" : "§a");
        g.drawString(this.font, String.format("§7CO₂ %s%.0f §8ppm  §8| §7体感 §f%.1f §8°C",
                co2col, ppm, a.apparentC()), x, row, 0xFFFFFF, false);
        row += 11;
        g.drawString(this.font, String.format("§7云底 §f%.0f §8m   §7图层 §f%d §8/8",
                a.cloudBaseM(), ClientRadarCache.selectedLayer() + 1), x, row, 0xFFFFFF, false);
    }

    private int kv(GuiGraphics g, int x, int y, String k, String v) {
        g.drawString(this.font, "§7" + k, x, y, 0xAAAAAA, false);
        g.drawString(this.font, v, x + 62, y, 0xFFFFFF, false);
        return y + 11;
    }

    private void drawFooter(GuiGraphics g, int left, int top) {
        g.drawString(this.font, "§8R/ESC 关闭 · 1-8 或 ←→ 切换图层 · 1 秒刷新",
                left + 6, top + PANEL_H - 11, 0x777777, false);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------- procedural ring texture ----------------

    private static synchronized void ensureGrid() {
        if (gridReady) return;
        Minecraft mc = Minecraft.getInstance();
        DynamicTexture tex = null;
        try {
            int size = 128;
            NativeImage img = new NativeImage(size, size, false);
            float c = size / 2f;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = (x + 0.5f) - c;
                    float dy = (y + 0.5f) - c;
                    float r = (float) Math.sqrt(dx * dx + dy * dy) / c;
                    int argb = 0;
                    if (r <= 1.0f) {
                        int base = (int) (Mth.clamp(0.10f - r * 0.05f, 0f, 1f) * 255);
                        argb = 0x88000000 | (base << 16) | ((base + 6) << 8) | (base + 10);
                        for (float ring : new float[]{0.25f, 0.5f, 0.75f, 1.0f}) {
                            if (Math.abs(r - ring) < 0.006f) {
                                argb = ring >= 1f ? 0xCC3C5A6E : 0x66A8D8F0;
                            }
                        }
                        if (Math.abs(dx) < 0.7f || Math.abs(dy) < 0.7f) {
                            argb = 0x44A8D8F0;
                        }
                        double ang = Math.atan2(dy, dx);
                        double deg = Math.toDegrees(ang);
                        if (Math.abs(deg % 30.0) < 0.9 && r > 0.9f && r <= 1.0f) {
                            argb = 0xCC7FE8C0;
                        }
                    }
                    img.setPixelRGBA(x, y, argb);
                }
            }
            tex = new DynamicTexture(img);
            mc.getTextureManager().register(GRID_ID, tex);
            tex.upload();
            gridReady = true;
        } catch (Throwable t) {
            gridReady = false;
            if (tex != null) {
                try {
                    tex.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
