package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 玩家所在位置的大气探针 / Local atmosphere probe.
 *
 * 1 Hz 广播。HUD 与雷达信息栏都读它——这样客户端不需要自己算热力学，
 * 也保证"屏幕上的数字"和"服务端判定低氧用的数字"是同一份，不会出现显示与判定不一致。
 *
 * {@code valid=false} 表示网格还没建立（刚进服/刚跨维度），客户端应显示"初始化中"。
 */
public record AtmosProbePacket(
        boolean valid, boolean underground,
        float pressureHpa, float tempC, float humidity, float oxygen,
        float windSpeed, float apparentC, float dewPointC, float cloudBaseM,
        float cloud, float groundT, float co2,
        float so2, float no2, float co, float pm,
        float exposure, int exposureGrade, float visibility) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(valid);
        buf.writeBoolean(underground);
        buf.writeFloat(pressureHpa);
        buf.writeFloat(tempC);
        buf.writeFloat(humidity);
        buf.writeFloat(oxygen);
        buf.writeFloat(windSpeed);
        buf.writeFloat(apparentC);
        buf.writeFloat(dewPointC);
        buf.writeFloat(cloudBaseM);
        buf.writeFloat(cloud);
        buf.writeFloat(groundT);
        buf.writeFloat(co2);
        buf.writeFloat(so2);
        buf.writeFloat(no2);
        buf.writeFloat(co);
        buf.writeFloat(pm);
        // v0.3.4：多气体联合暴露，供 HUD 显示"谁在起作用"，与服务端判定同源
        buf.writeFloat(exposure);
        buf.writeVarInt(exposureGrade);
        buf.writeFloat(visibility);
    }

    public static AtmosProbePacket decode(FriendlyByteBuf buf) {
        return new AtmosProbePacket(buf.readBoolean(), buf.readBoolean(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(AtmosProbePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.stellar.pmwlite.client.ClientRadarCache.acceptProbe(msg));
    }
}
