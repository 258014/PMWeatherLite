package com.stellar.pmwlite.atmos;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 地形风 / Terrain-aware wind.
 *
 * 原来的风场是"大气尺度"的：龙卷的 Rankine 涡旋 + FBM 背景风，世界是一块平地。
 * 真实的近地面风会被建筑强烈改写，这篇文章里点出的三种效应这里都做了：
 *
 *  1. **遮蔽 shelter**：上风向有比自己高的东西，风就被挡住（迎风面越近挡得越狠）；
 *  2. **狭管 venturi**：两侧高、来流方向通，风在缝隙里加速（巷子口的风）；
 *  3. **卡门涡街 wake**：背风侧出现交替脱落的涡，表现为横向的周期性摆动。
 *
 * 实现方式是按列（16×16 格一列）查高度图，而不是逐方块做遮挡——代价是分辨率粗，
 * 但每列只要 6~10 次 {@code getHeight}，能放进破坏扫描的预算里。
 * 结果按 (x,z) 缓存 10 tick，缓存上限 4096，超出整体清空（和 ForecastEngine 同策略）。
 */
public final class TerrainWind {

    /** 缓存条目。 */
    private static final class Entry {
        final float shelter;
        final float factor;
        final float gust;
        final float exposure;
        final long tick;

        Entry(float shelter, float factor, float gust, float exposure, long tick) {
            this.shelter = shelter;
            this.factor = factor;
            this.gust = gust;
            this.exposure = exposure;
            this.tick = tick;
        }
    }

    private static final int TTL = 10;
    private static final int MAX_ENTRIES = 4096;
    private static final Map<ServerLevel, Map<Long, Entry>> CACHE = new WeakHashMap<>();

    /** 输出结构，复用以免每次采样都分配。 */
    public static final class Result {
        public float shelter = 1f;
        public float factor = 1f;
        public float gust = 0f;
        public float exposure = 1f;

        void set(float shelter, float factor, float gust, float exposure) {
            this.shelter = shelter;
            this.factor = factor;
            this.gust = gust;
            this.exposure = exposure;
        }
    }

    private TerrainWind() {}

    public static void clear(ServerLevel level) {
        CACHE.remove(level);
    }

    /**
     * 采样某一列的风修正系数。
     *
     * @param y      采样高度（世界坐标）
     * @param windX  来流 x 分量
     * @param windZ  来流 z 分量
     * @param tick   当前游戏时间，用于 TTL 与涡街相位
     */
    public static void sample(ServerLevel level, int x, int z, double y,
                              float windX, float windZ, long tick, Result out) {
        float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
        if (speed < 0.4f) {
            out.set(1f, 1f, 0f, 1f);
            return;
        }

        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Map<Long, Entry> cache = CACHE.computeIfAbsent(level, l -> new HashMap<>());
        Entry e = cache.get(key);
        if (e != null && tick - e.tick <= TTL) {
            // 涡街相位随时间走，所以 gust 不能整体缓存
            float phase = (tick % 120L) / 120f * Mth.TWO_PI;
            out.set(e.shelter, e.factor, e.gust * (float) Math.sin(phase), e.exposure);
            return;
        }
        if (cache.size() > MAX_ENTRIES) cache.clear();

        float dx = windX / speed;
        float dz = windZ / speed;
        int self = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        double above = y - self;

        // ---- 1) 遮蔽：沿来流方向找比自己高的地形 ----
        float shelter = 1f;
        int nearest = -1;
        for (int d = 2; d <= 14; d += 2) {
            int hx = x - Math.round(dx * d);
            int hz = z - Math.round(dz * d);
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hx, hz);
            if (h > y + 0.8 && nearest < 0) {
                nearest = d;
            }
        }
        if (nearest > 0) {
            // 越近挡得越狠，最狠到 0.15
            shelter = Mth.clamp(0.15f + nearest / 14f * 0.75f, 0.15f, 0.95f);
        }

        // ---- 2) 狭管：两侧高、来流方向通 ----
        float px = -dz;
        float pz = dx;
        int leftH = 0;
        int rightH = 0;
        for (int d = 2; d <= 6; d += 2) {
            int lx = x + Math.round(px * d);
            int lz = z + Math.round(pz * d);
            int rx = x - Math.round(px * d);
            int rz = z - Math.round(pz * d);
            leftH = Math.max(leftH, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz));
            rightH = Math.max(rightH, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz));
        }
        boolean channel = leftH > y + 1.5 && rightH > y + 1.5 && nearest < 0;
        float venturi = channel ? 1.45f : 1f;

        // ---- 3) 卡门涡街：背风侧交替脱落的涡 ----
        float gustAmp = 0f;
        if (nearest > 0 && nearest <= 8) {
            gustAmp = 0.28f * (1f - nearest / 10f);
        }

        // ---- 4) 暴露度：站在屋顶/高处风更大 ----
        float exposure = above > 2.0 ? 1.18f : (above > 0.5 ? 1.06f : 0.92f);

        float factor = shelter * venturi * exposure;
        out.set(shelter, factor, gustAmp, exposure);
        cache.put(key, new Entry(shelter, factor, gustAmp, exposure, tick));
    }
}
