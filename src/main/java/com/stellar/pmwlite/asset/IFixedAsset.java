package com.stellar.pmwlite.asset;

import net.minecraft.core.BlockPos;

/**
 * Marker for "fixed assets": placeable objects owned by this mod that
 *  - are registered in {@link FixedAssetManager} so the existing object-management logic
 *    (storm scans, destruction, radar) can see them,
 *  - never get ripped up by a tornado, regardless of wind speed.
 *
 * Kept as an interface so future assets (anemometer, siren, shelter) only need to implement it
 * and call the manager; no change to StormManager / WeatherSystem is required.
 */
public interface IFixedAsset {

    /** Registry-style id used in messages and radar tooltips. */
    String assetId();

    /**
     * Wind threshold in the same units as {@code ServerConfig.blockStrengths}.
     *
     * @return a positive threshold, or a negative value when the asset is immune
     */
    default int windThreshold() {
        return -1;
    }

    /** Called once when the asset appears in the world (server side only). */
    default void onRegistered(net.minecraft.server.level.ServerLevel level, BlockPos pos) {}

    /** Called when the asset is removed or its chunk unloads (server side only). */
    default void onUnregistered(net.minecraft.server.level.ServerLevel level, BlockPos pos) {}
}
