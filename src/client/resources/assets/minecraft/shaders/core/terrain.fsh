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

// Shiny blocks, like Source's cubemap reflections ($envmap): a texture whose
// pixels are almost but not quite opaque asks for a reflection, the further
// below opaque the stronger (alpha 248 a mirror, 251 polished metal, 253 a
// gloss). What it reflects is a made-up surrounding (mcsource_env) and a
// glint of the sun, lit by the light on the block, so it costs a few sums
// and nothing else.
const vec3 MCSOURCE_SUN = vec3(-0.6567, 0.6567, 0.3707);

float mcsource_shine(float alpha) {
    // Only the exact marker values, so the soft edges of leaves and other
    // cut-outs, which pass through every alpha, don't catch a shine.
    float a = alpha * 255.0;
    if (abs(a - 248.0) < 0.4) {
        return 1.0;
    }
    if (abs(a - 251.0) < 0.4) {
        return 0.6;
    }
    if (abs(a - 253.0) < 0.4) {
        return 0.3;
    }
    return 0.0;
}

// What shiny blocks reflect, made up from the reflection's direction alone
// (no extra textures or passes): the sky's colour with drifting cloud
// streaks above, a ring of far hills and nearer green ones at the horizon,
// and dark ground below. It stands in for the cubemap a Source map bakes.
float mcsource_hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float mcsource_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mcsource_hash(i), mcsource_hash(i + vec2(1.0, 0.0)), f.x),
            mix(mcsource_hash(i + vec2(0.0, 1.0)), mcsource_hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec3 mcsource_env(vec3 r) {
    vec3 sky = FogColor.rgb;
    float az = atan(r.x, r.z);
    float elev = asin(clamp(r.y, -1.0, 1.0));
    vec3 zenith = sky * vec3(0.55, 0.7, 1.0);
    vec3 env = mix(sky, zenith, smoothstep(0.0, 0.9, elev));
    // Clouds: wrapped round the sky so there is no seam behind.
    vec2 c = vec2(cos(az), sin(az)) * (2.5 / max(r.y, 0.15));
    float cloud = mcsource_noise(c) * 0.6 + mcsource_noise(c * 2.7 + 3.1) * 0.4;
    cloud = smoothstep(0.55, 0.85, cloud) * smoothstep(0.03, 0.2, elev);
    env = mix(env, max(sky * 1.25, vec3(0.0)) + 0.1, cloud * 0.7);
    float far = 0.07 + 0.035 * sin(az * 3.0 + 1.0) + 0.02 * sin(az * 7.0 + 2.0) + 0.01 * sin(az * 17.0);
    float near = 0.025 + 0.015 * sin(az * 5.0 + 4.0) + 0.008 * sin(az * 13.0 + 1.0);
    if (elev < far) {
        env = sky * vec3(0.62, 0.68, 0.72);
    }
    if (elev < near) {
        env = vec3(0.32, 0.42, 0.24) * dot(sky, vec3(0.3, 0.5, 0.2)) * 1.4;
    }
    if (elev < 0.0) {
        float down = smoothstep(0.0, 0.6, -elev);
        env = mix(vec3(0.36, 0.46, 0.26), vec3(0.2, 0.22, 0.15), down) * dot(sky, vec3(0.3, 0.5, 0.2)) * 1.4;
    }
    return env;
}

vec3 mcsource_reflect(vec3 albedo, vec3 light, float shine) {
    vec3 n = normalize(cross(dFdx(mcsourcePosition), dFdy(mcsourcePosition)));
    vec3 view = normalize(mcsourcePosition);
    if (dot(n, view) > 0.0) {
        n = -n;
    }
    vec3 r = reflect(view, n);
    vec3 env = mcsource_env(r);
    float daylight = clamp(dot(FogColor.rgb, vec3(0.3, 0.5, 0.2)) * 1.6, 0.0, 1.0);
    float glint = pow(max(dot(r, MCSOURCE_SUN), 0.0), 220.0) * 3.0 * daylight;
    float facing = clamp(1.0 + dot(view, n), 0.0, 1.0);
    float fresnel = mix(0.35, 1.0, facing * facing * facing * facing * facing);
    if (shine >= 1.0) {
        // A mirror: all reflection, a little cool, with the sun's glint.
        return (env * vec3(0.92, 0.96, 1.0) * 1.2 + glint) * light;
    }
    float amount = shine * fresnel;
    // Light on the block stands in for how bright its surroundings are.
    vec3 seen = (env * 1.3 + glint) * light;
    return mix(albedo * light, seen, amount);
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
    vec4 texel = mcsource_sample(Sampler0, texCoord0, 1.0f / TextureSize);
    vec4 color = texel * vertexColor;
    float shine = vertexColor.a > 0.99 ? mcsource_shine(texel.a) : 0.0;
    if (shine > 0.0) {
        color = vec4(mcsource_reflect(texel.rgb, vertexColor.rgb, shine), 1.0);
    }
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
