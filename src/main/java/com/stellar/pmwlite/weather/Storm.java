package com.stellar.pmwlite.weather;

import com.stellar.pmwlite.config.ServerConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ported from PMWeather Storm.java state machine (939 lines -> core simulation subset).
 * Rendering/entity/block registries are intentionally omitted: visuals are provided by
 * the shader pack, and destruction is handled by TornadoDestroyHandler.
 */
public class Storm {
    public static final float RESISTANCE = 0.985f;   // velocity damping per tick
    public static final float TICK_CONVERSION = 1f / 20f;
    public static final float RANKINE_FACTOR = 4.5f; // radius-of-max-wind divisor (from source)

    public long id;
    public Vec3 position;
    public Vec3 lastPosition;
    public Vec3 velocity = Vec3.ZERO;

    public StormType stormType;
    public int stage = 0;
    public int maxStage;
    public int energy = 0;
    public int windspeed;
    public int maxWindspeed;
    public float smoothWindspeed = 0f;
    public float width = 15f;
    public float maxWidth = 15f;
    public float smoothWidth = 15f;
    public float spin = 0f;
    public float lastSpin = 0f;

    public int tickCount = 0;
    public int touchdownSpeed = 65 + (int) (Math.random() * 55); // source: nextInt(65,120)
    public int tornadoOnGroundTicks = 0;
    public boolean dead = false;
    public boolean isDying = false;
    public int ticksSinceDying = 0;
    public int growthSpeed = 20;

    public float influenceRadius = 220f;
    public float growthRate = 1f;
    public float rainFactor = 1f;
    public List<Vorticy> vorticies = new ArrayList<>();
    private final NoiseField noise;

    public Storm(long id, Vec3 position, StormType type, NoiseField noise, ServerConfig cfg) {
        this.id = id;
        this.position = position;
        this.lastPosition = position;
        this.stormType = type;
        this.noise = noise;
        this.maxStage = type == StormType.FIRE_WHIRL ? 2 : 3;
        this.windspeed = (int) (40 * cfg.squallStrengthMultiplier);
        this.maxWindspeed = (int) (type.baseWindspeed * cfg.squallStrengthMultiplier);
        this.maxWidth = type.baseWidth;
        this.width = 15f;
        this.smoothWidth = this.width;
        spawnVorticies(cfg);
    }

    private void spawnVorticies(ServerConfig cfg) {
        int count = switch (stormType) {
            case SUPERCELL -> 3;
            case CYCLONE -> 5;
            default -> 1;
        };
        for (int i = 0; i < count; i++) {
            vorticies.add(new Vorticy(this,
                    0.5f + (float) Math.random() * 0.5f,
                    0.2f + (float) Math.random() * 0.5f,
                    0.1f + (float) Math.random() * 0.4f,
                    120 + (int) (Math.random() * 240)));
        }
    }

    public boolean isCyclone() { return stormType == StormType.CYCLONE; }

    /** Advance one tick: motion, stage growth, spin, energy, sub-vortices. */
    public void tick(ServerConfig cfg) {
        if (dead) return;
        tickCount++;

        lastPosition = position;
        velocity = velocity.scale(RESISTANCE).add(backgroundDrift(cfg));
        position = position.add(velocity.scale(TICK_CONVERSION));

        // spin accumulates with wind / width, mirroring VolumeShader.tick()
        lastSpin = spin;
        spin += (smoothWindspeed * 0.01f) / Math.max(smoothWidth, 20f);
        smoothWindspeed = Mth.lerp(0.02f, smoothWindspeed, windspeed);
        smoothWidth = Mth.lerp(0.02f, smoothWidth, width);

        // --- per-type physics (ported from Squall/Cyclone/FireWhirl) ---
        switch (stormType) {
            case SQUALL -> {
                // Squalls grow fast and produce frequent lightning but weaker tornado potential
                growthRate = 1.5f;
            }
            case CYCLONE -> {
                // Cyclones feed on sea surface temperature; intensify over warm water, decay over land.
                // Approximated with the noise temperature field (source: GetSST > 26C threshold).
                double sstField = noise.fbm(position.x / 15000.0, 0, position.z / 15000.0, 4, 2f, 0.5f, 1f);
                float sst = 27f + (float) sstField * 8f; // ocean temp proxy
                float growth = (sst - 26f) / 4.5f;
                growth = Mth.clamp(growth, -1.5f, 2f) * 1.25f;
                if (windspeed > 165) growth -= (windspeed - 165) / 15f;
                smoothWindspeed = Mth.clamp(smoothWindspeed + growth, 0, 220);
                windspeed = (int) smoothWindspeed;
                // cyclones are huge: wide influence
                influenceRadius = Math.max(width * 6f, 300f);
            }
            case FIRE_WHIRL -> {
                // Fire whirls feed on fire intensity and stay near the ground
                growthRate = 3f;
                if (windspeed >= 40) tornadoOnGroundTicks++;
                rainFactor = 0f; // no rain inside a fire whirl
            }
            default -> { }
        }

        // probabilistic stage progression, controlled by environment system
        // OPTIMIZATION v4: the environment FBM (4 simplex octaves) used to be evaluated every
        // tick even though the result only matters on the 20-tick stage gate. It is now
        // computed at most once per 20 ticks per storm (-95% noise work for this term).
        if (!isDying && stage < maxStage && tickCount % 20 == 0) {
            float chance = cfg.stageChance * growthRate;
            if (cfg.environmentSystem) chance *= environmentFactor();
            if (Math.random() < chance) {
                stage++;
                windspeed += cfg.windPerStage;
                width += cfg.widthPerStage;
                energy += 20;
            }
        }

        if (isDying) {
            ticksSinceDying++;
            windspeed = (int) (windspeed * 0.98f);
            if (ticksSinceDying > 200) dead = true;
        }

        // touchdown: strong storms put funnel on ground and can destroy blocks
        boolean onGround = stage >= 2 && windspeed >= touchdownSpeed;
        if (onGround) tornadoOnGroundTicks++;
        influenceRadius = Math.max(width * 4f, 120f);

        for (Vorticy v : vorticies) v.tick();
        vorticies.removeIf(v -> v.dead);
    }

    private Vec3 backgroundDrift(ServerConfig cfg) {
        double a = noise.fbm(position.x / 6000.0, position.z / 6000.0, tickCount / 12000.0, 3, 2f, 0.5f, 1f) * Math.PI;
        double spd = 6 + Math.abs(noise.sample(tickCount / 20000.0, 0, 0)) * 10;
        return new Vec3(Math.cos(a) * spd, 0, Math.sin(a) * spd).scale(cfg.backgroundWindMultiplier);
    }

    /** Environment factor boosts storms over warm ocean / high instability (simplified). */
    private float environmentFactor() {
        double t = noise.fbm(position.x / 15000.0, 0, position.z / 15000.0, 4, 2f, 0.5f, 1f);
        return (float) Mth.clamp(1 + t, 0.4f, 1.8f);
    }

    public boolean isTornadoOnGround() {
        return stage >= 2 && windspeed >= touchdownSpeed && tornadoOnGroundTicks > 0;
    }

    /** Rain intensity contribution, scaled by storm stage and type (squall/cyclone heavier, fire whirl none). */
    public float rainIntensity() {
        float base = switch (stormType) {
            case CYCLONE -> 0.8f + stage * 0.1f;
            case SQUALL -> 0.5f + stage * 0.15f;
            case FIRE_WHIRL -> 0f;
            default -> 0.3f + stage * 0.25f;
        };
        return base * rainFactor;
    }

    /** Lightning frequency per type (squalls闪电最频繁, 气旋几乎无). */
    public float lightningChance() {
        return switch (stormType) {
            case SQUALL -> 0.08f + stage * 0.01f;
            case SUPERCELL -> 0.03f + stage * 0.015f;
            default -> 0.01f;
        };
    }

    /** Temperature depression near an active storm (source: getTemperatureOffset). */
    public float getTemperatureOffset(Vec3 pos) {
        double d = pos.distanceTo(position);
        if (d > influenceRadius) return 0f;
        return (float) ((1 - d / influenceRadius) * (windspeed / 12f));
    }

    public enum StormType {
        SUPERCELL(0, 130, 26f),
        SQUALL(1, 95, 20f),
        CYCLONE(2, 160, 40f),
        FIRE_WHIRL(3, 70, 12f);

        public final int legacyId;
        public final int baseWindspeed;
        public final float baseWidth;

        StormType(int legacyId, int baseWindspeed, float baseWidth) {
            this.legacyId = legacyId;
            this.baseWindspeed = baseWindspeed;
            this.baseWidth = baseWidth;
        }
    }
}
