package com.stellar.pmwlite.weather;

import com.stellar.pmwlite.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side storm lifecycle manager. Ported conceptually from PMWeather WeatherHandlerServer
 * but rebuilt on Forge with a plain per-dimension manager (no NeoForge DataAttachments).
 */
public final class StormManager {
    private final ServerLevel level;
    private final NoiseField noise;
    private final WindField wind;
    private final ServerConfig cfg;
    private final List<Storm> storms = new ArrayList<>();
    private long nextId = 1;
    private int spawnCooldownTicks = 0;

    public StormManager(ServerLevel level, ServerConfig cfg) {
        this.level = level;
        this.cfg = cfg;
        this.noise = new NoiseField(level.getSeed());
        this.wind = new WindField(noise, cfg.backgroundWindMultiplier, 1f);
    }

    /** Called every server tick from ServerTickEvent.Post. */
    public void tick() {
        for (Storm s : storms) s.tick(cfg);
        storms.removeIf(s -> s.dead);

        if (--spawnCooldownTicks <= 0) {
            trySpawn();
            spawnCooldownTicks = 20 * 60; // evaluate once per minute
        }
    }

    private void trySpawn() {
        if (storms.size() >= cfg.maxStorms) return;
        if (level.players().isEmpty()) return;
        if (Math.random() > cfg.stormSpawnChancePerMinute) return;

        var player = level.players().get(level.random.nextInt(level.players().size()));
        double ang = Math.random() * Math.PI * 2;
        double r = cfg.spawnRange * (0.6 + Math.random() * 0.4);
        Vec3 pos = player.position().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);

        // --- Thermodynamic gating: only spawn where the atmosphere is actually unstable ---
        float risk = formationRiskAt(pos);
        if (risk < 0.35f) return; // stable atmosphere -> no storm (source: RiskEngine)
        // Higher risk -> stronger, wider storms
        float strength = MthHelper.clamp(risk / 1.2f, 0.6f, 1.6f);

        Storm.StormType type = Storm.StormType.SUPERCELL;
        int roll = level.random.nextInt(cfg.chanceInOneCyclone);
        if (roll == 0 && cfg.doCyclones && risk > 0.9f) type = Storm.StormType.CYCLONE;
        else if (level.random.nextInt(cfg.chanceInOneSquall) == 0 && cfg.doSqualls) type = Storm.StormType.SQUALL;
        if (type == Storm.StormType.SUPERCELL && !cfg.doTornadoes) type = Storm.StormType.SQUALL;

        Storm s = new Storm(nextId++, pos, type, noise, cfg);
        s.windspeed = (int) (s.windspeed * strength);
        s.maxWindspeed = (int) (s.maxWindspeed * strength);
        s.maxWidth *= strength;
        storms.add(s);
    }

    /** Sample atmospheric instability at a position via the ported thermodynamics core. */
    private float formationRiskAt(Vec3 pos) {
        var biome = level.getBiome(net.minecraft.core.BlockPos.containing(pos));
        float biomeTemp = biome.value().getBaseTemperature();
        float humidity = MthHelper.clamp(biome.value().getModifiedClimateSettings().downfall(), 0f, 1f);
        float sfcTemp = biomeTemp > 0
                ? (float) Math.pow(biomeTemp / 1.85, 0.5) * 35f
                : -(float) Math.pow(-biomeTemp, 1.5) * 60f;
        sfcTemp += humidity * 3f + Thermodynamics.diurnalBonus(level.getDayTime());
        float sfcDew = sfcTemp - (1f - humidity) * 15f;
        float sfcPressure = 1013.25f;
        int groundY = level.getSeaLevel();
        // v4: array-backed sounding (no boxing); res is coarse because this only runs once
        // per minute per spawn attempt and CAPE integrates smoothly over 250 m layers.
        Thermodynamics.Profile profile =
                Thermodynamics.buildProfile(noise, pos, sfcTemp, sfcDew, sfcPressure,
                        groundY, level.getGameTime(), 250, 16000);
        return Thermodynamics.formationRisk(profile, noise, pos, level.getDayTime(), 1.0f);
    }

    /** Small Mth shim to avoid importing server-side Mth in this helper. */
    private static final class MthHelper {
        static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    /** Convenience helper used by the command to summon a supercell at a position. */
    public static void summonAt(StormManager manager, Vec3 pos) {
        manager.summon(pos, Storm.StormType.SUPERCELL);
    }

    public List<Storm> getStorms() { return storms; }
    public WindField getWind() { return wind; }
    public NoiseField getNoise() { return noise; }

    /** Summon a storm at a position (used by /pmwlite summon command). */
    public Storm summon(Vec3 pos, Storm.StormType type) {
        Storm s = new Storm(nextId++, pos, type, noise, cfg);
        storms.add(s);
        return s;
    }

    /** Total precipitation intensity near a position (drives vanilla rain state). */
    public float precipitationAt(Vec3 pos) {
        float total = 0f;
        for (Storm s : storms) {
            // v4: squared-distance reject first -- avoids a sqrt for storms that cannot reach.
            double r = s.influenceRadius;
            double d2 = pos.distanceToSqr(s.position);
            if (d2 < r * r) {
                double d = Math.sqrt(d2);
                total += (float) (1 - d / r) * (s.rainIntensity());
            }
        }
        return Math.min(total, 1f);
    }
}
