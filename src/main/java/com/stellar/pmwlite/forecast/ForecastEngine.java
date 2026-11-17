package com.stellar.pmwlite.forecast;

import com.stellar.pmwlite.weather.NoiseField;
import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.Thermodynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Short-range severe-weather forecast.
 *
 * This is the "预报" half of the alert feature: it reuses the very same thermodynamic core that
 * drives storm spawning ({@link Thermodynamics#buildProfile} + {@link Thermodynamics#formationRisk}),
 * but samples it ahead of the event instead of waiting for a storm entity to exist.
 *
 * Cost control: the sounding is the expensive part, so results are cached per 64-block cell for
 * 200 ticks (10 s). Storm proximity is recomputed cheaply on every query so the ETA stays fresh.
 */
public final class ForecastEngine {
    /** Cache TTL for the thermodynamic part. */
    private static final int CACHE_TICKS = 200;
    /** Bounded cache: beyond this, entries are dropped wholesale (cheap and safe). */
    private static final int MAX_CELLS = 512;

    private static final Map<ServerLevel, NoiseField> NOISE = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<Long, Cached>> CACHE = new WeakHashMap<>();

    /** Alert tiers: 0 = none, 1 = blue, 2 = yellow, 3 = orange, 4 = red. */
    public static final int LEVEL_NONE = 0, LEVEL_BLUE = 1, LEVEL_YELLOW = 2, LEVEL_ORANGE = 3, LEVEL_RED = 4;

    private record Cached(float risk, int stormEtaTicks, long expiry) {}

    private ForecastEngine() {}

    public static void clear(ServerLevel level) {
        NOISE.remove(level);
        CACHE.remove(level);
    }

    private static NoiseField noise(ServerLevel level) {
        return NOISE.computeIfAbsent(level, l -> new NoiseField(l.getSeed() ^ 0x50CA57L));
    }

    /**
     * Compute (or reuse) a forecast at a position.
     *
     * @param manager may be null; when present, nearby storms refine the ETA
     */
    public static Forecast forecast(ServerLevel level, BlockPos pos, StormManager manager) {
        long now = level.getGameTime();
        long cx = pos.getX() >> 6;
        long cz = pos.getZ() >> 6;
        long key = (cx << 32) ^ (cz & 0xffffffffL);

        Map<Long, Cached> cache = CACHE.computeIfAbsent(level, l -> new HashMap<>());
        if (cache.size() > MAX_CELLS) cache.clear();

        Cached c = cache.get(key);
        if (c == null || c.expiry < now) {
            Vec3 center = new Vec3((cx << 6) + 32, pos.getY(), (cz << 6) + 32);
            float risk = riskAt(level, center, now);
            c = new Cached(risk, -1, now + CACHE_TICKS);
            cache.put(key, c);
        }

        int stormEta = manager == null ? -1 : etaFromStorms(manager, pos);
        int alert = alertLevelFor(c.risk());
        int eta = stormEta >= 0 ? stormEta : etaFromRisk(c.risk());

        boolean precise = com.stellar.pmwlite.asset.FixedAssetManager.of(level)
                .coveredByStation(pos);
        int confidence = confidenceFor(c.risk(), alert, precise);

        return new Forecast(alert, c.risk(), eta, headlineFor(alert, c.risk()),
                expectedTypes(level, pos, c.risk()), confidence, precise);
    }

    /** Thermodynamic risk at a point, identical formula to storm spawning. */
    private static float riskAt(ServerLevel level, Vec3 pos, long gameTime) {
        var biome = level.getBiome(BlockPos.containing(pos));
        float biomeTemp = biome.value().getBaseTemperature();
        float humidity = net.minecraft.util.Mth.clamp(
                biome.value().getModifiedClimateSettings().downfall(), 0f, 1f);
        float sfcTemp = biomeTemp > 0
                ? (float) Math.pow(biomeTemp / 1.85, 0.5) * 35f
                : -(float) Math.pow(-biomeTemp, 1.5) * 60f;
        sfcTemp += humidity * 3f + Thermodynamics.diurnalBonus(level.getDayTime());
        float sfcDew = sfcTemp - (1f - humidity) * 15f;

        Thermodynamics.Profile profile = Thermodynamics.buildProfile(
                noise(level), pos, sfcTemp, sfcDew, 1013.25f,
                level.getSeaLevel(), gameTime, 500, 8000);
        return Thermodynamics.formationRisk(profile, noise(level), pos, level.getDayTime(), 1.0f);
    }

    /** ETA from an existing storm that is approaching the position, or -1 when none is close. */
    private static int etaFromStorms(StormManager manager, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (Storm s : manager.getStorms()) {
            double d = Math.sqrt(s.position.distanceToSqr(Vec3.atCenterOf(pos)));
            if (d > 800) continue;
            if (d < best) best = d;
        }
        if (best == Double.MAX_VALUE) return -1;
        // 120 mph storms drift at roughly 8-14 m/s; use a conservative 10 m/s.
        int ticks = (int) (best / 10.0 * 20.0);
        return Math.max(0, Math.min(ticks, 3600));
    }

    /** Lead time implied by instability alone; higher risk means sooner. */
    private static int etaFromRisk(float risk) {
        if (risk < 0.35f) return -1;
        float t = Math.min((risk - 0.35f) / 1.0f, 1f);
        return (int) (3600 - t * 3000); // 3 min .. 30 s
    }

    public static int alertLevelFor(float risk) {
        if (risk < 0.35f) return LEVEL_NONE;
        if (risk < 0.65f) return LEVEL_BLUE;
        if (risk < 0.95f) return LEVEL_YELLOW;
        if (risk < 1.25f) return LEVEL_ORANGE;
        return LEVEL_RED;
    }

    private static int confidenceFor(float risk, int alert, boolean precise) {
        int base = 55 + Math.round(Math.min(Math.abs(risk - 0.35f) * 30f, 30f));
        if (alert >= LEVEL_ORANGE) base += 5;
        if (precise) base += 10;
        return Math.min(base, 98);
    }

    private static String headlineFor(int alert, float risk) {
        return switch (alert) {
            case LEVEL_RED -> "红色预警：数分钟内可能出现强对流";
            case LEVEL_ORANGE -> "橙色预警：大气极不稳定";
            case LEVEL_YELLOW -> "黄色预警：对流条件正在形成";
            case LEVEL_BLUE -> "蓝色预警：轻微不稳定";
            default -> "暂无强对流风险";
        };
    }

    private static List<String> expectedTypes(ServerLevel level, BlockPos pos, float risk) {
        List<String> out = new ArrayList<>();
        if (risk < 0.35f) return out;
        var biome = level.getBiome(pos);
        float temp = biome.value().getBaseTemperature();
        float downfall = biome.value().getModifiedClimateSettings().downfall();

        if (temp < 0.2f) {
            out.add("暴风雪/雪暴");
            if (downfall > 0.4f) out.add("冰暴");
        } else if (temp > 0.8f && downfall < 0.3f) {
            out.add("沙尘暴");
        } else if (temp > 0.6f && downfall > 0.5f) {
            out.add("超级单体雷暴");
            if (risk >= 0.95f) out.add("龙卷风");
            if (risk >= 1.15f) out.add("冰雹");
        } else if (temp > 0.5f) {
            out.add("飑线");
        } else {
            out.add("特大暴雨");
        }
        return out;
    }

    /**
     * Immutable forecast snapshot handed to the station GUI, the alert manager and the radar.
     *
     * @param alertLevel 0 none, 1 blue, 2 yellow, 3 orange, 4 red
     * @param risk       0..1.75 formation risk (same scale as storm spawning)
     * @param etaTicks   ticks until the expected impact, or -1 when there is no threat
     * @param precise    true when a weather station covers this position (better confidence)
     */
    public record Forecast(int alertLevel, float risk, int etaTicks, String headline,
                           List<String> expectedTypes, int confidence, boolean precise) {

        public static final Forecast CALM = new Forecast(0, 0f, -1, "暂无强对流风险", List.of(), 0, false);

        public int etaSeconds() {
            return etaTicks < 0 ? -1 : etaTicks / 20;
        }

        public String colorCode() {
            return switch (alertLevel) {
                case 4 -> "§c";
                case 3 -> "§6";
                case 2 -> "§e";
                case 1 -> "§9";
                default -> "§a";
            };
        }
    }
}
