package com.stellar.pmwlite.atmos;

import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.Thermodynamics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 八层全球环流 / Eight-layer global circulation.
 *
 * 本地网格看的是"我家门口这一团空气"，这一层看的是"这颗星球的大气"：
 * 从地面到 16000 m 共 8 层，每层都有气压、温度、湿度、云量、风。
 *
 * 三件事让它不只是个装饰：
 *  1. **地转风**：由气压梯度 + 科氏参数算出各层风。科氏参数随纬度变化，
 *     而 Minecraft 没有纬度——这里把 **z 坐标当纬度**（−80°..80°），
 *     所以南北向移动时风的偏转会真的改变，热带（z≈0）几乎没有偏转，中纬才有。
 *  2. **热成风**：气压场由温度场按测高公式积分得到，于是"暖柱 → 高层高压"自动成立，
 *     上下层风切变随之出现——这就是高空西风急流的来源。
 *  3. **风暴注入**：把每个风暴当成一个暖心低压，按高度加权压进气压场，
 *     于是风暴周围的等压线、辐合、引导气流都是算出来的，而不是写死的。
 *
 * 代价：8×8 列 × 8 层 = 512 个格子，每 20 tick 一步，实测远低于 0.1 ms。
 * 它超前计算并缓存，本地网格与预报都从它取值——对应文章里"预计算 + 逐帧播放"的做法。
 */
public final class GlobalModel {
    public static final int GX = 8, GZ = 8;
    public static final int LAYERS = 8;
    /** 水平格边长（方块）。 */
    public static final int CELL = 512;
    /** 八层的代表高度（米）。 */
    public static final float[] HEIGHTS = {10f, 300f, 900f, 2000f, 4000f, 7000f, 11000f, 16000f};
    public static final String[] LAYER_NAMES = {
            "近地面", "边界层顶", "低层云", "中层(引导气流)", "高层云", "对流层顶", "平流层下部", "平流层上部"
    };

    /**
     * 地转风量级标定。
     *
     * Minecraft 的一个格子是 512 格（方块），而真实的天气系统水平尺度是 1000 km 量级：
     * 直接套 {@code u = −∂p/∂z /(f·ρ)}，同样的气压差摊在 512 m 上会算出 5000 m/s 的"急流"，
     * 全部撞到 70 m/s 的裁剪上限，方向对但毫无层次。
     *
     * 这个系数把量级拉回真实（中层 10~40 m/s、近地面 5~20 m/s），只保留**方向与相对强弱**——
     * 也就是我们要它做的事：给风暴一个合理的引导气流，而不是精确复现地球的自转速度。
     */
    private static final float GEO_SCALE = 0.003f;

    private final float[] p = new float[GX * GZ * LAYERS];
    private final float[] t = new float[GX * GZ * LAYERS];
    private final float[] q = new float[GX * GZ * LAYERS];
    private final float[] cloud = new float[GX * GZ * LAYERS];
    private final float[] u = new float[GX * GZ * LAYERS];
    private final float[] v = new float[GX * GZ * LAYERS];
    private final float[] surfT = new float[GX * GZ];
    private final float[] surfHum = new float[GX * GZ];

    private final ServerLevel level;
    private int originX, originZ;
    private boolean ready;
    private long lastStep;
    private int steps;

    public GlobalModel(ServerLevel level) {
        this.level = level;
    }

    public boolean ready() {
        return ready;
    }

    public int steps() {
        return steps;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    private static int idx(int x, int l, int z) {
        return (l * GZ + z) * GX + x;
    }

    public boolean covers(double x, double z) {
        return x >= originX && x < originX + GX * CELL
                && z >= originZ && z < originZ + GZ * CELL;
    }

    /** 重心移动超过一格就重建（重建只读 64 次生物群系，很便宜）。 */
    public boolean ensureAround(double cx, double cz, long gameTime) {
        int wantX = Mth.floor((cx - GX * CELL * 0.5) / CELL) * CELL;
        int wantZ = Mth.floor((cz - GZ * CELL * 0.5) / CELL) * CELL;
        if (ready && wantX == originX && wantZ == originZ) return false;
        rebuild(wantX, wantZ, gameTime);
        return true;
    }

    private void rebuild(int newX, int newZ, long gameTime) {
        originX = newX;
        originZ = newZ;
        for (int z = 0; z < GZ; z++) {
            for (int x = 0; x < GX; x++) {
                int col = z * GX + x;
                int wx = originX + x * CELL + CELL / 2;
                int wz = originZ + z * CELL + CELL / 2;
                var biome = level.getBiome(new net.minecraft.core.BlockPos(wx, level.getSeaLevel(), wz));
                float biomeTemp = biome.value().getBaseTemperature();
                float hum = Mth.clamp(biome.value().getModifiedClimateSettings().downfall(), 0f, 1f);
                float surf = biomeTemp > 0
                        ? (float) Math.pow(biomeTemp / 1.85, 0.5) * 35f
                        : -(float) Math.pow(-biomeTemp, 1.5) * 60f;
                // 纬度降温：把 z 当纬度，赤道暖、极地冷。
                // 没有这一项，同一种生物群系铺满全场时气压梯度恒为 0，全球风也就恒为 0——
                // 那等于"全球环流模型"只是个空壳。真实大气的第一驱动力就是赤道-极地温差。
                float latDeg = Mth.clamp(wz / 3000f, -1f, 1f) * 80f;
                float latF = Math.abs(latDeg) / 80f;
                surf -= 38f * latF * latF;
                surfT[col] = surf + Thermodynamics.diurnalBonus(gameTime) * 0.5f;
                surfHum[col] = hum;
            }
        }
        recompute(gameTime, null);
        ready = true;
        steps = 0;
    }

    /** 每 20 tick 调一次。 */
    public void step(long gameTime, List<Storm> storms) {
        if (!ready) return;
        if (gameTime - lastStep < 20) return;
        lastStep = gameTime;
        steps++;
        recompute(gameTime, storms);
    }

    private void recompute(long gameTime, List<Storm> storms) {
        // ---- 1) 逐列按测高公式建立温压廓线 ----
        for (int z = 0; z < GZ; z++) {
            for (int x = 0; x < GX; x++) {
                int col = z * GX + x;
                float st = surfT[col];
                for (int l = 0; l < LAYERS; l++) {
                    int i = idx(x, l, z);
                    // 对流层用 6.5 K/km，平流层转为等温（11 km 以上）
                    float h = HEIGHTS[l];
                    float lt = h <= 11000f ? st - 0.0065f * h : (st - 71.5f);
                    t[i] = lt;
                    p[i] = Thermodynamics.pressureAtHeight(h, lt, 0f, 1013.25f);
                    float rh = Mth.clamp(0.78f - h / 16000f * 0.6f, 0.08f, 0.95f)
                            * (0.6f + surfHum[col] * 0.7f);
                    q[i] = ApparentTemperature.saturationMixingRatio(lt, p[i]) * rh;
                    cloud[i] = rh > 0.85f ? (rh - 0.85f) * 4f : 0f;
                    u[i] = 0f;
                    v[i] = 0f;
                }
            }
        }

        // ---- 2) 风暴作为暖心低压注入（按高度加权，近地面最强）----
        if (storms != null) {
            for (Storm s : storms) {
                if (!covers(s.position.x, s.position.z)) continue;
                int cx = Mth.clamp((int) ((s.position.x - originX) / CELL), 0, GX - 1);
                int cz = Mth.clamp((int) ((s.position.z - originZ) / CELL), 0, GZ - 1);
                float strength = Mth.clamp(s.smoothWindspeed / 30f, 0.2f, 1f);
                for (int l = 0; l < LAYERS; l++) {
                    int i = idx(cx, l, cz);
                    float weight = 1f - l / (float) LAYERS * 0.8f;
                    p[i] -= 22f * strength * weight;
                    t[i] += 3.5f * strength * weight;
                    q[i] *= 1f + 0.25f * weight;
                    cloud[i] = Math.min(1f, cloud[i] + 0.35f * strength * weight);
                }
            }
        }

        // ---- 3) 地转风：由气压梯度 + 科氏参数 ----
        // 纬度用 z 映射：z = 0 为赤道，±2400 格约 ±80°
        for (int z = 0; z < GZ; z++) {
            double latDeg = Mth.clamp((originZ + (z + 0.5) * CELL) / 3000.0 * 80.0, -80.0, 80.0);
            float f = 2f * 7.292e-5f * (float) Math.sin(latDeg * Mth.DEG_TO_RAD);
            if (Math.abs(f) < 1.2e-4f) f = Math.copySign(1.2e-4f, f == 0f ? 1f : f);
            for (int x = 0; x < GX; x++) {
                for (int l = 0; l < LAYERS; l++) {
                    int i = idx(x, l, z);
                    int xm = idx(Math.max(x - 1, 0), l, z);
                    int xp = idx(Math.min(x + 1, GX - 1), l, z);
                    int zm = idx(x, l, Math.max(z - 1, 0));
                    int zp = idx(x, l, Math.min(z + 1, GZ - 1));
                    float dpdx = (p[xp] - p[xm]) / (2f * CELL) * 100f;   // Pa/m
                    float dpdz = (p[zp] - p[zm]) / (2f * CELL) * 100f;
                    float rho = 1.2f * (float) Math.exp(-HEIGHTS[l] / 8500f);
                    float ug = -dpdz / (f * rho);
                    float vg = dpdx / (f * rho);
                    // 摩擦层：近地面风偏转并减速（埃克曼螺旋的粗糙版）
                    float friction = l == 0 ? 0.45f : (l == 1 ? 0.7f : 0.92f);
                    u[i] = Mth.clamp(ug * GEO_SCALE * friction, -70f, 70f);
                    v[i] = Mth.clamp(vg * GEO_SCALE * friction, -70f, 70f);
                }
            }
        }
    }

    // ---------------- 查询 ----------------

    /** 第 l 层在 (x,z) 的格子索引；越界返回 -1。 */
    public int indexAt(double wx, double wz, int layer) {
        if (!ready || !covers(wx, wz)) return -1;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, GX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, GZ - 1);
        return idx(x, Mth.clamp(layer, 0, LAYERS - 1), z);
    }

    public float pressureAt(double wx, double wz, int layer) {
        int i = indexAt(wx, wz, layer);
        return i < 0 ? Float.NaN : p[i];
    }

    public float temperatureAt(double wx, double wz, int layer) {
        int i = indexAt(wx, wz, layer);
        return i < 0 ? Float.NaN : t[i];
    }

    public float cloudAt(double wx, double wz, int layer) {
        int i = indexAt(wx, wz, layer);
        return i < 0 ? 0f : cloud[i];
    }

    /** 引导气流：中层（约 2000 m）的平均风，风暴就是被它推着走的。 */
    public boolean steeringWind(double wx, double wz, float[] out) {
        int i = indexAt(wx, wz, 3);
        if (i < 0) return false;
        out[0] = u[i];
        out[1] = v[i];
        return true;
    }

    /**
     * 近地面风（第 0 层）。本地网格用它做动量的背景场，
     * 这样"玩家身边的风"和"推着风暴走的风"来自同一个环流，而不是两套互不相干的噪声。
     */
    public boolean surfaceWind(double wx, double wz, float[] out) {
        int i = indexAt(wx, wz, 0);
        if (i < 0) return false;
        out[0] = u[i];
        out[1] = v[i];
        return true;
    }

    /** 世界坐标处的某层风分量（越界返回 0，方便命令/显示直接调用）。 */
    public float windUAt(double wx, double wz, int layer) {
        int i = indexAt(wx, wz, layer);
        return i < 0 ? 0f : u[i];
    }

    public float windVAt(double wx, double wz, int layer) {
        int i = indexAt(wx, wz, layer);
        return i < 0 ? 0f : v[i];
    }

    /** 一整层切片，用于雷达的全球图层。 */
    public void slice(int layer, float[] out) {
        int l = Mth.clamp(layer, 0, LAYERS - 1);
        for (int z = 0; z < GZ; z++) {
            for (int x = 0; x < GX; x++) {
                out[z * GX + x] = p[idx(x, l, z)];
            }
        }
    }

    public float layerPressure(int x, int l, int z) {
        return p[idx(x, Mth.clamp(l, 0, LAYERS - 1), z)];
    }

    public float layerTemperature(int x, int l, int z) {
        return t[idx(x, Mth.clamp(l, 0, LAYERS - 1), z)];
    }

    public float layerCloud(int x, int l, int z) {
        return cloud[idx(x, Mth.clamp(l, 0, LAYERS - 1), z)];
    }

    public float layerWindU(int x, int l, int z) {
        return u[idx(x, Mth.clamp(l, 0, LAYERS - 1), z)];
    }

    public float layerWindV(int x, int l, int z) {
        return v[idx(x, Mth.clamp(l, 0, LAYERS - 1), z)];
    }

    public float surfaceTemp(int x, int z) {
        return surfT[z * GX + x];
    }
}
