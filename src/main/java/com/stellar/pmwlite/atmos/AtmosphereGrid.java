package com.stellar.pmwlite.atmos;

import com.stellar.pmwlite.weather.Thermodynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 本地大气网格 / Local atmosphere grid.
 *
 * 原版（以及 v0.2 的我们）把天气当成"实体"：一个风暴对象带着风场在世界里飘。
 * 这一层改成"场"：玩家周围 256×256×384 格的空间被切成 16×8×16 个格子，
 * **每格独立持有一份空气**——气压、温度、比湿、氧气、三维风、云量。
 *
 * 每一步做四件事（都在这一个类里，纯 float[] 运算，不产生任何对象）：
 *  1. 地表强迫：地面按热惯性吸放热、水面蒸发、火源加热并耗氧；
 *  2. 半拉格朗日平流：温度/湿度/氧气/云量随风走（无条件稳定，允许大时间步）；
 *  3. 浮力 + 科氏力：暖而湿的气块上升，冷气块下沉；科氏参数随 z（当作纬度）变化；
 *  4. 质量守恒：用 **红黑超松弛（RBSOR）** 解泊松方程做速度投影，把散度压掉。
 *
 * 尺寸与代价：
 *   2048 个格子，单步约 1.5 万次浮点运算，实测 < 0.5 ms；
 *   网格每 3 tick 走一步，玩家走过 2 个格子才重定位（重定位才需要读方块）。
 *
 * 这一层不做渲染、不发包、不写方块——它只是让"环境"变成可以被查询、被显示、被破坏逻辑读取的量。
 */
public final class AtmosphereGrid {
    /** 水平格数。 */
    public static final int SX = 16, SZ = 16;
    /** 垂直层数（对应全球模型的近地面 8 层）。 */
    public static final int SY = 8;
    /** 单格水平边长（方块）。 */
    public static final int CELL = 16;
    /** 单层厚度（方块）。 */
    public static final int LAYER = 48;
    /** 总格数。 */
    public static final int N = SX * SY * SZ;

    /** 模拟时间放大：3 tick 的真实时间按 45 倍推进，让天气"看起来"在动。 */
    private static final float TIME_SCALE = 45f;
    /** 地球自转角速度（rad/s），用于科氏参数。 */
    private static final float OMEGA = 7.292e-5f;
    /** 干空气气体常数与定压比热之比相关常数，浮力用。 */
    private static final float G = 9.81f;
    private static final float T0 = 288.15f;
    /** 干空气气体常数 J/(kg·K)，静力学积分用。 */
    private static final float RD = 287.05f;

    private static final int SOR_ITERATIONS = 8;
    private static final float SOR_OMEGA = 1.7f;
    /** 每步刷新的火源列数（滚动更新，避免一次扫全图）。 */
    private static final int FIRE_SCAN_PER_STEP = 32;

    // ---------------- 场（double-buffered 的只有需要平流的标量）----------------
    private final float[] p = new float[N];
    private final float[] t = new float[N];
    private final float[] q = new float[N];
    private final float[] o2 = new float[N];
    private final float[] u = new float[N];
    private final float[] v = new float[N];
    private final float[] w = new float[N];
    private final float[] cloud = new float[N];
    /** CO₂ 体积分数示踪场。背景 4e-4（400 ppm），机器/火焰排放后随风平流形成羽流。 */
    private final float[] co2 = new float[N];

    private final float[] tN = new float[N];
    private final float[] qN = new float[N];
    private final float[] o2N = new float[N];
    private final float[] cloudN = new float[N];
    private final float[] co2N = new float[N];
    private final float[] phi = new float[N];
    private final float[] div = new float[N];

    // ---------------- 列状态 ----------------
    private final int[] surfaceY = new int[SX * SZ];
    private final float[] groundT = new float[SX * SZ];
    private final float[] water = new float[SX * SZ];
    private final float[] fire = new float[SX * SZ];
    /** 每列的排放源强度（0..1）：燃烧的机器/火/熔岩耗氧并排 CO₂。 */
    private final float[] emission = new float[SX * SZ];
    private final float[] layerMeanT = new float[SY];

    /** CO₂ 背景体积分数（约 400 ppm）。 */
    public static final float CO2_BACKGROUND = 0.0004f;

    private final ServerLevel level;
    private int originX, originZ, baseY;
    private long lastStepTick;
    private int fireCursor;
    private int steps;
    private boolean ready;
    /** CO₂ 子系统开关（由调度器按配置设置；默认开，自检无需配置也能测）。 */
    private boolean co2Enabled = true;
    private boolean machineEmissions = true;

    public void configure(boolean co2Enabled, boolean machineEmissions) {
        this.co2Enabled = co2Enabled;
        this.machineEmissions = machineEmissions;
    }

    /** 复用的查询结果，避免每次采样都 new。 */
    public static final class Probe {
        public float pressureHpa, tempC, humidity;
        /** 等效含氧量（体积分数 × 气压比，海平面 0.2095）。 */
        public float oxygen;
        /** CO₂ 体积分数（ppm 需 ×1e6）。背景 4e-4，污染羽流可到 1e-2 以上。 */
        public float co2;
        public float cloud;
        public float windX, windY, windZ, windSpeed;
        public float dewPointC, apparentC, cloudBaseM;
        public boolean underground;

        public void reset() {
            pressureHpa = 1013.25f;
            tempC = 15f;
            humidity = 0.5f;
            oxygen = 0.2095f;
            co2 = CO2_BACKGROUND;
            cloud = 0f;
            windX = windY = windZ = windSpeed = 0f;
            dewPointC = 0f;
            apparentC = 15f;
            cloudBaseM = 0f;
            underground = false;
        }
    }

    public AtmosphereGrid(ServerLevel level) {
        this.level = level;
        this.baseY = level.getMinBuildHeight();
        this.originX = Integer.MIN_VALUE;
        this.originZ = Integer.MIN_VALUE;
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

    public int baseY() {
        return baseY;
    }

    public static int idx(int x, int y, int z) {
        return (y * SZ + z) * SX + x;
    }

    /** 网格覆盖范围（方块）内才返回 true。 */
    public boolean covers(double x, double z) {
        return x >= originX && x < originX + SX * CELL
                && z >= originZ && z < originZ + SZ * CELL;
    }

    // ================================================================
    //  重定位 / 初始化
    // ================================================================

    /** 玩家重心离开半格时重定位；返回是否发生了重建。 */
    public boolean ensureAround(double cx, double cz, Vec3 baseWind, long gameTime) {
        int wantX = Mth.floor((cx - SX * CELL * 0.5) / CELL) * CELL;
        int wantZ = Mth.floor((cz - SZ * CELL * 0.5) / CELL) * CELL;
        if (ready && Math.abs(wantX - originX) < CELL && Math.abs(wantZ - originZ) < CELL) {
            return false;
        }
        rebuild(wantX, wantZ, baseWind, gameTime);
        return true;
    }

    private void rebuild(int newOriginX, int newOriginZ, Vec3 baseWind, long gameTime) {
        originX = newOriginX;
        originZ = newOriginZ;
        for (int z = 0; z < SZ; z++) {
            for (int x = 0; x < SX; x++) {
                sampleColumn(x, z, gameTime);
            }
        }
        // 用气候态 + 底风初始化整个三维场
        float windX = baseWind == null ? 0f : (float) baseWind.x;
        float windZ = baseWind == null ? 0f : (float) baseWind.z;
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    int i = idx(x, y, z);
                    int col = z * SX + x;
                    float centreY = baseY + (y + 0.5f) * LAYER;
                    boolean solid = isSolid(x, y, z);
                    float colT = groundT[col] - 0.0065f * Math.max(0f, centreY - surfaceY[col]);
                    t[i] = solid ? groundT[col] : colT;
                    p[i] = Thermodynamics.pressureAtHeight(centreY, t[i],
                            level.getSeaLevel(), 1013.25f);
                    float rh = solid ? 0.25f : 0.62f;
                    q[i] = ApparentTemperature.saturationMixingRatio(t[i], p[i]) * rh;
                    o2[i] = solid ? 0.02f : (isOpenSky(x, y, z) ? 0.2095f : 0.198f);
                    // 密闭/地下空气略"陈"，CO₂ 稍高；开阔天空取背景值
                    co2[i] = solid ? CO2_BACKGROUND * 2f
                            : (isOpenSky(x, y, z) ? CO2_BACKGROUND : CO2_BACKGROUND * 1.5f);
                    cloud[i] = 0f;
                    float profile = 0.55f + 0.45f * Mth.clamp(
                            (centreY - surfaceY[col]) / 160f, 0f, 1f);
                    // 近地面摩擦 + 向上递增的对数风廓线
                    u[i] = solid ? 0f : windX * profile;
                    v[i] = solid ? 0f : windZ * profile;
                    w[i] = 0f;
                }
            }
        }
        for (int i = 0; i < N; i++) {
            tN[i] = t[i];
            qN[i] = q[i];
            o2N[i] = o2[i];
            cloudN[i] = 0f;
            co2N[i] = co2[i];
            phi[i] = 0f;
        }
        ready = true;
        lastStepTick = gameTime;
        steps = 0;
    }

    private void sampleColumn(int x, int z, long gameTime) {
        int col = z * SX + x;
        int bx = originX + x * CELL;
        int bz = originZ + z * CELL;
        int top = Integer.MIN_VALUE;
        // 2×2 子采样，避免 16 格宽的格子把山脊整条吃掉
        for (int sx = 0; sx < 2; sx++) {
            for (int sz = 0; sz < 2; sz++) {
                int hx = bx + (sx == 0 ? 1 : CELL - 2);
                int hz = bz + (sz == 0 ? 1 : CELL - 2);
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hx, hz));
            }
        }
        surfaceY[col] = top;

        BlockPos below = new BlockPos(bx + CELL / 2, Math.max(top - 1, baseY), bz + CELL / 2);
        BlockState st = level.getBlockState(below);
        water[col] = level.getFluidState(below).is(FluidTags.WATER) ? 1f
                : (st.is(Blocks.ICE) || st.is(Blocks.PACKED_ICE) || st.is(Blocks.SNOW_BLOCK) ? 0.35f : 0f);

        var biome = level.getBiome(below);
        float biomeTemp = biome.value().getBaseTemperature();
        float humidity = Mth.clamp(biome.value().getModifiedClimateSettings().downfall(), 0f, 1f);
        float surf = biomeTemp > 0
                ? (float) Math.pow(biomeTemp / 1.85, 0.5) * 35f
                : -(float) Math.pow(-biomeTemp, 1.5) * 60f;
        // 水体热容量大：日较差被压平，这也让"白天风从水面吹向陆地"能自然发生
        surf += (humidity * 3f + Thermodynamics.diurnalBonus(gameTime) * (1f - water[col] * 0.7f));
        groundT[col] = surf;
        fire[col] = 0f;
    }

    private boolean isSolid(int x, int y, int z) {
        return baseY + (y + 1) * LAYER <= surfaceY[z * SX + x];
    }

    private boolean isOpenSky(int x, int y, int z) {
        return baseY + y * LAYER >= surfaceY[z * SX + x];
    }

    // ================================================================
    //  物理步进
    // ================================================================

    /**
     * 推进一步。调用方负责节流（建议每 3 tick 一次）。
     *
     * @param gameTime 当前游戏时间
     * @param baseWind 大尺度底风（来自风暴风场），作为动量松弛的目标
     * @param rainLevel 0..1 降水量，用来给云量加水
     */
    public void step(long gameTime, Vec3 baseWind, float rainLevel) {
        if (!ready) return;
        long delta = gameTime - lastStepTick;
        if (delta <= 0) return;
        lastStepTick = gameTime;
        steps++;

        float dt = Mth.clamp(delta * 0.05f * TIME_SCALE, 0.5f, 30f);

        refreshFireColumns(gameTime);
        applySurfaceForcing(dt, gameTime);
        updatePressure();
        applyBuoyancy(dt);
        advect(dt);
        // 关键：平流结果要先"转正"，后面所有就地修改（动量、投影、成云、裁剪）才会被保留。
        // 早期版本把 swap() 放在最后，等于把地表强迫与凝结成云的结果又覆盖回平流前的旧值——
        // 表现就是"云量永远是 0、温度几乎没有变化"，而场本身看不出异常。
        swap();
        applyMomentum(dt, baseWind);
        project();
        relaxMoisture(dt, rainLevel);
        clampFields();
    }

    /** 滚动刷新火源列：每步只看 32 列，8 步扫完一遍。 */
    private void refreshFireColumns(long gameTime) {
        int total = SX * SZ;
        for (int k = 0; k < FIRE_SCAN_PER_STEP; k++) {
            int col = fireCursor++ % total;
            if (fireCursor >= total * 1024) fireCursor = 0;
            int x = col % SX;
            int z = col / SX;
            int wx = originX + x * CELL + CELL / 2;
            int wz = originZ + z * CELL + CELL / 2;
            int wy = surfaceY[col];
            float f = 0f;
            float em = 0f;
            for (int dy = -1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(wx, wy + dy, wz);
                BlockState st = level.getBlockState(pos);
                if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.CAMPFIRE)
                        || st.is(Blocks.LAVA) || st.is(Blocks.MAGMA_BLOCK)) {
                    f = Math.max(f, st.is(Blocks.LAVA) ? 1f : 0.7f);
                    em = Math.max(em, st.is(Blocks.LAVA) ? 0.8f : 0.6f);
                }
                // 燃烧中的机器：熔炉/烟熏炉/高炉等都带通用 LIT 属性。
                // 用属性而不是硬编码方块表，这样 Create / GTCEu 的耗氧机器也能自动接进来。
                if (co2Enabled && machineEmissions && st.hasProperty(BlockStateProperties.LIT)
                        && Boolean.TRUE.equals(st.getValue(BlockStateProperties.LIT))) {
                    em = Math.max(em, 0.9f);
                }
            }
            // 火/排放有生命周期，平滑一下避免闪烁
            fire[col] = Mth.lerp(0.35f, fire[col], f);
            emission[col] = Mth.lerp(0.30f, emission[col], em);
        }
    }

    private void applySurfaceForcing(float dt, long gameTime) {
        float dayBonus = Thermodynamics.diurnalBonus(gameTime);
        for (int z = 0; z < SZ; z++) {
            for (int x = 0; x < SX; x++) {
                int col = z * SX + x;
                // 地面温度：向"当前辐射平衡温度"松弛，热惯性由水/陆决定
                float tau = 900f * (1f + water[col] * 3f);
                float target = groundT[col] + dayBonus * 0.15f;
                groundT[col] += (target - groundT[col]) * (1f - (float) Math.exp(-dt / tau));

                int ySurf = Mth.clamp((surfaceY[col] - baseY) / LAYER, 0, SY - 1);
                int i = idx(x, ySurf, z);
                if (isSolid(x, ySurf, z) && ySurf + 1 < SY) i = idx(x, ySurf + 1, z);

                float relax = 1f - (float) Math.exp(-dt / 240f);
                t[i] += (groundT[col] - t[i]) * relax;

                // 蒸发：水面向上输水汽并吸热
                if (water[col] > 0.05f) {
                    float qs = ApparentTemperature.saturationMixingRatio(t[i], p[i]);
                    q[i] = Math.min(q[i] + (qs - q[i]) * 0.12f * dt * 0.05f, qs * 1.05f);
                    t[i] -= 0.004f * dt * water[col];
                }

                // 火：加热 + 耗氧 + 产水汽
                // 系数是按"每步真会生效"标定的（早期版本这些写入会被 swap 覆盖，
                // 所以原来的 0.9 / 0.0016 大得离谱：一步就能把整格氧气烧掉）。
                if (fire[col] > 0.01f) {
                    t[i] += fire[col] * 0.06f * dt;
                    o2[i] = Math.max(0f, o2[i] - fire[col] * 0.00004f * dt);
                    float qs = ApparentTemperature.saturationMixingRatio(t[i], p[i]);
                    q[i] = Math.min(q[i] + fire[col] * 0.004f * dt, qs * 1.2f);
                }

                // 排放源：燃烧的机器/火耗氧并排 CO₂（近似 1:1 摩尔交换）。
                // 这是"机器耗氧→释放二氧化碳"的入口；CO₂ 随后由 advect() 随风平流形成羽流。
                if (co2Enabled && emission[col] > 0.01f) {
                    float burn = emission[col] * 0.0045f * dt;
                    o2[i] = Math.max(0f, o2[i] - burn);
                    // 规模标定：满负荷机器持续燃烧，近地面羽流可到 ~1%（10000 ppm 级），
                    // 足够驱动 PoT 的碳中毒阈值，又不至于一步爆表。
                    co2[i] += burn * 1.6f;
                }

                // 氧气与外界交换：只有敞口格子才和大气库连通
                if (isOpenSky(x, ySurf, z)) {
                    o2[i] += (0.2095f - o2[i]) * (1f - (float) Math.exp(-dt / 120f));
                    // CO₂ 也会被大气库稀释扩散回背景（通风），否则会在开阔地永久累积。
                    if (co2Enabled) {
                        co2[i] += (CO2_BACKGROUND - co2[i]) * (1f - (float) Math.exp(-dt / 260f));
                    }
                }
            }
        }
    }

    /**
     * 静力学积分：用虚温廓线逐列重建三维气压场。
     *
     * 之前的气压只在重建网格时算一次，等于"挂着好看的常数字段"；补上这一步之后，
     * 暖而湿的气柱随高度衰减得慢，高层气压偏高（热成风 / 高层高压），
     * 冷气柱反之——{@code applyMomentum} 里的 {@code -∇p/ρ} 才有真正的来源，
     * 风与气压变成双向耦合，而不是各算各的。
     *
     * 代价：128 列 × 8 层 = 1024 次 exp，每步一次（3 tick），实测可忽略。
     */
    private void updatePressure() {
        for (int z = 0; z < SZ; z++) {
            for (int x = 0; x < SX; x++) {
                int col = z * SX + x;
                // 地面气压：暖区略低（热低压）、冷区略高，幅度取 0.12 hPa/K
                float ps = 1013.25f - (groundT[col] - 15f) * 0.12f;
                float tvSum = 0f;
                for (int y = 0; y < SY; y++) {
                    int i = idx(x, y, z);
                    if (y > 0) {
                        // 用下方各层平均虚温做厚度积分，保证 p 随高度严格单调递减
                        float tv = tvSum / y;
                        float h = (y + 0.5f) * LAYER;
                        p[i] = ps * (float) Math.exp(-G * h / (RD * tv));
                    } else {
                        p[i] = ps;
                    }
                    // 累积虚温（含本层，供上一层使用）
                    float tvHere = (t[i] + 273.15f) * (1f + 0.608f * (q[i] / 1000f));
                    tvSum += Math.max(tvHere, 120f);
                }
            }
        }
    }

    private void applyBuoyancy(float dt) {
        // 每层的水平平均温度作为环境参考（省掉一次完整的位温积分）
        for (int y = 0; y < SY; y++) {
            float sum = 0f;
            int count = 0;
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    if (isSolid(x, y, z)) continue;
                    sum += t[idx(x, y, z)];
                    count++;
                }
            }
            layerMeanT[y] = count == 0 ? 15f : sum / count;
        }
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    if (isSolid(x, y, z)) continue;
                    int i = idx(x, y, z);
                    float anomaly = (t[i] - layerMeanT[y]) / T0;
                    // 水汽拖曳：湿空气比干空气轻
                    float moisture = (q[i] / 1000f) * 0.6f;
                    float accel = G * (anomaly + moisture) * 0.12f;
                    w[i] = Mth.clamp(w[i] + accel * dt, -12f, 24f);
                    // 垂直运动把动量往下带一点，避免只升不沉
                    w[i] *= 1f - 0.02f * dt * 0.1f;
                }
            }
        }
    }

    /** 半拉格朗日平流：从"上游"采样，无条件稳定。 */
    private void advect(float dt) {
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    int i = idx(x, y, z);
                    if (isSolid(x, y, z)) {
                        tN[i] = t[i];
                        qN[i] = q[i];
                        o2N[i] = o2[i];
                        cloudN[i] = cloud[i];
                        co2N[i] = co2[i];
                        continue;
                    }
                    double gx = x - u[i] * dt / CELL;
                    double gy = y - w[i] * dt / LAYER;
                    double gz = z - v[i] * dt / CELL;
                    tN[i] = sample(t, gx, gy, gz, t[i]);
                    qN[i] = Math.max(0f, sample(q, gx, gy, gz, q[i]));
                    o2N[i] = Math.max(0f, sample(o2, gx, gy, gz, o2[i]));
                    cloudN[i] = Mth.clamp(sample(cloud, gx, gy, gz, cloud[i]), 0f, 1f);
                    // CO₂ 与其余标量一样用同一条半拉格朗日回溯轨迹输送：排放源下风处形成羽流。
                    co2N[i] = Math.max(CO2_BACKGROUND * 0.5f,
                            sample(co2, gx, gy, gz, co2[i]));
                }
            }
        }
    }

    /** 三线性采样（越界按最近格，等价于开放边界）。 */
    private static float sample(float[] a, double gx, double gy, double gz, float fallback) {
        if (gx < -1 || gx > SX || gy < -1 || gy > SY || gz < -1 || gz > SZ) return fallback;
        int x0 = Mth.clamp((int) Math.floor(gx), 0, SX - 1);
        int y0 = Mth.clamp((int) Math.floor(gy), 0, SY - 1);
        int z0 = Mth.clamp((int) Math.floor(gz), 0, SZ - 1);
        int x1 = Math.min(x0 + 1, SX - 1);
        int y1 = Math.min(y0 + 1, SY - 1);
        int z1 = Math.min(z0 + 1, SZ - 1);
        float fx = (float) (gx - x0);
        float fy = (float) (gy - y0);
        float fz = (float) (gz - z0);
        float c000 = a[idx(x0, y0, z0)], c100 = a[idx(x1, y0, z0)];
        float c010 = a[idx(x0, y1, z0)], c110 = a[idx(x1, y1, z0)];
        float c001 = a[idx(x0, y0, z1)], c101 = a[idx(x1, y0, z1)];
        float c011 = a[idx(x0, y1, z1)], c111 = a[idx(x1, y1, z1)];
        float c00 = Mth.lerp(fx, c000, c100);
        float c10 = Mth.lerp(fx, c010, c110);
        float c01 = Mth.lerp(fx, c001, c101);
        float c11 = Mth.lerp(fx, c011, c111);
        return Mth.lerp(fz, Mth.lerp(fy, c00, c10), Mth.lerp(fy, c01, c11));
    }

    private void applyMomentum(float dt, Vec3 baseWind) {
        float bx = baseWind == null ? 0f : (float) baseWind.x;
        float bz = baseWind == null ? 0f : (float) baseWind.z;
        float relax = 1f - (float) Math.exp(-dt / 210f);
        float lat = Mth.clamp(originZ / 3000f, -80f, 80f) * Mth.DEG_TO_RAD;
        float f = 2f * OMEGA * (float) Math.sin(lat);

        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    if (isSolid(x, y, z)) {
                        u[idx(x, y, z)] = 0f;
                        v[idx(x, y, z)] = 0f;
                        continue;
                    }
                    int i = idx(x, y, z);
                    // 气压梯度力：-(1/ρ) ∇p，ρ 取 1.2 kg/m³
                    int xm = idx(Math.max(x - 1, 0), y, z);
                    int xp = idx(Math.min(x + 1, SX - 1), y, z);
                    int zm = idx(x, y, Math.max(z - 1, 0));
                    int zp = idx(x, y, Math.min(z + 1, SZ - 1));
                    float dpdx = (p[xp] - p[xm]) / (2f * CELL) * 100f;   // hPa→Pa
                    float dpdz = (p[zp] - p[zm]) / (2f * CELL) * 100f;
                    u[i] += -dpdx / 1.2f * dt * 0.05f;
                    v[i] += -dpdz / 1.2f * dt * 0.05f;

                    // 大尺度底风松弛 + 地面摩擦
                    float friction = isOpenSky(x, y, z) ? 0.020f : 0.006f;
                    u[i] += (bx - u[i]) * relax;
                    v[i] += (bz - v[i]) * relax;
                    u[i] *= 1f - friction;
                    v[i] *= 1f - friction;

                    // 科氏偏转（网格尺度效应很小，保留是为了物理完整性）
                    if (f != 0f) {
                        float fu = u[i];
                        u[i] += f * v[i] * dt * 0.02f;
                        v[i] -= f * fu * dt * 0.02f;
                    }

                    u[i] = Mth.clamp(u[i], -60f, 60f);
                    v[i] = Mth.clamp(v[i], -60f, 60f);
                }
            }
        }
    }

    /**
     * 红黑超松弛（RBSOR）速度投影。
     *
     * 解 ∇²φ = ∇·u（Neumann 边界），再令 u ← u − ∇φ，把非物理的散度压掉。
     * 固定 12 次迭代、每步固定开销——这是"实时可跑"与"真的在解方程"之间的折中。
     */
    private void project() {
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    int i = idx(x, y, z);
                    if (isSolid(x, y, z)) {
                        div[i] = 0f;
                        continue;
                    }
                    int xm = idx(Math.max(x - 1, 0), y, z);
                    int xp = idx(Math.min(x + 1, SX - 1), y, z);
                    int ym = idx(x, Math.max(y - 1, 0), z);
                    int yp = idx(x, Math.min(y + 1, SY - 1), z);
                    int zm = idx(x, y, Math.max(z - 1, 0));
                    int zp = idx(x, y, Math.min(z + 1, SZ - 1));
                    div[i] = (u[xp] - u[xm]) / (2f * CELL)
                            + (w[yp] - w[ym]) / (2f * LAYER)
                            + (v[zp] - v[zm]) / (2f * CELL);
                    phi[i] = 0f;
                }
            }
        }

        float h2 = CELL * CELL;
        for (int it = 0; it < SOR_ITERATIONS; it++) {
            for (int parity = 0; parity < 2; parity++) {
                for (int y = 0; y < SY; y++) {
                    for (int z = 0; z < SZ; z++) {
                        for (int x = 0; x < SX; x++) {
                            if (((x + y + z) & 1) != parity) continue;
                            if (isSolid(x, y, z)) continue;
                            int i = idx(x, y, z);
                            int xm = idx(Math.max(x - 1, 0), y, z);
                            int xp = idx(Math.min(x + 1, SX - 1), y, z);
                            int ym = idx(x, Math.max(y - 1, 0), z);
                            int yp = idx(x, Math.min(y + 1, SY - 1), z);
                            int zm = idx(x, y, Math.max(z - 1, 0));
                            int zp = idx(x, y, Math.min(z + 1, SZ - 1));
                            float sum = phi[xm] + phi[xp] + phi[ym] + phi[yp] + phi[zm] + phi[zp];
                            float guess = (sum - div[i] * h2) / 6f;
                            phi[i] = phi[i] + SOR_OMEGA * (guess - phi[i]);
                        }
                    }
                }
            }
        }

        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    if (isSolid(x, y, z)) continue;
                    int i = idx(x, y, z);
                    int xm = idx(Math.max(x - 1, 0), y, z);
                    int xp = idx(Math.min(x + 1, SX - 1), y, z);
                    int ym = idx(x, Math.max(y - 1, 0), z);
                    int yp = idx(x, Math.min(y + 1, SY - 1), z);
                    int zm = idx(x, y, Math.max(z - 1, 0));
                    int zp = idx(x, y, Math.min(z + 1, SZ - 1));
                    u[i] = Mth.clamp(u[i] - (phi[xp] - phi[xm]) / (2f * CELL), -60f, 60f);
                    v[i] = Mth.clamp(v[i] - (phi[zp] - phi[zm]) / (2f * CELL), -60f, 60f);
                    // 垂直分量也要修正，否则散度只被压掉三分之二，垂直运动仍会"凭空生风"
                    w[i] = Mth.clamp(w[i] - (phi[yp] - phi[ym]) / (2f * LAYER), -25f, 30f);
                }
            }
        }
    }

    /** 湿度收支 + 成云：过饱和即生成云，云够厚就降水并反过来消耗水汽。 */
    private void relaxMoisture(float dt, float rainLevel) {
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    if (isSolid(x, y, z)) continue;
                    int i = idx(x, y, z);
                    float qs = ApparentTemperature.saturationMixingRatio(t[i], p[i]);
                    float rh = qs <= 0.01f ? 0f : q[i] / qs;
                    if (rh > 1f) {
                        float excess = (rh - 1f) * qs;
                        q[i] -= excess * 0.35f;
                        cloud[i] = Math.min(1f, cloud[i] + excess * 0.05f);
                        t[i] += excess * 2.5f;          // 凝结放热
                    } else {
                        cloud[i] = Math.max(0f, cloud[i] - 0.02f * dt * 0.1f);
                        q[i] = Math.max(0f, q[i] - (1f - rh) * 0.0008f * dt * 0.1f);
                    }

                    // 大尺度水汽供应：正在下雨，就说明上空一定有成云的水汽。
                    // 没有这一步，云量会一直是 0（初始相对湿度 62% 永远到不了饱和），
                    // 而"云量调制降水"就会得出"下雨但没云 → 不下雨"的自相矛盾。
                    if (rainLevel > 0.1f && y >= SY / 2) {
                        float supply = rainLevel * 0.002f * dt;
                        q[i] = Math.min(qs * 1.02f, q[i] + supply * qs);
                        cloud[i] = Math.min(1f, cloud[i] + supply * 2.5f);
                    }

                    // 降水把云清掉
                    if (cloud[i] > 0.55f && rainLevel > 0.15f) {
                        float drop = (cloud[i] - 0.55f) * rainLevel * 0.08f * dt;
                        cloud[i] = Math.max(0f, cloud[i] - drop);
                        q[i] = Math.max(0f, q[i] - drop * 0.4f);
                    }
                }
            }
        }
    }

    private void clampFields() {
        for (int i = 0; i < N; i++) {
            p[i] = Mth.clamp(p[i], 300f, 1100f);
            t[i] = Mth.clamp(t[i], -90f, 70f);
            q[i] = Mth.clamp(q[i], 0f, 60f);
            o2[i] = Mth.clamp(o2[i], 0f, 0.30f);
            co2[i] = Mth.clamp(co2[i], 0f, 0.12f);
            cloud[i] = Mth.clamp(cloud[i], 0f, 1f);
            w[i] = Mth.clamp(w[i], -25f, 30f);
        }
    }

    private void swap() {
        System.arraycopy(tN, 0, t, 0, N);
        System.arraycopy(qN, 0, q, 0, N);
        System.arraycopy(o2N, 0, o2, 0, N);
        System.arraycopy(cloudN, 0, cloud, 0, N);
        System.arraycopy(co2N, 0, co2, 0, N);
    }

    // ================================================================
    //  查询
    // ================================================================

    /**
     * 采样一个世界坐标处的大气状态。结果写进 {@code out}，不分配。
     * 网格外返回 false（调用方应回退到原有风场/气候态）。
     */
    public boolean probe(double wx, double wy, double wz, Probe out) {
        if (!ready || !covers(wx, wz)) return false;
        double gx = (wx - originX) / CELL - 0.5;
        double gy = (wy - baseY) / LAYER - 0.5;
        double gz = (wz - originZ) / CELL - 0.5;
        if (gy < -1 || gy > SY) return false;

        out.pressureHpa = sample(p, gx, gy, gz, 1013.25f);
        out.tempC = sample(t, gx, gy, gz, 15f);
        float mix = sample(q, gx, gy, gz, 6f);
        out.humidity = ApparentTemperature.relativeHumidity(mix, out.tempC, out.pressureHpa);
        // 等效含氧量 = 体积分数 × 气压比。
        // 只报体积分数的话，海拔再高也永远是 20.95%，低氧永远不触发；
        // 真正让人高原反应的是**氧分压**，所以这里按气压折算。
        out.oxygen = sample(o2, gx, gy, gz, 0.2095f)
                * Mth.clamp(out.pressureHpa / 1013.25f, 0f, 1f);
        out.co2 = sample(co2, gx, gy, gz, CO2_BACKGROUND);
        out.cloud = sample(cloud, gx, gy, gz, 0f);
        out.windX = sample(u, gx, gy, gz, 0f);
        out.windY = sample(w, gx, gy, gz, 0f);
        out.windZ = sample(v, gx, gy, gz, 0f);
        out.windSpeed = (float) Math.sqrt(out.windX * out.windX
                + out.windY * out.windY + out.windZ * out.windZ);
        out.dewPointC = ApparentTemperature.dewPoint(out.tempC, out.humidity);
        out.apparentC = ApparentTemperature.of(out.tempC, out.windSpeed, out.humidity);
        out.cloudBaseM = ApparentTemperature.cloudBaseMetres(out.tempC, out.dewPointC);
        out.underground = !isOpenSkyWorld(wx, wy, wz);
        return true;
    }

    private boolean isOpenSkyWorld(double wx, double wy, double wz) {
        if (!ready || !covers(wx, wz)) return true;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, SX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, SZ - 1);
        return wy >= surfaceY[z * SX + x];
    }

    /**
     * 兼容层入口：在世界坐标处注入一份排放源（耗氧机器 / KubeJS / 其他 mod 调用）。
     * 强度 0..1，取列内最大值；下一场 step 就会耗氧并排 CO₂，随后随风平流。
     * 这是"机器耗氧→排 CO₂"对外的通用接线点，不必硬编码具体机器方块表。
     */
    public void injectEmission(double wx, double wz, float strength) {
        if (!ready || !covers(wx, wz)) return;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, SX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, SZ - 1);
        int col = z * SX + x;
        emission[col] = Math.max(emission[col], Mth.clamp(strength, 0f, 1f));
    }

    /** 指定列的地面温度（供"海陆风"与 HUD 使用）。 */
    public float groundTempAt(double wx, double wz) {
        if (!ready || !covers(wx, wz)) return 15f;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, SX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, SZ - 1);
        return groundT[z * SX + x];
    }

    /** 网格内水面占比，用来判断"附近有没有海/湖"，从而触发海陆风。 */
    public float waterFraction() {
        if (!ready) return 0f;
        float sum = 0f;
        for (int i = 0; i < SX * SZ; i++) sum += water[i] > 0.5f ? 1f : 0f;
        return sum / (SX * SZ);
    }

    /**
     * 整列云量的最大值（0..1）。
     *
     * 云是长在空中的，玩家站的那一层往往云量接近 0，所以"这里该不该下雨"
     * 必须看整根气柱，而不是看脚边那一格。降水调制与降水图层都用这个值。
     */
    public float columnCloud(double wx, double wz) {
        if (!ready || !covers(wx, wz)) return 0f;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, SX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, SZ - 1);
        float best = 0f;
        for (int y = 0; y < SY; y++) {
            if (isSolid(x, y, z)) continue;
            float c = cloud[idx(x, y, z)];
            if (c > best) best = c;
        }
        return best;
    }

    /** 一整层的水平云量切片，用于雷达图层（0=地面层）。实心格写 NaN，客户端画成透明。 */
    public void slice(int layer, float[] out) {
        int y = Mth.clamp(layer, 0, SY - 1);
        for (int i = 0; i < SX * SZ; i++) out[i] = 0f;
        if (!ready) return;
        for (int z = 0; z < SZ; z++) {
            for (int x = 0; x < SX; x++) {
                int i = idx(x, y, z);
                out[z * SX + x] = isSolid(x, y, z) ? Float.NaN : cloud[i];
            }
        }
    }

    public float pressure(int x, int y, int z) {
        return p[idx(x, y, z)];
    }

    public float temperature(int x, int y, int z) {
        return t[idx(x, y, z)];
    }

    public float mixingRatio(int x, int y, int z) {
        return q[idx(x, y, z)];
    }

    public float oxygen(int x, int y, int z) {
        return o2[idx(x, y, z)];
    }

    public float co2(int x, int y, int z) {
        return co2[idx(x, y, z)];
    }

    /** 整列 CO₂ 的最大值（玩家可能站在任一高度，取全列最强污染）。 */
    public float columnCo2(double wx, double wz) {
        if (!ready || !covers(wx, wz)) return CO2_BACKGROUND;
        int x = Mth.clamp((int) ((wx - originX) / CELL), 0, SX - 1);
        int z = Mth.clamp((int) ((wz - originZ) / CELL), 0, SZ - 1);
        float best = 0f;
        for (int y = 0; y < SY; y++) {
            if (isSolid(x, y, z)) continue;
            float c = co2[idx(x, y, z)];
            if (c > best) best = c;
        }
        return best;
    }

    public float cloud(int x, int y, int z) {
        return cloud[idx(x, y, z)];
    }

    public float windU(int x, int y, int z) {
        return u[idx(x, y, z)];
    }

    public float windV(int x, int y, int z) {
        return v[idx(x, y, z)];
    }

    public float windW(int x, int y, int z) {
        return w[idx(x, y, z)];
    }

    public boolean solidAt(int x, int y, int z) {
        return isSolid(x, y, z);
    }

    public int surfaceAt(int x, int z) {
        return surfaceY[z * SX + x];
    }
}
