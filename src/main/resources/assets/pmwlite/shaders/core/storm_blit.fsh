#version 150

// PMWeather Lite - copy-back fragment stage. Straight copy of the composited temp target.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0);
}
