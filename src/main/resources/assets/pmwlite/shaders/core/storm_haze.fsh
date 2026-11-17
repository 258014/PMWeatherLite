#version 150

// PMWeather Lite - storm haze fragment stage.
//
// A cheap stand-in for PMWeather's volumetric pass: desaturate toward a slate tint, keep the
// luminance structure so terrain stays readable, darken the rim, plus an optional lightning
// additive term. StormHaze / Cold / Flash are driven per frame from the synced weather state.

uniform sampler2D DiffuseSampler;

uniform float StormHaze;
uniform float Cold;
uniform float Flash;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

void main() {
    vec4 src = texture(DiffuseSampler, texCoord);
    float luma = dot(src.rgb, LUMA);

    vec3 warmTint = vec3(0.40, 0.45, 0.53);
    vec3 coldTint = vec3(0.70, 0.75, 0.84);
    vec3 tint = mix(warmTint, coldTint, clamp(Cold, 0.0, 1.0));

    // 1) haze: blend toward tint * luminance (keeps shading, kills oversaturation)
    vec3 graded = mix(src.rgb, tint * (0.30 + 0.70 * luma), StormHaze * 0.55);

    // 2) contrast lift so a dark storm does not read as mud
    graded = mix(graded, graded * 1.10 + vec3(0.015), StormHaze * 0.35);

    // 3) radial vignette
    float d = distance(texCoord, vec2(0.5));
    float vig = smoothstep(0.80, 0.28, d);
    graded *= mix(1.0, 0.62, StormHaze * (1.0 - vig));

    // 4) lightning
    graded += vec3(0.78, 0.84, 0.96) * Flash * 0.35;

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
