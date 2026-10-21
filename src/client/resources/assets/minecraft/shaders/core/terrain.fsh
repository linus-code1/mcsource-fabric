#version 330
#extension GL_ARB_separate_shader_objects : require

// MC:Source terrain fragment shader: Minecraft's, except for how block
// textures are sampled. Minecraft's own 16-pixel textures keep their sharp
// pixels. The player's Half-Life 2 textures, many times finer, are filtered
// smoothly between texels up close, as Source draws them. The two are told
// apart by how many texels fall on a block: Minecraft's at most 16, Half-Life
// 2's 50 or more. Written for this mod; no Valve code is used.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 mcsourcePosition;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Texels a block above which a texture is one of Half-Life 2's.
const float MCSOURCE_FINE_TEXELS = 32.0;
// Filtering stays inside cells of this many texels, so neighbouring
// textures in the atlas don't bleed in. Half-Life 2 textures are 256 texels
// and sit on such cells; a finer cell only shows as a slightly sharper step.
const float MCSOURCE_CELL = 64.0;

vec4 mcsource_filtered(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 texel = uv / pixelSize;
    vec2 cellMin = floor(texel / MCSOURCE_CELL) * MCSOURCE_CELL;
    vec2 cellMax = cellMin + (MCSOURCE_CELL - 1.0);
    vec2 p = texel - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    ivec2 lo = ivec2(clamp(base, cellMin, cellMax));
    ivec2 hi = ivec2(clamp(base + 1.0, cellMin, cellMax));
    vec4 c00 = texelFetch(source, lo, 0);
    vec4 c10 = texelFetch(source, ivec2(hi.x, lo.y), 0);
    vec4 c01 = texelFetch(source, ivec2(lo.x, hi.y), 0);
    vec4 c11 = texelFetch(source, hi, 0);
    // Weighted by alpha so cut-out edges don't pick up the dark colour of
    // their transparent texels.
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    vec4 a = vec4(c00.a, c10.a, c01.a, c11.a) * w;
    float alpha = a.x + a.y + a.z + a.w;
    vec3 rgb = c00.rgb * a.x + c10.rgb * a.y + c01.rgb * a.z + c11.rgb * a.w;
    rgb = alpha > 0.0 ? rgb / alpha : c00.rgb;
    return vec4(rgb, alpha);
}

vec4 mcsource_sample(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    // Texels a block: texture change across a pixel over distance across it.
    float worldStep = max(length(dFdx(mcsourcePosition)), length(dFdy(mcsourcePosition)));
    float texelStep = max(length(du / pixelSize), length(dv / pixelSize));
    float texelsPerBlock = worldStep > 0.0 ? texelStep / worldStep : 0.0;
    if (texelsPerBlock < MCSOURCE_FINE_TEXELS) {
        return UseRgss == 1 ? sampleRGSS(source, uv, pixelSize) : sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    }
    float texelsPerPixel = max(texelScreenSize.x / pixelSize.x, texelScreenSize.y / pixelSize.y);
    float far = smoothstep(0.7, 1.5, texelsPerPixel);
    vec4 near = far < 1.0 ? mcsource_filtered(source, uv, pixelSize) : vec4(0.0);
    vec4 mipped = textureGrad(source, uv, du, dv);
    return mix(near, mipped, far);
}

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 color = mcsource_sample(Sampler0, texCoord0, 1.0f / TextureSize) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
