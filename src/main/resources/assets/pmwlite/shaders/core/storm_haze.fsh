#version 150

// PMWeather Lite - storm haze / light-linkage fragment stage.
//
// 五个阶段严格按源码顺序执行（与 PostEffectPipeline 的 javadoc 一致）：
//   A 太阳方向空气光 → B 屏幕空间阴影幕 → C 风暴雾 → D 闪电 → E 暗角。
// 它们共享同一个像素，所以放在一个 pass 里；拆成多个 pass 只会多一次全屏采样。
//
// 所有光照量来自 LightingState（每客户端 tick 一次），不是 shader 里拍的常数：
// 太阳低了会变暖、阴影变软；下雨了直射光变弱、云把阴影摊开；
// 黄昏时暗角带一点暖边。这样光影包关掉时后处理仍在正确的时间感里工作，
// 光影包开着时我们的 2D 合成层读同一份快照，不会出现两套色温。

uniform sampler2D DiffuseSampler;

uniform vec2 OutSize;
uniform float StormHaze;
uniform float Cold;
uniform float Flash;

// 太阳/环境（LightingState）
uniform vec3 SunScreen;      // xy=屏幕方向, z=是否入镜（>0 在前方）
uniform vec3 SunColor;
uniform vec3 AmbientColor;
uniform float Sunlight;
uniform float Ambient;
uniform float Daylight;
uniform float Dusk;

// 阴影幕（LightingState）
uniform float ShadowStrength;
uniform float ShadowSoftness;
uniform float ShadowResolution;
uniform vec2 ShadowOffset;

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// 格子内确定性抖动：让量化边缘不变成硬邦邦的横带（便宜的"软化"）
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec4 src = texture(DiffuseSampler, texCoord);

    // ---------- Stage A: 太阳方向空气光 / 前向散射 ----------
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 view = (texCoord - 0.5) * vec2(aspect, 1.0) * 2.0;
    vec2 sun = SunScreen.xy * vec2(aspect, 1.0);
    float sunLen = length(sun);
    vec2 sunDir = sunLen > 1.0e-4 ? sun / sunLen : vec2(0.0, 1.0);
    float viewLen = length(view);
    vec2 viewDir = viewLen > 1.0e-4 ? view / viewLen : sunDir;
    float scatter = pow(max(dot(viewDir, sunDir), 0.0), 3.0) * max(SunScreen.z, 0.0);
    float air = Sunlight * (0.08 + 0.62 * StormHaze) * scatter;
    vec3 graded = src.rgb + SunColor * air * 0.32;

    // ---------- Stage B: 屏幕空间阴影幕 ----------
    // ShadowResolution = 量化网格数（不是 Iris 的 shadowMapResolution）；
    // ShadowSoftness 控制边缘宽度；ShadowOffset 让暗部随太阳反向偏移，
    // 所以转视角时阴影是"跟着世界走"的，而不是贴在屏幕上的固定贴纸。
    float grid = max(ShadowResolution, 1.0);
    vec2 cell = floor((texCoord + ShadowOffset) * grid);
    float jitter = (hash21(cell) - 0.5) / grid;
    float q = floor((1.0 - texCoord.y) * grid) / grid + jitter;
    float veil = smoothstep(0.22, 0.22 + max(ShadowSoftness, 0.02), q);
    veil *= ShadowStrength * (1.0 - 0.35 * scatter) * (0.35 + 0.65 * Daylight);
    graded *= 1.0 - veil * 0.30;
    graded += AmbientColor * veil * Ambient * 0.025;   // 暗部留一点天空反弹光

    // ---------- Stage C: 风暴雾 / 去饱和 ----------
    float luma = dot(graded, LUMA);
    vec3 warmTint = vec3(0.40, 0.45, 0.53);
    vec3 coldTint = vec3(0.70, 0.75, 0.84);
    vec3 stormTint = mix(warmTint, coldTint, clamp(Cold, 0.0, 1.0));
    vec3 tint = mix(stormTint, AmbientColor, 0.30 * Daylight);
    graded = mix(graded, tint * (0.30 + 0.70 * luma), StormHaze * 0.55);
    graded = mix(graded, graded * 1.10 + vec3(0.015), StormHaze * 0.35);

    // ---------- Stage D: 闪电 ----------
    vec3 flashColor = mix(vec3(0.78, 0.84, 0.96), SunColor, 0.25 * Daylight);
    graded += flashColor * Flash * 0.35;

    // ---------- Stage E: 暗角（黄昏带一点暖边） ----------
    float d = distance(texCoord, vec2(0.5));
    float vig = 1.0 - smoothstep(0.28, 0.80, d);
    graded *= mix(1.0, 0.62, StormHaze * (1.0 - vig));
    graded += SunColor * Dusk * (1.0 - vig) * 0.012;

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
