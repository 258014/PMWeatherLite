package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client radar snapshot.
 *
 * Carries everything the radar screen needs in one packet (storms, weather events, stations and
 * the local forecast) so the GUI never has to poke at server state.
 */
public record RadarSyncPacket(
        int alertLevel, float risk, int etaSeconds, int confidence, boolean precise,
        String headline, List<String> types, List<Blip> blips,
        int stormCount, int eventCount, int assetCount, int reinforcedCount) {

    /** kind: 0 = storm physics, 1 = weather event, 2 = fixed asset (weather station). */
    public record Blip(byte kind, float dx, float dz, float radius, float intensity, int code) {}

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(alertLevel);
        buf.writeFloat(risk);
        buf.writeVarInt(etaSeconds);
        buf.writeVarInt(confidence);
        buf.writeBoolean(precise);
        buf.writeUtf(headline, 128);
        buf.writeVarInt(types.size());
        for (String t : types) buf.writeUtf(t, 64);
        buf.writeVarInt(blips.size());
        for (Blip b : blips) {
            buf.writeByte(b.kind());
            buf.writeFloat(b.dx());
            buf.writeFloat(b.dz());
            buf.writeFloat(b.radius());
            buf.writeFloat(b.intensity());
            buf.writeVarInt(b.code());
        }
        buf.writeVarInt(stormCount);
        buf.writeVarInt(eventCount);
        buf.writeVarInt(assetCount);
        buf.writeVarInt(reinforcedCount);
    }

    public static RadarSyncPacket decode(FriendlyByteBuf buf) {
        int alert = buf.readVarInt();
        float risk = buf.readFloat();
        int eta = buf.readVarInt();
        int conf = buf.readVarInt();
        boolean precise = buf.readBoolean();
        String headline = buf.readUtf(128);
        int nTypes = buf.readVarInt();
        List<String> types = new ArrayList<>(nTypes);
        for (int i = 0; i < nTypes; i++) types.add(buf.readUtf(64));
        int nBlips = buf.readVarInt();
        List<Blip> blips = new ArrayList<>(nBlips);
        for (int i = 0; i < nBlips; i++) {
            blips.add(new Blip(buf.readByte(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readVarInt()));
        }
        return new RadarSyncPacket(alert, risk, eta, conf, precise, headline, types, blips,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(RadarSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.stellar.pmwlite.client.ClientRadarCache.accept(msg));
    }
}
