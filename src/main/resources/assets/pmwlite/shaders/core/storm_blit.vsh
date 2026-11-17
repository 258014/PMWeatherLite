#version 150

// PMWeather Lite - copy-back vertex stage (temp target -> main), matrix-free for the same reason
// as storm_haze.vsh.

in vec4 Position;

uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    texCoord = Position.xy / OutSize;
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
}
