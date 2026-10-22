#version 330
#extension GL_ARB_separate_shader_objects : require

// MC:Source map worlds. The texture (blended towards a second one on
// displacements, by the vertex alpha) times the map's baked light, which is
// stored gamma encoded at half strength, times the vertex colour (light for
// props, halved; one half for faces).

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightCoord;
layout(location = 4) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 albedo = mix(texture(Sampler0, texCoord0), texture(Sampler1, texCoord0), vertexColor.a);
#ifdef ALPHA_CUTOUT
    if (albedo.a < ALPHA_CUTOUT) {
        discard;
    }
#else
    albedo.a = 1.0;
#endif
    vec3 baked = texture(Sampler2, lightCoord / vec2(textureSize(Sampler2, 0))).rgb * 1.3703;
    vec3 light = baked * vertexColor.rgb * 2.0;
    vec4 color = vec4(albedo.rgb * light, albedo.a) * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
