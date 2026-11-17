package com.stellar.pmwlite.atmos;

import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WeatherSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 大气场调度器 / Atmosphere runtime.
 *
 * 一个维度一套（本地网格 + 全球模型）。负责：
 *  - 按玩家重心搬运网格；
 *  - 节流：本地场每 3 tick 一步，全球场每 20 tick 一步；
 *  - 给游戏逻辑提供统一查询入口（HUD、雷达、破坏扫描）；
 *  - 低氧后果（可关）。
 *
 * 设计约束：**没有玩家时不计算**。这和风暴系统不同——风暴是世界状态，大气场是"观测窗口"，
 * 没人看的时候没必要烧 CPU。玩家一进来，网格会在第一 tick 重建并把气候态填满，
 * 所以不会出现"空场"读数的突兀。
 */
public final class AtmosphereManager {
    /** 本地场步进间隔（tick）。文章里本地引擎是 3 tick/帧，这里沿用。 */
    private static final int LOCAL_INTERVAL = 3;
    /** 全球场步进间隔（tick）。 */
    private static final int GLOBAL_INTERVAL = 20;
    /** 低氧阈值。 */
    private static final float O2_FATIGUE = 0.145f;
    private static final float O2_DAMAGE = 0.085f;

    private static final Map<ServerLevel, AtmosphereManager> INSTANCES = new WeakHashMap<>();

    private final ServerLevel level;
    private final ServerConfig cfg;
    private final AtmosphereGrid grid;
    private final GlobalModel global;
    private final AtmosphereGrid.Probe sharedProbe = new AtmosphereGrid.Probe();
    /** 全球近地面风采样容器（复用，避免每步分配）。 */
    private final float[] surfaceWindBuf = new float[2];
    /** 每个玩家的低氧伤害计时，避免每 tick 都扣血。 */
    private final Map<java.util.UUID, Integer> damageCooldown = new HashMap<>();

    private int tick;
    private boolean loggedOnce;

    private AtmosphereManager(ServerLevel level, ServerConfig cfg) {
        this.level = level;
        this.cfg = cfg;
        this.grid = new AtmosphereGrid(level);
        this.global = new GlobalModel(level);
    }

    public static AtmosphereManager of(ServerLevel level, ServerConfig cfg) {
        AtmosphereManager m = INSTANCES.get(level);
        if (m == null) {
            m = new AtmosphereManager(level, cfg);
            INSTANCES.put(level, m);
        }
        return m;
    }

    public static void clear(ServerLevel level) {
        INSTANCES.remove(level);
        TerrainWind.clear(level);
    }

    public AtmosphereGrid grid() {
        return grid;
    }

    public GlobalModel global() {
        return global;
    }

    /** 供 HUD / 雷达 / 破坏扫描复用的查询结果（单线程使用，不要跨线程持有）。 */
    public AtmosphereGrid.Probe probeShared() {
        return sharedProbe;
    }

    /**
     * 采样某个位置的本地大气。网格未就绪或越界时返回 false。
     *
     * @param out 结果容器，可为 {@link #probeShared()}
     */
    public boolean probe(Vec3 pos, AtmosphereGrid.Probe out) {
        if (!cfg.doAtmosphere) return false;
        return grid.probe(pos.x, pos.y, pos.z, out);
    }

    public void tick(StormManager storms, WeatherSystem weather) {
        if (!cfg.doAtmosphere) return;
        if (++tick % LOCAL_INTERVAL != 0 && tick % GLOBAL_INTERVAL != 0) {
            // 仍然需要给玩家做低氧检查（每秒一次）
            if (tick % 20 == 0) effectTick();
            return;
        }

        double cx = 0, cz = 0;
        int n = 0;
        double sumY = 0;
        for (ServerPlayer p : level.players()) {
            cx += p.getX();
            cz += p.getZ();
            sumY += p.getY();
            n++;
        }
        if (n == 0) {
            if (tick % 20 == 0) effectTick();
            return;
        }
        cx /= n;
        cz /= n;
        sumY /= n;

        long gameTime = level.getGameTime();
        Vec3 baseWind = storms.getWind().backgroundWind(
                new Vec3(cx, Math.max(sumY, level.getSeaLevel()), cz), gameTime);

        // v0.3: 全球近地面风作为背景场的第二来源。
        // 只有 FBM 噪声的话，"玩家身边的风"和"推着风暴走的风"是两套互不相干的场；
        // 把环流层的风混进来（权重 0.35、幅度 ±18 m/s），局部风才会跟着大尺度形势走。
        if (global.surfaceWind(cx, cz, surfaceWindBuf)) {
            baseWind = baseWind.add(
                    Mth.clamp(surfaceWindBuf[0] * 0.35f, -18f, 18f), 0.0,
                    Mth.clamp(surfaceWindBuf[1] * 0.35f, -18f, 18f));
        }

        boolean moved = grid.ensureAround(cx, cz, baseWind, gameTime);
        if (moved && !loggedOnce) {
            loggedOnce = true;
            com.stellar.pmwlite.PMWLite.LOGGER.info(
                    "[PMWLite] 本地大气网格已建立 ({}×{}×{} 格, 每格 {}×{}) —— 气压/风/湿度/温度/氧气开始求解",
                    AtmosphereGrid.SX, AtmosphereGrid.SY, AtmosphereGrid.SZ,
                    AtmosphereGrid.CELL, AtmosphereGrid.LAYER);
        }

        if (tick % LOCAL_INTERVAL == 0 && grid.ready()) {
            float rain = level.getRainLevel(1f);
            grid.step(gameTime, baseWind, rain);
        }
        if (tick % GLOBAL_INTERVAL == 0) {
            global.ensureAround(cx, cz, gameTime);
            global.step(gameTime, storms.getStorms());
        }
        if (tick % 20 == 0) effectTick();
    }

    /** 低氧后果：先给挖掘疲劳，再给缓慢伤害。数值只做提示，不做惩罚性秒杀。 */
    private void effectTick() {
        if (!cfg.oxygenEffects || level.players().isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            var probe = new AtmosphereGrid.Probe();
            if (!grid.probe(p.getX(), p.getEyeY(), p.getZ(), probe)) continue;
            if (p.isCreative() || p.isSpectator()) continue;

            if (probe.oxygen < O2_FATIGUE) {
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 80, 0, true, false, false));
            }
            if (probe.oxygen < O2_DAMAGE) {
                int cd = damageCooldown.getOrDefault(p.getUUID(), 0);
                if (cd <= 0) {
                    p.hurt(p.damageSources().drown(), 1.0f);
                    damageCooldown.put(p.getUUID(), 40);
                } else {
                    damageCooldown.put(p.getUUID(), cd - 20);
                }
            } else {
                damageCooldown.remove(p.getUUID());
            }
        }
        if (damageCooldown.size() > 256) {
            damageCooldown.keySet().retainAll(level.players().stream().map(ServerPlayer::getUUID).toList());
        }
    }
}
