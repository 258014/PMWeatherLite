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
    /**
     * v0.3 新增了图层收/发两个包，协议号必须跟着升，否则 v0.2 客户端会用错位的解码器
     * 去读 v0.3 的数据包。宁可让版本不匹配的客户端连不上，也不要静默串包。
     */
    private static final String PROTOCOL = "5";

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

        CHANNEL.messageBuilder(AtmosLayerPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AtmosLayerPacket::encode)
                .decoder(AtmosLayerPacket::decode)
                .consumerMainThread(AtmosLayerPacket::handle)
                .add();

        CHANNEL.messageBuilder(AtmosLayerRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AtmosLayerRequestPacket::encode)
                .decoder(AtmosLayerRequestPacket::decode)
                .consumerMainThread(AtmosLayerRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(AtmosProbePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AtmosProbePacket::encode)
                .decoder(AtmosProbePacket::decode)
                .consumerMainThread(AtmosProbePacket::handle)
                .add();

        CHANNEL.messageBuilder(AtmosOpticsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AtmosOpticsPacket::encode)
                .decoder(AtmosOpticsPacket::decode)
                .consumerMainThread(AtmosOpticsPacket::handle)
                .add();
    }
}
