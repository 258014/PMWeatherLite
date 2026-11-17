#version 150

// PMWeather Lite - storm haze vertex stage.
// Deliberately matrix-free: the quad is supplied in pixel space and mapped straight to clip
// space, so the pass cannot be broken by whatever projection state the renderer happens to hold.

in vec4 Position;

uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    texCoord = Position.xy / OutSize;
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
}
