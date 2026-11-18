package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 环境光学现象状态包（1 Hz，服务端→客户端）。
 *
 * 传的是"服务端判定用的那份强度"，客户端只做呈现，保证 HUD 文案与画面一致。
 * 各分项强度都发（客户端可叠加绘制），主导现象 kind 与 artistic 标记同包下发。
 * ~40 字节。
 */
public record AtmosOpticsPacket(
        int kind, boolean artistic, int durationSec,
        float parhelion, float halo, float fogbow, float afterglow, float aurora) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind);
        buf.writeBoolean(artistic);
        buf.writeVarInt(durationSec);
        buf.writeFloat(parhelion);
        buf.writeFloat(halo);
        buf.writeFloat(fogbow);
        buf.writeFloat(afterglow);
        buf.writeFloat(aurora);
    }

    public static AtmosOpticsPacket decode(FriendlyByteBuf buf) {
        return new AtmosOpticsPacket(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(AtmosOpticsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.stellar.pmwlite.client.ClientRadarCache.acceptOptics(msg));
    }

    /** 从光学场结果构造。 */
    public static AtmosOpticsPacket from(com.stellar.pmwlite.atmos.OpticalField.Result r) {
        return new AtmosOpticsPacket(
                r.dominant.ordinal(), r.artistic, r.durationSec,
                r.strength[com.stellar.pmwlite.atmos.OpticalField.Kind.PARHELION.ordinal()],
                r.strength[com.stellar.pmwlite.atmos.OpticalField.Kind.HALO.ordinal()],
                r.strength[com.stellar.pmwlite.atmos.OpticalField.Kind.FOGBOW.ordinal()],
                r.strength[com.stellar.pmwlite.atmos.OpticalField.Kind.AFTERGLOW.ordinal()],
                r.strength[com.stellar.pmwlite.atmos.OpticalField.Kind.AURORA.ordinal()]);
    }
}
