package com.stellar.pmwlite.weather;

import com.stellar.pmwlite.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Real-world severe weather system manager. Complements the storm physics engine by
 * managing the full WeatherType taxonomy: spawning, intensity grading, duration,
 * affected radius, and environmental effects — all grounded in the parameters encoded
 * in WeatherType (CMA/NWS/EF/Saffir-Simpson standards).
 *
 * Visuals stay shader-compatible: this class only drives vanilla weather state and
 * exposes active events for the particle FX systems; it never touches post-processing.
 */
public final class WeatherSystem {
    private final ServerLevel level;
    private final ServerConfig cfg;
    private final NoiseField noise;
    private final List<WeatherEvent> active = new ArrayList<>();
    private int checkCooldown = 0;

    public WeatherSystem(ServerLevel level, ServerConfig cfg) {
        this.level = level;
        this.cfg = cfg;
        this.noise = new NoiseField(level.getSeed() ^ 0x5EED);
    }

    /** One active extreme-weather event. */
    public static final class WeatherEvent {
        public final WeatherType type;
        public final Vec3 center;
        public final int startTick;
        public final int durationTicks;
        public int grade = 0;          // intensity grade within the type's scale
        public float intensity = 0f;   // 0..1 normalized
        public Vec3 velocity = Vec3.ZERO;

        public WeatherEvent(WeatherType type, Vec3 center, int now) {
            this.type = type;
            this.center = center;
            this.startTick = now;
            this.durationTicks = type.durationTicks();
            this.grade = (int) (Math.random() * gradeCount(type));
        }

        public float progress(int now) {
            return (float) (now - startTick) / durationTicks;
        }
        public boolean expired(int now) { return now - startTick >= durationTicks; }

        /** Remaining lifetime in whole seconds (for /pmwlite status display). */
        public int remainingSeconds(long now) {
            int rem = durationTicks - (int) (now - startTick);
            return Math.max(rem / 20, 0);
        }
    }

    private static int gradeCount(WeatherType t) {
        return switch (t) {
            case TORNADO -> 6;                       // EF0-EF5
            case HURRICANE -> 6;                     // TD, TS, Cat1-4 (5 scaled)
            case DUST_STORM -> 5;                    // 浮尘→特强
            case HEAT_WAVE, COLD_WAVE -> 3;          // 黄/橙/红
            case ICE_STORM -> 4;                     // 轻微→灾难
            default -> 3;
        };
    }

    /** Called each server tick. */
    public void tick() {
        int now = (int) level.getGameTime();
        active.removeIf(e -> e.expired(now));

        if (--checkCooldown <= 0) {
            evaluateSpawn(now);
            checkCooldown = 600; // every 30 seconds
        }

        // Update intensity envelope: ramp up, peak, decay.
        for (WeatherEvent e : active) {
            float p = e.progress(now);
            e.intensity = Math.min(p * 5f, 1f) * Math.min((1f - p) * 5f, 1f);
        }
    }

    private void evaluateSpawn(int now) {
        if (level.players().isEmpty()) return;
        var player = level.players().get(level.random.nextInt(level.players().size()));
        Vec3 base = player.position();

        // OPTIMIZATION v4: the environment (biome temperature/downfall/ocean flag) is sampled
        // ONCE per evaluation. It used to be recomputed inside meetsConditions() for each of
        // the 17 weather types -- 17 biome lookups and 17 registry-key Strings every 30 s.
        Environment env = environmentAt(base);

        // Weighted pick over the eligible types.
        // OPTIMIZATION v4: the old code materialised the weights by appending each type to an
        // ArrayList up to 40 times (up to 680 entries, allocated every evaluation) and then
        // picked uniformly. A cumulative-weight roll gives the exact same probability
        // distribution in O(17) time and O(1) space.
        int totalWeight = 0;
        for (WeatherType t : WeatherType.values()) {
            if (meetsConditions(t, env)) totalWeight += weightOf(t);
        }
        if (totalWeight <= 0) return;
        if (Math.random() > 0.25f) return; // spawn chance per evaluation

        int roll = level.random.nextInt(totalWeight);
        WeatherType chosen = null;
        for (WeatherType t : WeatherType.values()) {
            if (!meetsConditions(t, env)) continue;
            roll -= weightOf(t);
            if (roll < 0) { chosen = t; break; }
        }
        if (chosen == null) return;

        double ang = Math.random() * Math.PI * 2;
        double r = chosen.outerRadiusBlocks * (0.3 + Math.random() * 0.3);
        active.add(new WeatherEvent(chosen, base.add(Math.cos(ang) * r, 0, Math.sin(ang) * r), now));
    }

    /** Spawn weight: common weather more likely; planetary/extreme events far rarer (capped at 40). */
    private static int weightOf(WeatherType t) {
        return Math.min(Math.max(1, (int) (40f / t.spawnRarity)), 40);
    }

    /** Snapshot of the environment at a candidate spawn point (sampled once per evaluation). */
    private record Environment(float temp, float downfall, float y, boolean ocean) {}

    private Environment environmentAt(Vec3 pos) {
        var biome = level.getBiome(net.minecraft.core.BlockPos.containing(pos));
        float temp = biome.value().getBaseTemperature();
        float downfall = biome.value().getModifiedClimateSettings().downfall();
        var key = level.registryAccess().registry(net.minecraft.core.registries.Registries.BIOME)
                .get().getKey(biome.value());
        return new Environment(temp, downfall, (float) pos.y, key != null && key.toString().contains("ocean"));
    }

    /** Check real-world formation conditions against the current environment. */
    private boolean meetsConditions(WeatherType t, Environment env) {
        float temp = env.temp();
        boolean highTemp = temp > 0.6f;
        boolean cold = temp < 0.2f || env.y() > 90;
        boolean wet = env.downfall() > 0.5f;
        boolean dry = env.downfall() < 0.3f;
        return switch (t.category) {
            case CONVECTIVE -> wet && highTemp;                 // 强对流需暖湿
            case TROPICAL -> env.ocean() && highTemp;           // 热带气旋需暖洋面
            case WINTER -> cold || (wet && temp < 0.5f);        // 冬季降水需低温
            case DUST -> dry && highTemp;                       // 沙尘需干旱
            case THERMAL -> true;                               // 热浪/寒潮按地点判定
            case FIRE -> dry && highTemp;                       // 火天气需干热
            case HYDRO -> wet;                                  // 洪涝需水汽
        };
    }

    /** Aggregate precipitation intensity from all active events near a position. */
    public float precipitationAt(Vec3 pos) {
        float total = 0;
        for (WeatherEvent e : active) {
            double d = pos.distanceTo(e.center);
            if (d < e.type.outerRadiusBlocks) {
                float falloff = (float) (1 - d / e.type.outerRadiusBlocks);
                total += falloff * e.intensity * basePrecip(e.type);
            }
        }
        return Math.min(total, 1f);
    }

    private float basePrecip(WeatherType t) {
        return switch (t) {
            case RAINSTORM, FLASH_FLOOD, HURRICANE, TYPHOON_EXTRATROPICAL -> 1f;
            case SQUALL_LINE, SUPERCELL, DOWNBURST -> 0.8f;
            case BLIZZARD, SLEET, ICE_STORM -> 0.6f;
            case DUST_STORM, SANDSTORM_MARS, HEAT_WAVE, COLD_WAVE, WILDFIRE -> 0f;
            default -> 0.3f;
        };
    }

    /** Current threat level near a position, used to drive warning subtitles. */
    public String warningNear(Vec3 pos) {
        String worst = null;
        float worstScore = 0;
        for (WeatherEvent e : active) {
            double d = pos.distanceTo(e.center);
            if (d < e.type.coreRadiusBlocks) {
                float score = e.intensity * (1 - (float) d / e.type.coreRadiusBlocks);
                if (score > worstScore) { worstScore = score; worst = e.type.name; }
            }
        }
        return worstScore > 0.3f ? worst : null;
    }

    /** Aggregate wind from all active events at a point (extra forcing for WindField). */
    public Vec3 windAt(Vec3 pos, long gameTime) {
        Vec3 total = Vec3.ZERO;
        for (WeatherEvent e : active) {
            double d = pos.distanceTo(e.center);
            if (d > e.type.outerRadiusBlocks || d < 1e-3) continue;
            double ang = Math.atan2(pos.z - e.center.z, pos.x - e.center.x);
            float speed = e.intensity * windSpeedFor(e) * (float) (1 - d / e.type.outerRadiusBlocks);
            // cyclonic events rotate; straight-line events push radially outward
            Vec3 dir;
            if (e.type.category == WeatherType.Category.TROPICAL || e.type == WeatherType.TORNADO) {
                dir = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
            } else {
                dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            }
            total = total.add(dir.scale(speed));
        }
        return total;
    }

    private float windSpeedFor(WeatherEvent e) {
        return switch (e.type) {
            case TORNADO -> 30 + e.grade * 12f;      // EF0..EF5
            case HURRICANE -> 25 + e.grade * 10f;
            case SQUALL_LINE, DOWNBURST -> 28f;
            case DUST_STORM -> 18 + e.grade * 4f;
            case BLIZZARD -> 22f;
            default -> 8f;
        };
    }

    public List<WeatherEvent> getActive() { return Collections.unmodifiableList(active); }
    public NoiseField getNoise() { return noise; }

    /** Clear all active events (used by /pmwlite clear). */
    public void clearAll() { active.clear(); }

    /**
     * Per-tick aggregate over every player/event pair, returned as one immutable snapshot.
     *
     * OPTIMIZATION v4: the weather bridge used to walk the players x events grid three
     * separate times per tick (precipitationAtFirstPlayer + hasThunderstormNearPlayers +
     * warningNearPlayers). With P players and E events that was 3*P*E distance computations
     * plus three full iterations of the event list every tick; it is now a single pass.
     */
    public record PlayerSample(float maxPrecip, boolean thunderous, String warning) {
        public static final PlayerSample EMPTY = new PlayerSample(0f, false, null);
    }

    public PlayerSample sampleAtPlayers(ServerLevel level) {
        if (level.players().isEmpty() || active.isEmpty()) return PlayerSample.EMPTY;

        float maxPrecip = 0f;
        boolean thunderous = false;
        String worstName = null;
        float worstScore = 0f;

        for (var player : level.players()) {
            Vec3 pp = player.position();
            float sum = 0f;
            for (int i = 0, n = active.size(); i < n; i++) {
                WeatherEvent e = active.get(i);
                double outer = e.type.outerRadiusBlocks;
                double d = pp.distanceTo(e.center);

                if (d < outer) {
                    float falloff = (float) (1 - d / outer);
                    sum += falloff * e.intensity * basePrecip(e.type);
                    if (!thunderous && e.intensity >= 0.3f
                            && (e.type.category == WeatherType.Category.CONVECTIVE
                            || e.type.category == WeatherType.Category.HYDRO)) {
                        thunderous = true;
                    }
                }
                double core = e.type.coreRadiusBlocks;
                if (d < core) {
                    float score = e.intensity * (1f - (float) d / e.type.coreRadiusBlocks);
                    if (score > worstScore) { worstScore = score; worstName = e.type.name; }
                }
            }
            if (sum > maxPrecip) maxPrecip = sum;
        }
        return new PlayerSample(Math.min(maxPrecip, 1f), thunderous,
                worstScore > 0.3f ? worstName : null);
    }

    /**
     * Returns the display name of the most threatening active event near any player,
     * or null if none. Thin wrapper over {@link #sampleAtPlayers(ServerLevel)}.
     */
    public String warningNearPlayers(ServerLevel level) {
        return sampleAtPlayers(level).warning();
    }

    /** Manually trigger an event (debug / script integration). */
    public WeatherEvent trigger(WeatherType type, Vec3 pos) {
        WeatherEvent e = new WeatherEvent(type, pos, (int) level.getGameTime());
        active.add(e);
        return e;
    }
}
