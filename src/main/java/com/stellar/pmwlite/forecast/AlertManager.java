package com.stellar.pmwlite.forecast;

import com.stellar.pmwlite.registry.ModRegistry;
import com.stellar.pmwlite.weather.StormManager;
// Forecast is a nested record of the engine; imported here so the alert code reads naturally.
import com.stellar.pmwlite.forecast.ForecastEngine.Forecast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns forecasts into player-facing alerts.
 *
 * Two triggers, mirroring real warning practice:
 *  - ESCALATION: the alert tier rises (blue -> yellow -> orange -> red) -> alert immediately;
 *  - REMINDER:   the tier stays >= yellow and 30 s have passed -> repeat once.
 *
 * Downgrades are silent (no "all clear" spam); the tier is stored so the next escalation fires.
 * All state is per-player and released on logout, so this cannot leak.
 */
public final class AlertManager {
    /** Minimum ticks between two reminders for the same player. */
    private static final long REMIND_TICKS = 600;

    private final Map<UUID, Integer> lastLevel = new HashMap<>();
    private final Map<UUID, Long> lastAlertTick = new HashMap<>();
    private int tick = 0;

    /** Called once per server tick per level. */
    public void tick(ServerLevel level, StormManager manager) {
        if (++tick % 20 != 0) return;               // 1 Hz is plenty for warnings
        if (level.players().isEmpty()) return;

        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            Forecast f = ForecastEngine.forecast(level, player.blockPosition(), manager);
            int level_ = f.alertLevel();
            UUID id = player.getUUID();

            if (level_ == ForecastEngine.LEVEL_NONE) {
                lastLevel.put(id, 0);
                continue;
            }

            int prev = lastLevel.getOrDefault(id, 0);
            boolean escalated = level_ > prev;
            Long last = lastAlertTick.get(id);
            boolean remind = level_ >= ForecastEngine.LEVEL_YELLOW
                    && last != null && now - last >= REMIND_TICKS;
            if (!escalated && !remind) continue;

            announce(player, f);
            lastLevel.put(id, level_);
            lastAlertTick.put(id, now);
        }

        // Bound the maps if players change dimensions without logging out.
        if (lastLevel.size() > 128) {
            lastLevel.keySet().retainAll(level.players().stream().map(ServerPlayer::getUUID).toList());
            lastAlertTick.keySet().retainAll(lastLevel.keySet());
        }
    }

    private void announce(ServerPlayer player, Forecast f) {
        String tier = switch (f.alertLevel()) {
            case ForecastEngine.LEVEL_RED -> "红色预警";
            case ForecastEngine.LEVEL_ORANGE -> "橙色预警";
            case ForecastEngine.LEVEL_YELLOW -> "黄色预警";
            default -> "蓝色预警";
        };
        StringBuilder sb = new StringBuilder();
        sb.append(f.colorCode()).append("⚠ ").append(tier);
        if (f.etaSeconds() >= 0) sb.append(" §7约 §f").append(f.etaSeconds()).append("s §7后");
        if (!f.expectedTypes().isEmpty()) sb.append("§7 ").append(f.expectedTypes().get(0));
        sb.append(" §8| §7风险 ").append(String.format("%.2f", f.risk()));
        sb.append(" §8| §7按 §fR §7查看雷达");

        player.displayClientMessage(Component.literal(sb.toString()), true);

        SoundEvent ev = switch (f.alertLevel()) {
            case ForecastEngine.LEVEL_RED -> ModRegistry.ALERT_RED.get();
            case ForecastEngine.LEVEL_ORANGE -> ModRegistry.ALERT_ORANGE.get();
            case ForecastEngine.LEVEL_YELLOW -> ModRegistry.ALERT_YELLOW.get();
            default -> ModRegistry.ALERT_BLUE.get();
        };
        player.playNotifySound(ev, SoundSource.WEATHER, 1.0f,
                f.alertLevel() >= ForecastEngine.LEVEL_ORANGE ? 0.8f : 1.0f);

        // Orange and above also lands in chat, so it is not missed behind the action bar.
        if (f.alertLevel() >= ForecastEngine.LEVEL_ORANGE) {
            player.sendSystemMessage(Component.literal("§6[PMWLite] §c" + tier
                    + " §7预计 " + Math.max(f.etaSeconds(), 0) + "s 后影响你所在区域"
                    + " · 可能 " + String.join("、", f.expectedTypes())
                    + " §8(用加固套件保护建筑)"));
        }
    }

    /** Release per-player state on logout. */
    public void forget(UUID id) {
        lastLevel.remove(id);
        lastAlertTick.remove(id);
    }

    public int currentLevel(UUID id) {
        return lastLevel.getOrDefault(id, 0);
    }
}
