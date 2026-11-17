package com.stellar.pmwlite.sound;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sound layer for PMWeatherLite.
 *
 * Design goals (minimal-footprint):
 *  - Reuse VANILLA sound events only -> no extra .ogg assets, no resource-pack risk,
 *    and shader packs are unaffected (sounds are pure client audio).
 *  - Per-player mute set (session scoped) + a global config switch.
 *  - Edge-triggered alerts with cooldown, so a storm does not spam sirens every tick.
 *
 * All playback is server-side: vanilla sends the packet to the relevant clients.
 */
public final class SoundManager {

    /** Vanilla sound events used by the mod, named by intent. */
    public static final class Sfx {
        /** Light UI tick for successful operations. */
        public static final SoundEvent UI_CONFIRM = SoundEvents.NOTE_BLOCK_PLING.value();
        /** Dull tone for rejected/invalid operations. */
        public static final SoundEvent UI_DENY = SoundEvents.NOTE_BLOCK_BASS.value();
        /** Heavy thunder for storm summon. */
        public static final SoundEvent SUMMON = SoundEvents.LIGHTNING_BOLT_THUNDER;
        /** Alarm-like horn for severe weather warning. */
        public static final SoundEvent WARNING = SoundEvents.RAID_HORN.value();
        /** Long bell for tornado touchdown warning. */
        public static final SoundEvent TORNADO_ALERT = SoundEvents.BELL_RESONATE;
        /** Bell for clearing weather. */
        public static final SoundEvent CLEAR = SoundEvents.BELL_BLOCK;
        /** Deep growl as an ominous ambience cue. */
        public static final SoundEvent OMINOUS = SoundEvents.ENDER_DRAGON_GROWL;
    }

    /** Players who muted mod sounds (session scoped). */
    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();
    /** Per-player cooldown for alert sounds, in game ticks. */
    private static final Map<UUID, Long> ALERT_COOLDOWN = new ConcurrentHashMap<>();

    private SoundManager() {}

    // ---------- mute state ----------

    public static boolean isMuted(ServerPlayer player) {
        return MUTED.contains(player.getUUID());
    }

    /** @return the new muted state. */
    public static boolean toggleMute(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!MUTED.remove(id)) {
            MUTED.add(id);
            return true;
        }
        return false;
    }

    public static void setMuted(ServerPlayer player, boolean muted) {
        if (muted) MUTED.add(player.getUUID());
        else MUTED.remove(player.getUUID());
    }

    /** Called on logout to avoid unbounded growth of the mute/cooldown maps. */
    public static void forget(UUID id) {
        MUTED.remove(id);
        ALERT_COOLDOWN.remove(id);
    }

    // ---------- playback ----------

    /** Play a sound at a position for everyone in range (respects each player's mute). */
    public static void playAt(ServerLevel level, Vec3 pos, SoundEvent sound,
                              SoundSource source, float volume, float pitch) {
        if (!com.stellar.pmwlite.config.ServerConfig.soundEnabled) return;
        for (ServerPlayer player : level.players()) {
            if (isMuted(player)) continue;
            if (player.distanceToSqr(pos) > 64 * 64) continue;
            player.playNotifySound(sound, source, volume, pitch);
        }
    }

    /** Play a sound only for one player (respects mute). Used for command feedback. */
    public static void playUi(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player == null) return;
        if (!com.stellar.pmwlite.config.ServerConfig.soundEnabled) return;
        if (isMuted(player)) return;
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    /**
     * Edge-triggered alert with a per-player cooldown.
     * Returns true if the alert was actually played to at least one player.
     */
    public static boolean alertNearby(ServerLevel level, Vec3 center, double radius,
                                      SoundEvent sound, long gameTime, int cooldownTicks,
                                      Component actionBar) {
        if (!com.stellar.pmwlite.config.ServerConfig.soundEnabled) return false;
        boolean played = false;
        double r2 = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) > r2) continue;
            // action bar feedback should show even if muted (it is text, not audio)
            if (actionBar != null) player.displayClientMessage(actionBar, true);
            if (isMuted(player)) continue;
            Long last = ALERT_COOLDOWN.get(player.getUUID());
            if (last != null && gameTime - last < cooldownTicks) continue;
            ALERT_COOLDOWN.put(player.getUUID(), gameTime);
            player.playNotifySound(sound, SoundSource.AMBIENT, 1.0f, 1.0f);
            played = true;
        }
        return played;
    }

    /** Small helper for consistent action-bar styling. */
    public static Component warningText(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
}
