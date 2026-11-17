package com.stellar.pmwlite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 大气图层数据 / Atmosphere layer snapshot.
 *
 * 雷达 GUI 一次只看一层，所以这个包只带一层：16×16 的量化值 + 取值范围 + 单位。
 * 主值放 {@code cells}，需要第二个分量的图层（风场：角度 + 速度）放 {@code aux}。
 *
 * 量化方式：{@code byte = round((v - min) / (max - min) * 255)}，NaN（实心地形）固定为 -1，
 * 客户端据此画空洞而不是画成 0。32×32 字节 = 1 KB 上限，实测整包约 300 B。
 */
public record AtmosLayerPacket(
        int kind, int gridSize, int originX, int originZ, int cellSize,
        float min, float max, String label, String unit,
        byte[] cells, byte[] aux, float auxMin, float auxMax) {

    /** 图层种类，顺序与客户端切换顺序一致。 */
    public static final int LAYER_PRECIP = 0;
    public static final int LAYER_PRESSURE = 1;
    public static final int LAYER_TEMP = 2;
    public static final int LAYER_HUMIDITY = 3;
    public static final int LAYER_OXYGEN = 4;
    public static final int LAYER_WIND = 5;
    public static final int LAYER_APPARENT = 6;
    public static final int LAYER_COUNT = 7;

    public static final String[] NAMES = {
            "降水/云量", "气压", "气温", "相对湿度", "氧气", "风场", "体感温度"
    };

    public static AtmosLayerPacket empty(int kind) {
        return new AtmosLayerPacket(kind, 0, 0, 0, 16, 0f, 0f,
                NAMES[Math.max(0, Math.min(kind, NAMES.length - 1))], "", new byte[0], null, 0f, 0f);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind);
        buf.writeVarInt(gridSize);
        buf.writeVarInt(originX);
        buf.writeVarInt(originZ);
        buf.writeVarInt(cellSize);
        buf.writeFloat(min);
        buf.writeFloat(max);
        buf.writeUtf(label, 32);
        buf.writeUtf(unit, 8);
        buf.writeByteArray(cells == null ? new byte[0] : cells);
        buf.writeBoolean(aux != null);
        if (aux != null) {
            buf.writeByteArray(aux);
            buf.writeFloat(auxMin);
            buf.writeFloat(auxMax);
        }
    }

    public static AtmosLayerPacket decode(FriendlyByteBuf buf) {
        int kind = buf.readVarInt();
        int size = buf.readVarInt();
        int ox = buf.readVarInt();
        int oz = buf.readVarInt();
        int cell = buf.readVarInt();
        float min = buf.readFloat();
        float max = buf.readFloat();
        String label = buf.readUtf(32);
        String unit = buf.readUtf(8);
        byte[] cells = buf.readByteArray();
        byte[] aux = null;
        float auxMin = 0f;
        float auxMax = 0f;
        if (buf.readBoolean()) {
            aux = buf.readByteArray();
            auxMin = buf.readFloat();
            auxMax = buf.readFloat();
        }
        return new AtmosLayerPacket(kind, size, ox, oz, cell, min, max, label, unit, cells, aux, auxMin, auxMax);
    }

    public static void handle(AtmosLayerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.stellar.pmwlite.client.ClientRadarCache.acceptLayer(msg));
    }
}
