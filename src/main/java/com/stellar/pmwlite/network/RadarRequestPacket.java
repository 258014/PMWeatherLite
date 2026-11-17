package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: "I opened the radar, send me a snapshot".
 *
 * Empty body by design; the request is rate-limited server-side by the sender's own screen
 * (one request per second) and carries no client-controlled data.
 */
public record RadarRequestPacket() {

    public void encode(FriendlyByteBuf buf) {
        // no payload
    }

    public static RadarRequestPacket decode(FriendlyByteBuf buf) {
        return new RadarRequestPacket();
    }

    public static void handle(RadarRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        var sender = c.getSender();
        if (sender != null) {
            com.stellar.pmwlite.radar.RadarService.sendTo(sender);
        }
    }
}
