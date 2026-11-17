package com.stellar.pmwlite.network;

import com.stellar.pmwlite.PMWLite;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Single Forge channel for all mod packets.
 *
 * The channel name/version are part of the mod's wire contract: bump {@link #PROTOCOL} whenever a
 * packet layout changes, so mismatched clients are rejected cleanly instead of desyncing.
 */
public final class NetworkHandler {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(PMWLite.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private NetworkHandler() {}

    public static void init() {
        int id = 0;
        CHANNEL.messageBuilder(RadarSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RadarSyncPacket::encode)
                .decoder(RadarSyncPacket::decode)
                .consumerMainThread(RadarSyncPacket::handle)
                .add();

        CHANNEL.messageBuilder(RadarRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RadarRequestPacket::encode)
                .decoder(RadarRequestPacket::decode)
                .consumerMainThread(RadarRequestPacket::handle)
                .add();
    }
}
