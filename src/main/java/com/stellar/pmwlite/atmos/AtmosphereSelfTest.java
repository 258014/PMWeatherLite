package com.stellar.pmwlite.atmos;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 大气场自检 / Atmosphere self-test.
 *
 * 存在的理由：大气场是"算出来的"东西，肉眼看不出来它是不是在发散或算成 NaN。
 * 这个类把物理上必须成立的不变量逐条断言一遍，服务端用 {@code /pmwlite atmoscheck} 就能跑，
 * 不需要玩家在线（网格会按命令位置临时建立），所以专用服务器冒烟测试也能覆盖到。
 *
 * 断言的都是"物理",不是"实现"：
 *  - 所有场值有限且在物理区间内（NaN 一旦出现会污染后续所有计算）；
 *  - 气压随高度严格单调递减（静力学平衡）；
 *  - 速度投影后散度不大（质量守恒的弱形式）；
 *  - 全球模型八层：气压递减、风有界、引导气流可取；
 *  - 体感/露点/云底公式在参考点上与教科书值一致。
 */
public final class AtmosphereSelfTest {

    /** 检查结果。 */
    public static final class Report {
        private final StringBuilder sb = new StringBuilder();
        public int failures;
        public int checks;

        public void line(String s) {
            sb.append(s).append('\n');
        }

        public void check(boolean ok, String what) {
            checks++;
            if (!ok) failures++;
            sb.append(ok ? "  §a✔ " : "  §c✘ ").append("§7").append(what).append('\n');
        }

        public void checkRange(float v, float lo, float hi, String what) {
            check(Float.isFinite(v) && v >= lo && v <= hi,
                    what + " = " + String.format("%.3f", v) + " ∈ [" + lo + ", " + hi + "]");
        }

        public String text() {
            return sb.toString();
        }
    }

    private AtmosphereSelfTest() {}

    /**
     * 执行一次完整自检。
     *
     * @param steps 本地网格要推进的步数（建议 ≥ 24，足以暴露发散）
     */
    public static Report run(ServerLevel level, Vec3 at, int steps) {
        Report r = new Report();

        // ---------------- 1) 本地网格 ----------------
        AtmosphereGrid grid = new AtmosphereGrid(level);
        Vec3 wind = new Vec3(6, 0, -3);
        long t0 = level.getGameTime();
        boolean built = grid.ensureAround(at.x, at.z, wind, t0);
        r.check(built && grid.ready(), "本地网格建立成功（16×8×16 格）");
        if (!grid.ready()) return r;

        // 预热 24 步再计时：第一轮是解释执行 + C1，测出来的 2.3 ms/步是冷启动假象，
        // 稳态才是决定"每 3 tick 一步能不能负担得起"的数字。
        int warm = Math.min(steps, 24);
        long tStart = 0L;
        for (int i = 1; i <= steps + warm; i++) {
            if (i == warm + 1) tStart = System.nanoTime();
            grid.step(t0 + i * 3L, wind, level.getRainLevel(1f));
        }
        double usPerStep = (System.nanoTime() - tStart) / 1000.0 / Math.max(steps, 1);
        r.line("§8推进 " + steps + " 步（" + (steps * 3) + " tick）后检查场值");
        r.check(usPerStep < 1500.0, String.format("稳态单步耗时 %.0f µs（2048 格，预算 < 1500）", usPerStep));

        int bad = 0, nonFinite = 0;
        float pMin = Float.MAX_VALUE, pMax = -Float.MAX_VALUE;
        float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
        float wMax = 0f;
        float cloudMax = 0f;
        for (int y = 0; y < AtmosphereGrid.SY; y++) {
            for (int z = 0; z < AtmosphereGrid.SZ; z++) {
                for (int x = 0; x < AtmosphereGrid.SX; x++) {
                    float p = grid.pressure(x, y, z);
                    float t = grid.temperature(x, y, z);
                    float q = grid.mixingRatio(x, y, z);
                    float o2 = grid.oxygen(x, y, z);
                    float c = grid.cloud(x, y, z);
                    float u = grid.windU(x, y, z);
                    float v = grid.windV(x, y, z);
                    float w = grid.windW(x, y, z);
                    if (!Float.isFinite(p) || !Float.isFinite(t) || !Float.isFinite(q)
                            || !Float.isFinite(o2) || !Float.isFinite(u)
                            || !Float.isFinite(v) || !Float.isFinite(w)) {
                        nonFinite++;
                        continue;
                    }
                    if (p < 300f || p > 1100f || t < -90f || t > 70f || q < 0f || q > 60f
                            || o2 < 0f || o2 > 0.30f || c < 0f || c > 1f
                            || Math.abs(u) > 60f || Math.abs(v) > 60f
                            || w < -25f || w > 30f) {
                        bad++;
                    }
                    pMin = Math.min(pMin, p);
                    pMax = Math.max(pMax, p);
                    tMin = Math.min(tMin, t);
                    tMax = Math.max(tMax, t);
                    wMax = Math.max(wMax, Math.abs(w));
                    cloudMax = Math.max(cloudMax, c);
                }
            }
        }
        r.check(nonFinite == 0, "无 NaN/Inf（" + nonFinite + " 个坏格）");
        r.check(bad == 0, "所有场值在物理区间内（" + bad + " 个越界）");
        r.check(pMax - pMin > 1f, "§7气压有空间变化 (" + String.format("%.1f…%.1f hPa", pMin, pMax) + ")");
        r.check(tMax - tMin > 0.5f, "温度有空间变化 (" + String.format("%.2f…%.2f ℃", tMin, tMax) + ")");

        // 静力学单调
        int nonMono = 0;
        for (int z = 0; z < AtmosphereGrid.SZ; z++) {
            for (int x = 0; x < AtmosphereGrid.SX; x++) {
                for (int y = 1; y < AtmosphereGrid.SY; y++) {
                    if (grid.pressure(x, y, z) >= grid.pressure(x, y - 1, z)) nonMono++;
                }
            }
        }
        r.check(nonMono == 0, "气压随高度严格递减（静力学平衡，违例 " + nonMono + " 处）");

        // 散度（质量守恒的弱形式）：投影后应该远小于 1 s⁻¹
        float divSum = 0f;
        int divN = 0;
        for (int y = 0; y < AtmosphereGrid.SY; y++) {
            for (int z = 0; z < AtmosphereGrid.SZ; z++) {
                for (int x = 0; x < AtmosphereGrid.SX; x++) {
                    if (grid.solidAt(x, y, z)) continue;
                    int xm = Math.max(x - 1, 0), xp = Math.min(x + 1, AtmosphereGrid.SX - 1);
                    int zm = Math.max(z - 1, 0), zp = Math.min(z + 1, AtmosphereGrid.SZ - 1);
                    int ym = Math.max(y - 1, 0), yp = Math.min(y + 1, AtmosphereGrid.SY - 1);
                    float d = (grid.windU(xp, y, z) - grid.windU(xm, y, z)) / (2f * AtmosphereGrid.CELL)
                            + (grid.windV(x, y, zp) - grid.windV(x, y, zm)) / (2f * AtmosphereGrid.CELL)
                            + (grid.windW(x, yp, z) - grid.windW(x, ym, z)) / (2f * AtmosphereGrid.LAYER);
                    divSum += Math.abs(d);
                    divN++;
                }
            }
        }
        float meanDiv = divN == 0 ? 0f : divSum / divN;
        r.check(meanDiv < 0.5f, String.format("平均散度 %.4f s⁻¹ < 0.5（质量守恒弱检查）", meanDiv));
        r.line(String.format("§8  参考：最大垂直速度 %.2f m/s，最大云量 %.0f%%",
                wMax, cloudMax * 100f));

        // 探针
        AtmosphereGrid.Probe probe = new AtmosphereGrid.Probe();
        boolean ok = grid.probe(at.x, at.y, at.z, probe);
        r.check(ok, "探针可在命令位置取值");
        if (ok) {
            r.check(probe.dewPointC <= probe.tempC + 0.01f,
                    String.format("露点 %.2f ℃ ≤ 气温 %.2f ℃", probe.dewPointC, probe.tempC));
            r.check(probe.cloudBaseM >= 0f,
                    String.format("云底高度 %.0f m ≥ 0（LCL=125·(T−Td)）", probe.cloudBaseM));
            r.checkRange(probe.humidity, 0f, 1.2f, "相对湿度");
            r.checkRange(probe.oxygen, 0f, 0.30f, "氧气含量");
        }

        // 1b) 降水必须能成云。这一条是被真实缺陷逼出来的：初始相对湿度 62%，
        //     若没有"降水→水汽供应"这一项，云量会永远是 0，而按云量调制的降水就永远不下雨。
        AtmosphereGrid rainGrid = new AtmosphereGrid(level);
        rainGrid.ensureAround(at.x, at.z, wind, t0);
        for (int i = 1; i <= 60; i++) {
            rainGrid.step(t0 + i * 3L, wind, 0.8f);
        }
        float colCloud = rainGrid.columnCloud(at.x, at.z);
        r.check(colCloud > 0.05f, String.format("降水条件下能成云（列云量 %.0f%%）", colCloud * 100f));
        r.check(colCloud <= 1.0f, String.format("云量 ≤ 100%%（%.0f%%）", colCloud * 100f));

        // 1c) CO₂ 示踪场：注入排放源后必须成羽流，且随风输送、始终有界。
        //     这一条钉住"机器耗氧→排 CO₂→半拉格朗日平流"整条链路——
        //     早期若把 co2N 漏出 swap()，排放会被平流前的旧值覆盖，羽流永远为 0。
        AtmosphereGrid co2Grid = new AtmosphereGrid(level);
        co2Grid.ensureAround(at.x, at.z, wind, t0);
        float co2Before = co2Grid.columnCo2(at.x, at.z);
        // 初值按列状态分三档：开阔天空 400 ppm、密闭/地下 600 ppm、实心 800 ppm。
        // 命令位置 y=-59 在地下，列多为密闭，不能写死 400 ppm——
        // 这是被真实失败逼出来的边界：早期 <2e-4 的容差恰好把 600 ppm 误判成错误
        // （0.0006f−0.0004f 的 float 实际值略大于 2e-4，正好卡在边界上）。
        r.check(co2Before >= AtmosphereGrid.CO2_BACKGROUND * 0.95f
                        && co2Before <= AtmosphereGrid.CO2_BACKGROUND * 2.05f,
                String.format("CO₂ 初值为背景量级（%.0f ppm，预期 400~800）", co2Before * 1e6f));
        for (int i = 1; i <= 40; i++) {
            co2Grid.injectEmission(at.x, at.z, 1f);   // 满负荷耗氧机器持续排放
            co2Grid.step(t0 + i * 3L, wind, 0f);
        }
        float co2After = co2Grid.columnCo2(at.x, at.z);
        // 半拉格朗日平流会把源点排放迅速送走，所以源点本身只小幅上升（实测约 +30%），
        // 羽流真正的证据是下风处浓度。早期写成 >3×背景是错的：那要求"原地堆积"，
        // 与平流的设计目标正好相反。
        r.check(co2After > co2Before * 1.1f,
                String.format("排放源使 CO₂ 上升（%.0f → %.0f ppm）",
                        co2Before * 1e6f, co2After * 1e6f));
        r.check(co2After <= 0.1201f, String.format("CO₂ 有界 ≤ 12%%（%.0f ppm）", co2After * 1e6f));
        // 下风方向（wind = (6,0,-3)，即 +x/-z）应也能测到高于背景的 CO₂。
        // 密闭列无通风稀释，满负荷持续排放会累积到百分级；上界仍由 clampFields 钉死在 12%。
        float co2Down = co2Grid.columnCo2(at.x + AtmosphereGrid.CELL * 2f,
                at.z - AtmosphereGrid.CELL * 1f);
        r.check(co2Down > co2Before * 2f && co2Down <= 0.1201f,
                String.format("CO₂ 被风平流到下风处（%.0f ppm，应 >2×背景且 ≤12%%）",
                        co2Down * 1e6f));

        // 1d) 多气体列场：背景初值、注入上升、有界、平流到下风。
        //     列场是二维的（地表边界层），用 inject 注入后应沿同一条风路走。
        AtmosphereGrid gasGrid = new AtmosphereGrid(level);
        gasGrid.ensureAround(at.x, at.z, wind, t0);
        float so2Bg = gasGrid.gases().at(at.x, at.z, GasField.SO2);
        float pmBg = gasGrid.gases().at(at.x, at.z, GasField.PM);
        r.check(so2Bg >= GasField.BACKGROUND[GasField.SO2] * 0.999f
                        && so2Bg <= GasField.BACKGROUND[GasField.SO2] * 1.2f,
                String.format("SO₂ 初值为背景量级（%.1f ppb）", so2Bg * 1e9f));
        for (int i = 1; i <= 40; i++) {
            gasGrid.injectGas(at.x, at.z, "so2", 1f);
            gasGrid.injectGas(at.x, at.z, "pm", 1f);
            gasGrid.step(t0 + i * 3L, wind, 0f);
        }
        // 半拉格朗日平流会把源点浓度立刻送走，所以源点本身可能已回到背景；
        // 羽流的证据是"下风处浓度高于背景"，这与 CO₂ 段是同一个物理事实。
        float so2Down = gasGrid.gases().at(at.x + AtmosphereGrid.CELL, at.z, GasField.SO2);
        float pmDown = gasGrid.gases().at(at.x + AtmosphereGrid.CELL, at.z, GasField.PM);
        r.check(so2Down > so2Bg * 3f && so2Down <= GasField.MAX[GasField.SO2] * 1.001f,
                String.format("SO₂ 注入后成羽流且有界（背景 %.0f → 下风 %.0f ppb）",
                        so2Bg * 1e9f, so2Down * 1e9f));
        // 整场最大值判据：PM 单点下风值可能被平流摊薄到 0，用全场峰值更稳健，
        // 也能防止"只是把源点搬走、总量没增加"的假象。
        float so2Max = 0f, pmMax = 0f;
        for (int col = 0; col < AtmosphereGrid.SX * AtmosphereGrid.SZ; col++) {
            so2Max = Math.max(so2Max, gasGrid.gases().column(GasField.SO2, col));
            pmMax = Math.max(pmMax, gasGrid.gases().column(GasField.PM, col));
        }
        r.check(so2Max > so2Bg * 3f,
                String.format("SO₂ 全场峰值上升（%.0f ppb）", so2Max * 1e9f));
        r.check(pmMax > 0.05f && pmMax <= 1.001f,
                String.format("PM 全场峰值上升且有界（浑浊度 %.2f，下风点 %.2f）", pmMax, pmDown));

        // 1e) 光学求解器：科学可解释 vs 艺术化夸张的判定必须正确。
        //     这是"参数组合→现象"的纯函数，直接构造输入验证物理方向。
        var oi = new OpticalField.Input();
        oi.tempC = -12f; oi.humidity = 0.62f; oi.dewPointC = -18f;
        oi.cloud = 0.45f; oi.cloudBaseM = 6000f; oi.pm = 0.02f;
        // 低太阳角（日出后）+ 冰晶 + 薄云 → 幻日
        oi.sunAltitudeDeg = OpticalField.sunAltitude(6000L + 3600L - 900L); // ≈15°
        var lowSunResult = OpticalField.solve(oi, null);
        float phLow = lowSunResult.strength[OpticalField.Kind.PARHELION.ordinal()];
        // 正午高太阳角 → 幻日应显著减弱（现实中 >60° 消失）
        oi.sunAltitudeDeg = 85f;
        var highSunResult = OpticalField.solve(oi, null);
        float phHigh = highSunResult.strength[OpticalField.Kind.PARHELION.ordinal()];
        r.check(phLow > 0.2f && phLow > phHigh * 2f,
                String.format("幻日随太阳高度角降低而增强（15° %.2f vs 85° %.2f）", phLow, phHigh));
        r.check(OpticalField.Kind.PARHELION.scientific,
                "幻日标记为科学可解释");
        r.check(!OpticalField.Kind.AURORA.scientific,
                "极光样光幕标记为艺术化（不冒充科学）");

        // 极光：夜间 + 高磁纬 + 高 Kp + 通透 → 强；白天/低纬 → 弱
        var ai = new OpticalField.Input();
        ai.sunAltitudeDeg = -40f; ai.geomagneticLat = 70f; ai.kp = 8f;
        ai.cloud = 0.2f; ai.pm = 0.05f; ai.tempC = -20f; ai.dewPointC = -25f;
        var auroraNight = OpticalField.solve(ai, null);
        float auNight = auroraNight.strength[OpticalField.Kind.AURORA.ordinal()];
        ai.sunAltitudeDeg = 30f; ai.geomagneticLat = 10f; ai.kp = 1f;
        var auroraDay = OpticalField.solve(ai, null);
        float auDay = auroraDay.strength[OpticalField.Kind.AURORA.ordinal()];
        r.check(auNight > 0.3f && auNight > auDay * 5f,
                String.format("极光需夜+高纬+磁扰（夜 %.2f vs 昼 %.2f）", auNight, auDay));
        r.check(auNight > 0.3f && !OpticalField.Kind.AURORA.scientific,
                "极光样光幕标记为艺术化（不冒充科学）");

        // 霞光：低太阳角 + 气溶胶 → 触发；所有强度有界且有限
        var si = new OpticalField.Input();
        si.sunAltitudeDeg = 2f; si.pm = 0.7f; si.so2 = 3e-4f; si.no2 = 1e-4f;
        si.cloud = 0.3f;
        var glow = OpticalField.solve(si, null);
        float ag = glow.strength[OpticalField.Kind.AFTERGLOW.ordinal()];
        r.check(ag > 0.2f, String.format("污染霞光在低角度+气溶胶下触发（%.2f）", ag));
        boolean bounded = true;
        for (int i = 0; i < OpticalField.KIND_COUNT; i++) {
            float v = glow.strength[i];
            if (!Float.isFinite(v) || v < 0f || v > 1f) bounded = false;
        }
        r.check(bounded, "光学强度全部有限且在 0..1");

        // 地下不应看到任何天空光学现象
        var ui = new OpticalField.Input();
        ui.underground = true;
        var underground = OpticalField.solve(ui, null);
        r.check(underground.dominant == OpticalField.Kind.NONE,
                "地下不触发任何天空光学现象");

        // 1f) v0.3.4 多气体联合暴露：协同、湿沉降、CO 不溶于水、分级单调。
        var gNone = GasExposure.evaluate(0f, 0f, 0f, 0f, 4e-4f, 0f, null);
        var gS = GasExposure.evaluate(3e-4f, 0f, 0f, 0f, 4e-4f, 0f, null);
        var gP = GasExposure.evaluate(0f, 0f, 0f, 0.30f, 4e-4f, 0f, null);
        var gSP = GasExposure.evaluate(3e-4f, 0f, 0f, 0.30f, 4e-4f, 0f, null);
        r.check(gNone.grade == GasExposure.Grade.NONE, "清洁空气联合暴露为无风险");
        r.check(gSP.index > gS.index * 1.4f && gSP.index > gP.index * 1.4f,
                String.format("SO₂+PM 协同风险高于任一单气体（组合 %.2f，单 %+.2f/%+.2f）",
                        gSP.index, gS.index, gP.index));
        var gWet = GasExposure.evaluate(3e-4f, 0f, 0f, 0.30f, 4e-4f, 1f, null);
        r.check(gWet.index < gSP.index && gWet.visibility > gSP.visibility,
                String.format("降雨湿沉降降低暴露并提高能见度（%.2f→%.2f）", gSP.index, gWet.index));
        var gCoDry = GasExposure.evaluate(0f, 0f, 3e-3f, 0f, 4e-4f, 0f, null);
        var gCoWet = GasExposure.evaluate(0f, 0f, 3e-3f, 0f, 4e-4f, 1f, null);
        r.check(Math.abs(gCoDry.index - gCoWet.index) < 1e-6f,
                "CO 难溶于水，降雨不清除一氧化碳风险");
        boolean exposureBounded = true;
        for (float[] combo : new float[][]{{0f, 0f, 0f, 0f, 4e-4f},
                {2e-3f, 1e-3f, 1e-2f, 1f, 0.05f}}) {
            var ex = GasExposure.evaluate(combo[0], combo[1], combo[2], combo[3], combo[4], 0f, null);
            if (!Float.isFinite(ex.index) || ex.index < 0f || ex.index > 1f
                    || ex.visibility < 0f || ex.visibility > 1f) exposureBounded = false;
        }
        r.check(exposureBounded, "联合暴露指数与能见度始终有限且在 0..1");

        // 1g) 月亮纯函数：相位、照度、月食只发生在满月食季。
        r.check(LunarField.illumination(0L) > 0.99f, "月相 0 = 满月，照度≈100%");
        r.check(LunarField.illumination(24000L * 4L) < 0.01f, "月相 4 = 新月，照度≈0");
        r.check(LunarField.eclipse(0L) > 0.5f, "满月 + 食季触发月食");
        r.check(LunarField.eclipse(24000L * 4L) == 0f, "新月不可能月食");
        r.check(LunarField.eclipse(24000L * 8L * 7L) == 0f, "非食季不可能月食");
        r.check(LunarField.altitudeDeg(6000L) < -80f, "正午月亮在地平线以下");
        r.check(LunarField.moonlight(0L, 0.8f, 0f) < LunarField.moonlight(0L, 0f, 0f),
                "云量削弱可用月光");

        // 1h) v0.3.4 的 16 种新现象：逐个构造必要条件，验证"该触发时触发、不该触发时不触发"。
        r.check(OpticalField.KIND_COUNT == 22, "光学现象枚举共 22 种（前 6 个 ordinal 未变）");
        OpticalField.Kind[] wire = {OpticalField.Kind.NONE, OpticalField.Kind.PARHELION,
                OpticalField.Kind.HALO, OpticalField.Kind.FOGBOW, OpticalField.Kind.AFTERGLOW,
                OpticalField.Kind.AURORA};
        boolean wireStable = true;
        for (int i = 0; i < wire.length; i++) {
            if (OpticalField.Kind.values()[i] != wire[i]) wireStable = false;
        }
        r.check(wireStable, "前 6 个 ordinal 与 v0.3.3 网络协议一致（新现象只追加）");

        var ni = new OpticalField.Input();
        ni.tempC = -10f; ni.dewPointC = -16f; ni.humidity = 0.55f;
        ni.cloud = 0.42f; ni.cloudBaseM = 5500f; ni.pm = 0.02f;
        ni.sunAltitudeDeg = 15f;
        float czaLow = OpticalField.solve(ni, null).strength[OpticalField.Kind.CIRCUMZENITHAL.ordinal()];
        ni.sunAltitudeDeg = 70f;
        var highSun = OpticalField.solve(ni, null);
        float czaHigh = highSun.strength[OpticalField.Kind.CIRCUMZENITHAL.ordinal()];
        float chaHigh = highSun.strength[OpticalField.Kind.CIRCUMHORIZONTAL.ordinal()];
        r.check(czaLow > 0.5f && czaHigh < 0.01f,
                String.format("环天顶弧只在低太阳角触发（15° %.2f vs 70° %.2f）", czaLow, czaHigh));
        r.check(chaHigh > 0.5f, String.format("环地平弧在高太阳角触发（70° %.2f）", chaHigh));

        ni.sunAltitudeDeg = 0f; ni.cloud = 0.10f;
        float pillarLow = OpticalField.solve(ni, null).strength[OpticalField.Kind.SUN_PILLAR.ordinal()];
        ni.sunAltitudeDeg = 70f;
        float pillarHigh = OpticalField.solve(ni, null).strength[OpticalField.Kind.SUN_PILLAR.ordinal()];
        r.check(pillarLow > 0.5f && pillarHigh < 0.15f,
                String.format("日柱随太阳高度升高而消失（%.2f → %.2f）", pillarLow, pillarHigh));

        var ii = new OpticalField.Input();
        ii.tempC = 18f; ii.dewPointC = 10f; ii.cloud = 0.45f; ii.pm = 0.01f; ii.sunAltitudeDeg = 35f;
        r.check(OpticalField.solve(ii, null).strength[OpticalField.Kind.IRIDESCENT.ordinal()] > 0.5f,
                "薄云 + 中小水滴触发虹彩云");

        var gi2 = new OpticalField.Input();
        gi2.tempC = 1f; gi2.dewPointC = 0.5f; gi2.humidity = 1f;
        gi2.cloud = 0.10f; gi2.cloudBaseM = 0f; gi2.sunAltitudeDeg = 4f;
        r.check(OpticalField.solve(gi2, null).strength[OpticalField.Kind.GLORY.ordinal()] > 0.35f,
                "背光 + 近饱和低雾触发宝光");

        var ri = new OpticalField.Input();
        ri.sunAltitudeDeg = 15f; ri.rain = 1f; ri.cloud = 0.65f; ri.tempC = 12f; ri.dewPointC = 10f;
        r.check(OpticalField.solve(ri, null).strength[OpticalField.Kind.RAINBOW.ordinal()] > 0.6f,
                "低太阳角 + 降水触发双彩虹");

        var mi = new OpticalField.Input();
        mi.moonAltitudeDeg = 40f; mi.moonIllum = 1f; mi.cloud = 0.42f; mi.pm = 0.01f;
        mi.sunAltitudeDeg = -30f;
        var moonSolve = OpticalField.solve(mi, null);
        r.check(moonSolve.strength[OpticalField.Kind.MOON_CORONA.ordinal()] > 0.6f,
                "明月 + 薄云触发月华");
        mi.moonEclipse = 1f;
        mi.cloud = 0.05f;
        r.check(OpticalField.solve(mi, null).strength[OpticalField.Kind.LUNAR_ECLIPSE.ordinal()] > 0.6f,
                "满月月食触发月食现象");
        r.check(OpticalField.solve(mi, null).strength[OpticalField.Kind.MOON_CORONA.ordinal()]
                < moonSolve.strength[OpticalField.Kind.MOON_CORONA.ordinal()],
                "月食期间月华被遮蔽");

        var ci = new OpticalField.Input();
        ci.pm = 0.01f; ci.cloud = 0.50f;
        ci.sunAltitudeDeg = 2f;
        float crep = OpticalField.solve(ci, null).strength[OpticalField.Kind.CREPUSCULAR.ordinal()];
        ci.sunAltitudeDeg = -2f;
        var twi = OpticalField.solve(ci, null);
        r.check(crep > 0.5f && twi.strength[OpticalField.Kind.ANTICREPUSCULAR.ordinal()] > 0.4f,
                "曙暮辉与反曙暮辉分别在日落前后触发");
        ci.sunAltitudeDeg = -6f; ci.cloud = 0.10f;
        var duskSolve = OpticalField.solve(ci, null);
        r.check(duskSolve.strength[OpticalField.Kind.BELT_VENUS.ordinal()] > 0.5f,
                "民用暮光触发维纳斯带");
        r.check(duskSolve.strength[OpticalField.Kind.EARTH_SHADOW.ordinal()] > 0.3f,
                "民用暮光触发地球阴影");

        var ai2 = new OpticalField.Input();
        ai2.sunAltitudeDeg = -40f; ai2.cloud = 0.05f; ai2.pm = 0.01f;
        float air = OpticalField.solve(ai2, null).strength[OpticalField.Kind.AIRGLOW.ordinal()];
        r.check(air > 0.3f && air < 0.46f,
                String.format("气辉在晴朗夜间出现且比极光暗（%.2f）", air));

        var fi = new OpticalField.Input();
        fi.inversionC = 10f; fi.sunAltitudeDeg = 5f; fi.cloud = 0.10f; fi.pm = 0.01f;
        float fm = OpticalField.solve(fi, null).strength[OpticalField.Kind.FATA_MORGANA.ordinal()];
        fi.inversionC = 0f;
        float fmNo = OpticalField.solve(fi, null).strength[OpticalField.Kind.FATA_MORGANA.ordinal()];
        r.check(fm > 0.35f && fmNo < 0.01f,
                String.format("法塔莫甘娜需要强逆温（%.2f → 无逆温 %.2f）", fm, fmNo));

        var bi = new OpticalField.Input();
        bi.pm = 0.8f; bi.sunAltitudeDeg = 12f; bi.cloud = 0.1f;
        r.check(OpticalField.solve(bi, null).strength[OpticalField.Kind.BLUE_SUN.ordinal()] > 0.7f,
                "浓气溶胶触发蓝太阳");
        var ei = new OpticalField.Input();
        ei.sunAltitudeDeg = 0.2f; ei.pm = 0.01f; ei.cloud = 0.05f; ei.tempC = 15f; ei.dewPointC = 13f;
        r.check(OpticalField.solve(ei, null).strength[OpticalField.Kind.GREEN_FLASH.ordinal()] > 0.5f,
                "地平线 + 通透空气触发绿闪");

        boolean allScientific = true;
        for (OpticalField.Kind k : OpticalField.Kind.values()) {
            if (k != OpticalField.Kind.AURORA && k != OpticalField.Kind.NONE && !k.scientific) allScientific = false;
        }
        r.check(allScientific, "除极光样光幕外，新增现象均标为科学可解释");

        var extreme = new OpticalField.Input();
        extreme.sunAltitudeDeg = 999f; extreme.moonAltitudeDeg = -999f;
        extreme.tempC = 999f; extreme.dewPointC = -999f; extreme.humidity = 99f;
        extreme.cloud = 99f; extreme.cloudBaseM = -99f; extreme.pm = 99f;
        extreme.so2 = 99f; extreme.no2 = 99f; extreme.co = 99f; extreme.co2 = 99f;
        extreme.inversionC = 999f; extreme.moonIllum = 99f; extreme.moonEclipse = 99f;
        extreme.rain = 99f; extreme.kp = 999f; extreme.geomagneticLat = 999f;
        boolean extBounded = true;
        var er = OpticalField.solve(extreme, null);
        for (int i = 0; i < OpticalField.KIND_COUNT; i++) {
            float v = er.strength[i];
            if (!Float.isFinite(v) || v < 0f || v > 1f) extBounded = false;
        }
        r.check(extBounded, "极端输入下 22 种强度仍全部有限且有界");

        // ---------------- 2) 全球模型 ----------------
        GlobalModel gm = new GlobalModel(level);
        gm.ensureAround(at.x, at.z, t0);
        gm.step(t0 + 20L, null);
        r.check(gm.ready(), "八层全球模型建立成功");

        int gNonMono = 0;
        float gMaxWind = 0f;
        float prevP = Float.MAX_VALUE;
        for (int l = 0; l < GlobalModel.LAYERS; l++) {
            float p = gm.pressureAt(at.x, at.z, l);
            float u = gm.windUAt(at.x, at.z, l);
            float v = gm.windVAt(at.x, at.z, l);
            if (!Float.isFinite(p) || !Float.isFinite(u) || !Float.isFinite(v)) {
                gNonMono++;
                continue;
            }
            if (p >= prevP) gNonMono++;
            prevP = p;
            gMaxWind = Math.max(gMaxWind, (float) Math.sqrt(u * u + v * v));
        }
        r.check(gNonMono == 0, "八层气压随高度递减且有限（违例 " + gNonMono + " 处）");
        r.check(gMaxWind <= 70f, String.format("各层风速 ≤ 70 m/s（实测最大 %.1f）", gMaxWind));
        r.check(gMaxWind > 0.01f, "地转风非零（气压梯度确实驱动了风）");

        float[] steer = new float[2];
        boolean hasSteer = gm.steeringWind(at.x, at.z, steer);
        r.check(hasSteer && Float.isFinite(steer[0]) && Float.isFinite(steer[1]),
                String.format("中层引导气流可取（u %.2f, v %.2f m/s）", steer[0], steer[1]));

        // ---------------- 3) 经验公式参考点 ----------------
        float wc = ApparentTemperature.windChill(0f, 10f);
        r.check(wc < -3f && wc > -12f, String.format("风寒 0℃/10m/s → %.2f ℃（NWS 参考 ≈ −6.6）", wc));
        float hi = ApparentTemperature.heatIndex(35f, 0.8f);
        r.check(hi > 40f && hi < 60f, String.format("热指数 35℃/80%% → %.2f ℃（NWS 参考 ≈ 47）", hi));
        float dp = ApparentTemperature.dewPoint(25f, 0.6f);
        r.check(Math.abs(dp - 16.7f) < 1.5f, String.format("露点 25℃/60%% → %.2f ℃（参考 ≈ 16.7）", dp));
        float mr = ApparentTemperature.saturationMixingRatio(20f, 1013.25f);
        r.check(Math.abs(mr - 14.7f) < 1.5f, String.format("饱和比湿 20℃/1013hPa → %.2f g/kg（参考 ≈ 14.7）", mr));
        float rh = ApparentTemperature.relativeHumidity(mr, 20f, 1013.25f);
        r.check(Math.abs(rh - 1f) < 0.02f, String.format("比湿↔相对湿度往返一致（%.3f）", rh));
        float lcl = ApparentTemperature.cloudBaseMetres(20f, 15f);
        r.check(Math.abs(lcl - 625f) < 1f, String.format("云底 20℃/Td 15℃ → %.0f m（125·ΔT）", lcl));

        // ---------------- 4) 风暴循环（含引导气流接线） ----------------
        // 这里真的构造一个 StormManager 跑几十 tick：目的是让 "风暴 tick → steer() → 全球模型"
        // 这条新接线被真实执行一次（全球模型未就绪时应安静地跳过，而不是抛异常）。
        var cfg = com.stellar.pmwlite.PMWLite.instance() != null
                ? com.stellar.pmwlite.PMWLite.instance().config()
                : new com.stellar.pmwlite.config.ServerConfig();
        var sm = new com.stellar.pmwlite.weather.StormManager(level, cfg);
        com.stellar.pmwlite.weather.StormManager.summonAt(sm, at);
        if (sm.getStorms().isEmpty()) {
            r.check(false, "风暴可生成（summonAt 返回空）");
        } else {
            Vec3 start = sm.getStorms().get(0).position;
            boolean finite = true;
            // 120 tick（6 秒）：风暴速度是从 0 慢慢加速到背景漂移的，太短只会测到加速段
            for (int i = 0; i < 120; i++) {
                sm.tick();
                for (var s : sm.getStorms()) {
                    if (!Double.isFinite(s.position.x) || !Double.isFinite(s.position.z)
                            || !Double.isFinite(s.velocity.x) || !Double.isFinite(s.velocity.z)) {
                        finite = false;
                    }
                }
            }
            r.check(!sm.getStorms().isEmpty(), "风暴推进 120 tick 后仍存活");
            r.check(finite, "风暴位置/速度无 NaN");
            if (!sm.getStorms().isEmpty()) {
                double moved = sm.getStorms().get(0).position.subtract(start).horizontalDistance();
                // 6 秒位移：真实雷暴 12~20 m/s → 72~120 格（含加速段，下限放宽）
                r.check(moved > 8.0 && moved < 200.0,
                        String.format("风暴移速合理（120 tick 位移 %.1f 格，约 %.1f 格/秒）",
                                moved, moved / 6.0));
            }
        }

        return r;
    }

    /** 简单数值夹逼，供外部复用。 */
    public static float clamp01(float v) {
        return Mth.clamp(v, 0f, 1f);
    }
}
