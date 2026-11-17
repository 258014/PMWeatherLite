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
