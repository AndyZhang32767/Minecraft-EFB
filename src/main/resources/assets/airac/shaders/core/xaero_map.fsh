#version 330
uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    // Navigation charts use daylight colours. Xaero alpha encodes light, not opacity.
    fragColor = vec4(texture(Sampler0, texCoord0).rgb * vertexColor.rgb, 1.0);
}
