package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求某一层大气数据。只带一个图层编号，服务端会做范围校验。
 */
public record AtmosLayerRequestPacket(int kind) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind);
    }

    public static AtmosLayerRequestPacket decode(FriendlyByteBuf buf) {
        return new AtmosLayerRequestPacket(buf.readVarInt());
    }

    public static void handle(AtmosLayerRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        var sender = c.getSender();
        if (sender != null) {
            com.stellar.pmwlite.radar.RadarService.sendLayer(sender, msg.kind());
        }
    }
}
