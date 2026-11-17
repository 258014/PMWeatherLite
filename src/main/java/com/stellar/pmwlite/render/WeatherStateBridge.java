package com.stellar.pmwlite.render;

import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.sound.SoundManager;
import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WeatherSystem;
import com.stellar.pmwlite.weather.WeatherType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * THE KEY TO SHADER COMPATIBILITY.
 *
 * Drives Minecraft's vanilla weather state (raining / thundering) so shader packs such as
 * Complementary / Cristal / Aureveil render their own volumetric storm clouds + lightning.
 * The mod supplies the SIMULATION; the shader pack supplies the VISUALS. No post-processing
 * pipeline is hijacked -> full shader compatibility.
 *
 * OPTIMIZATION v2:
 *  - setWeatherParameters is now called ONLY when the desired state actually changes
 *    (was every tick -> caused a network packet storm to every player each tick).
 *  - Now also consumes WeatherSystem events, so all 17 weather types affect the sky.
 *  - Lightning uses per-level RNG and a cooldown; no allocations when idle.
 */
public final class WeatherStateBridge {
    private int thunderCountdown = 0;

    // Cached last-applied vanilla weather state, to avoid redundant sync packets.
    private boolean lastRaining = false;
    private boolean lastThundering = false;
    private boolean initialized = false;
    /** Storm ids that already had a touchdown siren fired (edge-trigger guard). */
    private final Set<Long> touchedDown = new HashSet<>();

    /** Optional config reference for warning toggle; may be null (defaults to on). */
    private final ServerConfig config;

    public WeatherStateBridge() { this(null); }
    public WeatherStateBridge(ServerConfig config) { this.config = config; }

    /** Called each server tick. Cheap when no weather is active. */
    public void tick(ServerLevel level, StormManager manager, WeatherSystem weather) {
        float maxPrecip = 0f;
        boolean strongStormNear = false;

        // 1) From storm physics. v4: storms on the outside so the player loop can break early.
        for (var player : level.players()) {
            maxPrecip = Math.max(maxPrecip, manager.precipitationAt(player.position()));
        }
        for (Storm s : manager.getStorms()) {
            if (s.windspeed <= 90) continue;
            double r2 = (double) s.influenceRadius * s.influenceRadius;
            for (var player : level.players()) {
                if (s.position.distanceToSqr(player.position()) < r2) { strongStormNear = true; break; }
            }
            if (strongStormNear) break;
        }

        // 2) From the full 17-type weather system -- ONE pass over players x events (v4).
        WeatherSystem.PlayerSample sample = weather == null
                ? WeatherSystem.PlayerSample.EMPTY : weather.sampleAtPlayers(level);
        if (sample.maxPrecip() > maxPrecip) maxPrecip = sample.maxPrecip();
        if (!strongStormNear) strongStormNear = sample.thunderous();

        boolean wantRain = maxPrecip > 0.15f;
        boolean wantThunder = strongStormNear;

        // Only write vanilla weather when state actually changes (network + stability fix)
        if (!initialized || wantRain != lastRaining || wantThunder != lastThundering) {
            level.setWeatherParameters(12000, wantRain ? 200 : 0, wantRain, wantThunder);
            lastRaining = wantRain;
            lastThundering = wantThunder;
            initialized = true;
        }

        // Lightning: cooldown-gated, only when a thunderstorm is present
        if (wantThunder && --thunderCountdown <= 0) {
            if (level.random.nextInt(400) == 0) {
                strikeLightningNearPlayer(level, manager);
                thunderCountdown = 200 + level.random.nextInt(600);
            }
        }

        // --- Sound + interaction feedback (edge-triggered, cooldown-gated) ---
        if (config != null && config.weatherWarnings) {
            announceTornadoTouchdown(level, manager);
            announceSevereWeather(level, sample);
        }
    }

    /** Tornado siren + action-bar warning when a funnel first touches down near players. */
    private void announceTornadoTouchdown(ServerLevel level, StormManager manager) {
        for (Storm s : manager.getStorms()) {
            if (!s.isTornadoOnGround()) continue;
            boolean was = touchedDown.contains(s.id);
            if (was) continue;
            touchedDown.add(s.id);
            SoundManager.alertNearby(level, s.position, 220,
                    SoundManager.Sfx.TORNADO_ALERT, level.getGameTime(), 400,
                    SoundManager.warningText("⚠ 龙卷风触地！EF" + efScale(s.windspeed)
                            + " — 立即寻找掩体", ChatFormatting.RED));
        }
        // prune ids of dead storms to avoid unbounded set growth
        if (touchedDown.size() > 64) {
            touchedDown.removeIf(id -> manager.getStorms().stream().noneMatch(s -> s.id == id));
        }
    }

    /** Severe-weather warning sound + action bar from the 17-type weather system. */
    private void announceSevereWeather(ServerLevel level, WeatherSystem.PlayerSample sample) {
        String name = sample.warning();
        if (name == null) return;
        SoundManager.alertNearby(level, firstPlayerPos(level), 200,
                SoundManager.Sfx.WARNING, level.getGameTime(), 600,
                SoundManager.warningText("⚠ 极端天气来袭：" + name, ChatFormatting.GOLD));
    }

    private Vec3 firstPlayerPos(ServerLevel level) {
        return level.players().isEmpty() ? Vec3.ZERO : level.players().get(0).position();
    }

    /** Map wind speed (approx mph) to an EF-scale label for the warning text. */
    private static String efScale(int windspeedMph) {
        if (windspeedMph >= 201) return "5";
        if (windspeedMph >= 166) return "4";
        if (windspeedMph >= 136) return "3";
        if (windspeedMph >= 111) return "2";
        if (windspeedMph >= 86) return "1";
        return "0";
    }

    private void strikeLightningNearPlayer(ServerLevel level, StormManager manager) {
        if (level.players().isEmpty()) return;
        var player = level.players().get(level.random.nextInt(level.players().size()));
        Vec3 p = player.position().add(
                (level.random.nextDouble() - 0.5) * 60, 0, (level.random.nextDouble() - 0.5) * 60);
        // Vanilla lightning entities -> shader packs render flash lighting automatically.
        spawnBolt(level, p);
        int branches = 1 + level.random.nextInt(2);
        for (int i = 0; i < branches; i++) {
            spawnBolt(level, p.add(
                    (level.random.nextDouble() - 0.5) * 24,
                    -level.random.nextDouble() * 20,
                    (level.random.nextDouble() - 0.5) * 24));
        }
    }

    private void spawnBolt(ServerLevel level, Vec3 p) {
        net.minecraft.world.entity.LightningBolt bolt =
                net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.setPos(p);
            level.addFreshEntity(bolt);
        }
    }
}
