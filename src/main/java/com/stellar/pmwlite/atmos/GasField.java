package com.stellar.pmwlite.atmos;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 多气体列场 / Multi-gas column field.
 *
 * CO₂ 住在 {@link AtmosphereGrid} 的三维体场里（它要参与云/浮力/健康判定）。
 * 其余机器废气用**二维列场**承载：SO₂ / NO₂ / CO / 颗粒物 PM。理由有三：
 *  1. 排放源在地表，光学现象关心的是"整列积分浓度"，竖直分层没有意义；
 *  2. 列场 4×16×16=1024 格，双缓冲 8 KB，二维平流每步只 1024 次采样——
 *     加进来之后本地场单步仍远低于 1500 µs 预算；
 *  3. 平流仍用同一套半拉格朗日回溯，只是退化到 (u,v) 两个分量，物理图像一致。
 *
 * 列场与体场共享地表高度（{@code surfaceY}）和近地面风，因此 CO₂ 羽流与 SO₂/PM 羽流
 * 会沿同一条风路走——不会出现"两种污染各飘各的"这种穿帮。
 *
 * 背景值取对流层清洁空气的近地层含量（数量级），上限取"能看到明显健康/光学后果"的浓度。
 * PM 不是体积分数而是 0..1 的浑浊度指数（无量纲）。
 */
public final class GasField {
    /** 气体下标。 */
    public static final int SO2 = 0, NO2 = 1, CO = 2, PM = 3, COUNT = 4;
    /** 中文名 / 键（ radar、KubeJS 兼容层用）。 */
    public static final String[] KEYS = {"so2", "no2", "co", "pm"};
    public static final String[] NAMES = {"二氧化硫", "二氧化氮", "一氧化碳", "颗粒物"};
    /** 背景体积分数：SO₂≈0.5 ppb、NO₂≈0.02 ppb、CO≈0.1 ppm、PM=0。 */
    public static final float[] BACKGROUND = {
            5.0e-7f, 2.0e-8f, 1.0e-4f, 0.0f
    };
    /** 浓度上限（体积分数 / PM 指数）——clampFields 用。 */
    public static final float[] MAX = {
            2.0e-3f, 1.0e-3f, 1.0e-2f, 1.0f
    };
    /** 每步滚动扫描的列数（8 步扫完 256 列）。 */
    private static final int SCAN_PER_STEP = 32;

    private final ServerLevel level;
    private final AtmosphereGrid grid;
    private final int sx = AtmosphereGrid.SX, sz = AtmosphereGrid.SZ;
    private final int cols = sx * sz;
    /** 双缓冲列场：[gas][col]。 */
    private final float[][] c = new float[COUNT][];
    private final float[][] cN = new float[COUNT][];
    /** 每种气体的列源强度 0..1。 */
    private final float[][] src = new float[COUNT][];
    private int cursor = 0;
    private boolean enabled = true;

    public GasField(ServerLevel level, AtmosphereGrid grid) {
        this.level = level;
        this.grid = grid;
        for (int g = 0; g < COUNT; g++) {
            c[g] = new float[cols];
            cN[g] = new float[cols];
            src[g] = new float[cols];
        }
    }

    /** 总开关：关闭后不再检测排放、平流，读数维持背景。 */
    public void configure(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            for (int g = 0; g < COUNT; g++) {
                java.util.Arrays.fill(c[g], BACKGROUND[g]);
                java.util.Arrays.fill(src[g], 0f);
            }
        }
    }

    /** 网格重定位时重建：整场回到背景，源清零。 */
    public void reset() {
        for (int g = 0; g < COUNT; g++) {
            java.util.Arrays.fill(c[g], BACKGROUND[g]);
            java.util.Arrays.fill(cN[g], BACKGROUND[g]);
            java.util.Arrays.fill(src[g], 0f);
        }
        cursor = 0;
    }

    /** 网格外返回 -1，否则返回列号。 */
    private int column(double wx, double wz) {
        if (!grid.ready() || !grid.covers(wx, wz)) return -1;
        int x = Mth.clamp((int) ((wx - grid.originX()) / AtmosphereGrid.CELL), 0, sx - 1);
        int z = Mth.clamp((int) ((wz - grid.originZ()) / AtmosphereGrid.CELL), 0, sz - 1);
        return z * sx + x;
    }

    /**
     * 兼容层入口：在世界坐标处注入一种气体（KubeJS / 其他 mod 调用）。
     * gas 用 {@link #SO2} 等常量；strength 0..1，取列内最大值，下一 step 生效。
     */
    public void inject(double wx, double wz, int gas, float strength) {
        if (!enabled || gas < 0 || gas >= COUNT) return;
        int col = column(wx, wz);
        if (col < 0) return;
        src[gas][col] = Math.max(src[gas][col], Mth.clamp(strength, 0f, 1f));
    }

    /** 便捷重载：兼容层用键名调用，避免依赖常量下标。 */
    public void inject(double wx, double wz, String key, float strength) {
        for (int g = 0; g < COUNT; g++) {
            if (KEYS[g].equals(key)) {
                inject(wx, wz, g, strength);
                return;
            }
        }
    }

    /** 读取某列某气体的浓度。 */
    public float column(int gas, int col) {
        if (gas < 0 || gas >= COUNT || col < 0 || col >= cols) return BACKGROUND[Math.max(0, gas)];
        return c[gas][col];
    }

    /** 读取世界坐标处的浓度（网格外回背景）。 */
    public float at(double wx, double wz, int gas) {
        if (gas < 0 || gas >= COUNT) return 0f;
        int col = column(wx, wz);
        return col < 0 ? BACKGROUND[gas] : c[gas][col];
    }

    /**
     * 推进一步。由 {@link AtmosphereGrid#step(long, net.minecraft.world.phys.Vec3, float)}
     * 在平流之后调用——此时最新鲜的风场已经就绪。
     */
    public void step(long gameTime) {
        if (!enabled || !grid.ready()) return;
        scanSources();
        applySources();
        advect2D();
        clampFields();
    }

    /** 滚动扫描地表方块，把机器/火山排放平滑写入 src。 */
    private void scanSources() {
        for (int k = 0; k < SCAN_PER_STEP; k++) {
            int col = cursor++ % cols;
            int x = col % sx, z = col / sx;
            int wx = grid.originX() + x * AtmosphereGrid.CELL + AtmosphereGrid.CELL / 2;
            int wz = grid.originZ() + z * AtmosphereGrid.CELL + AtmosphereGrid.CELL / 2;
            int wy = grid.surfaceAt(x, z);
            float so2 = 0f, no2 = 0f, co = 0f, pm = 0f;
            for (int dy = -1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(wx, wy + dy, wz);
                BlockState st = level.getBlockState(pos);
                // 岩浆/岩浆块/灵魂火：含硫挥发分 → SO₂；高温 → 少量 NO₂
                if (st.is(Blocks.LAVA) || st.is(Blocks.MAGMA_BLOCK)) {
                    so2 = Math.max(so2, 0.9f);
                    no2 = Math.max(no2, 0.25f);
                } else if (st.is(Blocks.SOUL_FIRE) || st.is(Blocks.SOUL_CAMPFIRE)) {
                    so2 = Math.max(so2, 0.6f);
                    co = Math.max(co, 0.5f);
                } else if (st.is(Blocks.FIRE) || st.is(Blocks.CAMPFIRE)) {
                    co = Math.max(co, 0.7f);           // 不完全燃烧 → CO
                    pm = Math.max(pm, 0.4f);           // 烟尘
                } else if (st.is(Blocks.TNT)) {
                    no2 = Math.max(no2, 0.8f);         // 爆炸高温固氮（引燃瞬间）
                }
                // 燃烧中的机器：按 LIT 属性自动接入（Create / GTCEu 同样带这个属性）。
                // 机器以粉尘与 NOx 为主；含硫矿冶炼（高炉）额外给 SO₂。
                if (st.hasProperty(BlockStateProperties.LIT)
                        && Boolean.TRUE.equals(st.getValue(BlockStateProperties.LIT))) {
                    boolean blast = st.is(Blocks.BLAST_FURNACE)
                            || st.is(Blocks.SMOKER) || st.is(Blocks.FURNACE);
                    no2 = Math.max(no2, blast ? 0.5f : 0.35f);
                    pm = Math.max(pm, blast ? 0.7f : 0.45f);
                    co = Math.max(co, 0.3f);
                    if (blast) so2 = Math.max(so2, 0.4f);
                }
            }
            // 平滑，避免火焰闪动导致浓度跳变
            src[SO2][col] = Mth.lerp(0.30f, src[SO2][col], Math.max(src[SO2][col] * 0.7f, so2));
            src[NO2][col] = Mth.lerp(0.30f, src[NO2][col], Math.max(src[NO2][col] * 0.7f, no2));
            src[CO][col]  = Mth.lerp(0.30f, src[CO][col],  Math.max(src[CO][col]  * 0.7f, co));
            src[PM][col]  = Mth.lerp(0.30f, src[PM][col],  Math.max(src[PM][col]  * 0.7f, pm));
        }
    }

    /**
     * 把源强度写进浓度，并让边界层缓慢向背景通风。
     *
     * 列场表达的是"地表边界层"，头顶就是自由大气，因此所有列都持续交换——
     * 通风时间常数取 240 s，足够让满负荷排放形成稳定羽流（平衡浓度接近上限），
     * 又保证源移除后几分钟内回落。源项系数按各自的背景/上限标定：
     * 满负荷下 SO₂≈1.6e-3、NO₂≈9e-4、CO≈2.5e-2、PM 羽流峰值≈0.1~0.3，均不顶格。
     * PM 用较大系数是因为二维平流会把单点源迅速摊薄到整列；系数太小会导致
     * 浑浊度低到肉眼不可见（自检也抓到了这一点：初版 peak 仅 0.02）。
     */
    private void applySources() {
        final float[] RATE = {0.001f, 0.001f, 0.02f, 3.5f};
        float vent = 1f - (float) Math.exp(-6.75f / 240f);
        for (int col = 0; col < cols; col++) {
            for (int g = 0; g < COUNT; g++) {
                float s = src[g][col];
                if (s > 0.01f) c[g][col] += s * RATE[g] * 0.05f;
                c[g][col] += (BACKGROUND[g] - c[g][col]) * vent;
            }
        }
    }

    /** 二维半拉格朗日平流：从上游 (x−u·dt/CELL, z−v·dt/CELL) 三线性取样。 */
    private void advect2D() {
        float dt = 6.75f;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int col = z * sx + x;
                // 用近地面 2 层的水平风平均，代表边界层输送
                int y = Math.min(1, AtmosphereGrid.SY - 1);
                float u = grid.windU(x, y, z);
                float v = grid.windV(x, y, z);
                double gx = x - u * dt / AtmosphereGrid.CELL;
                double gz = z - v * dt / AtmosphereGrid.CELL;
                for (int g = 0; g < COUNT; g++) {
                    cN[g][col] = Math.max(BACKGROUND[g],
                            sample2D(c[g], gx, gz, c[g][col]));
                }
            }
        }
        for (int g = 0; g < COUNT; g++) {
            System.arraycopy(cN[g], 0, c[g], 0, cols);
        }
    }

    /** 双线性采样，越界按最近格（开放边界）。 */
    private float sample2D(float[] a, double gx, double gz, float fallback) {
        if (gx < -1 || gx > sx || gz < -1 || gz > sz) return fallback;
        int x0 = Mth.clamp((int) Math.floor(gx), 0, sx - 1);
        int z0 = Mth.clamp((int) Math.floor(gz), 0, sz - 1);
        int x1 = Math.min(x0 + 1, sx - 1);
        int z1 = Math.min(z0 + 1, sz - 1);
        float fx = (float) (gx - x0), fz = (float) (gz - z0);
        float a00 = a[z0 * sx + x0], a10 = a[z0 * sx + x1];
        float a01 = a[z1 * sx + x0], a11 = a[z1 * sx + x1];
        return Mth.lerp(fz, Mth.lerp(fx, a00, a10), Mth.lerp(fx, a01, a11));
    }

    private void clampFields() {
        for (int g = 0; g < COUNT; g++) {
            for (int i = 0; i < cols; i++) {
                c[g][i] = Mth.clamp(c[g][i], BACKGROUND[g], MAX[g]);
            }
        }
    }
}
