package com.stellar.pmwlite.atmos;

import net.minecraft.util.Mth;

/**
 * 体感温度 / Apparent temperature.
 *
 * 参考现实世界两套官方经验公式，而不是拍一个线性系数：
 *  - 风寒指数 Wind Chill（加拿大环境部 / NWS 2001 修订式），适用于 T ≤ 10 ℃、风速 > 4.8 km/h；
 *  - 热指数 Heat Index（Rothfusz 回归，NWS），适用于 T ≥ 27 ℃。
 *
 * 两者都只依赖气温、风速、相对湿度三个量——正好是本地大气网格里已有的字段，
 * 所以这套换算不引入任何额外状态。
 */
public final class ApparentTemperature {

    private ApparentTemperature() {}

    /**
     * 风寒温度（℃）。不满足适用条件时返回原气温。
     *
     * @param tempC  气温
     * @param windMs 风速（m/s）
     */
    public static float windChill(float tempC, float windMs) {
        if (tempC > 10f || windMs < 1.34f) return tempC;   // 1.34 m/s ≈ 4.8 km/h
        double v = windMs * 3.6;                            // km/h
        double p = Math.pow(v, 0.16);
        double wc = 13.12 + 0.6215 * tempC - 11.37 * p + 0.3965 * tempC * p;
        return (float) wc;
    }

    /**
     * 热指数（℃）。Rothfusz 回归在高温高湿区间才成立，其余区间返回原气温。
     *
     * @param tempC 气温
     * @param rh    相对湿度 0..1
     */
    public static float heatIndex(float tempC, float rh) {
        if (tempC < 27f) return tempC;
        double t = tempC * 9.0 / 5.0 + 32.0;   // ℉
        double r = Mth.clamp(rh, 0f, 1f) * 100.0;
        double hi = -42.379 + 2.04901523 * t + 10.14333127 * r
                - 0.22475541 * t * r - 6.83783e-3 * t * t
                - 5.481717e-2 * r * r + 1.22874e-3 * t * t * r
                + 8.5282e-4 * t * r * r - 1.99e-6 * t * t * r * r;
        return (float) ((hi - 32.0) * 5.0 / 9.0);
    }

    /** 综合体感：低温走风寒、高温走热指数，中间区间原样返回。 */
    public static float of(float tempC, float windMs, float rh) {
        if (tempC <= 10f) return windChill(tempC, windMs);
        if (tempC >= 27f) return heatIndex(tempC, rh);
        return tempC;
    }

    /** 露点温度（℃），Magnus 反解；用于「闷热/干燥」判断与云底高度。 */
    public static float dewPoint(float tempC, float rh) {
        double r = Mth.clamp(rh, 0.01f, 1f);
        double gamma = Math.log(r) + 17.67 * tempC / (tempC + 243.5);
        return (float) (243.5 * gamma / (17.67 - gamma));
    }

    /**
     * 抬升凝结高度（云底高度，米）。
     * 经验式：LCL ≈ 125 × (T − Td)，T/Td 单位 ℃。这是真实探空里最常用的一行公式。
     */
    public static float cloudBaseMetres(float tempC, float dewPointC) {
        return Math.max(0f, 125f * (tempC - dewPointC));
    }

    /**
     * 饱和比湿 q_s（g/kg）。
     *
     * @param tempC 温度
     * @param pressHpa 气压
     */
    public static float saturationMixingRatio(float tempC, float pressHpa) {
        double es = 6.112 * Math.exp(17.67 * tempC / (tempC + 243.5));   // hPa
        double q = 622.0 * es / Math.max(pressHpa - 0.378 * es, 1.0);     // g/kg
        return (float) q;
    }

    /** 相对湿度（0..1），由比湿与温压反算。 */
    public static float relativeHumidity(float mixingRatio, float tempC, float pressHpa) {
        float qs = saturationMixingRatio(tempC, pressHpa);
        return qs <= 0.01f ? 0f : Mth.clamp(mixingRatio / qs, 0f, 1.2f);
    }
}
