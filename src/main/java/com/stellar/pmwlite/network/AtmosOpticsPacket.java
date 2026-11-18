package com.stellar.pmwlite.network;

import com.stellar.pmwlite.atmos.LunarField;
import com.stellar.pmwlite.atmos.OpticalField;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 环境光学 + 天文状态包（1 Hz，服务端→客户端），协议 v5。
 *
 * 传的是"服务端判定用的那份强度"，客户端只做呈现，保证 HUD 文案与画面一致。
 * v0.3.4 把 5 个具名字段改成 {@code float[] strengths}，长度随
 * {@link OpticalField.KIND_COUNT} 自动扩展；新增现象不再需要改包结构。
 * 月亮状态（相/照度/高度/月食/月光）同包下发，服务端与客户端画的是同一轮月。
 *
 * ~120 字节（22 个 float），1 Hz 单玩家，成本可忽略。
 */
public record AtmosOpticsPacket(
        int kind, boolean artistic, int durationSec,
        float[] strengths,
        int moonPhase, float moonIllum, float moonAlt, float moonEclipse, float moonlight) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind);
        buf.writeBoolean(artistic);
        buf.writeVarInt(durationSec);
        float[] s = strengths == null ? new float[0] : strengths;
        buf.writeVarInt(s.length);
        for (float v : s) buf.writeFloat(v);
        buf.writeVarInt(moonPhase);
        buf.writeFloat(moonIllum);
        buf.writeFloat(moonAlt);
        buf.writeFloat(moonEclipse);
        buf.writeFloat(moonlight);
    }

    public static AtmosOpticsPacket decode(FriendlyByteBuf buf) {
        // 读取顺序必须与 encode 完全一致（先三个头字段，再数组，再月亮字段）。
        int kind = buf.readVarInt();
        boolean artistic = buf.readBoolean();
        int duration = buf.readVarInt();
        int n = Mth_clamp(buf.readVarInt(), 0, OpticalField.KIND_COUNT);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = buf.readFloat();
        int moonPhase = buf.readVarInt();
        float moonIllum = buf.readFloat();
        float moonAlt = buf.readFloat();
        float moonEclipse = buf.readFloat();
        float moonlight = buf.readFloat();
        return new AtmosOpticsPacket(kind, artistic, duration, s,
                moonPhase, moonIllum, moonAlt, moonEclipse, moonlight);
    }

    private static int Mth_clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static void handle(AtmosOpticsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.stellar.pmwlite.client.ClientRadarCache.acceptOptics(msg));
    }

    /** 兼容旧调用：没有月亮信息时用中性值。 */
    public static AtmosOpticsPacket from(com.stellar.pmwlite.atmos.OpticalField.Result r) {
        return from(r, 0L, 0f, 0f);
    }

    /** 从光学场结果与世界时间构造；月亮读数由 {@link LunarField} 统一推导。 */
    public static AtmosOpticsPacket from(com.stellar.pmwlite.atmos.OpticalField.Result r,
                                         long dayTime, float cloud, float rain) {
        return from(r, dayTime, cloud, rain, true);
    }

    /** 服务端开关 {@code moonSystem} 关闭时下发全 0 月亮读数，客户端画面与判定一起熄灭。 */
    public static AtmosOpticsPacket from(com.stellar.pmwlite.atmos.OpticalField.Result r,
                                         long dayTime, float cloud, float rain, boolean moonEnabled) {
        float[] s = new float[OpticalField.KIND_COUNT];
        System.arraycopy(r.strength, 0, s, 0, Math.min(s.length, r.strength.length));
        // 月食/月华的光学强度本身也要受开关约束，不能只关画面关不掉 HUD
        if (!moonEnabled) {
            s[OpticalField.Kind.MOON_CORONA.ordinal()] = 0f;
            s[OpticalField.Kind.LUNAR_ECLIPSE.ordinal()] = 0f;
        }
        return new AtmosOpticsPacket(r.dominant.ordinal(), r.artistic, r.durationSec, s,
                moonEnabled ? LunarField.phaseIndex(dayTime) : 0,
                moonEnabled ? LunarField.illumination(dayTime) : 0f,
                moonEnabled ? LunarField.altitudeDeg(dayTime) : -90f,
                moonEnabled ? LunarField.eclipse(dayTime) : 0f,
                moonEnabled ? LunarField.moonlight(dayTime, cloud, rain) : 0f);
    }
}
