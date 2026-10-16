#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

// MC:Source sky: Minecraft's sky colour with a soft, painted-looking cloud
// layer like a Half-Life 2 skybox, in place of Minecraft's blocky clouds.
// The clouds are made from noise in this shader; no Valve art is used.

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec3 skyDirection;

layout(location = 0) out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    mat2 turn = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 6; i++) {
        value += amplitude * noise(p);
        p = turn * p * 2.03 + 17.0;
        amplitude *= 0.5;
    }
    return value;
}

vec3 clouds(vec3 sky, vec3 direction) {
    if (direction.y <= 0.0) {
        return sky;
    }
    // Project onto a flat cloud deck high above; nearer the horizon the
    // clouds bunch up and fade into haze.
    vec2 uv = direction.xz / (direction.y + 0.12) * 1.4;
    float shape = fbm(uv);
    float detail = fbm(uv * 3.1 + 5.0);
    float density = smoothstep(0.42, 0.78, shape + (detail - 0.5) * 0.25);
    float horizon = smoothstep(0.02, 0.3, direction.y);
    // Clouds are lit like the sky around them: bright tops, greyer thick parts.
    float brightness = max(max(sky.r, sky.g), sky.b);
    vec3 lit = vec3(min(1.0, brightness * 1.15 + 0.08));
    vec3 shadowed = mix(sky, vec3(brightness * 0.7), 0.7);
    vec3 cloud = mix(lit, shadowed, smoothstep(0.55, 0.95, shape));
    return mix(sky, cloud, density * horizon * 0.92);
}

void main() {
    vec4 color = ColorModulator;
    color.rgb = clouds(color.rgb, normalize(skyDirection));
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
}
